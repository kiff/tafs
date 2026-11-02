import XCTest
@testable import Tafs

/// The dial as tafs cloud sends it (`GET /v1/tafs/{id}/knob`), and how it turns.
final class KnobTests: XCTestCase {
    /// The knob API's own example decodes as written, each stop's words, state and tap kept.
    func testTheKnobAPIsExampleDecodes() throws {
        let json = #"""
        {"version":7,"does":{"label":"Refund","line":"Refunds a payment in Stripe, within its Card."},
         "wakes":[{"id":"trg_1","kind":"door","door":"door_1","label":"Email","words":"Wakes on email to refunds@shop.example","on":true,"state":"Open","tap":"Tap to close"},
                  {"id":"trg_2","kind":"follow_up","label":"Follow-up","words":"Checks refund re_1 again","expires":"2026-11-09T09:00:00Z"}],
         "when":[{"id":"when_arrivals","kind":"arrivals","label":"Arrivals","words":"Works each one as it arrives"}],
         "results":[{"id":"res_1","place":"door_reply","label":"Sender","words":"Replies to whoever wrote","on":false}],
         "rim":{"done":12,"working":1,"retrying":1,"needs_you":1,"line":"12 done today · 1 working · 1 retrying its reply"},
         "draft":{"version":8,"words":["Runs at 09:00 on weekdays too"]}}
        """#
        let k = try JSONDecoder().decode(TafsCloud.Knob.self, from: Data(json.utf8))
        XCTAssertEqual(k.version, 7)
        XCTAssertEqual(k.does.label, "Refund")
        XCTAssertEqual(k.wakes.map(\.label), ["Email", "Follow-up"])
        XCTAssertEqual(k.wakes[0].state, "Open")
        XCTAssertEqual(k.wakes[0].tap, "Tap to close")
        XCTAssertEqual(k.wakes[0].door, "door_1", "a door's own id, for the doors API")
        XCTAssertNil(k.wakes[1].door)
        XCTAssertNil(k.wakes[1].tap, "no tap: the face does nothing for it")
        XCTAssertTrue(k.wakes[1].on, "a stop that doesn't say is on")
        XCTAssertEqual(k.wakes[1].expires, "2026-11-09T09:00:00Z")
        XCTAssertEqual(k.when.first?.kind, "arrivals")
        XCTAssertEqual(k.results.first?.place, "door_reply")
        XCTAssertFalse(k.results[0].on)
        XCTAssertEqual(k.rim, TafsCloud.Knob.Rim(done: 12, working: 1, retrying: 1, needsYou: 1, line: "12 done today · 1 working · 1 retrying its reply"))
        XCTAssertEqual(k.rim.total, 15)
        XCTAssertEqual(k.draft, TafsCloud.Knob.Draft(version: 8, words: ["Runs at 09:00 on weekdays too"]))
        XCTAssertEqual(TafsCloud.Knob.Ring.allCases.map { k.items($0).count }, [2, 1, 1])
    }

    /// What tafs cloud leaves out has its default, and a null draft is none.
    func testAnEmptyKnob() throws {
        let k = try JSONDecoder().decode(TafsCloud.Knob.self, from: Data(#"{"does":{"label":"Refund"},"draft":null}"#.utf8))
        XCTAssertEqual(k.does.line, "")
        XCTAssertTrue(k.wakes.isEmpty && k.when.isEmpty && k.results.isEmpty)
        XCTAssertEqual(k.rim.total, 0)
        XCTAssertNil(k.draft)
    }

    func testConnectApps() throws {
        let json = #"{"apps":[{"entry":"stripe@1","vendor":"Stripe","title":"Stripe","how":"oauth","state":"connected","grant":"g_1"},{"entry":"desk@1","vendor":"Desk","how":"key","state":"needs_reconnect"}],"by_key":true}"#
        let apps = try JSONDecoder().decode(TafsCloud.ConnectApps.self, from: Data(json.utf8))
        XCTAssertTrue(apps.byKey)
        XCTAssertEqual(apps.apps.map(\.id), ["stripe@1", "desk@1"])
        XCTAssertTrue(apps.apps[0].connected)
        XCTAssertTrue(apps.apps[1].needsReconnect)
        XCTAssertEqual(apps.apps[1].how, "key")
    }

    /// A ring turns its chosen stop to the top by the shorter way round.
    func testTheRingTurnsTheShortWay() {
        XCTAssertEqual(TafKnobView.nearestTurn(from: 0, to: 1, stops: 4), 1)
        XCTAssertEqual(TafKnobView.nearestTurn(from: 0, to: 3, stops: 4), -1)
        XCTAssertEqual(TafKnobView.nearestTurn(from: 5, to: 0, stops: 4), 4)
        XCTAssertEqual(TafKnobView.nearestTurn(from: -1, to: 3, stops: 4), -1)
        XCTAssertEqual(TafKnobView.nearestTurn(from: 2, to: 0, stops: 0), 2)
    }
}
