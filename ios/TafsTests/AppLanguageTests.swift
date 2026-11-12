import XCTest
@testable import Tafs

/// Account › Language names the app's language in that language, as the system does.
final class AppLanguageTests: XCTestCase {
    func testEachLanguageIsNamedInItself() {
        XCTAssertEqual(AppLanguage.name(of: "en"), "English")
        XCTAssertEqual(AppLanguage.name(of: "de"), "Deutsch")
        XCTAssertEqual(AppLanguage.name(of: "fr"), "Français")
        XCTAssertEqual(AppLanguage.name(of: "es"), "Español")
        XCTAssertEqual(AppLanguage.name(of: "it"), "Italiano")
        XCTAssertEqual(AppLanguage.name(of: "pt"), "Português")
        XCTAssertEqual(AppLanguage.name(of: "nl"), "Nederlands")
        XCTAssertEqual(AppLanguage.name(of: "pl"), "Polski")
    }

    func testEveryShippedLanguageHasAName() {
        for code in Words.languages {
            XCTAssertFalse(AppLanguage.name(of: code).isEmpty, code)
            XCTAssertNotEqual(AppLanguage.name(of: code), code, "a name, not the code")
        }
    }
}
