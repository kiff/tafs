import XCTest
@testable import Tafs

/// A held browser step (docs/chrome.md, "The approval card"): KIFF's held-call view carries `browser`, in planque's
/// words, and the card draws it as sent. The site and planque's words for the act are what the owner approves; the
/// page's context and the basket's lines are words to read, never anything the app acts on. The taf's reason and the
/// total are KIFF's own, as for any hold.
@MainActor
final class BrowserStepCardTests: XCTestCase {
    private struct List: Decodable { let exceptions: [KIFFClient.CardException] }

    private func held(browser: String?, kiff: String = "") throws -> HeldCall {
        let block = browser.map { #","browser":"# + $0 } ?? ""
        let body = #"""
        {"exceptions":[{"id":"ce_1","subject":"shopper-k5j6m5qx","action":"browse_click","title":"Click in Chrome","entity_id":"st_1",
          "status":"held","breaches":[{"outcome":"held_always","reason":"A pay step always asks you"}],
          "arguments":{"ref":"e12","kiff_operation_id":"op_1"}\#(kiff)\#(block),
          "created_at":"2026-11-21T14:00:00.000Z","expires_at":"2026-11-21T14:10:00Z"}]}
        """#
        let e = try KIFFClient.decoder.decode(List.self, from: Data(body.utf8)).exceptions[0]
        return KIFFMapping.held(e, tafID: UUID())
    }

    private let checkout = #"""
    {"site":"shop.example","origin":"https://checkout.shop.example","action_words":"Click “Place order”",
     "context":{"title":"Checkout – Shop","text":"Delivery Tuesday 8–10am. Place order"},
     "checkout":{"lines":[{"item":"Milk  1 L","quantity":2,"price":"2.40"},{"item":"Bread","quantity":1,"price":"1.90"}]},
     "chrome":{"id":"chr_1","name":"Work laptop"}}
    """#
    private let kiffsOwn = #","parameters":{"amount":430},"parameter_units":{"amount":{"unit":"EUR","scale":2}},"taf_reason":"Your weekly groceries, as agreed""#

    /// Every part of the shape reads: the act is what the card asks, and the total and the taf's reason are KIFF's.
    func testAHeldCheckoutStepReadsWhole() throws {
        let call = try held(browser: checkout, kiff: kiffsOwn)
        let step = try XCTUnwrap(call.browser)
        XCTAssertEqual(step.site, "shop.example")
        XCTAssertEqual(step.origin, "https://checkout.shop.example")
        XCTAssertEqual(step.actionWords, "Click “Place order”")
        XCTAssertEqual(step.pageTitle, "Checkout – Shop")
        XCTAssertEqual(step.pageText, "Delivery Tuesday 8–10am. Place order")
        XCTAssertEqual(step.basket, ["2 × Milk 1 L · 2.40", "Bread · 1.90"], "planque's objects, one plain line each, runs of space as one")
        XCTAssertEqual(step.chromeName, "Work laptop")
        XCTAssertEqual(call.proposed.summary, "Click “Place order”", "planque's words for the act, not the tool's name")
        XCTAssertEqual(call.proposed.amount, Money(minor: 430, currency: "EUR", scale: 2), "the total is KIFF's amount")
        XCTAssertEqual(call.tafReason, "Your weekly groceries, as agreed", "the taf's reason is KIFF's taf_reason")
        XCTAssertEqual(call.reason, "A pay step always asks you", "KIFF's rule stays KIFF's")
        XCTAssertEqual(call.details, ["ref: e12"])
    }

    /// A step that isn't a checkout has no basket, and nothing that wasn't sent is drawn.
    func testOnlyWhatsSentIsDrawn() throws {
        let call = try held(browser: #"{"site":"news.example","action_words":"Click “Next page”"}"#)
        let step = try XCTUnwrap(call.browser)
        XCTAssertNil(step.origin)
        XCTAssertNil(step.pageTitle)
        XCTAssertEqual(step.basket, [])
        XCTAssertNil(step.chromeName)
        XCTAssertNil(call.tafReason)
        XCTAssertNil(call.proposed.amount)
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
        let call = try held(browser: #"{"site":"shop.example","action_words":"Click “Pay”","context":"x","checkout":{"lines":[{"item":"a"},3,"b",{"quantity":2},{"item":"c","quantity":"x","price":1.5}]},"chrome":[1]}"#)
        let step = try XCTUnwrap(call.browser)
        XCTAssertNil(step.pageTitle)
        XCTAssertEqual(step.basket, ["a", "c · 1.5"], "a line that isn't an object, or has no item, is left out; a bad quantity is dropped")
        XCTAssertNil(step.chromeName)
    }
}
