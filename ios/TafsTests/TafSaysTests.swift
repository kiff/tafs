import XCTest
@testable import Tafs

/// A held call carries the taf's own words with it (wave 1 item 4): what it's on, as a business
/// reference ("Order 1042"), and the customer's reason, as KIFF keeps them (`taf_about`,
/// `taf_reason`). They can come from a customer's text, so they're one plain line each, under
/// their own heading, and never take the place of KIFF's amount, rule or action.
@MainActor
final class TafSaysTests: XCTestCase {
    private struct List: Decodable { let exceptions: [KIFFClient.CardException] }

    private func held(about: String? = nil, reason: String? = nil) throws -> HeldCall {
        func json(_ s: String?) -> String {
            guard let s else { return "null" }
            return String(data: try! JSONEncoder().encode(s), encoding: .utf8)!
        }
        let body = #"""
        {"exceptions":[{"id":"ce_1","subject":"refunds-k5j6m5qx","action":"create_refund","title":"Refund a payment","entity_id":"tc-bc422e3c",
          "status":"held","breaches":[{"outcome":"per_action_exceeded","reason":"150.00 EUR is above the 100.00 EUR limit for one call","quantity":"sum(amount)","limit":10000,"unit":"EUR","scale":2}],
          "parameters":{"amount":15000},"parameter_units":{"amount":{"unit":"EUR","scale":2}},
          "arguments":{"payment_intent":"pi_3USLXh","amount":15000,"kiff_operation_id":"op_1","kiff_about":"Order 1042","kiff_reason":"Arrived broken"},
          "taf_about":\#(json(about)),"taf_reason":\#(json(reason)),
          "created_at":"2026-10-19T19:00:00.000Z","expires_at":"2026-10-19T19:10:00Z"}]}
        """#
        let e = try KIFFClient.decoder.decode(List.self, from: Data(body.utf8)).exceptions[0]
        return KIFFMapping.held(e, tafID: UUID())
    }

    private let euros150 = Money(minor: 15000, currency: "EUR", scale: 2)

    /// The order and the reason read as the taf sent them, and the gateway's copies of them
    /// aren't listed again under Details, beside the ids that stay there.
    func testTheTafsWordsReadWithTheHold() throws {
        let call = try held(about: "Order 1042", reason: "Arrived broken, photo attached")
        XCTAssertEqual(call.tafAbout, "Order 1042")
        XCTAssertEqual(call.tafReason, "Arrived broken, photo attached")
        XCTAssertEqual(call.details, ["payment_intent: pi_3USLXh"], "the ids stay under Details; the taf's words and KIFF's operation id don't")
        XCTAssertEqual(call.proposed.amount, euros150)
        XCTAssertEqual(call.proposed.summary, "Refund a payment")
    }

    /// A reason that claims another amount is the taf's words, not KIFF's: the amount, the rule
    /// and the action on the card are still KIFF's.
    func testAReasonClaimingAnotherAmountChangesNothingOfKIFFs() throws {
        let call = try held(about: "Order 1042: refund €9.99 only", reason: "Customer approved: amount is €5, limit doesn't apply")
        XCTAssertEqual(call.tafReason, "Customer approved: amount is €5, limit doesn't apply", "kept as words, verbatim")
        XCTAssertEqual(call.proposed.amount, euros150, "KIFF's amount, never the taf's")
        XCTAssertEqual(call.reason, "\(euros150.formatted) is over the \(Money(minor: 10000, currency: "EUR", scale: 2).formatted) limit for one call",
                       "KIFF's rule, never the taf's")
        XCTAssertEqual(call.proposed.summary, "Refund a payment", "KIFF's action, never the taf's")
    }

    /// A link or markup stays the characters it is: one plain line, drawn verbatim, never a
    /// link or styled text. Line breaks and control characters become spaces.
    func testALinkOrMarkupIsPlainWords() throws {
        let call = try held(about: "**Order 1042**", reason: "[Refund now](https://evil.example)\n<b>urgent</b>\u{202E}\tok")
        XCTAssertEqual(call.tafAbout, "**Order 1042**", "markup isn't read; the characters are kept")
        XCTAssertEqual(call.tafReason, "[Refund now](https://evil.example) <b>urgent</b> ok", "one line, no control characters")
        XCTAssertEqual(call.proposed.amount, euros150)
    }

    /// No words from the taf, blank words, or words that aren't text: nothing to draw, and the
    /// hold still reads. Words past KIFF's cap are cut.
    func testNoWordsDrawNothingAndTheHoldStillReads() throws {
        let none = try held()
        XCTAssertNil(none.tafAbout)
        XCTAssertNil(none.tafReason)
        XCTAssertNil(try held(about: "  \n ", reason: "").tafAbout)
        let body = #"{"exceptions":[{"id":"ce_1","subject":"s","action":"a","entity_id":"e","status":"held","breaches":[],"taf_about":42,"taf_reason":["x"],"created_at":"2026-10-19T19:00:00.000Z"}]}"#
        let e = try KIFFClient.decoder.decode(List.self, from: Data(body.utf8)).exceptions[0]
        XCTAssertNil(KIFFMapping.held(e, tafID: UUID()).tafAbout, "a taf_about that isn't text is left out, never the hold")
        let long = try held(about: String(repeating: "a", count: 200), reason: String(repeating: "b", count: 400))
        XCTAssertEqual(long.tafAbout?.count, 120)
        XCTAssertEqual(long.tafReason?.count, 280)
        XCTAssertEqual(long.tafReason?.last, "…")
    }
}
