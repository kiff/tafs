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
        AppLanguage.choose("sw", defaults: defaults)
        XCTAssertNil(AppLanguage.chosen(defaults), "not one it ships: the device's language")
        defaults.set("sw", forKey: AppLanguage.key)
        XCTAssertNil(AppLanguage.chosen(defaults))
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["sw-KE", "pt-BR"]), "pt-BR", "Brazil reads pt-BR")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["pt-PT"]), "pt", "Portugal keeps pt")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["pt"]), "pt")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["es-419"]), "es", "a numeric region has no variant: its language")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["en-US"]), "en")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["pt_BR"]), "pt-BR")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["pt-BR"], shipped: ["en", "pt"]), "pt", "no variant shipped: its language")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["sw-KE"]), "en")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["ja-JP"]), "ja", "Japanese ships")
    }

    /// Traditional Chinese ships as "zh-Hant": a phone says it by its script, or only by a region that writes it. Simplified
    /// Chinese isn't shipped, so it falls through to the person's next language, else English.
    func testTraditionalChineseIsReadByItsScriptOrItsRegion() {
        for tag in ["zh-Hant", "zh-Hant-TW", "zh-TW", "zh_TW", "zh-HK", "zh-MO", "zh-Hant-HK"] {
            XCTAssertEqual(AppLanguage.deviceLanguage(preferred: [tag]), "zh-Hant", tag)
        }
        for tag in ["zh", "zh-CN", "zh-Hans", "zh-Hans-CN", "zh-SG"] {
            XCTAssertEqual(AppLanguage.deviceLanguage(preferred: [tag]), "en", tag)
        }
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["zh-Hans-CN", "fr-FR"]), "fr", "Simplified is skipped, not mistaken for Traditional")
        // Before the app ships it, it isn't one the app has.
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["zh-TW"], shipped: ["en", "pt"]), "en")
    }

    /// Serbian ships in Latin script ("sr-Latn"): a phone says it by its script. Cyrillic isn't shipped, so it falls through to
    /// the person's next language, else English. Azerbaijani, Bengali, Hebrew and the others ship under their own codes.
    func testSerbianIsReadByItsScript() {
        for tag in ["sr-Latn", "sr-Latn-RS", "sr_Latn_ME", "sr-Latn-BA"] {
            XCTAssertEqual(AppLanguage.deviceLanguage(preferred: [tag]), "sr-Latn", tag)
        }
        for tag in ["sr", "sr-RS", "sr-Cyrl", "sr-Cyrl-RS"] {
            XCTAssertEqual(AppLanguage.deviceLanguage(preferred: [tag]), "en", tag)
        }
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["sr-Cyrl-RS", "fr-FR"]), "fr", "Cyrillic is skipped, not mistaken for Latin")
        XCTAssertEqual(AppLanguage.deviceLanguage(preferred: ["sr-Latn-RS"], shipped: ["en", "pt"]), "en", "before the app ships it, it isn't one the app has")
        for (tag, code) in [("ar-SA", "ar"), ("sq-AL", "sq"), ("ka-GE", "ka"), ("hy-AM", "hy"), ("az-Latn-AZ", "az"), ("bn-BD", "bn"), ("bn-IN", "bn"), ("he-IL", "he")] {
            XCTAssertEqual(AppLanguage.deviceLanguage(preferred: [tag]), code, tag)
        }
    }

    /// The picker's rows: the device's language, by the device's name, then the 8, each named in itself.
    func testThePickerListsTheDeviceThenTheEight() throws {
        XCTAssertEqual(Words.Settings.languageDevice(device: "iPhone"), "This iPhone's language")
        let names = Words.languages.map { AppLanguage.name(of: $0) }
        // Traditional Chinese is named with its script, so it doesn't read as Simplified ("繁體中文", or "中文 (繁體)").
        let hant = try XCTUnwrap(Words.languages.firstIndex(of: "zh-Hant"))
        XCTAssertTrue(names[hant].contains("繁體"), names[hant])
        let pinned = ["en": "English", "de": "Deutsch", "fr": "Français", "es": "Español", "it": "Italiano", "pt": "Português", "nl": "Nederlands", "pl": "Polski", "ko": "한국어", "ja": "日本語", "et": "Eesti", "lt": "Lietuvių", "lv": "Latviešu", "cs": "Čeština", "sk": "Slovenčina", "sl": "Slovenščina", "hu": "Magyar", "ro": "Română", "bg": "Български", "hr": "Hrvatski", "tr": "Türkçe", "id": "Indonesia", "vi": "Tiếng Việt", "th": "ไทย", "ca": "Català", "mk": "Македонски", "mn": "Монгол", "ne": "नेपाली", "km": "ខ្មែរ", "lo": "ລາວ", "pt-BR": "Português (Brasil)"]
        // Batch 4's languages are each named in its own script; ICU spells a few of the names differently by OS version, so each is read by a part of it.
        let parts = ["ar": "العربية", "sr-Latn": "rpski", "sq": "hqip", "ka": "ქართული", "hy": "երեն", "az": "zərbaycan", "bn": "বাংলা", "he": "עברית", "tk": "rkmen", "hi": "हिन्द", "ur": "اردو"]
        XCTAssertEqual(Set(pinned.keys).union(parts.keys).union(["zh-Hant"]), Set(Words.languages), "every shipped language is checked")
        for (code, name) in zip(Words.languages, names) {
            if let want = pinned[code] { XCTAssertEqual(name, want, code) }
            if let part = parts[code] { XCTAssertTrue(name.localizedCaseInsensitiveContains(part), "\(code): \(name)") }
        }
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
