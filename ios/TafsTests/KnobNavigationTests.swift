import XCTest
@testable import Tafs

final class KnobNavigationTests: XCTestCase {
    private func knob() throws -> TafsCloud.Knob {
        try JSONDecoder().decode(TafsCloud.Knob.self, from: Data(#"""
        {"version":7,"shown":8,"why":"Refund double charges",
         "rings":[{"key":"wakes","name":"When"},{"key":"what","name":"Recipe"},{"key":"results","name":"Where"}],
         "wakes":[{"id":"email","label":"Email","grant":"g_mail","ok":"door","look":"knock"},{"id":"schedule","label":"Weekdays"},{"id":"add_door","label":"Add a door","ok":"coach"}],
         "what":[{"id":"step1","step":1,"label":"Check"},{"id":"step2","step":2,"label":"Log","grant":"g_log"}],
         "when":[{"id":"busy","label":"If busy"}],
         "results":[{"id":"log","label":"Log","grant":"g_log"}],
         "connections":[{"grant":"g_mail","name":"Mail","wakes":1,"results":0},{"grant":"g_log","name":"Log","wakes":0,"results":1}]}
        """#.utf8))
    }

    func testFiltersEveryRingByGrantWithoutDrawingLegacyWhen() throws {
        let k = try knob()
        XCTAssertEqual(k.why, "Refund double charges")
        XCTAssertEqual(k.shown, 8)
        XCTAssertEqual(k.wakes[0].ok, "door")
        XCTAssertEqual(k.wakes[0].look, "knock")
        XCTAssertNil(k.what[0].ok)
        XCTAssertEqual(k.what[0].ringLabel, "1 · Check")
        XCTAssertEqual(k.title(.what), "Recipe", "use the name cloud supplied")
        let own = KnobNavigation.stops(k, filter: .taf)
        XCTAssertEqual(own.map(\.item.id), ["schedule", "add_door", "step1"])
        XCTAssertEqual(KnobNavigation.stops(k, filter: .connection("g_log")).map(\.item.id), ["step2", "log"])
        XCTAssertTrue(KnobNavigation.stops(k, filter: .connection("missing")).isEmpty)
        XCTAssertFalse(KnobNavigation.stops(k, filter: .all).contains { $0.item.id == "busy" })
    }

    func testRemoteCrossesEmptyRingsAndWrapsInBothDirections() throws {
        let stops = KnobNavigation.stops(try knob(), filter: .connection("g_log"))
        XCTAssertEqual(KnobNavigation.next(stops, ring: nil, id: nil, direction: 1)?.item.id, "step2")
        XCTAssertEqual(KnobNavigation.next(stops, ring: nil, id: nil, direction: -1)?.item.id, "log")
        XCTAssertEqual(KnobNavigation.next(stops, ring: .what, id: "step2", direction: 1)?.item.id, "log")
        XCTAssertEqual(KnobNavigation.next(stops, ring: .results, id: "log", direction: 1)?.item.id, "step2")
        XCTAssertEqual(KnobNavigation.next(stops, ring: .what, id: "step2", direction: -1)?.item.id, "log")
        XCTAssertNil(KnobNavigation.next([], ring: nil, id: nil, direction: 1))
    }

    func testCrowdedRingsKeepEveryStopReachable() {
        let items = (0..<37).map { TafsCloud.Knob.Item(id: "s\($0)", label: "Stop \($0)", words: "") }
        let k = TafsCloud.Knob(wakes: items)
        let stops = KnobNavigation.stops(k, filter: .all)
        var current: KnobNavigation.Stop?
        var visited = Set<String>()
        for _ in 0..<stops.count {
            current = KnobNavigation.next(stops, ring: current?.ring, id: current?.item.id, direction: 1)
            guard let current else { return XCTFail("all stops remain reachable") }
            visited.insert(current.item.id)
            let page = KnobNavigation.page(KnobNavigation.items(k, ring: .wakes, filter: .all), selectedID: current.item.id, capacity: 4)
            XCTAssertLessThanOrEqual(page.count, 4)
            XCTAssertTrue(page.contains { $0.id == current.item.id })
        }
        XCTAssertEqual(visited.count, 37, "all server stops remain reachable, with no app-created stops")
        XCTAssertEqual(KnobNavigation.nearestTurn(from: 0, to: 3, stops: 4), -1)
    }

    func testLowerHalfLabelsFlipAfterTheRingTurns() {
        XCTAssertEqual(KnobNavigation.labelFlip(at: 0), 0)
        XCTAssertEqual(KnobNavigation.labelFlip(at: 90), 0)
        XCTAssertEqual(KnobNavigation.labelFlip(at: 180), 180)
        XCTAssertEqual(KnobNavigation.labelFlip(at: 270), 0)
        XCTAssertEqual(KnobNavigation.labelFlip(at: 360), 0)
        XCTAssertEqual(KnobNavigation.labelFlip(at: -180), 180)
        // The bottom stop moves to the pointer after half a turn and no longer flips.
        XCTAssertEqual(KnobNavigation.labelFlip(at: 180 - 2 * 90), 0)
        XCTAssertEqual(KnobNavigation.labelFlip(at: 180 + 4 * 360), 180)
    }

    func testSizeChoicesAreBoundedAndKeepTheWholeSetting() throws {
        let json = #"""
        {"id":"schedule","label":"Weekdays","size":{"label":"Time","index":1,"choices":[
          {"label":"08:00","setting":{"wakes":[{"kind":"schedule","at":"08:00","skip":["2026-12-25"]}],"busy":"latest","results":["app",{"place":"action","grant":"g_log","action":"log","args":{"page":"pg_1"}}],"why":"Refund double charges","what":[{"label":"Check","words":"Look up the charge","tool":"check"}]}},
          {"label":"09:00","setting":{"wakes":[],"when":{"busy":"latest"},"results":["app"]}}]}}
        """#
        let item = try JSONDecoder().decode(TafsCloud.Knob.Item.self, from: Data(json.utf8))
        let size = try XCTUnwrap(item.size)
        XCTAssertNil(size.choice(moving: 1), "the upper bound cannot wrap")
        XCTAssertNil(size.choice(moving: 2), "only a single step is allowed")
        let choice = try XCTUnwrap(size.choice(moving: -1))
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(choice.setting)) as? [String: Any])
        XCTAssertEqual((body["when"] as? [String: Any])?["busy"] as? String, "latest")
        XCTAssertEqual(body["why"] as? String, "Refund double charges")
        XCTAssertEqual((body["what"] as? [[String: Any]])?.first?["tool"] as? String, "check")
        XCTAssertEqual((body["results"] as? [Any])?.count, 2)
        XCTAssertEqual(((body["wakes"] as? [[String: Any]])?.first?["skip"]) as? [String], ["2026-12-25"])
        XCTAssertEqual(size.choices[1].setting.busy, "latest", "both setting and PUT forms decode")
        var invalid = size; invalid.index = -1
        XCTAssertNil(invalid.choice(moving: 1))
        invalid.index = 99
        XCTAssertNil(invalid.choice(moving: -1))
    }

    func testDraftHasTheRecipeAndLegacyRulesDoNotBecomeRecipeSteps() throws {
        let draft = try JSONDecoder().decode(TafsCloud.Knob.Draft.self, from: Data(#"{"version":8,"wakes":["Weekdays"],"when":["If busy, skip"],"what":["1 · Check"],"results":["Reply"]}"#.utf8))
        XCTAssertEqual(draft.rings.map { $0.0 }, [.wakes, .what, .results])
        XCTAssertEqual(draft.rings[1].1, ["1 · Check"])
        let pending = try JSONDecoder().decode(TafsCloud.Knob.self, from: Data(#"{"version":7,"pending":{"version":9,"changes":3},"rings":[{"key":"results","name":"Where"},{"key":"wakes","name":"When"}]}"#.utf8))
        XCTAssertEqual(pending.pending?.changes, 3)
        XCTAssertEqual(pending.pending?.version, 9)
        XCTAssertEqual(pending.orderedRings, [.results, .wakes])
        XCTAssertTrue(TafsCloud.Knob().items(.what).isEmpty, "the app invents no recipe step")
        let grouped = try JSONDecoder().decode(TafsCloud.Knob.Draft.self, from: Data(#"{"version":8,"groups":[{"grant":"g_log","name":"Log","wakes":[],"what":[],"results":["Post each result"]},{"name":"This taf","wakes":["Weekdays"],"what":["1 · Check"],"results":[]}]}"#.utf8))
        XCTAssertEqual(grouped.groups.map(\.id), ["g_log", "taf"])
        XCTAssertEqual(grouped.groups[0].rings.first?.0, .results)
        XCTAssertEqual(grouped.groups[1].rings.map { $0.0 }, [.wakes, .what])
        var knob = TafsCloud.Knob(version: 7, draft: draft)
        XCTAssertFalse(knob.acceptsChoices())
        knob.shown = 8
        XCTAssertTrue(knob.acceptsChoices())
        XCTAssertFalse(knob.acceptsChoices(waitingFor: 9), "failed read after PUT must block stale choices")
        knob.draft = nil; knob.shown = nil
        XCTAssertFalse(knob.acceptsChoices(waitingFor: 8))
        knob.version = 8
        XCTAssertTrue(knob.acceptsChoices(waitingFor: 8), "the draft is now live")
    }
    func testCrowdedInnerRingLabelsFitTheirShareWithoutNegativeWidths() {
        let inner = KnobNavigation.labelWidth(radius: 90, stops: 6, maximum: 100, gap: 12)
        XCTAssertTrue(abs(inner - 78) < 0.001)
        XCTAssertTrue(KnobNavigation.labelWidth(radius: 180, stops: 6, maximum: 100, gap: 12) == 100)
        XCTAssertEqual(KnobNavigation.labelWidth(radius: 90, stops: 1, maximum: 100, gap: 12), 100)
        XCTAssertEqual(KnobNavigation.labelWidth(radius: 2, stops: 6, maximum: 100, gap: 12), 0)
    }

    func testTheServerCanDisablePauseAndOlderServersKeepItAvailable() throws {
        let decoder = JSONDecoder()
        let noPause = try decoder.decode(TafsCloud.Knob.self, from: Data(#"{"can_pause":false}"#.utf8))
        let canPause = try decoder.decode(TafsCloud.Knob.self, from: Data(#"{"can_pause":true}"#.utf8))
        let legacy = try decoder.decode(TafsCloud.Knob.self, from: Data(#"{}"#.utf8))
        XCTAssertFalse(noPause.canPause)
        XCTAssertTrue(canPause.canPause)
        XCTAssertTrue(legacy.canPause)
    }

}
