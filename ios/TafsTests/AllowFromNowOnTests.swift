import XCTest
@testable import Tafs

/// "Allow this from now on" on a held call (docs/allow-from-now-on.md): the app's own rule for
/// the button, tafs cloud's offer made on the tap, and one signature only over KIFF's draft as
/// the phone read it, when it is what was offered. The same cases as Android's
/// AllowFromNowOnTest.
@MainActor
final class AllowFromNowOnTests: XCTestCase {
    private static let g = #""grants":"#
    private static let cap = #"{"quantity":"amount","max":15000,"unit":"EUR","scale":2}"#
    private static let raised = #"{"quantity":"amount","max":32000,"unit":"EUR","scale":2}"#
    private static let day = #"{"quantity":"amount","limit":50000,"window":"day","unit":"EUR","scale":2}"#

    private static func refund(_ caps: String...) -> String {
        #"{"action":"create_refund","per_action_max":[\#(caps.joined(separator: ","))]}"#
    }

    /// KIFF's draft: Card c1 revised to `after`, with `current` its terms in force, and anything `extra` adds.
    private static func card(_ after: String, _ current: String, extra: String = "", retires: String = "", from: String = "", agent: String = #""agent_id":"refunds-1","#) -> String {
        #"{"id":"dr_9",\#(agent)"status":"draft","version":"v7","cards":[{"revises":"c1",\#(after),"current":{\#(current)}}\#(extra)]\#(retires)\#(from)}"#
    }

    private static func draft(max: Int = 32000, extra: String = "", retires: String = "") -> String {
        card("\(g)[\(refund(cap.replacingOccurrences(of: "15000", with: String(max))))]", "\(g)[\(refund(cap))]", extra: extra, retires: retires)
    }

    private static func offerJSON(kind: String = "per_action", before: String = "15000", after: String = "32000", unit: String = #""unit":"EUR","#, window: String = "") -> String {
        #"{"offer":{"changes":[{"mandate_id":"c1","kind":"\#(kind)","before":\#(before),"after":\#(after),\#(unit)\#(window)"words_before":"Refunds up to €150 each","words_after":"Refunds up to €320 each","rounded":false}],"would":{"calls":4,"words":"With this Card, 4 more refunds would have gone out on their own (€1,180)"},"draft":"dr_9","confirm_key":"critical","send_held":true,"round":{"after":35000,"words":"€350"}}}"#
    }

    private func offer(_ json: String = offerJSON()) throws -> TafsCloud.AllowOffer {
        try XCTUnwrap(KIFFClient.decoder.decode(TafsCloud.AllowAnswer.self, from: Data(json.utf8)).offer)
    }

    private func signs(_ draft: String, _ offerJSON: String = offerJSON(), agent: String? = "refunds-1", hold: String? = "ce_9") throws -> Bool {
        let d = try KIFFClient.decoder.decode(KIFFClient.AgentDraft.self, from: Data(draft.utf8))
        return HoldAllow.matches(d, try offer(offerJSON), agent: agent, hold: hold, action: "create_refund")
    }

    private func held(_ outcomes: [String]?, kiffID: String? = "ce_9") -> HeldCall {
        var call = HeldCall(tafID: UUID(), proposed: ProposedAction(action: "create_refund", summary: "Refund a payment"), reason: "Over",
                            heldAt: Date(), expiresAt: Date().addingTimeInterval(600))
        call.kiffID = kiffID
        call.agent = "refunds-1"
        call.accountingVersion = 3
        call.breachOutcomes = outcomes
        return call
    }

    // MARK: The app's own rule for the button

    func testTheButtonShowsOnAHoldACardChangeCanLift() {
        XCTAssertTrue(HoldAllow.allowable(held(["per_action_exceeded"])))
        XCTAssertTrue(HoldAllow.allowable(held(["limit_reached", "resource_not_on_card"])))
        XCTAssertFalse(HoldAllow.allowable(held(["per_action_exceeded", "action_not_on_card"])), "one breach no Card change lifts")
        XCTAssertFalse(HoldAllow.allowable(held(["something_new"])), "a breach this app doesn't know")
        XCTAssertFalse(HoldAllow.allowable(held([])), "no breach")
        XCTAssertFalse(HoldAllow.allowable(held(nil)), "a sample, or a call saved before its breaches were kept")
        XCTAssertFalse(HoldAllow.allowable(held(["per_action_exceeded"], kiffID: nil)), "not KIFF's")
    }

    /// The button waits for the service to say the feature is live: `holds.allow` among the
    /// account's capabilities. Unlike the older capabilities, no list means no button.
    func testTheButtonWaitsForTheServiceToSayItIsLive() throws {
        XCTAssertTrue(AppModel.allowIsLive(["holds.answer", "cards.manage", "holds.allow"]))
        XCTAssertFalse(AppModel.allowIsLive(["holds.answer", "cards.manage"]), "not live yet")
        XCTAssertFalse(AppModel.allowIsLive([]))
        XCTAssertFalse(AppModel.allowIsLive(nil), "an account that lists no capabilities")
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/App/AppModel.swift")
        let file = try String(contentsOf: app, encoding: .utf8)
        let offers = try XCTUnwrap(file.range(of: "func offersAllow(_ call: HeldCall) -> Bool {"))
        let body = file[offers.upperBound...].prefix(300)
        XCTAssertTrue(body.contains("Self.allowIsLive(kiffAccount?.capabilities)"), "the button's rule asks it")
    }

    /// The held call KIFF sends carries what the rule reads: its agent and each breach's outcome.
    func testAHeldCallKeepsItsAgentAndItsBreaches() throws {
        let json = #"{"id":"ce_9","subject":"refunds-1","action":"create_refund","entity_id":"ch_1","status":"held","breaches":[{"mandate_id":"c1","outcome":"per_action_exceeded","quantity":"amount","limit":15000}],"parameters":{"amount":32000},"created_at":"2026-11-12T09:00:00Z","expires_at":"2099-01-01T00:00:00Z","accounting_version":3}"#
        let e = try KIFFClient.decoder.decode(KIFFClient.CardException.self, from: Data(json.utf8))
        let call = KIFFMapping.held(e, tafID: UUID())
        XCTAssertEqual(call.agent, "refunds-1")
        XCTAssertEqual(call.breachOutcomes, ["per_action_exceeded"])
        XCTAssertTrue(HoldAllow.allowable(call))
    }

    // MARK: The device signs what the person saw

    func testADraftThatCarriesExactlyTheOfferedChangeMatches() throws {
        XCTAssertTrue(try signs(Self.draft()))
    }

    func testAnotherFigureAnotherCardARetiredCardOrANewCardIsNotWhatWasOffered() throws {
        XCTAssertFalse(try signs(Self.draft(max: 50000)), "another figure")
        let other = #",{"revises":"c2","grants":[{"action":"list_charges"}],"current":{"grants":[{"action":"list_charges"},{"action":"create_refund"}]}}"#
        XCTAssertFalse(try signs(Self.draft(extra: other)), "another Card changed")
        XCTAssertFalse(try signs(Self.draft(retires: #","retires":[{"id":"c3"}]"#)), "a Card retired")
        XCTAssertFalse(try signs(Self.draft(extra: #",{"grants":[{"action":"create_refund"}]}"#)), "a Card that revises none in force")
        let unchanged = #",{"revises":"c2","unchanged":true,"current":{"grants":[{"action":"list_charges"}]},"grants":[{"action":"list_charges"}]}"#
        XCTAssertTrue(try signs(Self.draft(extra: unchanged)), "a Card left as it is doesn't count")
        let twice = #",{"revises":"c1","grants":[\#(Self.refund(Self.raised))],"current":{"grants":[\#(Self.refund(Self.cap))]}}"#
        XCTAssertFalse(try signs(Self.draft(extra: twice)), "the named Card twice")
    }

    func testAChangeOfAKindTheContractDoesntNameIsNeverSigned() throws {
        XCTAssertFalse(try signs(Self.draft(), Self.offerJSON(kind: "something_new")))
    }

    func testTheChangedCardReadsAsTheCardInForceWithOnlyTheOfferedFigureMoved() throws {
        let g = Self.g, cap = Self.cap, raised = Self.raised, day = Self.day
        let count = cap.replacingOccurrences(of: "amount", with: "count")
        XCTAssertTrue(try signs(Self.card("\(g)[\(Self.refund(raised))]", "\(g)[\(Self.refund(cap))]")), "the offered cap, nothing else")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(raised, count))]", "\(g)[\(Self.refund(cap, count.replacingOccurrences(of: "15000", with: "3")))]")), "a second cap moved too")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(raised, cap.replacingOccurrences(of: "amount", with: "fee")))]", "\(g)[\(Self.refund(cap))]")), "a cap added")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund())]", "\(g)[\(Self.refund(cap))]")), "a cap taken away")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(raised)),{\"action\":\"delete_customer\"}]", "\(g)[\(Self.refund(cap))]")), "an action added")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(raised))]", "\(g)[\(Self.refund(cap)),{\"action\":\"list_charges\"}]")), "an action taken away")
        let payout = #"{"action":"create_payout","per_action_max":[\#(cap)]}"#
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(cap)),\(payout.replacingOccurrences(of: "15000", with: "32000"))]", "\(g)[\(Self.refund(cap)),\(payout)]")), "the same figures on another action's cap")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(raised))],\"aggregates\":[\(day)]", "\(g)[\(Self.refund(cap))]")), "a total added")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(raised))]", "\(g)[\(Self.refund(cap))],\"aggregates\":[\(day)]")), "a total dropped")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(raised))],\"aggregates\":[\(day.replacingOccurrences(of: "day\"", with: "week\""))]", "\(g)[\(Self.refund(cap))],\"aggregates\":[\(day)]")), "a total's window changed")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(raised))],\"aggregates\":[\(day.replacingOccurrences(of: "50000", with: "90000"))]", "\(g)[\(Self.refund(cap))],\"aggregates\":[\(day)]")), "a total's figure changed")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(raised))],\"on_exceed\":\"refuse\"", "\(g)[\(Self.refund(cap))],\"on_exceed\":\"owner\"")), "what happens past a limit changed")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(raised))]", "\(g)[\(Self.refund(cap))],\"on_exceed\":\"owner\"")), "what happens past a limit dropped")
        XCTAssertTrue(try signs(Self.card("\(g)[\(Self.refund(raised).replacingOccurrences(of: "{\"action\"", with: "{\"title\":\"Refund\",\"action\""))],\"lines\":[\"x\"]", "\(g)[\(Self.refund(cap))],\"lines\":[\"y\"]")),
                      "KIFF's lines and a grant's title are words, not terms")
    }

    func testTheUnitMustBeTheOffersExactly() throws {
        let g = Self.g
        let usd = (Self.raised.replacingOccurrences(of: "EUR", with: "USD"), Self.cap.replacingOccurrences(of: "EUR", with: "USD"))
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(usd.0))]", "\(g)[\(Self.refund(usd.1))]")), "another currency")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(Self.raised))]", "\(g)[\(Self.refund(Self.cap))]"), Self.offerJSON(unit: "")), "an offer with no unit for a cap in euros")
        let bare = (#"{"quantity":"amount","max":32000}"#, #"{"quantity":"amount","max":15000}"#)
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(bare.0))]", "\(g)[\(Self.refund(bare.1))]")), "an offer in euros for a cap with no unit")
        XCTAssertTrue(try signs(Self.card("\(g)[\(Self.refund(bare.0))]", "\(g)[\(Self.refund(bare.1))]"), Self.offerJSON(unit: "")), "no unit on either")
    }

    func testATotalOrACountChangesOnlyItsFigureInItsWindow() throws {
        let g = Self.g, cap = Self.cap, day = Self.day
        let total = Self.offerJSON(kind: "total", before: "50000", after: "60000")
        let now = "\(g)[\(Self.refund(cap))],\"aggregates\":[\(day)]"
        func after(_ d: String) -> String { "\(g)[\(Self.refund(cap))],\"aggregates\":[\(d)]" }
        XCTAssertTrue(try signs(Self.card(after(day.replacingOccurrences(of: "50000", with: "60000")), now), total))
        XCTAssertFalse(try signs(Self.card(after(day.replacingOccurrences(of: "50000", with: "70000")), now), total), "another figure")
        XCTAssertFalse(try signs(Self.card(after(day.replacingOccurrences(of: "50000", with: "60000").replacingOccurrences(of: "day\"", with: "week\"")), now), total), "the window moved with it")
        XCTAssertFalse(try signs(Self.card("\(g)[\(Self.refund(Self.raised))],\"aggregates\":[\(day.replacingOccurrences(of: "50000", with: "60000"))]", now), total), "the cap moved too")
        XCTAssertFalse(try signs(Self.card(after(day.replacingOccurrences(of: "50000", with: "60000")), now), Self.offerJSON(kind: "total", before: "50000", after: "60000", unit: #""unit":"USD","#)), "another unit")

        // Two totals at the same figure, a day's and a month's: the offer's window says which one moves.
        let month = day.replacingOccurrences(of: "day\"", with: "month\"")
        let both = "\(g)[\(Self.refund(cap))],\"aggregates\":[\(day),\(month)]"
        let dayMoved = "\(g)[\(Self.refund(cap))],\"aggregates\":[\(day.replacingOccurrences(of: "50000", with: "60000")),\(month)]"
        let monthMoved = "\(g)[\(Self.refund(cap))],\"aggregates\":[\(day),\(month.replacingOccurrences(of: "50000", with: "60000"))]"
        let daily = Self.offerJSON(kind: "total", before: "50000", after: "60000", window: #""window":"day","#)
        XCTAssertTrue(try signs(Self.card(dayMoved, both), daily))
        XCTAssertFalse(try signs(Self.card(monthMoved, both), daily), "the month's total moved, the day's was offered")
    }

    /// A `resource` change is exactly the values in force plus the offered one, on the held
    /// action's scope for the offered parameter.
    func testAResourceChangeAddsExactlyTheOfferedValue() throws {
        let cap = Self.cap
        func scoped(_ values: String, parameter: String = "account", action: String = "create_refund") -> String {
            #""grants":[{"action":"\#(action)","per_action_max":[\#(cap)],"resources":{"parameter":"\#(parameter)","values":[\#(values)]}}]"#
        }
        let resource = #"{"offer":{"changes":[{"mandate_id":"c1","kind":"resource","parameter":"account","value":"acct_2","words_before":"On acct_1","words_after":"On acct_1 and acct_2"}],"draft":"dr_9"}}"#
        let now = scoped(#""acct_1""#)
        XCTAssertTrue(try signs(Self.card(scoped(#""acct_1","acct_2""#), now), resource))
        XCTAssertTrue(try signs(Self.card(scoped(#""acct_2","acct_1""#), now), resource), "the order of the values doesn't matter")
        XCTAssertFalse(try signs(Self.card(now, now), resource), "nothing added")
        XCTAssertFalse(try signs(Self.card(scoped(#""acct_1","acct_2","acct_3""#), now), resource), "a second value added")
        XCTAssertFalse(try signs(Self.card(scoped(#""acct_1","acct_3""#), now), resource), "another value added")
        XCTAssertFalse(try signs(Self.card(scoped(#""acct_2""#), now), resource), "a value in force dropped")
        XCTAssertFalse(try signs(Self.card(#""grants":[\#(Self.refund(cap))]"#, now), resource), "the scope dropped: any value")
        XCTAssertFalse(try signs(Self.card(scoped(#""acct_1","acct_2""#, parameter: "customer"), scoped(#""acct_1""#, parameter: "customer")), resource), "another parameter's scope")
        XCTAssertFalse(try signs(Self.card(scoped(#""acct_1","acct_2""#, action: "create_payout"), scoped(#""acct_1""#, action: "create_payout")), resource), "another action's scope")
        XCTAssertFalse(try signs(Self.card(scoped(#""acct_1","acct_2""#).replacingOccurrences(of: "15000", with: "32000"), now), resource), "a cap moved with it")
        XCTAssertFalse(try signs(Self.card(scoped(#""acct_1","acct_2""#), now), resource.replacingOccurrences(of: #""value":"acct_2","#, with: "")), "an offer that names no value")
        XCTAssertFalse(try signs(Self.card(scoped(#""acct_1","acct_1""#), scoped(#""acct_1""#)), resource.replacingOccurrences(of: "acct_2", with: "acct_1")), "a value already in force")
    }

    /// Each term that decides what a taf may do, one at a time: a draft that differs from the
    /// Card in force in that term, beside the offered cap, is never signed.
    func testEveryOtherTermMustBeTheCardInForces() throws {
        let g = Self.g, cap = Self.cap, raised = Self.raised
        func grant(_ caps: String, _ more: String = "") -> String { #"{"action":"create_refund","per_action_max":[\#(caps)]\#(more)}"# }
        func differs(_ after: String, _ now: String, _ what: String, file: StaticString = #filePath, line: UInt = #line) throws {
            XCTAssertFalse(try signs(Self.card(after, now)), what, file: file, line: line)
        }
        let scope = #","resources":{"parameter":"account","values":["acct_1"]}"#
        // The same terms on both sides, with only the cap moved: signed. Each test below starts from here.
        let whole = #","resources":{"parameter":"account","values":["acct_1"]},"entity_states":["paid"],"next_steps":["return_first"]"#
        let totals = #","aggregates":[{"quantity":"count","limit":20,"window":"day","measure":"attempts","per":"invoice_id"}],"on_exceed":"owner","subject":"refunds-1","valid_until":"2027-01-01T00:00:00Z","domain":"Shop","environment":"live""#
        XCTAssertTrue(try signs(Self.card("\(g)[\(grant(raised, whole))]\(totals)", "\(g)[\(grant(cap, whole))]\(totals),\"subjects\":[\"refunds-1\"]")), "every term the same, the cap moved")

        // A grant's scope.
        try differs("\(g)[\(grant(raised))]", "\(g)[\(grant(cap, scope))]", "the scope dropped")
        try differs("\(g)[\(grant(raised, scope.replacingOccurrences(of: #"["acct_1"]"#, with: #"["acct_1","acct_2"]"#)))]", "\(g)[\(grant(cap, scope))]", "a value added to the scope")
        try differs("\(g)[\(grant(raised, scope.replacingOccurrences(of: "account", with: "customer")))]", "\(g)[\(grant(cap, scope))]", "the scope's parameter changed")
        try differs("\(g)[\(grant(raised, scope))]", "\(g)[\(grant(cap))]", "a scope added")
        // The states it applies in.
        try differs("\(g)[\(grant(raised))]", "\(g)[\(grant(cap, #","entity_states":["paid"]"#))]", "a state restriction dropped")
        try differs("\(g)[\(grant(raised, #","entity_states":["paid","refunded"]"#))]", "\(g)[\(grant(cap, #","entity_states":["paid"]"#))]", "a state added")
        XCTAssertTrue(try signs(Self.card("\(g)[\(grant(raised))]", "\(g)[\(grant(cap, #","entity_states":[]"#))]")), "no states, written as none or as an empty list")
        XCTAssertTrue(try signs(Self.card("\(g)[\(grant(raised))]", "\(g)[\(grant(cap, #","entity_states":null"#))]")), "no states, written as null")
        // The owner's other answers.
        try differs("\(g)[\(grant(raised))]", "\(g)[\(grant(cap, #","next_steps":["return_first"]"#))]", "a next step dropped")
        try differs("\(g)[\(grant(raised, #","next_steps":["return_first","proof_first"]"#))]", "\(g)[\(grant(cap, #","next_steps":["return_first"]"#))]", "a next step added")
        // A total: per item or one total, and what it counts.
        let perItem = #"{"quantity":"count","limit":1,"window":"week","measure":"attempts","per":"invoice_id"}"#
        try differs("\(g)[\(grant(raised))],\"aggregates\":[\(perItem.replacingOccurrences(of: #","per":"invoice_id""#, with: ""))]", "\(g)[\(grant(cap))],\"aggregates\":[\(perItem)]", "a per-item total turned into one total")
        try differs("\(g)[\(grant(raised))],\"aggregates\":[\(perItem.replacingOccurrences(of: "invoice_id", with: "customer_id"))]", "\(g)[\(grant(cap))],\"aggregates\":[\(perItem)]", "a total kept per another parameter")
        try differs("\(g)[\(grant(raised))],\"aggregates\":[\(perItem.replacingOccurrences(of: "attempts", with: "effects"))]", "\(g)[\(grant(cap))],\"aggregates\":[\(perItem)]", "what a total counts changed")
        // A measure a draft leaves out is KIFF's default: attempts for a count, effects for a sum.
        let sum = #"{"quantity":"sum(amount)","limit":50000,"window":"day"}"#
        XCTAssertTrue(try signs(Self.card("\(g)[\(grant(raised))],\"aggregates\":[\(sum)]", "\(g)[\(grant(cap))],\"aggregates\":[\(sum.replacingOccurrences(of: "}", with: #","measure":"effects"}"#))]")), "a sum's measure left out reads as effects")
        try differs("\(g)[\(grant(raised))],\"aggregates\":[\(sum)]", "\(g)[\(grant(cap))],\"aggregates\":[\(sum.replacingOccurrences(of: "}", with: #","measure":"attempts"}"#))]", "a sum counting attempts in force, left out in the draft")
        let count = #"{"quantity":"count","limit":20,"window":"day"}"#
        XCTAssertTrue(try signs(Self.card("\(g)[\(grant(raised))],\"aggregates\":[\(count)]", "\(g)[\(grant(cap))],\"aggregates\":[\(count.replacingOccurrences(of: "}", with: #","measure":"attempts"}"#))]")), "a count's measure left out reads as attempts")
        try differs("\(g)[\(grant(raised))],\"aggregates\":[\(count)]", "\(g)[\(grant(cap))],\"aggregates\":[\(count.replacingOccurrences(of: "}", with: #","measure":"effects"}"#))]", "a count counting effects in force, left out in the draft")
        // When it starts and ends.
        try differs("\(g)[\(grant(raised))],\"valid_until\":\"2028-01-01T00:00:00Z\"", "\(g)[\(grant(cap))],\"valid_until\":\"2027-01-01T00:00:00Z\"", "its end moved later")
        try differs("\(g)[\(grant(raised))]", "\(g)[\(grant(cap))],\"valid_until\":\"2027-01-01T00:00:00Z\"", "its end dropped")
        try differs("\(g)[\(grant(raised))],\"valid_from\":\"2026-01-01T00:00:00Z\"", "\(g)[\(grant(cap))],\"valid_from\":\"2026-06-01T00:00:00Z\"", "its start moved earlier")
        // Who holds it.
        try differs("\(g)[\(grant(raised))],\"subject\":\"refunds-1\",\"subjects\":[\"refunds-1\",\"payouts-1\"]", "\(g)[\(grant(cap))],\"subject\":\"refunds-1\",\"subjects\":[\"refunds-1\"]", "a holder added")
        try differs("\(g)[\(grant(raised))],\"subject\":\"payouts-1\"", "\(g)[\(grant(cap))],\"subject\":\"refunds-1\",\"subjects\":[\"refunds-1\"]", "another first holder")
        XCTAssertTrue(try signs(Self.card("\(g)[\(grant(raised))],\"subject\":\"refunds-1\"", "\(g)[\(grant(cap))],\"subject\":\"refunds-1\",\"subjects\":[\"refunds-1\",\"support-1\"]")),
                      "a draft that names only the same first holder keeps the Card's holders")
        // Where it applies.
        try differs("\(g)[\(grant(raised))],\"domain\":\"other\"", "\(g)[\(grant(cap))],\"domain\":\"shop\"", "another domain")
        try differs("\(g)[\(grant(raised))],\"environment\":\"live\"", "\(g)[\(grant(cap))],\"environment\":\"test\"", "another environment")
        try differs("\(g)[\(grant(raised))],\"location\":\"Europe/Paris\"", "\(g)[\(grant(cap))],\"location\":\"America/New_York\"", "another time zone for its daily totals")
        // What only a draft carries.
        try differs("\(g)[\(grant(raised))],\"hold_expiry\":\"24h\"", "\(g)[\(grant(cap))]", "how long a hold waits, set by the draft")
        try differs("\(g)[\(grant(raised))],\"revoked_at\":\"2026-11-12T00:00:00Z\"", "\(g)[\(grant(cap))]", "a draft that revokes")
    }

    /// A Card marked unchanged is compared on the same terms, not trusted.
    func testACardMarkedUnchangedIsComparedOnEveryTerm() throws {
        let lying = #",{"revises":"c2","unchanged":true,"current":{"grants":[{"action":"list_charges","resources":{"parameter":"account","values":["acct_1"]}}],"valid_until":"2027-01-01T00:00:00Z"},"grants":[{"action":"list_charges"}],"valid_until":"2027-01-01T00:00:00Z"}"#
        XCTAssertFalse(try signs(Self.draft(extra: lying)), "marked unchanged, with its scope dropped")
        let later = #",{"revises":"c2","unchanged":true,"current":{"grants":[{"action":"list_charges"}],"valid_until":"2027-01-01T00:00:00Z"},"grants":[{"action":"list_charges"}],"valid_until":"2030-01-01T00:00:00Z"}"#
        XCTAssertFalse(try signs(Self.draft(extra: later)), "marked unchanged, with its end moved later")
    }

    func testACardMarkedUnchangedMustReadAsInForce() throws {
        let lying = #",{"revises":"c2","unchanged":true,"current":{"grants":[{"action":"list_charges"}]},"grants":[{"action":"list_charges"},{"action":"delete_customer"}]}"#
        XCTAssertFalse(try signs(Self.draft(extra: lying)), "marked unchanged, with an action added")
    }

    func testTheDraftMustBeThisHeldCallsAgentsAndFromThisHoldWhenKiffSays() throws {
        let g = Self.g
        let after = "\(g)[\(Self.refund(Self.raised))]", now = "\(g)[\(Self.refund(Self.cap))]"
        XCTAssertFalse(try signs(Self.card(after, now, agent: #""agent_id":"payouts-1","#)), "another agent's draft")
        XCTAssertFalse(try signs(Self.card(after, now), agent: nil), "a held call with no agent")
        XCTAssertFalse(try signs(Self.card(after, now), agent: ""), "a held call with an empty agent")
        XCTAssertFalse(try signs(Self.card(after, now, from: #","from_hold":"ce_8""#)), "made from another held call")
        XCTAssertTrue(try signs(Self.card(after, now, from: #","from_hold":"ce_9""#)), "made from this one")
    }

    // MARK: The offer, read on the tap

    private func clients() -> (TafsCloud, KIFFClient) {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        return (TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: session),
                KIFFClient(baseURL: URL(string: "https://api.test")!, webURL: URL(string: "https://app.test")!, token: "dev_token",
                           serverVerifiesSignatures: true, session: session))
    }

    private func sent(_ method: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    private func body(_ request: URLRequest?) throws -> [String: Any] {
        let request = try XCTUnwrap(request)
        var data = request.httpBody
        if data == nil, let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var read = Data()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buffer, maxLength: buffer.count)
                if n <= 0 { break }
                read.append(buffer, count: n)
            }
            data = read
        }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(data)) as? [String: Any])
    }

    func testTheOfferIsAskedOnTheTapAndTheDraftIsReadFromKiff() async throws {
        let (cloud, kiff) = clients()
        StubProtocol.responses["POST /v1/tafs/taf_1/holds/ce_9/allow"] = (200, Self.offerJSON())
        StubProtocol.responses["GET /v1/me/agent-drafts/dr_9"] = (200, Self.draft())
        let read = try await HoldAllow.read(cloud: cloud, kiff: kiff, taf: "taf_1", call: held(["per_action_exceeded"]), round: false)
        XCTAssertTrue(read.matches)
        XCTAssertEqual(read.draft?.version, "v7")
        XCTAssertEqual(read.answer.offer?.round?.words, "€350")
        XCTAssertTrue(try body(sent("POST", "/v1/tafs/taf_1/holds/ce_9/allow").last).isEmpty, "the exact figure: an empty body")

        _ = try await HoldAllow.read(cloud: cloud, kiff: kiff, taf: "taf_1", call: held(["per_action_exceeded"]), round: true)
        XCTAssertEqual(try body(sent("POST", "/v1/tafs/taf_1/holds/ce_9/allow").last)["round"] as? Bool, true, "rounding is tafs cloud's, never the app's")
    }

    func testADraftThatIsntWhatWasOfferedDoesNotMatch() async throws {
        let (cloud, kiff) = clients()
        StubProtocol.responses["POST /v1/tafs/taf_1/holds/ce_9/allow"] = (200, Self.offerJSON())
        StubProtocol.responses["GET /v1/me/agent-drafts/dr_9"] = (200, Self.draft(max: 99000))
        let read = try await HoldAllow.read(cloud: cloud, kiff: kiff, taf: "taf_1", call: held(["per_action_exceeded"]), round: false)
        XCTAssertFalse(read.matches)
        XCTAssertNotNil(read.draft, "read, so the sheet says it isn't what was offered")
    }

    /// No offer, for a reason this app knows or not: tafs cloud's words, and no draft is read.
    func testNoOfferReadsNoDraft() async throws {
        for none in ["no_change", "refused", "never", "something_new"] {
            let (cloud, kiff) = clients()
            StubProtocol.responses["POST /v1/tafs/taf_1/holds/ce_9/allow"] = (200, #"{"none":"\#(none)","words":"No Card limit holds this one back."}"#)
            let read = try await HoldAllow.read(cloud: cloud, kiff: kiff, taf: "taf_1", call: held(["per_action_exceeded"]), round: false)
            XCTAssertNil(read.answer.offer, none)
            XCTAssertEqual(read.answer.words, "No Card limit holds this one back.")
            XCTAssertNil(read.draft)
            XCTAssertFalse(read.matches)
            XCTAssertTrue(sent("GET", "/v1/me/agent-drafts/dr_9").isEmpty)
        }
    }

    /// An offer without what it would have done still reads: the replay couldn't be read.
    func testAnOfferWithoutItsReplayStillReads() throws {
        let json = #"{"offer":{"changes":[{"mandate_id":"c1","kind":"per_action","before":15000,"after":32000,"unit":"EUR"}],"draft":"dr_9"}}"#
        let offer = try offer(json)
        XCTAssertNil(offer.would)
        XCTAssertNil(offer.round)
        XCTAssertNil(offer.sendHeld)
    }

    // MARK: What's signed and sent

    /// The sheet's file signs only a draft that matches, and sends the held call only with the switch on.
    func testTheModelSignsOnlyAMatchAndSendsTheHeldCallOnlyWithTheSwitchOn() throws {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/App/AppModel.swift")
        let file = try String(contentsOf: app, encoding: .utf8)
        let start = try XCTUnwrap(file.range(of: "func confirmAllow() async -> Bool {"))
        let end = try XCTUnwrap(file.range(of: "// MARK: The owner answers", range: start.upperBound..<file.endIndex))
        let confirm = String(file[start.upperBound..<end.lowerBound])
        let guardLine = try XCTUnwrap(confirm.range(of: "open.matches"))
        let sign = try XCTUnwrap(confirm.range(of: "signer.sign("))
        XCTAssertTrue(guardLine.lowerBound < sign.lowerBound, "the match is checked before anything is signed")
        XCTAssertTrue(confirm.contains("tafs.cards.v1|\\(tenant)|\\(draft.id)|\\(draft.version)"), "the version this phone read is what's signed")
        XCTAssertTrue(confirm.contains("if open.sendHeld {"))
        XCTAssertTrue(confirm.contains("if let hold {"), "the held call goes only when the switch is on")
        let load = String(file[try XCTUnwrap(file.range(of: "func loadAllow(round: Bool) async -> Bool {")).upperBound..<start.lowerBound])
        XCTAssertTrue(load.contains("if !round { now.sendHeld"), "rounding up keeps the person's switch")
        XCTAssertTrue(load.contains("now.call.id == call.id"), "a late answer for another call changes nothing")
    }

    /// The confirm names the held call and the version of it the person saw.
    func testTheConfirmCarriesTheHeldCall() async throws {
        let (_, kiff) = clients()
        kiff.tenant = "t_01"
        kiff.setupSigner = { _ in "signature" }
        StubProtocol.responses["POST /v1/me/agent-drafts/dr_9/confirm"] = (200, #"{"id":"dr_9","agent_id":"refunds-1","status":"confirmed","version":"v7"}"#)
        let confirmed = try await kiff.confirmDraft(id: "dr_9", version: "v7", hold: "ce_9", accountingVersion: 3)
        XCTAssertTrue(confirmed.confirmed)
        let sent = try body(sent("POST", "/v1/me/agent-drafts/dr_9/confirm").last)
        XCTAssertEqual(sent["version"] as? String, "v7")
        let hold = try XCTUnwrap(sent["hold"] as? [String: Any])
        XCTAssertEqual(hold["id"] as? String, "ce_9")
        XCTAssertEqual(hold["accounting_version"] as? Int, 3)
    }
}
