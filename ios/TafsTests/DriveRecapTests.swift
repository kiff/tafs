import XCTest
@testable import Tafs

/// A drive's end in Coach's thread, and where a card that waits goes.
@MainActor
final class DriveRecapTests: XCTestCase {
    private func at(_ s: Int) -> Date { Date(timeIntervalSince1970: 1_800_000_000 + Double(s)) }

    private func shown(_ json: String, _ t: Int) throws -> ShownComponent {
        ShownComponent(firstAt: at(t), session: "s1", run: "r1", component: try SetupTests.component(json))
    }

    private func recap(_ json: String) throws -> TafsCloud.DriveRecap {
        try KIFFClient.decoder.decode(TafsCloud.DriveRecap.self, from: Data(json.utf8))
    }

    /// A drive ends, then Coach writes: "What I did" comes before the reply.
    func testADrivesEndComesBeforeCoachsReply() throws {
        var thread = SetupThread()
        thread.messages = [
            TafsCloud.ChatMessage(at: at(0), from: .owner, text: "Turn on alerts", session: "s1"),
            TafsCloud.ChatMessage(at: at(2), from: .taf, text: "I'll do it in the app.", session: "s1"),
            TafsCloud.ChatMessage(at: at(6), from: .taf, text: "Alerts are on.", session: "s1"),
        ]
        // 1_800_000_005 s: 2027-01-15T08:00:05Z.
        thread.drives = [try recap(#"{"id":"drv_1","session":"s1","task":"Turn on alerts","why":"done","words":"Coach finished","steps":[{"at":"2027-01-15T08:00:04Z","line":"Tapped","touched":{"words":"Alerts","taf":"Refunds"}}],"at":"2027-01-15T08:00:05Z"}"#)]
        let order = thread.items.map(\.id)
        let drive = try XCTUnwrap(order.firstIndex(of: "d|drv_1"))
        XCTAssertLessThan(try XCTUnwrap(order.firstIndex(of: "m|" + thread.messages[1].id)), drive)
        XCTAssertEqual(drive + 1, try XCTUnwrap(order.firstIndex(of: "m|" + thread.messages[2].id)), "the recap, then the reply")

        // At the same time, the message first.
        thread.messages[2] = TafsCloud.ChatMessage(at: at(5), from: .taf, text: "Alerts are on.", session: "s1")
        let tie = thread.items.map(\.id)
        XCTAssertLessThan(try XCTUnwrap(tie.firstIndex(of: "m|" + thread.messages[2].id)), try XCTUnwrap(tie.firstIndex(of: "d|drv_1")))
    }

    /// A card that says it waits goes after Coach's words, though its type isn't one that asks.
    func testACardThatWaitsGoesAfterCoachsWords() throws {
        var thread = SetupThread()
        thread.messages = [
            TafsCloud.ChatMessage(at: at(0), from: .owner, text: "Set its dial", session: "s1"),
            TafsCloud.ChatMessage(at: at(3), from: .taf, text: "Pick how careful it is.", session: "s1"),
            TafsCloud.ChatMessage(at: at(10), from: .owner, text: "Done", session: "s1"),
        ]
        thread.components = [try shown(#"{"type":"knob","id":"dial","taf_id":"taf-01","version":8,"waits":"owner"}"#, 2)]
        XCTAssertEqual(thread.components.first?.component.waits, "owner")
        let order = thread.items.map(\.id)
        let card = try XCTUnwrap(order.firstIndex(of: "c|s1#dial"))
        XCTAssertEqual(card, try XCTUnwrap(order.firstIndex(of: "m|" + thread.messages[1].id)) + 1, "right after Coach's words")
        XCTAssertLessThan(card, try XCTUnwrap(order.firstIndex(of: "m|" + thread.messages[2].id)), "and before the owner's answer")
    }

    /// Without `waits`, a card stays at its own time.
    func testACardWithoutWaitsStaysAtItsTime() throws {
        var thread = SetupThread()
        thread.messages = [
            TafsCloud.ChatMessage(at: at(0), from: .owner, text: "Set its dial", session: "s1"),
            TafsCloud.ChatMessage(at: at(3), from: .taf, text: "Here's its dial.", session: "s1"),
        ]
        thread.components = [try shown(#"{"type":"knob","id":"dial","taf_id":"taf-01","version":8}"#, 2)]
        XCTAssertNil(thread.components.first?.component.waits)
        let order = thread.items.map(\.id)
        XCTAssertLessThan(try XCTUnwrap(order.firstIndex(of: "c|s1#dial")), try XCTUnwrap(order.firstIndex(of: "m|" + thread.messages[1].id)))
        // Whatever its type: tafs cloud marks every card, old ones too, so the app keeps no list of its own.
        thread.components = [try shown(#"{"type":"approval","id":"connect","grant":"g_01"}"#, 2)]
        let approval = thread.items.map(\.id)
        XCTAssertLessThan(try XCTUnwrap(approval.firstIndex(of: "c|s1#connect")), try XCTUnwrap(approval.firstIndex(of: "m|" + thread.messages[1].id)))
    }

    /// The thread's `drive` items are read; one the app can't read is left out, never the thread.
    func testAThreadReadsItsDrivesAndDropsABadOne() throws {
        let json = #"""
        {"messages":[{"at":"2027-01-15T08:00:00Z","from":"owner","kind":"message","text":"Turn on alerts","session":"s1"}],
         "components":[],
         "items":[
          {"type":"message","message":{"at":"2027-01-15T08:00:00Z"}},
          {"type":"drive","drive":{"id":"drv_1","session":"s1","task":"Turn on alerts","why":"done","words":"Coach finished","steps":[{"at":"2027-01-15T08:00:04Z","line":"Tapped","touched":{"words":"Alerts"}}],"at":"2027-01-15T08:00:05Z"}},
          {"type":"drive","drive":{"id":1}},
          {"type":"drive","drive":{"id":"drv_2","task":"Open Refunds","at":"2027-01-15T08:01:00Z"}}
         ]}
        """#
        let thread = try TafsCloud.setupThread(decoding: Data(json.utf8))
        XCTAssertEqual(thread.messages.count, 1)
        XCTAssertEqual(thread.drives.map(\.id), ["drv_1", "drv_2"])
        let first = try XCTUnwrap(thread.drives.first)
        XCTAssertEqual(first.task, "Turn on alerts")
        XCTAssertEqual(first.words, "Coach finished")
        XCTAssertEqual(first.steps, [TafsCloud.DriveStep(at: "2027-01-15T08:00:04Z", line: "Tapped", touched: TafsCloud.DriveTouched(words: "Alerts"))])
        XCTAssertEqual(first.at, at(5))
        XCTAssertEqual(thread.drives.last?.steps, [], "no steps: the no-steps line")
        XCTAssertEqual(thread.items.map(\.id), ["m|" + thread.messages[0].id, "d|drv_1", "d|drv_2"])
    }
}
