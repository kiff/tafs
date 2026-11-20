import XCTest
@testable import Tafs

/// A payee name, an IBAN or a reason from KIFF, a vendor or a taf never carries a direction character onto the approval
/// or the record (qa-kiff, 20 Nov): U+202E or an isolate could make "DE89 3704 … €150.00" look like other digits or
/// another amount right beside where the owner approves. They're taken out; any other control character is a space.
@MainActor
final class DirectionCharacterTests: XCTestCase {
    private struct List: Decodable { let exceptions: [KIFFClient.CardException] }
    private struct Calls: Decodable { let calls: [KIFFClient.ActivityCall] }

    private func hasDirection(_ s: String) -> Bool { s.unicodeScalars.contains(where: FileText.isDirection) }

    func testVisibleTakesOutDirectionAndSpacesControls() {
        XCTAssertEqual(KIFFMapping.visible("Acme\u{202E}DTL"), "AcmeDTL")
        XCTAssertEqual(KIFFMapping.visible("\u{2066}DE89 3704 0044 0532 0130 00\u{2069}"), "DE89 3704 0044 0532 0130 00")
        XCTAssertEqual(KIFFMapping.visible("a\u{200E}b\u{200F}c\u{061C}d"), "abcd")
        XCTAssertEqual(KIFFMapping.visible("one\ttwo\u{0007}"), "one two ")
        // Right-to-left words themselves stay: only the invisible controls go.
        XCTAssertEqual(KIFFMapping.visible("شركة أكمي"), "شركة أكمي")
        XCTAssertEqual(KIFFMapping.visible("€150.00"), "€150.00")
    }

    func testAHeldCallsDetailsSummaryAndReasonCarryNone() throws {
        let body = #"""
        {"exceptions":[{"id":"ce_1","subject":"pay-k5","action":"send_payment","title":"Pay‮ a supplier","entity_id":"inv⁧-42",
          "status":"held","breaches":[],"reason":"Over the limit‮ for one payment",
          "arguments":{"payee":"Acme‮DTL","iban":"⁦DE89 3704 0044 0532 0130 00⁩","note":"line\none"},
          "created_at":"2026-10-19T19:00:00.000Z","expires_at":"2026-10-19T19:10:00Z"}]}
        """#
        let e = try KIFFClient.decoder.decode(List.self, from: Data(body.utf8)).exceptions[0]
        let call = KIFFMapping.held(e, tafID: UUID())
        XCTAssertEqual(call.details, ["iban: DE89 3704 0044 0532 0130 00", "note: line one", "payee: AcmeDTL"])
        XCTAssertFalse(hasDirection(call.proposed.summary), call.proposed.summary)
        XCTAssertFalse(hasDirection(call.reason), call.reason)
    }

    func testARecordEntryCarriesNone() throws {
        let raw = #"""
        {"calls":[{"proposal_id":"p_1","action_name":"send_payment","title":"Pay‮","actor_id":"agt","outcome":"refused","held":false,
          "created_at":"2026-11-04T12:00:00Z","entity_id":"inv⁦-42","reason":"payee‮ blocked","reasons":["over⁨ €100"]}]}
        """#
        let calls = try KIFFClient.decoder.decode(Calls.self, from: Data(raw.utf8)).calls
        let entry = try XCTUnwrap(KIFFMapping.record(calls, ids: ["agt": UUID()]).first)
        for text in [entry.title, entry.detail, entry.entityID ?? "", entry.reason ?? ""] + (entry.why ?? []) {
            XCTAssertFalse(hasDirection(text), text)
        }
        XCTAssertEqual(entry.reason, "payee blocked")
    }

    /// A Card rule's label can be a vendor's tool name: it's drawn on the Card beside its limit, so it carries none either.
    func testACardRulesLabelCarriesNone() throws {
        let entry = HomeJSON.entry(agent: "rae-k3f9", name: "Rae")
            .replacingOccurrences(of: #""rules":[]"#, with: #""rules":[{"action":"refund","label":"Refund\u202E a payment","outside":"hold","window":"day"}]"#)
        XCTAssertTrue(entry.contains("u202E"), "the fixture has a rule with a label")
        let home = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(HomeJSON.list([entry]).utf8))
        let taf = KIFFMapping.taf(try XCTUnwrap(home.tafs.first), id: UUID(), owner: "You")
        let labels = taf.card.rules.map(\.label)
        XCTAssertFalse(labels.isEmpty)
        XCTAssertTrue(labels.allSatisfy { !hasDirection($0) }, "\(labels)")
        XCTAssertTrue(labels.contains("Refund a payment"))
    }

    /// agentgabo's read of #1547: a vendor's own message quoted into the record, a tool's or vendor's name in words, and a
    /// taf's name and job from tafs cloud carry none either, so every line `KIFFMapping` builds from a server value is clean.
    func testQuotedMessagesNamesAndTafsCarryNone() throws {
        XCTAssertEqual(KIFFMapping.quotable("Card\u{202E} declined"), "Card declined")
        XCTAssertEqual(KIFFMapping.toolWords("stripe.create\u{2066}_refund"), "create refund")
        XCTAssertEqual(KIFFMapping.vendorWords("mcp.str\u{202E}ipe.com"), "Stripe")
        XCTAssertEqual(KIFFMapping.name("x", title: "Pay\u{202E} now"), "Pay now")
        let (_, words) = KIFFMapping.executed(.init(status: "not_sent", message: "Bank\u{202E} said no"), approved: false)
        XCTAssertFalse(hasDirection(words), words)

        let entry = HomeJSON.entry(agent: "rae-k3f9", name: "Ra\u{202E}e")
        let home = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(HomeJSON.list([entry]).utf8))
        let taf = KIFFMapping.taf(try XCTUnwrap(home.tafs.first), id: UUID(), owner: "You")
        XCTAssertEqual(taf.name, "Rae")
        XCTAssertFalse(hasDirection(taf.job), taf.job)
    }
}
