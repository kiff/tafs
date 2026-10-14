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

/// Answers to KIFF's held calls: gated until KIFF verifies the phone's signature,
/// bound to KIFF's id, and matched by it across refreshes.
@MainActor
final class KIFFAnswerTests: XCTestCase {
    private let heldJSON = """
    {"exceptions":[{"id":"ce_1","proposal_id":"p1","subject":"refund-agent","action":"refund_order","entity_id":"ord_4502",
      "status":"held","breaches":[{"mandate_id":"m1","outcome":"per_action_exceeded","quantity":"sum(amount_cents)","limit":15000}],
      "parameters":{"amount_cents":48000},"created_at":"2026-10-14T10:41:00Z","expires_at":"2099-10-14T10:51:00Z"}]}
    """

    private func makeModel(verifies: Bool, signer: RecordingSigner) async throws -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let client = KIFFClient(baseURL: URL(string: "https://api.test")!, webURL: URL(string: "https://app.test")!,
                                token: "dev_token", serverVerifiesSignatures: verifies, session: URLSession(configuration: config))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, heldJSON)
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/approve-once"] = (200, #"{"id":"ce_1"}"#)
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/reject"] = (200, #"{"id":"ce_1"}"#)
        let model = AppModel(signer: signer, kiff: client)
        model.show([], try await client.heldCalls())
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

    func testTheSignatureNamesKIFFsHold() async throws {
        let signer = RecordingSigner()
        let model = try await makeModel(verifies: true, signer: signer)
        await model.answer(try XCTUnwrap(model.held.first), with: .nextStep("return first"))
        XCTAssertEqual(signer.signed, ["tafs.answer.v1|ce_1|reject:return first"])
        XCTAssertEqual(answersSent, ["/v1/me/card-exceptions/ce_1/reject"])
        XCTAssertTrue(model.held.isEmpty)
    }

    func testAHeldCallKeepsItsIdentityAcrossRefreshes() async throws {
        let model = try await makeModel(verifies: true, signer: RecordingSigner())
        let first = model.held.map(\.id)
        model.show([], try await model.kiff.heldCalls())
        XCTAssertEqual(model.held.map(\.id), first)
        XCTAssertEqual(first, [KIFFMapping.heldID("ce_1")])
        XCTAssertNotEqual(KIFFMapping.heldID("ce_1"), KIFFMapping.heldID("ce_2"))
    }

    func testARefreshDuringAnAnswerStillRemovesTheRightHold() async throws {
        let signer = RecordingSigner()
        let model = try await makeModel(verifies: true, signer: signer)
        let calls = try await model.kiff.heldCalls()
        // The refresh lands while Face ID is up.
        signer.whileSigning = { model.show([], calls) }
        // Answered from a row whose local id predates the refresh.
        var stale = try XCTUnwrap(model.held.first)
        stale.id = UUID()
        await model.answer(stale, with: .approveOnce)
        XCTAssertTrue(model.held.isEmpty, "resolved by KIFF's id")
        XCTAssertEqual(answersSent, ["/v1/me/card-exceptions/ce_1/approve-once"])
    }
}
