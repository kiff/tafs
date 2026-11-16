import XCTest
@testable import TafsWatch

/// The watch draws the phone's own words (app/strings): the catalog is built into the watch
/// app, in every language the phone ships.
final class WordsOnWatchTests: XCTestCase {
    func testALineIsItsWordsNotItsKey() {
        XCTAssertNotEqual(Words.NeedsYou.title, "needs_you.title")
        XCTAssertFalse(Words.NeedsYou.count(count: 2).contains("needs_you.count"))
    }

    func testEveryShippedLanguageIsInTheWatchApp() {
        for language in Words.languages {
            let folder = Bundle.main.path(forResource: language, ofType: "lproj")
            XCTAssertNotNil(folder, "\(language) is missing from the watch app")
            let lines = folder.flatMap(Bundle.init(path:))
            let title = lines?.localizedString(forKey: "needs_you.title", value: nil, table: nil)
            XCTAssertNotNil(title)
            XCTAssertNotEqual(title, "needs_you.title", "\(language) has no lines in the watch app")
        }
    }
}
