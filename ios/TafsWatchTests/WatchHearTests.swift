import XCTest
@testable import TafsWatch

/// Hear (docs/watch-app.md, "Coach"): Coach's answer read aloud on the watch by a voice of the app's own language, with nothing
/// sent or kept, and not offered where the watch has no such voice. As Wear OS's `Speaker`.
@MainActor
final class WatchHearTests: XCTestCase {
    /// A watch with these voices.
    private final class Speaker: Speaking {
        let voices: [String]
        var said: [(String, String)] = []
        var stops = 0
        init(_ voices: [String]) { self.voices = voices }
        func voice(for language: String) -> String? { WatchHear.voice(for: language, among: voices) }
        func speak(_ text: String, voice: String) { said.append((text, voice)) }
        func stop() { stops += 1 }
    }

    func testHearReadsTheAnswerByTheAppsOwnVoice() {
        let speaker = Speaker(["en-US", "ur-PK"])
        let hear = WatchHear(speaker: speaker, tag: "ur")
        XCTAssertTrue(hear.canHear)
        hear.hear("  Your refund went through.\n")
        XCTAssertEqual(speaker.said.map(\.0), ["Your refund went through."], "the words, trimmed")
        XCTAssertEqual(speaker.said.map(\.1), ["ur-PK"])
        hear.hear("Again")
        XCTAssertEqual(speaker.said.count, 2, "each tap reads it, the speaker stopping what it said")
    }

    /// A language the watch has no voice for isn't spoken, and Hear isn't offered: never another language's voice.
    func testALanguageWithNoVoiceIsNeverSpoken() {
        let speaker = Speaker(["en-US", "tr-TR", "ru-RU"])
        let hear = WatchHear(speaker: speaker, tag: "tk")
        XCTAssertFalse(hear.canHear)
        XCTAssertNil(hear.voice)
        hear.hear("Pul gaýtaryldy.")
        XCTAssertTrue(speaker.said.isEmpty)
    }

    /// The full tag first: Brazil's voice for pt-BR, Portugal's for pt, Taiwan's or Hong Kong's for zh-Hant; then the language.
    func testTheVoiceIsTheFullTagsFirst() {
        let voices = ["pt-PT", "pt-BR", "zh-CN", "zh-HK", "zh-TW", "en-GB", "en-US"]
        XCTAssertEqual(WatchHear(speaker: Speaker(voices), tag: "pt-BR").voice, "pt-BR")
        XCTAssertEqual(WatchHear(speaker: Speaker(voices), tag: "pt").voice, "pt-PT")
        XCTAssertEqual(WatchHear(speaker: Speaker(voices), tag: "zh-Hant").voice, "zh-TW")
        XCTAssertEqual(WatchHear(speaker: Speaker(["zh-CN", "zh-HK"]), tag: "zh-Hant").voice, "zh-HK")
        XCTAssertEqual(WatchHear(speaker: Speaker(["pt-PT"]), tag: "pt-BR").voice, "pt-PT", "then the language, in another region")
        XCTAssertEqual(WatchHear(speaker: Speaker(["en-US", "en-GB"]), tag: "en").voice, "en-GB", "a bare language: the same voice each time")
        XCTAssertNil(WatchHear(speaker: Speaker(["hr-HR", "ru-RU"]), tag: "sr-Latn").voice, "no Serbian voice: none")
    }

    func testTheCandidatesInOrder() {
        XCTAssertEqual(WatchHear.candidates(for: "pt-BR"), ["pt-BR", "pt"])
        XCTAssertEqual(WatchHear.candidates(for: "pt"), ["pt-PT", "pt"])
        XCTAssertEqual(WatchHear.candidates(for: "zh-Hant"), ["zh-TW", "zh-HK", "zh"])
        XCTAssertEqual(WatchHear.candidates(for: "sr-Latn"), ["sr"])
        XCTAssertEqual(WatchHear.candidates(for: "ur"), ["ur"])
        XCTAssertEqual(WatchHear.candidates(for: ""), [])
    }

    func testALineWithNoWordsIsNotSpoken() {
        let speaker = Speaker(["en-US"])
        let hear = WatchHear(speaker: speaker, tag: "en")
        hear.hear(" \n ")
        hear.hear("")
        XCTAssertTrue(speaker.said.isEmpty)
    }

    func testStopStopsTheSpeech() {
        let speaker = Speaker(["en-US"])
        let hear = WatchHear(speaker: speaker, tag: "en")
        hear.hear("Done")
        hear.stop()
        XCTAssertEqual(speaker.stops, 1)
    }

    /// The app's language is its catalog's own tag, as `Words` reads it.
    func testTheAppsTagIsTheCatalogs() {
        XCTAssertEqual(WatchHear.appTag, Words.bundle.localizedString(forKey: "_language", value: "en", table: nil))
        XCTAssertFalse(WatchHear.appTag.isEmpty)
    }
}
