import XCTest
@testable import Tafs

final class CardCheckTests: XCTestCase {
    private func taf(_ rules: [CardRule]) -> Taf {
        Taf(name: "Refunds", job: "", tools: [], colorIndex: 0,
            card: Card(rules: rules, issuedBy: "Owner", expires: .distantFuture))
    }

    private let refunds = CardRule(action: "refund", label: "Refunds", perAction: .usd(150), perWindow: .usd(2_000), window: .day)

    func testWithinTheCardIsAllowed() {
        let decision = CardCheck.decide(ProposedAction(action: "refund", summary: "", amount: .usd(40)), for: taf([refunds]))
        XCTAssertEqual(decision, .allowed)
    }

    func testOverPerActionIsHeld() {
        let decision = CardCheck.decide(ProposedAction(action: "refund", summary: "", amount: .usd(480)), for: taf([refunds]))
        XCTAssertEqual(decision, .held(reason: "Over $150 each"))
    }

    func testWindowTotalCountsWhatWasUsed() {
        var t = taf([refunds])
        for _ in 0..<13 { CardCheck.draw(ProposedAction(action: "refund", summary: "", amount: .usd(150)), on: &t) }
        // $1,950 used: another $100 would pass $2,000 a day.
        let decision = CardCheck.decide(ProposedAction(action: "refund", summary: "", amount: .usd(100)), for: t)
        XCTAssertEqual(decision, .held(reason: "Over $2,000 a day"))
    }

    func testNeverIsRefusedWhateverTheAmount() {
        let payouts = CardRule(action: "payout", label: "Payouts", never: true, outside: .refuse)
        let decision = CardCheck.decide(ProposedAction(action: "payout", summary: "", amount: .usd(1)), for: taf([payouts]))
        XCTAssertEqual(decision, .refused(reason: "Payouts: never allowed"))
    }

    func testAlwaysAskIsHeld() {
        let wires = CardRule(action: "wire", label: "Wires", alwaysAsk: true)
        let decision = CardCheck.decide(ProposedAction(action: "wire", summary: "", amount: .usd(10)), for: taf([wires]))
        XCTAssertEqual(decision, .held(reason: "Wires: always asks you"))
    }

    func testActionNotOnTheCardIsRefused() {
        let decision = CardCheck.decide(ProposedAction(action: "delete_user", summary: ""), for: taf([refunds]))
        XCTAssertEqual(decision, .refused(reason: "Not on this taf's Card"))
    }

    func testRevokedCardRefusesEverything() {
        var t = taf([refunds])
        t.card.revoked = true
        let decision = CardCheck.decide(ProposedAction(action: "refund", summary: "", amount: .usd(1)), for: t)
        XCTAssertEqual(decision, .refused(reason: "Card revoked"))
    }

    func testCountLimitHoldsTheNextOne() {
        let deploys = CardRule(action: "deploy", label: "Deploys", countPerWindow: 2, window: .day)
        var t = taf([deploys])
        CardCheck.draw(ProposedAction(action: "deploy", summary: ""), on: &t)
        CardCheck.draw(ProposedAction(action: "deploy", summary: ""), on: &t)
        XCTAssertEqual(CardCheck.decide(ProposedAction(action: "deploy", summary: ""), for: t), .held(reason: "Over 2 a day"))
    }
}
