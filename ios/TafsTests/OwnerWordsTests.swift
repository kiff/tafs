import XCTest
@testable import Tafs

/// What the owner reads on an approval and a Card is in their words (kiff/tafs#94):
/// no agent id, no "sum(amount)", no minor units.
final class OwnerWordsTests: XCTestCase {
    private func draft(_ json: String) throws -> KIFFClient.AgentDraft {
        try JSONDecoder().decode(KIFFClient.AgentDraft.self, from: Data(json.utf8))
    }

    /// The owner's iPad test: "create_refund: at most 10000 sum(amount) each" while the chat
    /// said €100. With the units KIFF now gives (kiff-cloud #1274), the lines say €100.00.
    func testADraftsLinesSayTheAmountsInTheirUnit() throws {
        let d = try draft(#"""
        {"id":"drf_1","agent_id":"refunds-k5j6m5qx","label":"Refunds assistant","status":"draft","version":"v1",
         "cards":[{"grants":[{"action":"create_refund","per_action_max":[{"quantity":"sum(amount)","max":10000,"unit":"EUR","scale":2}]}],
                   "aggregates":[{"quantity":"sum(amount)","limit":100000,"window":"calendar_day","unit":"EUR","scale":2},
                                 {"quantity":"count","limit":20,"window":"calendar_day"}]}]}
        """#)
        let euros = { (minor: Int64) in Money(minor: minor, currency: "EUR", scale: 2).formatted }
        XCTAssertEqual(d.cards?.flatMap(\.lines), [
            "Create refund: up to \(euros(10000)) each",
            "\(euros(100000)) in total a day",
            "20 calls a day",
        ])
        XCTAssertEqual(d.cardTitle, "Refunds assistant's Card", "the taf's name, never refunds-k5j6m5qx")
        for line in d.cards?.flatMap(\.lines) ?? [] {
            XCTAssertFalse(line.contains("sum("), line)
            XCTAssertFalse(line.contains("10000"), line)
        }
    }

    /// A draft with no name says whose Card it is without an id.
    func testADraftWithoutANameIsTheTafsCard() throws {
        let d = try draft(#"{"id":"drf_1","agent_id":"refunds-x","status":"draft","version":"v1","cards":[]}"#)
        XCTAssertEqual(d.cardTitle, "The taf's Card")
    }

    /// A Card with no unit declared stays bare, but a total and a count read apart: the old
    /// Refunds Card said "≤ 50 each · 10 a day · 20 a day".
    func testABareTotalAndACountReadApart() {
        var rule = CardRule(action: "refund_order", label: "Refund order")
        rule.perAction = .bare(50)
        rule.perWindow = .bare(10)
        rule.countPerWindow = 20
        XCTAssertEqual(rule.summary, "≤ 50 each · 10 in total a day · 20 calls a day")

        rule.perWindow = Money(minor: 100000, currency: "EUR", scale: 2)
        rule.countPerWindow = 1
        XCTAssertEqual(rule.summary, "≤ 50 each · \(Money(minor: 100000, currency: "EUR", scale: 2).formatted) a day · 1 call a day")
    }
}
