import XCTest
@testable import Tafs

/// Links in a taf's words from a page turn ask before opening (`page_turn`, `PageLinkGuard`): the flag as tafs cloud sends
/// it, on the thread's messages and on the stream, and the address as the confirm shows it.
final class PageLinksTests: XCTestCase {
    private func message(_ json: String) throws -> TafsCloud.ChatMessage {
        try KIFFClient.decoder.decode(TafsCloud.ChatMessage.self, from: Data(json.utf8))
    }

    /// Only `page_turn: true` on a taf's message asks first. None, anything else, and the owner's own words don't.
    func testOnlyATafsMessageMarkedPageTurnAsksFirst() throws {
        let base = #""at":"2026-11-24T08:00:00Z","text":"See [the page](https://shop.example/a?b=1)","session":"s1""#
        XCTAssertTrue(try message("{\(base),\"from\":\"taf\",\"page_turn\":true}").pageTurn)
        XCTAssertFalse(try message("{\(base),\"from\":\"taf\"}").pageTurn)
        XCTAssertFalse(try message("{\(base),\"from\":\"taf\",\"page_turn\":false}").pageTurn)
        XCTAssertFalse(try message("{\(base),\"from\":\"taf\",\"page_turn\":\"yes\"}").pageTurn, "not a flag: a plain message, still read")
        XCTAssertFalse(try message("{\(base),\"from\":\"owner\",\"page_turn\":true}").pageTurn, "never on the owner's own words")
        XCTAssertFalse(TafsCloud.ChatMessage(at: Date(), from: .owner, text: "x", session: "s1", pageTurn: true).pageTurn)
        XCTAssertTrue(TafsCloud.ChatMessage(at: Date(), from: .taf, text: "x", session: "s1", pageTurn: true).pageTurn)
    }

    /// The stream's `session` event says it too, for an answer still arriving; an older tafs cloud's says nothing.
    func testTheStreamsSessionEventCarriesPageTurn() {
        func e(_ json: String) -> TafsCloud.StreamEvent? { TafsCloud.streamEvent("session", data: Data(json.utf8)) }
        XCTAssertEqual(e(#"{"session":"s1","new_session":false,"run":"r2","page_turn":true}"#),
                       .session("s1", newSession: false, run: "r2", pageTurn: true))
        XCTAssertEqual(e(#"{"session":"s1","new_session":false,"run":"r2"}"#), .session("s1", newSession: false, run: "r2"))
        XCTAssertEqual(e(#"{"session":"s1","new_session":false,"page_turn":1}"#), .session("s1", newSession: false))
    }

    /// The confirm's address: whole, as the link has it, inside a left-to-right isolate.
    func testTheAddressIsShownWholeAndLeftToRight() throws {
        let url = try XCTUnwrap(URL(string: "https://shop.example/very/long/path?token=abc&next=%2Fpay#frag"))
        let shown = PageLinks.shown(url)
        XCTAssertEqual(shown, "\u{2066}https://shop.example/very/long/path?token=abc&next=%2Fpay#frag\u{2069}")
        XCTAssertTrue(Words.Chat.linkConfirmBody(address: shown).contains(url.absoluteString))
    }
}
