import XCTest
@testable import TafsWatch

/// Hear (docs/watch-app.md, "Where watchOS differs"): Coach's answer read aloud on the watch, in the app's language, with
/// nothing sent or kept. As Wear OS's `Speaker`.
@MainActor
final class WatchHearTests: XCTestCase {
    private final class Speaker: Speaking {
        var said: [(String, String)] = []
        var stops = 0
        func speak(_ text: String, language: String) { said.append((text, language)) }
        func stop() { stops += 1 }
    }

    func testHearReadsTheAnswerInTheAppsLanguage() {
        let speaker = Speaker()
        let hear = WatchHear(speaker: speaker, language: { "ur" })
        hear.hear("  Your refund went through.\n")
        XCTAssertEqual(speaker.said.map(\.0), ["Your refund went through."], "the words, trimmed")
        XCTAssertEqual(speaker.said.map(\.1), ["ur"])
        hear.hear("Again")
        XCTAssertEqual(speaker.said.count, 2, "each tap reads it, the speaker stopping what it said")
    }

    func testALineWithNoWordsIsNotSpoken() {
        let speaker = Speaker()
        let hear = WatchHear(speaker: speaker, language: { "en" })
        hear.hear(" \n ")
        hear.hear("")
        XCTAssertTrue(speaker.said.isEmpty)
    }

    func testStopStopsTheSpeech() {
        let speaker = Speaker()
        let hear = WatchHear(speaker: speaker, language: { "en" })
        hear.hear("Done")
        hear.stop()
        XCTAssertEqual(speaker.stops, 1)
    }

    /// The app's language is its words' own: a language code a voice can be found by, never a locale with extensions.
    func testTheAppsLanguageIsALanguageCode() {
        let code = WatchHear.appLanguage
        XCTAssertFalse(code.isEmpty)
        XCTAssertFalse(code.contains("-u-"), code)
        XCTAssertEqual(code, Words.locale.language.languageCode?.identifier ?? "en")
    }
}
