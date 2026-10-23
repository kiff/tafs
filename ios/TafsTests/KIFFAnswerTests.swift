import XCTest
@testable import Tafs

/// Amounts in the unit KIFF declares, in the shape `/v1/me/card-exceptions` returns
/// (kiff-cloud apps/api/internal/handlers/mandate_exceptions_units_test.go).
@MainActor
final class KIFFUnitsTests: XCTestCase {
    private func exception(_ extra: String, breach: String = #""quantity":"sum(amount_cents)""#) throws -> KIFFClient.CardException {
        let json = """
        {"id":"exc-1","proposal_id":"tc-1","subject":"claude","action":"refund","entity_id":"ord_1","status":"held",
         "breaches":[{"mandate_id":"mdt-any","outcome":"limit_reached","limit":2000,"used":1800,"draw":2550,\(breach)}],
         "parameters":{"amount_cents":2550},\(extra)
         "created_at":"2026-10-09T08:00:00Z"}
        """
        return try KIFFClient.decoder.decode(KIFFClient.CardException.self, from: Data(json.utf8))
    }

    func testEuroAtScaleTwo() throws {
        let e = try exception(#""parameter_units":{"amount_cents":{"scale":2,"unit":"EUR"}},"#,
                              breach: #""quantity":"sum(amount_cents)","unit":"EUR","scale":2"#)
        let amount = try XCTUnwrap(KIFFMapping.held(e, tafID: UUID()).proposed.amount)
        XCTAssertEqual(amount, Money(minor: 2550, currency: "EUR", scale: 2))
        XCTAssertEqual(amount.value, Decimal(string: "25.5"))
        XCTAssertTrue(amount.formatted.contains("25.50") || amount.formatted.contains("25,50"), amount.formatted)
        XCTAssertTrue(amount.formatted.contains("€"), amount.formatted)
        XCTAssertFalse(amount.formatted.contains("$"), amount.formatted)
    }

    func testScaleZeroIsWholeUnits() throws {
        let e = try exception(#""parameter_units":{"amount_cents":{"scale":0,"unit":"JPY"}},"#)
        let amount = try XCTUnwrap(KIFFMapping.held(e, tafID: UUID()).proposed.amount)
        XCTAssertEqual(amount.value, 2550, "not divided by 100")
        XCTAssertEqual(amount.formatted.filter(\.isNumber), "2550", amount.formatted)
    }

    func testAUnitThatIsNotACurrency() throws {
        let e = try exception(#""parameter_units":{"amount_cents":{"scale":1,"unit":"kWh"}},"#)
        let amount = try XCTUnwrap(KIFFMapping.held(e, tafID: UUID()).proposed.amount)
        XCTAssertEqual(amount.formatted, "255 kWh")
    }

    func testNoDeclaredUnitIsABareNumber() throws {
        let e = try exception("")
        let amount = try XCTUnwrap(KIFFMapping.held(e, tafID: UUID()).proposed.amount)
        XCTAssertEqual(amount, .bare(2550))
        XCTAssertNil(amount.currency)
        XCTAssertEqual(amount.formatted.filter(\.isNumber), "2550")
    }

    func testUnavailableUnitsAreNotGuessed() throws {
        let e = try exception(#""units_unavailable":true,"#, breach: #""quantity":"sum(amount_cents)","unit_unavailable":true"#)
        XCTAssertEqual(e.unitsUnavailable, true)
        XCTAssertEqual(KIFFMapping.held(e, tafID: UUID()).proposed.amount, .bare(2550))
    }

    func testAmbiguousCardTotalKeepsTheActionsOwnUnit() throws {
        let e = try exception(#""parameter_units":{"amount_cents":{"scale":2,"unit":"USD"}},"#,
                              breach: #""quantity":"sum(amount_cents)","unit_ambiguous":true"#)
        XCTAssertEqual(e.breaches.first?.unitAmbiguous, true)
        XCTAssertEqual(KIFFMapping.held(e, tafID: UUID()).proposed.amount, Money(minor: 2550, currency: "USD", scale: 2))

        let bare = try exception("", breach: #""quantity":"sum(amount_cents)","unit_ambiguous":true"#)
        XCTAssertEqual(KIFFMapping.held(bare, tafID: UUID()).proposed.amount, .bare(2550))
    }

    func testSamplesSavedBeforeUnitsStillReadAsDollars() throws {
        let old = try JSONDecoder().decode(Money.self, from: Data(#"{"minor":48000,"currency":"USD"}"#.utf8))
        XCTAssertEqual(old, .usd(480))
    }
}

/// Answers to KIFF's held calls: signed with KIFF's message over this hold and its
/// accounting version, sent only from a session that holds the phone's key, and
/// matched by KIFF's id across refreshes.
@MainActor
final class KIFFAnswerTests: XCTestCase {
    private let heldJSON = """
    {"exceptions":[{"id":"ce_1","proposal_id":"p1","subject":"refund-agent","action":"refund_order","entity_id":"ord_4502",
      "status":"held","breaches":[{"mandate_id":"m1","outcome":"per_action_exceeded","quantity":"sum(amount_cents)","limit":15000}],
      "parameters":{"amount_cents":48000},"created_at":"2026-10-14T10:41:00Z","expires_at":"2099-10-14T10:51:00Z",
      "accounting_version":2}]}
    """

    private let now = ISO8601DateFormatter().date(from: "2026-10-14T10:45:00Z")!

    private func makeModel(verifies: Bool, signer: RecordingSigner, keyed: Bool = true) async throws -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let client = KIFFClient(baseURL: URL(string: "https://api.test")!, webURL: URL(string: "https://app.test")!,
                                token: "dev_token", serverVerifiesSignatures: verifies, session: URLSession(configuration: config))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, heldJSON)
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/approve-once"] = (200, #"{"id":"ce_1"}"#)
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/reject"] = (200, #"{"id":"ce_1"}"#)
        let fixed = now
        let model = AppModel(signer: signer, kiff: client, clock: { fixed })
        model.kiffTenant = "t1"
        model.sessionHasKey = keyed
        model.show(TafsCloud.Home(tafs: [], others: [], removing: []), try await client.heldCalls())
        StubProtocol.sent = []
        return model
    }

    private var answersSent: [String] {
        StubProtocol.sent.compactMap(\.url?.path).filter { $0.hasSuffix("/approve-once") || $0.hasSuffix("/reject") }
    }

    func testALiveAnswerIsNotSentUntilKIFFVerifiesTheSignature() async throws {
        let signer = RecordingSigner()
        let model = try await makeModel(verifies: false, signer: signer)
        let call = try XCTUnwrap(model.held.first)
        XCTAssertFalse(model.canAnswer(call))
        XCTAssertEqual(model.answerPage(for: call)?.absoluteString, "https://app.test/needs-you/ce_1")

        for answer in [Answer.approveOnce, .refuse, .nextStep("return first")] {
            await model.answer(call, with: answer)
        }
        XCTAssertEqual(answersSent, [], "nothing reaches KIFF")
        XCTAssertEqual(signer.signed, [], "nothing is signed for an answer that is not sent")
        XCTAssertEqual(model.held.map(\.kiffID), ["ce_1"], "the call still waits")
        XCTAssertNotNil(model.lastError)
    }

    func testSampleAnswersStillWork() async throws {
        let model = AppModel(signer: StubSigner())
        let t = TafTemplates.refunds
        let draft = TafDraft(name: t.name, job: t.job, tools: t.tools, rules: t.rules, templateKey: t.key)
        let started = await model.start(draft, confirm: false)
        let taf = try XCTUnwrap(started)
        model.propose(ProposedAction(action: "refund", summary: "Refund #4502", amount: .usd(480)), by: taf.id)
        let call = try XCTUnwrap(model.held.first)
        XCTAssertTrue(model.canAnswer(call))
        await model.answer(call, with: .approveOnce)
        XCTAssertTrue(model.held.isEmpty)
    }

    func testTheSignatureIsKIFFsMessageForThisHold() async throws {
        let signer = RecordingSigner()
        let model = try await makeModel(verifies: true, signer: signer)
        await model.answer(try XCTUnwrap(model.held.first), with: .nextStep("return first"))
        XCTAssertEqual(signer.signed, ["kiff.answer.v1\nt1\nce_1\nreject\nreturn first\n\n2\n2026-10-14T10:45:00Z"])
        XCTAssertEqual(answersSent, ["/v1/me/card-exceptions/ce_1/reject"])
        let sent = try XCTUnwrap(StubProtocol.sent.last?.httpBody)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: sent) as? [String: Any])
        XCTAssertEqual(body["next_step"] as? String, "return first")
        let signature = try XCTUnwrap(body["signature"] as? [String: String])
        XCTAssertEqual(signature, ["signed_at": "2026-10-14T10:45:00Z", "value": "signature"])
        XCTAssertTrue(model.held.isEmpty)
    }

    func testApproveOnceSignsTheApproval() async throws {
        let signer = RecordingSigner()
        let model = try await makeModel(verifies: true, signer: signer)
        await model.answer(try XCTUnwrap(model.held.first), with: .approveOnce)
        XCTAssertEqual(signer.signed, ["kiff.answer.v1\nt1\nce_1\napprove-once\n\n\n2\n2026-10-14T10:45:00Z"])
        XCTAssertEqual(answersSent, ["/v1/me/card-exceptions/ce_1/approve-once"])
    }

    func testASessionWithoutThePhonesKeyCannotAnswer() async throws {
        let signer = RecordingSigner()
        let model = try await makeModel(verifies: true, signer: signer, keyed: false)
        let call = try XCTUnwrap(model.held.first)
        XCTAssertFalse(model.canAnswer(call))
        await model.answer(call, with: .approveOnce)
        XCTAssertEqual(answersSent, [])
        XCTAssertEqual(signer.signed, [])
        XCTAssertTrue(model.lastError?.contains("Sign out and in again") == true, model.lastError ?? "")
    }

    func testTheAnswerMessageHashesTheNote() {
        let message = KIFFClient.answerMessage(tenant: "t1", exceptionID: "ce_1", answer: "reject", nextStep: "return_first",
                                               note: "Ask for photos.", accountingVersion: 2, signedAt: "2026-10-14T12:00:00Z")
        XCTAssertEqual(String(decoding: message, as: UTF8.self),
                       "kiff.answer.v1\nt1\nce_1\nreject\nreturn_first\n2b951386ddf8c195c3f5603c65de2156acb7f9f2a13638581448711e019dc654\n2\n2026-10-14T12:00:00Z")
    }

    /// The owner's answer is filed under the proposal KIFF held, which KIFF's record files the
    /// call under, not under the hold's own id: a refusal showed twice, once where the card
    /// was and once as KIFF's outcome (kiff/tafs#138, item 51). Once KIFF has the outcome, one
    /// line is left.
    func testAnAnswerAndKIFFsOutcomeAreOneLine() async throws {
        let model = try await makeModel(verifies: true, signer: RecordingSigner())
        let call = try XCTUnwrap(model.held.first)
        XCTAssertEqual(call.proposalID, "p1")
        await model.answer(call, with: .refuse)
        let mine = KIFFMapping.recordID("p1")
        XCTAssertEqual(model.record.filter { $0.id == mine }.count, 1, "filed under KIFF's record id")
        XCTAssertTrue(model.record.allSatisfy { $0.id != KIFFMapping.recordID("ce_1") })

        let kiffs = RecordEntry(id: mine, tafID: call.tafID, kind: .refused, title: "Refund order", detail: "Not sent", at: now)
        let merged = AppModel.merge([kiffs], decided: [mine: try XCTUnwrap(model.record.first { $0.id == mine })])
        XCTAssertEqual(merged.record.filter { $0.id == mine }.count, 1)
        // KIFF's outcome takes the answer's place, in the owner's words (kiff/tafs#206).
        XCTAssertEqual(merged.record.first { $0.id == mine }?.detail, "Not sent")
        XCTAssertTrue(merged.record.first { $0.id == mine }?.title.hasPrefix("Refused by you") ?? false)
    }

    func testAHeldCallKeepsItsIdentityAcrossRefreshes() async throws {
        let model = try await makeModel(verifies: true, signer: RecordingSigner())
        let first = model.held.map(\.id)
        model.show(TafsCloud.Home(tafs: [], others: [], removing: []), try await model.kiff.heldCalls())
        XCTAssertEqual(model.held.map(\.id), first)
        XCTAssertEqual(first, [KIFFMapping.heldID("ce_1")])
        XCTAssertNotEqual(KIFFMapping.heldID("ce_1"), KIFFMapping.heldID("ce_2"))
    }

    /// The owner's answer stays in the conversation (kiff/tafs#138, item 31): a refresh just
    /// after it, while KIFF still lists the call as held, reads it as answered; once KIFF has
    /// the same outcome, KIFF's record carries it in the owner's words (kiff/tafs#206); a
    /// different outcome lets the answer go; one KIFF doesn't list yet shows.
    func testAnAnswerOutlivesARefreshThatStillHoldsTheCall() {
        let taf = UUID(), id = KIFFMapping.recordID("p1"), at = now
        let mine = RecordEntry(id: id, tafID: taf, kind: .refused, title: "Refused by you · 10:45 · Refund €480.00", detail: "Over its limit", at: at)
        func kiff(_ kind: RecordEntry.Kind) -> RecordEntry {
            RecordEntry(id: id, tafID: taf, kind: kind, title: "Refund an order", detail: kind == .held ? "Waiting for you" : "Not sent", at: at)
        }
        let held = AppModel.merge([kiff(.held)], decided: [id: mine])
        XCTAssertEqual(held.record, [mine], "still held at KIFF: the answer shows")
        XCTAssertTrue(held.settled.isEmpty)
        let same = AppModel.merge([kiff(.refused)], decided: [id: mine])
        var worded = kiff(.refused)
        worded.title = mine.title
        XCTAssertEqual(same.record, [worded], "KIFF's outcome carries it, in the owner's words")
        XCTAssertTrue(same.settled.isEmpty)
        let other = AppModel.merge([kiff(.notDone)], decided: [id: mine])
        XCTAssertEqual(other.record, [kiff(.notDone)], "a different outcome is KIFF's to say")
        XCTAssertEqual(other.settled, [id], "and the answer is let go")
        let waiting = AppModel.merge([], decided: [id: mine])
        XCTAssertEqual(waiting.record, [mine], "not listed yet: the answer shows")
        XCTAssertTrue(waiting.settled.isEmpty)
    }

    func testARefreshDuringAnAnswerStillRemovesTheRightHold() async throws {
        let signer = RecordingSigner()
        let model = try await makeModel(verifies: true, signer: signer)
        let calls = try await model.kiff.heldCalls()
        // The refresh lands while Face ID is up.
        signer.whileSigning = { model.show(TafsCloud.Home(tafs: [], others: [], removing: []), calls) }
        // Answered from a row whose local id predates the refresh.
        var stale = try XCTUnwrap(model.held.first)
        stale.id = UUID()
        await model.answer(stale, with: .approveOnce)
        XCTAssertTrue(model.held.isEmpty, "resolved by KIFF's id")
        XCTAssertEqual(answersSent, ["/v1/me/card-exceptions/ce_1/approve-once"])
    }
}
