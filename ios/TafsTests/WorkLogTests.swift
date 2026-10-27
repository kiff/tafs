import XCTest
@testable import Tafs

/// What a taf does while it answers, and what it did once it has (kiff/tafs#285).
final class WorkLogTests: XCTestCase {
    typealias S = AppModel.LiveStep.Status

    /// Tries of the same step after a failed one are one row, with the last try's outcome;
    /// a failure is left out once something worked, or while the answer is still worked on.
    func testStepsFoldTheirTriesAndHideStumbles() {
        let steps: [(words: String, status: S)] = [("Reading the order", .error), ("Reading the order", .error), ("Reading the order", .done), ("Refunding", .done)]
        XCTAssertEqual(WorkLog.rows(steps, running: false), [
            WorkLog.Row(words: "Reading the order", status: .done, tries: 3),
            WorkLog.Row(words: "Refunding", status: .done, tries: 1),
        ])
        // Nothing worked: the failures say why.
        let failed: [(words: String, status: S)] = [("Reading the order", .error), ("Refunding", .error)]
        XCTAssertEqual(WorkLog.rows(failed, running: false).map(\.status), [.error, .error])
        // Still at work: what got done shows, not the stumbles.
        XCTAssertEqual(WorkLog.rows(failed + [("Checking", .done)], running: true).map(\.words), ["Checking"])
        XCTAssertEqual(WorkLog.rows(failed, running: true), [])
        // The same step after one that worked is a new row.
        XCTAssertEqual(WorkLog.rows([("Reading", .done), ("Reading", .done)], running: false).map(\.tries), [1, 1])
    }

    /// One readable line of the thinking: the latest sentence, plain, not a list or code.
    func testTheThinkingLineIsOneCalmSentence() {
        XCTAssertEqual(WorkLog.thinkingLine("The customer asks for a refund. The order is **paid** in full."),
                       "The order is paid in full.")
        // The next sentence is still a fragment: the last complete one shows.
        XCTAssertEqual(WorkLog.thinkingLine("I should check the [order](https://x.test) first. Then the"),
                       "I should check the order first.")
        // Lists, code and a line that opens a list are skipped.
        XCTAssertEqual(WorkLog.thinkingLine("Checking what the Card allows.\nThe steps:\n- refund\n- {\"amount\": 20}"),
                       "Checking what the Card allows.")
        XCTAssertEqual(WorkLog.thinkingLine(""), "")
        XCTAssertEqual(WorkLog.thinkingLine("ok"), "")
        let long = WorkLog.thinkingLine(String(repeating: "refund the order now ", count: 20) + "please.")
        XCTAssertLessThanOrEqual(long.count, 141)
        XCTAssertTrue(long.hasSuffix("…"))
        XCTAssertFalse(long.hasSuffix(" …"))
    }

    func testHowLongItWorkedReads() {
        XCTAssertEqual(WorkLog.duration(ms: 300), "1 s")
        XCTAssertEqual(WorkLog.duration(ms: 12_400), "12 s")
        XCTAssertEqual(WorkLog.duration(ms: 65_000), "1 min 5 s")
    }

    /// An answer still being written never shows a Markdown symbol: what it's in the middle
    /// of is closed, and a link without its address yet shows its text.
    func testTextBeingWrittenIsHealed() {
        XCTAssertEqual(MarkdownBlock.healed("Refunded **$20"), "Refunded **$20**")
        XCTAssertEqual(MarkdownBlock.healed("Run `refund"), "Run `refund`")
        XCTAssertEqual(MarkdownBlock.healed("See [the order](https://shop.te"), "See the order")
        XCTAssertEqual(MarkdownBlock.healed("See [the ord"), "See the ord")
        XCTAssertEqual(MarkdownBlock.healed("Refunded **"), "Refunded ")
        // Finished formatting, earlier lines and an open code block stay as they are.
        XCTAssertEqual(MarkdownBlock.healed("**Done**"), "**Done**")
        XCTAssertEqual(MarkdownBlock.healed("**a**\nnext"), "**a**\nnext")
        XCTAssertEqual(MarkdownBlock.healed("```\nlet x = `"), "```\nlet x = `")
        for text in ["Refunded **$20", "See [the order](https://shop.te", "Run `refund"] {
            let drawn = MarkdownBlock.parse(MarkdownBlock.healed(text)).map { String($0.text.characters) }.joined()
            XCTAssertFalse(drawn.contains("*") || drawn.contains("`") || drawn.contains("["), "\(text) drew \(drawn)")
        }
    }

    /// A taf's message keeps what it did before writing it; one without says nothing.
    func testAMessagesWorkIsRead() throws {
        let json = #"""
        [{"at":"2026-10-14T09:01:20Z","from":"taf","kind":"message","text":"Refunded.","session":"s1",
          "work":{"ms":12000,"steps":[{"tool":"get_order","error":true},{"tool":"create_refund"}]}},
         {"at":"2026-10-14T09:01:30Z","from":"taf","kind":"message","text":"Anything else?","session":"s1"},
         {"at":"2026-10-14T09:01:40Z","from":"taf","kind":"message","text":"Odd.","session":"s1","work":{"ms":"x"}}]
        """#
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let messages = try decoder.decode([TafsCloud.ChatMessage].self, from: Data(json.utf8))
        XCTAssertEqual(messages[0].work, TafsCloud.Work(ms: 12000, steps: [.init(tool: "get_order", error: true), .init(tool: "create_refund")]))
        XCTAssertNil(messages[1].work)
        XCTAssertNil(messages[2].work, "one the app can't read leaves the message as it is")
    }

    /// A call the stream sends again (a run picked back up replays its steps) keeps its status:
    /// a finished step never goes back to running (the 859 test). One without an id is a new step.
    func testAStepSentAgainKeepsItsStatus() {
        var live = AppModel.LiveAnswer()
        live.began("kiff_pending", words: nil, call: "c1")
        live.ended("c1", .done)
        live.began("memory", words: nil, call: "c2")
        live.ended("c2", .done)
        live.began("list_things", words: nil, call: "c3")
        live.began("kiff_pending", words: nil, call: "c1")
        live.began("memory", words: nil, call: "c2")
        XCTAssertEqual(live.steps.map(\.status), [.done, .done, .running])
        live.began("memory", words: nil, call: nil)
        XCTAssertEqual(live.steps.count, 4)
    }

    /// The room under a pinned message is the screen less its turn (the 845 test): a whole
    /// screen until the turn is measured, shrinking as the answer grows, never below nothing.
    func testTheRoomUnderAPinnedMessageIsTheScreenLessItsTurn() {
        XCTAssertEqual(PinnedTurn().room(800), 800)
        XCTAssertEqual(PinnedTurn(top: 300, foot: 420).room(800), 680)
        XCTAssertEqual(PinnedTurn(top: 300, foot: 900).room(800), 200, "the answer grew: the room shrank by as much")
        XCTAssertEqual(PinnedTurn(top: 300, foot: 1400).room(800), 0)
        XCTAssertEqual(PinnedTurn(top: 300, foot: 200).room(800), 800, "not laid out yet")
    }
}
