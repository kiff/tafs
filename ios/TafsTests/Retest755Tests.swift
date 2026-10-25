import XCTest
import SwiftUI
@testable import Tafs

/// What the owner's device retest of TestFlight 755 found (25 Oct), on the iPad and the
/// iPhone, checked here where it can be without a device.
@MainActor
final class Retest755Tests: XCTestCase {
    private let usd = { (minor: Int64) in Money(minor: minor, currency: "USD", scale: 2) }

    private func taf(_ name: String) -> Taf {
        Taf(name: name, job: "Refunds", tools: [], colorIndex: 0, card: Card(rules: [], issuedBy: "You"))
    }

    /// The tafs list put the one just worked with at the bottom, out of view on the iPhone.
    /// The one waiting on the owner comes first, then the most recently used; the rest keep
    /// tafs cloud's order.
    func testTheTafJustUsedIsListedFirst() {
        let a = taf("Refunds"), b = taf("Refunds 2"), c = taf("Refunds 4"), d = taf("Chargebacks")
        let now = Date()
        let record = [RecordEntry(tafID: c.id, kind: .done, title: "Refund", detail: "", at: now.addingTimeInterval(-60)),
                      RecordEntry(tafID: a.id, kind: .done, title: "Refund", detail: "", at: now.addingTimeInterval(-3600))]
        let chats = [b.id: [TafsCloud.ChatMessage(at: now.addingTimeInterval(-600), from: .owner, text: "hi", session: "s")]]
        XCTAssertEqual(AppModel.byRecent([a, b, c, d], record: record, held: [], chats: chats).map(\.name),
                       ["Refunds 4", "Refunds 2", "Refunds", "Chargebacks"])
        let waiting = HeldCall(tafID: d.id, proposed: ProposedAction(action: "refund", summary: "Refund"), reason: "Over",
                               heldAt: now, expiresAt: now.addingTimeInterval(600))
        XCTAssertEqual(AppModel.byRecent([a, b, c, d], record: record, held: [waiting], chats: chats).first?.name, "Chargebacks")
    }

    /// The Card read "330 US$ a day" for 300 a day and 30 of extra room for today: the owner
    /// took it for an error. Its own total and the extra room read apart, as KIFF says them.
    func testTheCardsOwnTotalAndExtraRoomReadApart() {
        let ends = Date(timeIntervalSince1970: 1_792_969_200)
        let rule = CardRule(action: "refund", label: "Refunds", perAction: usd(5000), perWindow: usd(33000), window: .day,
                            extraRoom: usd(3000), baseLimit: usd(30000), roomEnds: ends)
        XCTAssertEqual(rule.ownTotal, usd(30000))
        XCTAssertEqual(rule.summary, "≤ \(usd(5000).formatted) each · \(usd(30000).formatted) a day · +\(usd(3000).formatted) extra room until \(ends.endsWords)")
        XCTAssertEqual(rule.left(used: usd(28000)), "\(usd(5000).formatted) left today", "what's left counts the extra room")

        let card = TafCard(id: "c", title: "Refunds", rules: [rule],
                           total: CardTotal(limit: usd(33000), used: usd(28000), window: .day, extra: usd(3000), base: usd(30000), roomEnds: ends))
        XCTAssertEqual(card.hero?.figure, usd(5000).formatted)
        XCTAssertEqual(card.hero?.words, "left today of \(usd(30000).formatted) + \(usd(3000).formatted) extra room until \(ends.endsWords)")

        // Nothing is worked out: no word of extra room unless KIFF gives its own limit with it.
        let plain = CardRule(action: "refund", label: "Refunds", perWindow: usd(33000))
        XCTAssertEqual(plain.summary, "\(usd(33000).formatted) a day")
        let half = CardRule(action: "refund", label: "Refunds", perWindow: usd(33000), extraRoom: usd(3000))
        XCTAssertEqual(half.ownTotal, usd(33000))
        XCTAssertNil(half.roomWords)
    }

    /// A week's or a month's room ends days later: "until 00:00" read as tonight. It says the
    /// date then, and only the time when it ends today.
    func testExtraRoomEndingAnotherDaySaysTheDate() {
        let later = Date().addingTimeInterval(3 * 24 * 3600)
        let week = CardRule(action: "refund", label: "Refunds", perWindow: usd(33000), window: .week,
                            extraRoom: usd(3000), baseLimit: usd(30000), roomEnds: later)
        XCTAssertEqual(week.roomWords, "+\(usd(3000).formatted) extra room until \(later.shown(date: .abbreviated, time: .shortened))")
        let tonight = AccountTime.calendar.date(bySettingHour: 23, minute: 59, second: 0, of: Date())!
        XCTAssertEqual(tonight.endsWords, tonight.shortTime)
    }

    /// tafs cloud passes KIFF's extra room on; an older one doesn't, and the Card reads as before.
    func testTheExtraRoomComesFromKIFFThroughTafsCloud() throws {
        let json = #"""
        {"tafs":[{"id":"taf-1","name":"Rae","job":"refunds","kiff_agent":"rae","status":"active","next_run":null,"last_run":null,
          "color":3,"state":"working","waiting":0,
          "card":{"ids":["live"],"active":true,"expires":null,"tools":["Refund a payment"],
            "rules":[{"action":"refund","label":"Refund a payment","outside":"hold","per_action":{"minor":5000,"unit":"USD","scale":2},
                      "per_window":{"minor":33000,"unit":"USD","scale":2},"extra_room":{"minor":3000,"unit":"USD","scale":2},
                      "base_limit":{"minor":30000,"unit":"USD","scale":2},"room_window_end":"2026-10-25T23:00:00Z",
                      "count_per_window":null,"window":"day"}],
            "used":{},"usage_unknown":[],
            "cards":[{"id":"live","title":"Refund a payment","environment":"","live":true,"active":true,"expires":null,"rules":[],
               "total":{"limit":{"minor":33000,"unit":"USD","scale":2},"used":{"minor":28000,"unit":"USD","scale":2},"window":"day",
                        "extra_room":{"minor":3000,"unit":"USD","scale":2},"base_limit":{"minor":30000,"unit":"USD","scale":2},
                        "room_window_end":"2026-10-25T23:00:00Z"},"calls":null}]}}],
         "others":[],"removing":[]}
        """#
        let home = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(json.utf8))
        let rae = KIFFMapping.taf(home.tafs[0], id: UUID(), owner: "You")
        let ends = Date(timeIntervalSince1970: 1_792_969_200)
        XCTAssertEqual(rae.card.rules.first?.extraRoom, usd(3000))
        XCTAssertEqual(rae.card.rules.first?.baseLimit, usd(30000))
        XCTAssertEqual(rae.card.rules.first?.roomEnds, ends)
        XCTAssertEqual(rae.kiffCards?.first?.total?.base, usd(30000))
        XCTAssertEqual(rae.kiffCards?.first?.hero?.words, "left today of \(usd(30000).formatted) + \(usd(3000).formatted) extra room until \(ends.endsWords)")
    }

    /// An answered hold read as if the approval came first: its line sat above the taf's
    /// "waiting for your approval". It goes where the owner answered it.
    func testAnAnsweredHoldSitsWhereItWasAnswered() {
        let made = Date(timeIntervalSince1970: 1_000), answered = Date(timeIntervalSince1970: 1_300)
        var entry = RecordEntry(tafID: UUID(), kind: .approved, title: "Refund", detail: "", at: Date(timeIntervalSince1970: 1_305))
        entry.madeAt = made
        XCTAssertEqual(AppModel.ChatItem.outcome(entry).at, made, "without the answer's time, where it was held")
        entry.answer = KIFFClient.ActivityCall.Answer(decision: "approved", at: answered)
        XCTAssertEqual(AppModel.ChatItem.outcome(entry).at, answered)

        // Its card was placed after the owner's message; once answered it goes by time.
        let owner = TafsCloud.ChatMessage(at: Date(timeIntervalSince1970: 990), from: .owner, text: "Refund $60", session: "s")
        let waiting = TafsCloud.ChatMessage(at: Date(timeIntervalSince1970: 1_010), from: .taf, text: "Waiting for your approval.", session: "s")
        let done = TafsCloud.ChatMessage(at: Date(timeIntervalSince1970: 1_310), from: .taf, text: "Refunded.", session: "s")
        let items: [AppModel.ChatItem] = [.message(owner), .message(waiting), .outcome(entry), .message(done)]
        let placed = AppModel.placed(items, anchors: [entry.id.uuidString: AppModel.ChatItem.message(owner).id])
        XCTAssertEqual(placed.map(\.id), items.map(\.id), "an answered call isn't moved back to where it was held")
    }

    /// The ring around a taf with an open door turned a rounded square, which swept its
    /// corners out and looked broken. Its arc runs along the edge instead, past its start too.
    func testTheDoorArcRunsAlongTheEdge() {
        let rect = CGRect(x: 0, y: 0, width: 60, height: 60)
        let start = PerimeterArc(cornerRadius: 18, phase: 0, length: 0.2).path(in: rect)
        let later = PerimeterArc(cornerRadius: 18, phase: 0.5, length: 0.2).path(in: rect)
        let over = PerimeterArc(cornerRadius: 18, phase: 0.9, length: 0.2).path(in: rect)
        let round = PerimeterArc(cornerRadius: 18, phase: 1.9, length: 0.2).path(in: rect)
        for path in [start, later, over] {
            XCTAssertFalse(path.isEmpty)
            XCTAssertTrue(rect.insetBy(dx: -0.5, dy: -0.5).contains(path.boundingRect), "it never leaves the edge")
        }
        XCTAssertNotEqual(start.boundingRect, later.boundingRect, "it moves")
        XCTAssertEqual(over.boundingRect, round.boundingRect, "a turn later it's where it was")
    }
}
