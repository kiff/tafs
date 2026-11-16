import XCTest
@testable import Tafs

/// Coach's thread keeps the order it has drawn (`SteadyOrder`), as Android's (tafs#1233): the owner's three
/// steps, replayed. Their message, sent from this device, is replaced by tafs cloud's copy at another time; the
/// card comes with tafs cloud's time; Coach's words join only when its turn ends. Nothing already drawn moves.
final class SteadyOrderTests: XCTestCase {
    private struct Item: Equatable {
        let id: String
        let at: Double
        var owner: String? = nil
    }

    private func order(_ items: [Item], drawn: [Item]) -> [String] {
        SteadyOrder.order(items, drawn: drawn.map { SteadyOrder.Entry(id: $0.id, ownerText: $0.owner) },
                          id: \.id, at: { Date(timeIntervalSince1970: $0.at) }, ownerText: \.owner).map(\.id)
    }

    func testTheOwnersThreeStepsMoveNothing() {
        let earlier = Item(id: "coach-1", at: 1)
        let sent = Item(id: "mine-local", at: 30, owner: "Refunds, please")
        // 1. Sent, then the card, as drawn.
        let card = Item(id: "card", at: 20)
        let drawn = [earlier, sent, card]
        // 2. tafs cloud's copy replaces the one sent here, at its own (earlier) time: it keeps the sent one's place.
        let copy = Item(id: "mine-server", at: 10, owner: "Refunds, please")
        XCTAssertEqual(order([earlier, copy, card], drawn: drawn), ["coach-1", "mine-server", "card"])
        // 3. Coach's words, timed before the card, join at the turn's end: after what's drawn, never above the card.
        let words = Item(id: "coach-2", at: 15)
        XCTAssertEqual(order([earlier, copy, words, card], drawn: [earlier, copy, card]), ["coach-1", "mine-server", "card", "coach-2"])
    }

    /// Nothing drawn yet (a reopened thread): the thread's own order. An earlier page goes before what's drawn.
    func testAReopenedThreadIsInItsOwnOrderAndAnEarlierPageGoesFirst() {
        let a = Item(id: "a", at: 5), b = Item(id: "b", at: 6), c = Item(id: "c", at: 7)
        XCTAssertEqual(order([c, a, b], drawn: []), ["c", "a", "b"], "as given")
        let older = Item(id: "older", at: 1)
        XCTAssertEqual(order([older, b, c], drawn: [b, c]), ["older", "b", "c"])
    }

    /// Two messages with the same words sent close together stay two, each in its own place.
    func testTwoOfTheSameWordsKeepTheirPlaces() {
        let one = Item(id: "local-1", at: 10, owner: "Yes"), two = Item(id: "local-2", at: 11, owner: "Yes")
        let card = Item(id: "card", at: 12)
        let first = Item(id: "server-1", at: 9, owner: "Yes"), second = Item(id: "server-2", at: 9.5, owner: "Yes")
        XCTAssertEqual(order([first, second, card], drawn: [one, card, two]), ["server-1", "card", "server-2"])
    }
}
