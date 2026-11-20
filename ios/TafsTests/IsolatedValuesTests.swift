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
}
