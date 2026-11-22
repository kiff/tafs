import XCTest
@testable import Tafs

/// A line in a right-to-left language sets each value apart (FSI … PDI), so "Rae" or "€150.00" inside an Arabic or
/// Hebrew sentence keeps its own direction without turning the sentence (agentgabo and dev-android, 20 Nov). Left to
/// right, values are exactly as passed, so no line in today's eight languages changes.
final class IsolatedValuesTests: XCTestCase {
    func testRightToLeftLinesIsolateTheirValues() {
        for language in ["ar", "he", "fa", "ur", "ar_SA"] {
            XCTAssertEqual(Words.isolated(["Rae", "€150.00"], in: Locale(identifier: language)),
                           ["\u{2068}Rae\u{2069}", "\u{2068}€150.00\u{2069}"], language)
        }
    }

    func testLeftToRightLinesAreUnchanged() {
        for language in ["en", "de", "fr", "es", "it", "pt", "nl", "pl", "ja", "ko"] {
            XCTAssertEqual(Words.isolated(["Rae", "€150.00"], in: Locale(identifier: language)), ["Rae", "€150.00"], language)
        }
    }

    /// qa-kiff's two asks: a value's own direction characters are taken out inside the isolate, so it can't close it
    /// early; and what's copied carries none, so a code copied from an Arabic screen pastes as its own characters.
    func testAValueCantCloseItsIsolateAndCopiesAreBare() {
        XCTAssertEqual(Words.isolated(["Ra\u{2069}e\u{202E}"], in: Locale(identifier: "ar")), ["\u{2068}Rae\u{2069}"])
        XCTAssertEqual(Words.withoutDirection("\u{2068}ABCD-EFGH\u{2069}"), "ABCD-EFGH")
        XCTAssertEqual(Words.withoutDirection("DE89 3704\nشركة"), "DE89 3704\nشركة", "letters and line breaks stay")
    }

    /// The ko preview: a section's small heading in Korean or Japanese keeps the plain face, untracked, so its words
    /// aren't pulled apart; Latin headings keep the monospaced, tracked look.
    func testIdeographicHeadingsKeepThePlainFace() {
        XCTAssertTrue(Eyebrow.ideographic("기억하는 것"))
        XCTAssertTrue(Eyebrow.ideographic("確認が必要"))
        XCTAssertTrue(Eyebrow.ideographic("内 TAFS"))
        XCTAssertEqual(ScaledFont.design(.monospaced, language: "ar"), .default, "the monospaced face splits Arabic's joined letters")
        XCTAssertEqual(ScaledFont.design(.monospaced, language: "fr"), .monospaced)
        XCTAssertEqual(ScaledFont.design(.rounded, language: "ar"), .rounded, "only the monospaced face is replaced")
        XCTAssertTrue(Eyebrow.ideographic("بانتظارك"), "Arabic's letters join: no tracking, no monospaced face")
        XCTAssertTrue(Eyebrow.ideographic("المتبقي 9:57"))
        XCTAssertFalse(Eyebrow.ideographic("WHAT IT REMEMBERS"))
        XCTAssertFalse(Eyebrow.ideographic("À VOUS DE VOIR"))
    }
}
