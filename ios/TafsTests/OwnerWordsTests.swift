import XCTest
@testable import Tafs

/// What the owner reads on an approval and a Card is in their words (kiff/tafs#94):
/// no agent id, no "sum(amount)", no minor units.
final class OwnerWordsTests: XCTestCase {
    private func draft(_ json: String) throws -> KIFFClient.AgentDraft {
        try JSONDecoder().decode(KIFFClient.AgentDraft.self, from: Data(json.utf8))
    }

    /// The owner's iPad test: "create_refund: at most 10000 sum(amount) each" while the chat
    /// said €100. With the units KIFF now gives (kiff-cloud #1274), the lines say €100.00, and
    /// the vendor's title stands beside KIFF's action (an approval names what KIFF decides on).
    func testADraftsLinesSayTheAmountsInTheirUnit() throws {
        let d = try draft(#"""
        {"id":"drf_1","agent_id":"refunds-k5j6m5qx","label":"Refunds assistant","status":"draft","version":"v1",
         "cards":[{"grants":[{"action":"create_refund_k3f9","title":"Refund a payment","per_action_max":[{"quantity":"sum(amount)","max":10000,"unit":"EUR","scale":2}]}],
                   "aggregates":[{"quantity":"sum(amount)","limit":100000,"window":"calendar_day","unit":"EUR","scale":2},
                                 {"quantity":"count","limit":20,"window":"calendar_day"}]}]}
        """#)
        let euros = { (minor: Int64) in Money(minor: minor, currency: "EUR", scale: 2).formatted }
        XCTAssertEqual(d.cards?.flatMap(\.lines), [
            "Refund a payment · create_refund_k3f9: up to \(euros(10000)) each",
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

/// A taf's tools are what its Cards grant, under KIFF's titles (kiff/tafs#96).
final class TafToolsTests: XCTestCase {
    func testATafListsTheToolsItsCardsGrant() throws {
        let json = #"""
        {"mandates":[{"id":"m1","subject":"refunds-k5j6m5qx","active":true,
          "grants":[{"action":"create_refund_k3f9","title":"Refund a payment"},{"action":"list_charges_k3f9"}],"aggregates":[]}]}
        """#
        struct List: Decodable { let mandates: [KIFFClient.Mandate] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let mandates = try decoder.decode(List.self, from: Data(json.utf8)).mandates
        var ids: [String: UUID] = [:]
        let taf = try XCTUnwrap(KIFFMapping.tafs(from: mandates, ids: &ids, owner: "You").first)
        XCTAssertEqual(taf.tools, ["Refund a payment", "list_charges_k3f9"], "KIFF's title, else the action as KIFF sends it")
        XCTAssertNil(taf.card.expires, "no valid_until: no end date")
    }
}

/// From the owner's test of 291 (kiff/tafs#102): what a held call is on, what's left on the
/// Card, and what a taf did, as KIFF says them.
@MainActor
final class AfterTheTestTests: XCTestCase {
    private let decoder: JSONDecoder = KIFFClient.decoder

    func testAHeldGatewayCallSaysWhatItIsOnNotItsToolCallID() throws {
        let json = #"""
        {"exceptions":[{"id":"ce_1","subject":"refunds-k5j6m5qx","action":"create_refund","title":"Refund a payment","entity_id":"tc-bc422e3c",
          "status":"held","breaches":[{"outcome":"per_action_exceeded","reason":"150.00 EUR is above the 100.00 EUR limit for one call","quantity":"sum(amount)","limit":10000,"unit":"EUR","scale":2}],
          "parameters":{"amount":15000},"parameter_units":{"amount":{"unit":"EUR","scale":2}},
          "arguments":{"payment_intent":"pi_3USLXh","amount":15000,"kiff_operation_id":"op_1"},
          "created_at":"2026-10-19T19:00:00.000Z","expires_at":"2026-10-19T19:10:00Z"}]}
        """#
        struct List: Decodable { let exceptions: [KIFFClient.CardException] }
        let e = try decoder.decode(List.self, from: Data(json.utf8)).exceptions[0]
        let held = KIFFMapping.held(e, tafID: UUID())
        XCTAssertEqual(held.proposed.summary, "Refund a payment · create_refund", "no tool-call id")
        XCTAssertEqual(held.details, ["payment_intent: pi_3USLXh"], "the amount shows above it, and KIFF's operation id isn't the owner's")
        XCTAssertEqual(held.reason, "150.00 EUR is above the 100.00 EUR limit for one call", "KIFF's words, in the unit")
    }

    func testTheCardSaysWhatsLeft() {
        var rule = CardRule(action: "create_refund", label: "Refund a payment")
        rule.perWindow = Money(minor: 100000, currency: "EUR", scale: 2)
        let left = rule.left(used: Money(minor: 20000, currency: "EUR", scale: 2))
        XCTAssertEqual(left, "\(Money(minor: 80000, currency: "EUR", scale: 2).formatted) left today")
        XCTAssertNil(rule.left(used: .bare(5)), "a use in another unit says nothing")
        XCTAssertNil(rule.left(used: nil))
    }

    func testTheRecordIsWhatKIFFRecorded() throws {
        let json = #"""
        {"calls":[
          {"proposal_id":"p3","action_name":"create_refund","title":"Refund a payment","actor_id":"refunds-k5j6m5qx","outcome":"allowed","held":true,"created_at":"2026-10-19T19:02:09Z","tool_outcome":{"status":"done"}},
          {"proposal_id":"p2","action_name":"create_refund","actor_id":"refunds-k5j6m5qx","outcome":"allowed","held":false,"created_at":"2026-10-19T19:02:08Z","tool_outcome":{"status":"done"}},
          {"proposal_id":"p1","action_name":"create_refund","actor_id":"someone-else","outcome":"allowed","held":false,"created_at":"2026-10-19T18:57:48Z"}]}
        """#
        struct List: Decodable { let calls: [KIFFClient.ActivityCall] }
        let calls = try decoder.decode(List.self, from: Data(json.utf8)).calls
        let taf = UUID()
        let record = KIFFMapping.record(calls, ids: ["refunds-k5j6m5qx": taf])
        XCTAssertEqual(record.map(\.kind), [.approved, .done], "a call of an agent the app doesn't show is left out")
        XCTAssertEqual(record.map(\.title), ["Refund a payment", "create_refund"])
        XCTAssertEqual(record.first?.tafID, taf)
        XCTAssertEqual(KIFFMapping.record(calls, ids: ["refunds-k5j6m5qx": taf]).map(\.id), record.map(\.id), "the same ids on every refresh")
    }
}
