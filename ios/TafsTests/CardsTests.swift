import XCTest
@testable import Tafs

/// Each KIFF Card drawn on its own (kiff/tafs#211): its figure, its lines, its environment.
final class CardsTests: XCTestCase {
    private let eur = { (minor: Int64) in Money(minor: minor, currency: "EUR", scale: 2) }

    func testEachCardComesFromTafsCloudOnItsOwn() throws {
        let json = #"""
        {"tafs":[{"id":"taf-1","name":"Rae","job":"refunds","kiff_agent":"rae","status":"active","next_run":null,"last_run":null,
          "color":3,"state":"working","waiting":0,
          "card":{"ids":["live","test"],"active":true,"expires":null,"tools":["Refund a payment"],
            "rules":[{"action":"refund","label":"Refund a payment","outside":"hold","per_action":{"minor":5000,"unit":"EUR","scale":2},
                      "per_window":{"minor":80000,"unit":"EUR","scale":2},"count_per_window":null,"window":"day"}],
            "used":{},"usage_unknown":[],
            "cards":[
              {"id":"live","title":"Refund a payment","environment":"production","live":true,"active":true,"expires":null,
               "rules":[{"action":"refund","label":"Refund a payment","outside":"hold","per_action":{"minor":5000,"unit":"EUR","scale":2},
                         "per_window":{"minor":80000,"unit":"EUR","scale":2},"count_per_window":null,"window":"day"}],
               "total":{"limit":{"minor":80000,"unit":"EUR","scale":2},"used":{"minor":18000,"unit":"EUR","scale":2},"window":"day"},
               "calls":null},
              {"id":"test","title":"Refund a payment","environment":"test","live":false,"active":true,"expires":null,
               "rules":[],"total":null,"calls":{"limit":200,"used":null,"window":"day"}}]}}],
         "others":[],"removing":[]}
        """#
        let home = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(json.utf8))
        let rae = KIFFMapping.taf(home.tafs[0], id: UUID(), owner: "You")
        let cards = try XCTUnwrap(rae.kiffCards)
        XCTAssertEqual(cards.map(\.id), ["live", "test"], "the live Card first, as tafs cloud sends them")
        XCTAssertEqual(cards[0].total, CardTotal(limit: eur(80000), used: eur(18000), window: .day))
        XCTAssertEqual(cards[0].environmentLabel, "LIVE")
        XCTAssertEqual(cards[0].rules.first?.perAction, eur(5000))
        XCTAssertEqual(cards[1].environmentLabel, "TEST")
        XCTAssertEqual(cards[1].calls, CardTotal(limit: 200, used: nil, window: .day))
        XCTAssertEqual(rae.shownCards.map(\.id), ["live", "test"])
    }

    func testTheBigFigureIsWhatIsLeft() {
        let card = TafCard(id: "c", title: "Refunds", rules: [CardRule(action: "refund", label: "Refunds", perAction: eur(5000))],
                           total: CardTotal(limit: eur(80000), used: eur(18000), window: .day))
        XCTAssertEqual(card.hero?.figure, eur(62000).formatted)
        XCTAssertEqual(card.hero?.words, "left today of \(eur(80000).formatted)")
        XCTAssertEqual(card.facts, ["≤ \(eur(5000).formatted) each · asks you above"])
    }

    func testUseKIFFCouldNotReadIsNeverShownAsZero() {
        let money = TafCard(id: "c", title: "Refunds", rules: [], total: CardTotal(limit: eur(80000), used: nil, window: .week))
        XCTAssertEqual(money.hero?.figure, eur(80000).formatted)
        XCTAssertEqual(money.hero?.words, "a week · use unavailable")
        let calls = TafCard(id: "c", title: "Reads", rules: [], calls: CardTotal(limit: 200, used: nil, window: .day))
        XCTAssertEqual(calls.hero?.figure, "200")
        XCTAssertEqual(calls.hero?.words, "calls a day · use unavailable")
    }

    func testACardWithNoMoneyTotalShowsItsCallsLeft() {
        let reads = TafCard(id: "r", title: "Read Stripe",
                            rules: [CardRule(action: "list", label: "List charges"), CardRule(action: "get", label: "Look up a charge")],
                            calls: CardTotal(limit: 200, used: 20, window: .day))
        XCTAssertEqual(reads.hero?.figure, "180")
        XCTAssertEqual(reads.hero?.words, "calls left today of 200")
        XCTAssertEqual(reads.facts, ["2 tools"])
        let last = TafCard(id: "r", title: "Read Stripe", rules: [], calls: CardTotal(limit: 200, used: 199, window: .day))
        XCTAssertEqual(last.hero?.words, "call left today of 200")
    }

    func testACardWithNeitherShowsItsFirstLines() {
        let card = TafCard(id: "c", title: "Lookups", rules: [
            CardRule(action: "a", label: "Look up", perAction: eur(1000)),
            CardRule(action: "b", label: "Payouts", never: true),
            CardRule(action: "c", label: "Notes"),
        ])
        XCTAssertNil(card.hero)
        XCTAssertEqual(card.facts.count, 3)
        XCTAssertEqual(card.facts[1], "payouts never")
        XCTAssertEqual(card.facts[2], "1 more")
    }

    func testOneCardFromATafsLinesWhenTafsCloudSendsNoCards() {
        var taf = Taf(name: "Refunds", job: "", tools: [], colorIndex: 0,
                      card: Card(rules: [CardRule(action: "refund", label: "Refunds", perAction: eur(5000), perWindow: eur(80000))],
                                 issuedBy: "You", expires: nil))
        taf.used = ["refund": eur(30000)]
        XCTAssertEqual(taf.shownCards.count, 1)
        XCTAssertEqual(taf.shownCards[0].title, "Refunds")
        XCTAssertEqual(taf.shownCards[0].total, CardTotal(limit: eur(80000), used: eur(30000), window: .day))
        XCTAssertTrue(taf.shownCards[0].live)

        taf.used = [:]
        XCTAssertEqual(taf.shownCards[0].total?.used, eur(0), "nothing used yet")
        taf.usageUnknown = ["refund"]
        XCTAssertNil(taf.shownCards[0].total?.used, "KIFF couldn't read it: unknown, not zero")
    }
}
