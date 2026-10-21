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

    /// kiff/tafs#115: KIFF's decision isn't what the tool did. Only the tool's own "done" is
    /// done; a refusal, a call never sent, one still sending, and no outcome at all are not,
    /// for an ordinary call and one the owner approved alike.
    func testTheRecordShowsWhatTheToolDidNotJustWhatKIFFAllowed() throws {
        let statuses: [String?] = ["done", "refused_by_tool", "not_sent", "sending", "unknown", "not_recorded", nil]
        let want: [String?: RecordEntry.Kind] = ["done": .done, "refused_by_tool": .notDone, "not_sent": .notDone,
                                                "sending": .sending, "unknown": .unknown, "not_recorded": .unknown]
        let taf = UUID()
        for held in [false, true] {
            for (i, status) in statuses.enumerated() {
                let outcome = status.map { #","tool_outcome":{"status":"\#($0)","message":"card_declined"}"# } ?? ""
                let json = #"{"proposal_id":"p\#(i)","action_name":"create_refund","actor_id":"rae","outcome":"allowed","held":\#(held),"created_at":"2026-10-20T09:00:00Z"\#(outcome)}"#
                let call = try decoder.decode(KIFFClient.ActivityCall.self, from: Data(json.utf8))
                let entry = try XCTUnwrap(KIFFMapping.record([call], ids: ["rae": taf]).first)
                var kind = want[status] ?? .unknown
                if kind == .done, held { kind = .approved }
                XCTAssertEqual(entry.kind, kind, "status \(status ?? "none"), held \(held)")
                if status != "done" {
                    XCTAssertFalse([.done, .approved].contains(entry.kind), "\(status ?? "none") read as done")
                    XCTAssertFalse(entry.detail.lowercased().contains("done"), entry.detail)
                }
                XCTAssertEqual(entry.ownerApproved, held ? true : nil, "the owner's approval is kept whatever the tool did")
                if status == "refused_by_tool" {
                    XCTAssertTrue(entry.detail.hasSuffix(": card_declined"), "the tool's own words: \(entry.detail)")
                }
            }
        }
    }

}

/// What the owner's retest of build 473 found in the words (kiff/tafs#138, items 54 and 55).
final class RetestWordsTests: XCTestCase {
    func testANameEndingInSHasItsOwnPossessive() throws {
        let d = try JSONDecoder().decode(KIFFClient.AgentDraft.self, from: Data(#"""
        {"id":"drf_2","agent_id":"refunds-bxkwf7vi","label":"Stripe refunds","status":"draft","version":"v1","cards":[]}
        """#.utf8))
        XCTAssertEqual(d.cardTitle, "Stripe refunds' Card")
    }

    /// A taf with no schedule isn't running: it works from its chat, and reads as ready.
    func testATafWithNoScheduleReadsAsWorkingFromItsChat() throws {
        let home = try KIFFClient.decoder.decode(TafsCloud.HomeTaf.self, from: Data(#"""
        {"id":"taf-1","name":"Stripe refunds","job":"refunds","kiff_agent":"refunds-bxkwf7vi","status":"active","unscheduled":true,
         "next_run":null,"last_run":null,"color":1,"state":"working","waiting":0,
         "card":{"ids":["c1"],"active":true,"expires":null,"tools":[],"rules":[],"used":{},"usage_unknown":[]}}
        """#.utf8))
        let taf = KIFFMapping.taf(home, id: UUID(), owner: "You")
        XCTAssertEqual(taf.now, "Works from its chat")
        XCTAssertEqual(taf.state, .idle)
        XCTAssertEqual(taf.state.label, "Ready")
    }
}
