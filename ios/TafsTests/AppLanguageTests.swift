import XCTest
@testable import Tafs

/// Account › Language names the app's language in that language, as the system does, and its
/// picker applies a language at once and keeps it for the next launch.
final class AppLanguageTests: XCTestCase {
    private var defaults: UserDefaults!
    private let suite = "AppLanguageTests"

    override func setUp() {
        super.setUp()
        UserDefaults().removePersistentDomain(forName: suite)
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suite)
        Words.bundle = .main
        super.tearDown()
    }

    /// A pick applies at once: the lines, their language and what's asked of the servers follow it,
    /// with no relaunch. It's kept, in iOS's own setting for the app too, and read again at launch.
    func testAPickedLanguageAppliesAtOnceAndIsKept() {
        AppLanguage.choose("de", defaults: defaults)
        XCTAssertEqual(Words.locale.identifier, "de")
        XCTAssertEqual(Words.Settings.languageTitle, "Sprache")
        XCTAssertEqual(KIFFClient.screenLanguages.first, "de", "tafs cloud and KIFF are asked in it first")
        XCTAssertEqual(AppLanguage.chosen(defaults), "de")
        XCTAssertEqual(defaults.stringArray(forKey: "AppleLanguages"), ["de"])

        Words.bundle = .main
        AppLanguage.apply(defaults)
        XCTAssertEqual(Words.locale.identifier, "de", "the next launch reads it again")
    }

    /// The device's language clears the pick, and a language tafs doesn't ship is never kept.
    func testTheDevicesLanguageClearsThePick() {
        AppLanguage.choose("fr", defaults: defaults)
        AppLanguage.choose(nil, defaults: defaults)
        XCTAssertNil(AppLanguage.chosen(defaults))
        // The app's own entry is gone; reading the key would fall through to the device's languages.
        XCTAssertNil(defaults.persistentDomain(forName: suite)?["AppleLanguages"])
        AppLanguage.choose("tr", defaults: defaults)
        XCTAssertNil(AppLanguage.chosen(defaults), "not one it ships: the device's language")
        defaults.set("tr", forKey: AppLanguage.key)
        XCTAssertNil(AppLanguage.chosen(defaults))
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["tr-TR", "pt-BR"]), "pt-BR", "Brazil reads pt-BR")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["pt-PT"]), "pt", "Portugal keeps pt")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["pt"]), "pt")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["es-419"]), "es", "a numeric region has no variant: its language")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["en-US"]), "en")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["pt_BR"]), "pt-BR")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["pt-BR"], shipped: ["en", "pt"]), "pt", "no variant shipped: its language")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["tr-TR"]), "en")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["ja-JP"]), "ja", "Japanese ships")
    }

    /// The picker's rows: the device's language, by the device's name, then the 8, each named in itself.
    func testThePickerListsTheDeviceThenTheEight() throws {
        XCTAssertEqual(Words.Settings.languageDevice(device: "iPhone"), "This iPhone's language")
        XCTAssertEqual(Words.languages.map { AppLanguage.name(of: $0) },
                       ["English", "Deutsch", "Français", "Español", "Italiano", "Português", "Nederlands", "Polski", "한국어", "日本語", "Português (Brasil)"])
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let account = try String(contentsOf: views.appendingPathComponent("SignInView.swift"), encoding: .utf8)
        XCTAssertTrue(account.contains("NavigationLink { LanguagePicker() }"), "Change language opens the app's own list")
        XCTAssertFalse(account.contains("Button(Words.Settings.changeLanguage) { openTafsSettings() }"))
    }
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
