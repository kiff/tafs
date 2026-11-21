import XCTest
@testable import Tafs

/// A held browser step (docs/chrome.md, "The approval card"): planque sends `browser` beside the held operation, and
/// the card draws it as sent. The site and planque's words for the act are what the owner approves; the page's
/// context and the basket are words to read, never anything the app acts on.
@MainActor
final class BrowserStepCardTests: XCTestCase {
    private struct List: Decodable { let exceptions: [KIFFClient.CardException] }

    private func held(browser: String?, arguments: String = #"{"ref":"e12","kiff_operation_id":"op_1"}"#) throws -> HeldCall {
        let block = browser.map { #","browser":"# + $0 } ?? ""
        let body = #"""
        {"exceptions":[{"id":"ce_1","subject":"shopper-k5j6m5qx","action":"browse_click","title":"Click in Chrome","entity_id":"st_1",
          "status":"held","breaches":[{"outcome":"held_always","reason":"A pay step always asks you"}],
          "arguments":\#(arguments)\#(block),
          "created_at":"2026-11-21T14:00:00.000Z","expires_at":"2026-11-21T14:10:00Z"}]}
        """#
        let e = try KIFFClient.decoder.decode(List.self, from: Data(body.utf8)).exceptions[0]
        return KIFFMapping.held(e, tafID: UUID())
    }

    private let checkout = #"""
    {"site":"shop.example","origin":"https://checkout.shop.example","action_words":"Click “Place order”",
     "reason":"Your weekly groceries, as agreed",
     "context":{"title":"Checkout – Shop","text":"Delivery Tuesday 8–10am. Place order"},
     "checkout":{"lines":["Milk ×2  €2.40","Bread  €1.90"],"total":"€4.30","currency":"EUR"},
     "chrome":{"id":"chr_1","name":"Work laptop"}}
    """#

    /// Every part of the shape reads: the act is what the card asks, the taf's reason sits under "The taf says", and
    /// the block itself isn't listed again under Details.
    func testAHeldCheckoutStepReadsWhole() throws {
        let call = try held(browser: checkout)
        let step = try XCTUnwrap(call.browser)
        XCTAssertEqual(step.site, "shop.example")
        XCTAssertEqual(step.origin, "https://checkout.shop.example")
        XCTAssertEqual(step.actionWords, "Click “Place order”")
        XCTAssertEqual(step.pageTitle, "Checkout – Shop")
        XCTAssertEqual(step.pageText, "Delivery Tuesday 8–10am. Place order")
        XCTAssertEqual(step.basket, ["Milk ×2 €2.40", "Bread €1.90"], "one plain line each, runs of space as one")
        XCTAssertEqual(step.total, "€4.30", "the currency isn't added twice")
        XCTAssertEqual(step.chromeName, "Work laptop")
        XCTAssertEqual(call.proposed.summary, "Click “Place order”", "planque's words for the act, not the tool's name")
        XCTAssertEqual(call.tafReason, "Your weekly groceries, as agreed")
        XCTAssertEqual(call.reason, "A pay step always asks you", "KIFF's rule stays KIFF's")
        XCTAssertEqual(call.details, ["ref: e12"])
    }

    /// A step that isn't a checkout has no basket; a total without its currency sign gets the currency beside it.
    func testOnlyWhatsSentIsDrawn() throws {
        let call = try held(browser: #"{"site":"news.example","action_words":"Click “Next page”"}"#)
        let step = try XCTUnwrap(call.browser)
        XCTAssertNil(step.origin)
        XCTAssertNil(step.pageTitle)
        XCTAssertEqual(step.basket, [])
        XCTAssertNil(step.total)
        XCTAssertNil(step.chromeName)
        XCTAssertNil(call.tafReason)
        let bare = try held(browser: #"{"site":"s.example","action_words":"Click “Pay”","checkout":{"total":4.3,"currency":"EUR"}}"#)
        XCTAssertEqual(bare.browser?.total, "4.3 EUR")
    }

    /// The page's words come from the site, so they're plain lines: no control or direction character, never markup,
    /// and cut to planque's cap.
    func testThePagesWordsArePlainAndCut() throws {
        let long = String(repeating: "a", count: 700)
        let call = try held(browser: #"""
        {"site":"shop.example‮moc.lapyap","action_words":"Click\n“[Pay](https://evil.example)”",
         "context":{"title":"<b>Checkout</b>","text":"\#(long)"}}
        """#)
        let step = try XCTUnwrap(call.browser)
        XCTAssertFalse(step.site.unicodeScalars.contains("\u{202E}"), "no direction character can reorder the site")
        XCTAssertEqual(step.actionWords, "Click “[Pay](https://evil.example)”", "kept as characters, one line")
        XCTAssertEqual(step.pageTitle, "<b>Checkout</b>")
        XCTAssertEqual(step.pageText?.count, 600)
    }

    /// Without a site or planque's words for the act, or with a block that can't be read, there's no browser card,
    /// and the hold still reads as any other.
    func testABrokenBlockIsLeftOutNeverTheHold() throws {
        for block in [#"{"action_words":"Click"}"#, #"{"site":"shop.example"}"#, #"{"site":" ","action_words":"Click"}"#,
                      #""shop.example""#, "42", #"{"site":1,"action_words":["x"]}"#] {
            let call = try held(browser: block)
            XCTAssertNil(call.browser, block)
            XCTAssertEqual(call.proposed.summary, "Click in Chrome", block)
        }
        XCTAssertNil(try held(browser: nil).browser)
    }

    /// A step's context with the wrong shape loses that part only.
    func testAWrongPartLosesThatPartOnly() throws {
        let call = try held(browser: #"{"site":"shop.example","action_words":"Click “Pay”","context":"x","checkout":{"lines":["a",3,"b"]},"chrome":[1]}"#)
        let step = try XCTUnwrap(call.browser)
        XCTAssertNil(step.pageTitle)
        XCTAssertEqual(step.basket, ["a", "b"])
        XCTAssertNil(step.chromeName)
    }
}
