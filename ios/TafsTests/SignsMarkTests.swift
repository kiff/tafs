import XCTest
@testable import Tafs

/// tafs cloud's mark of what an approval's fingerprint covers (`signs`, docs/secure-connection.md):
/// a Card approved next is drawn under its words and dimmed, never as if signed now.
@MainActor
final class SignsMarkTests: XCTestCase {
    func testTheMarkReadsAsSent() throws {
        let now = try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_1","draft":"dr_1","signs":{"card":true}}"#)
        XCTAssertEqual(now.signs?.card, true)
        XCTAssertFalse(now.signs?.cardNext ?? true)
        let next = try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_1","draft":"dr_1","signs":{"card":false,"words":"Its Card: you approve it next"}}"#)
        XCTAssertTrue(next.signs?.cardNext ?? false)
        XCTAssertEqual(next.signs?.words, "Its Card: you approve it next")
        XCTAssertNil(try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_1"}"#).signs, "a tafs cloud before it: as before")
        // Another shape is read as nothing said, never the card unread.
        let odd = try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_1","signs":{"card":"no","words":" "}}"#)
        XCTAssertNil(odd.signs?.card)
        XCTAssertNil(odd.signs?.words)
        XCTAssertFalse(odd.signs?.cardNext ?? false)
    }

    /// Next: its words and dimmed lines, never the Card's own title as if signed. Next with no words: not drawn.
    func testTheCardIsDrawnByTheMark() throws {
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let chat = try String(contentsOf: views.appendingPathComponent("SetupChatView.swift"), encoding: .utf8)
        XCTAssertTrue(chat.contains("if let draft, draft.cards != nil, !(component.signs?.cardNext == true && component.signs?.words == nil) {"))
        XCTAssertTrue(chat.contains("if next, let words = component.signs?.words {"))
        XCTAssertTrue(chat.contains(".foregroundColor(next ? Theme.dim : Theme.ink)"))
    }
}
