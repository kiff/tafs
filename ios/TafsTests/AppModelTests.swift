import XCTest
@testable import Tafs

/// Signs without Face ID, so tests never wait at a prompt.
struct StubSigner: ApprovalSigning {
    var publicKeyDER: Data { Data([0x30, 0x59]) }
    func sign(_ message: Data, reason: String) async throws -> String { "stub-signature" }
    func signConfirmed(_ message: Data) throws -> String { "stub-signature" }
}

/// Records what it signed, and can run something while "Face ID" is up.
@MainActor
final class RecordingSigner: ApprovalSigning {
    var signed: [String] = []
    var whileSigning: (() -> Void)?
    /// Whether each Face ID it asked for could count the device unlock.
    var reusedUnlock: [Bool] = []
    private var reuse = false

    var publicKeyDER: Data { Data([0x30, 0x59]) }

    func allowUnlockReuse() { reuse = true }
    func endUnlockReuse() { reuse = false }

    func sign(_ message: Data, reason: String) async throws -> String {
        reusedUnlock.append(reuse)
        reuse = false
        signed.append(String(decoding: message, as: UTF8.self))
        whileSigning?()
        return "signature"
    }

    func signConfirmed(_ message: Data) throws -> String {
        signed.append(String(decoding: message, as: UTF8.self))
        return "signature"
    }
}

@MainActor
final class AppModelTests: XCTestCase {
    private func startedRefunds(_ model: AppModel) async -> Taf {
        let t = TafTemplates.refunds
        let draft = TafDraft(name: t.name, job: t.job, tools: t.tools, rules: t.rules, templateKey: t.key)
        let taf = await model.start(draft, confirm: false)
        return try! XCTUnwrap(taf)
    }

    func testHeldCallWaitsThenApprovalDrawsOnTheCard() async {
        let model = AppModel(signer: StubSigner())
        let taf = await startedRefunds(model)

        model.propose(ProposedAction(action: "refund", summary: "Refund #4502", amount: .eur(480)), by: taf.id)
        XCTAssertEqual(model.held.count, 1)
        XCTAssertEqual(model.taf(taf.id)?.state, .waiting)

        await model.answer(model.held[0], with: .approveOnce)
        XCTAssertTrue(model.held.isEmpty)
        XCTAssertEqual(model.taf(taf.id)?.used["refund"], .eur(480))
        XCTAssertEqual(model.taf(taf.id)?.state, .working)
        XCTAssertEqual(model.record.first?.kind, .approved)
        XCTAssertNotNil(model.record.first?.signature, "an approval is signed on the phone")
    }

    /// Whose error it is goes with it, not in its words, and any other error clears it.
    func testAnErrorsSourceIsAFlagNotItsWords() {
        let model = AppModel(signer: StubSigner())
        model.fail("No desconectado: sin red", from: .disconnect)
        XCTAssertEqual(model.lastErrorFrom, .disconnect, "in any wording")
        XCTAssertEqual(model.lastError, "No desconectado: sin red")
        model.lastError = "Not disconnected: but from elsewhere"
        XCTAssertNil(model.lastErrorFrom, "another error is no screen's own, whatever it says")
        model.fail("The dial couldn't be read: offline", from: .knob)
        model.lastError = nil
        XCTAssertNil(model.lastErrorFrom)
    }

    func testRefusedAnswerDrawsNothing() async {
        let model = AppModel(signer: StubSigner())
        let taf = await startedRefunds(model)
        model.propose(ProposedAction(action: "refund", summary: "Refund #4502", amount: .eur(480)), by: taf.id)
        await model.answer(model.held[0], with: .refuse)
        XCTAssertNil(model.taf(taf.id)?.used["refund"])
        XCTAssertEqual(model.record.first?.kind, .refused)
    }

    func testExpiredHeldCallIsRefusedNotApproved() async {
        var now = Date()
        let model = AppModel(signer: StubSigner(), clock: { now })
        let taf = await startedRefunds(model)
        model.propose(ProposedAction(action: "refund", summary: "Refund #4502", amount: .eur(480)), by: taf.id)
        now = now.addingTimeInterval(11 * 60)
        await model.answer(model.held[0], with: .approveOnce)
        XCTAssertTrue(model.held.isEmpty)
        XCTAssertNil(model.taf(taf.id)?.used["refund"], "an expired hold must never be approved")
        XCTAssertEqual(model.record.first?.title, "Expired: Refund #4502")
    }

    func testRevokeStopsAndDropsWaitingCalls() async {
        let model = AppModel(signer: StubSigner())
        let taf = await startedRefunds(model)
        model.propose(ProposedAction(action: "refund", summary: "Refund #4502", amount: .eur(480)), by: taf.id)
        await model.revoke(taf.id)
        XCTAssertTrue(model.held.isEmpty)
        XCTAssertEqual(model.taf(taf.id)?.state, .revoked)

        model.propose(ProposedAction(action: "refund", summary: "Refund #4510", amount: .eur(10)), by: taf.id)
        XCTAssertEqual(model.record.first?.kind, .refused, "nothing is allowed after a revoke")
    }

    /// The sample is in euros, with the same numbers as Android's and kiff.dev/tafs.
    func testTheSampleIsInEurosWithAndroidsNumbers() throws {
        for t in TafTemplates.all {
            for money in t.rules.flatMap({ [$0.perAction, $0.perWindow] }).compactMap({ $0 }) + t.script.compactMap(\.amount) {
                XCTAssertEqual(money.currency, "EUR", "\(t.key)")
            }
        }
        let refund = try XCTUnwrap(TafTemplates.refunds.rules.first { $0.action == "refund" })
        XCTAssertEqual(refund.perAction, .eur(150))
        XCTAssertEqual(refund.perWindow, .eur(2_000))
        let bills = try XCTUnwrap(TafTemplates.template("billpay")?.rules.first { $0.action == "pay_bill" })
        XCTAssertEqual(bills.perAction, .eur(5_000))
        XCTAssertEqual(bills.perWindow, .eur(25_000))
        XCTAssertEqual(bills.window, .week)
        XCTAssertEqual(bills.window.label, "a week")
        let loss = try XCTUnwrap(TafTemplates.template("chargebacks")?.rules.first { $0.action == "accept_dispute" })
        XCTAssertEqual(loss.perAction, .eur(100))
        XCTAssertEqual(loss.perWindow, .eur(500))
        XCTAssertEqual(loss.window, .week)
    }

    /// The sample has no new-taf form: a new taf starts in Coach, signed in, and its foot says so.
    func testTheSampleHasNoNewTafButtonAndSaysWhereOneStarts() async {
        let model = AppModel(signer: StubSigner())
        await model.startSample()
        XCTAssertEqual(model.mode, .sample)
        XCTAssertFalse(model.canStartTafs)
        XCTAssertEqual(Words.Home.sampleFoot, "Sample data. The Card is checked on this phone. Signed in with KIFF, KIFF decides, and a new taf starts in Coach.")
    }
}
