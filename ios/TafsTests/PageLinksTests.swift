import XCTest
@testable import Tafs

/// Links in a taf's words from a page turn ask before opening (`page_turn`, `PageLinkGuard`): the flag as tafs cloud sends
/// it, on the thread's messages and on the stream, and the address as the confirm shows it.
@MainActor
final class PageLinksTests: XCTestCase {
    private let suite = "PageLinksTests"

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suite)
        Words.bundle = .main
        super.tearDown()
    }

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

    /// In a right-to-left language the address keeps its order: the confirm's line holds it whole, inside its isolate, so the
    /// address read is the one that opens.
    func testInArabicTheAddressKeepsItsOrder() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        AppLanguage.choose("ar", defaults: defaults)
        let url = try XCTUnwrap(URL(string: "https://shop.example/a/b-2?id=42&q=x.y"))
        let line = Words.Chat.linkConfirmBody(address: PageLinks.shown(url))
        XCTAssertNotEqual(line, "It came from a page Coach read. It goes to: \(PageLinks.shown(url))", "the Arabic line")
        XCTAssertTrue(line.contains("\u{2066}https://shop.example/a/b-2?id=42&q=x.y\u{2069}"))
    }

    /// Open is offered for an https address alone: another app's deep link, a phone number or plain http gets Cancel only.
    func testOnlyAnHttpsAddressCanBeOpened() throws {
        for ok in ["https://shop.example/a", "HTTPS://shop.example"] { XCTAssertTrue(PageLinks.opens(try XCTUnwrap(URL(string: ok))), ok) }
        for no in ["http://shop.example", "tel:+15551234", "sms:+15551234", "bank://pay?to=x", "market://details?id=x", "mailto:a@b.c",
                   "javascript:alert(1)", "https:no-host", "https://shop.example/%E2%80%AEgpj.exe", "https://shop.example/a%20b",
                   "https://shop.example/%E2%81%A6x%E2%81%A9", "https://shop.example/%0Anext"] {
            if let url = URL(string: no) { XCTAssertFalse(PageLinks.opens(url), no) }
        }
    }
}
