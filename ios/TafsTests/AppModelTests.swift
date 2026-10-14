import XCTest
@testable import Tafs

/// Signs without Face ID, so tests never wait at a prompt.
struct StubSigner: ApprovalSigning {
    func sign(answer: Answer, heldID: String, reason: String) async throws -> String { "stub-signature" }
}

/// Records what it signed, and can run something while "Face ID" is up.
@MainActor
final class RecordingSigner: ApprovalSigning {
    var signed: [String] = []
    var whileSigning: (() -> Void)?

    func sign(answer: Answer, heldID: String, reason: String) async throws -> String {
        signed.append("tafs.answer.v1|\(heldID)|\(answer.wireName)")
        whileSigning?()
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

        model.propose(ProposedAction(action: "refund", summary: "Refund #4502", amount: .usd(480)), by: taf.id)
        XCTAssertEqual(model.held.count, 1)
        XCTAssertEqual(model.taf(taf.id)?.state, .waiting)

        await model.answer(model.held[0], with: .approveOnce)
        XCTAssertTrue(model.held.isEmpty)
        XCTAssertEqual(model.taf(taf.id)?.used["refund"], .usd(480))
        XCTAssertEqual(model.taf(taf.id)?.state, .working)
        XCTAssertEqual(model.record.first?.kind, .approved)
        XCTAssertNotNil(model.record.first?.signature, "an approval is signed on the phone")
    }

    func testRefusedAnswerDrawsNothing() async {
        let model = AppModel(signer: StubSigner())
        let taf = await startedRefunds(model)
        model.propose(ProposedAction(action: "refund", summary: "Refund #4502", amount: .usd(480)), by: taf.id)
        await model.answer(model.held[0], with: .refuse)
        XCTAssertNil(model.taf(taf.id)?.used["refund"])
        XCTAssertEqual(model.record.first?.kind, .refused)
    }

    func testExpiredHeldCallIsRefusedNotApproved() async {
        var now = Date()
        let model = AppModel(signer: StubSigner(), clock: { now })
        let taf = await startedRefunds(model)
        model.propose(ProposedAction(action: "refund", summary: "Refund #4502", amount: .usd(480)), by: taf.id)
        now = now.addingTimeInterval(11 * 60)
        await model.answer(model.held[0], with: .approveOnce)
        XCTAssertTrue(model.held.isEmpty)
        XCTAssertNil(model.taf(taf.id)?.used["refund"], "an expired hold must never be approved")
        XCTAssertEqual(model.record.first?.title, "Expired: Refund #4502")
    }

    func testRevokeStopsAndDropsWaitingCalls() async {
        let model = AppModel(signer: StubSigner())
        let taf = await startedRefunds(model)
        model.propose(ProposedAction(action: "refund", summary: "Refund #4502", amount: .usd(480)), by: taf.id)
        await model.revoke(taf.id)
        XCTAssertTrue(model.held.isEmpty)
        XCTAssertEqual(model.taf(taf.id)?.state, .revoked)

        model.propose(ProposedAction(action: "refund", summary: "Refund #4510", amount: .usd(10)), by: taf.id)
        XCTAssertEqual(model.record.first?.kind, .refused, "nothing is allowed after a revoke")
    }

    func testDraftMatchesTheJob() {
        let draft = AppModel(signer: StubSigner()).draft(job: "Pay our vendor bills when they match a PO")
        XCTAssertEqual(draft.templateKey, "billpay")
        XCTAssertTrue(draft.rules.contains { $0.action == "new_payee" && $0.never })
    }
}
