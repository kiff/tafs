import AVFoundation
import SwiftUI

/// What speaks a line aloud: the watch's own speech, or a test's stand-in.
@MainActor
protocol Speaking: AnyObject {
    /// The voice this watch has for `language` (a BCP 47 tag such as "pt-BR", or a bare language code such as "pt"), as the
    /// tag that voice speaks; nil when it has none.
    func voice(for language: String) -> String?
    /// Speaks `text` with the voice for `voice` (a tag `voice(for:)` gave), stopping whatever it was saying.
    func speak(_ text: String, voice: String)
    func stop()
}

/// The watch's own speech (`AVSpeechSynthesizer`), on its speaker or the headphones it's connected to. It keeps its own
/// audio session (`usesApplicationAudioSession = false`), so it never takes the one the recorder uses.
@MainActor
final class SystemSpeaker: Speaking {
    private let synthesizer: AVSpeechSynthesizer = {
        let s = AVSpeechSynthesizer()
        s.usesApplicationAudioSession = false
        return s
    }()

    func voice(for language: String) -> String? {
        let voices = AVSpeechSynthesisVoice.speechVoices().map(\.language)
        return WatchHear.voice(for: language, among: voices)
    }

    func speak(_ text: String, voice: String) {
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: voice)
        synthesizer.speak(utterance)
    }

    func stop() { synthesizer.stopSpeaking(at: .immediate) }
}

/// Hear (docs/watch-app.md, "Coach"; Wear OS's `Speaker`): Coach's answer read aloud on the watch, by a voice of the app's own
/// language. Nothing is sent anywhere and nothing is kept. A language the watch has no voice for isn't spoken at all, so Hear
/// isn't offered: never another language's voice reading it. It stops when its screen goes.
@MainActor
final class WatchHear: ObservableObject {
    private let speaker: Speaking
    /// The voice the answer is read in, found once: nil when the watch has none for the app's language.
    let voice: String?

    /// `tag` is the app's words' language as the catalog names it ("pt-BR", "zh-Hant", "ur"): `Words`' own by default.
    init(speaker: Speaking? = nil, tag: String? = nil) {
        let speaker = speaker ?? SystemSpeaker()
        self.speaker = speaker
        var found: String?
        for candidate in Self.candidates(for: tag ?? Self.appTag) {
            if let v = speaker.voice(for: candidate) { found = v; break }
        }
        voice = found
    }

    /// The app's words' language, as its catalog names it.
    static var appTag: String { Words.bundle.localizedString(forKey: "_language", value: "en", table: nil) }

    /// Whether Hear is offered: only with a voice of the app's own language.
    var canHear: Bool { voice != nil }

    /// The voices to look for, in order, for an app language: its own region first (pt-BR); a script, by the regions that
    /// write it (zh-Hant: zh-TW, then zh-HK); a bare language by its home region where another region has its own catalog
    /// (pt: pt-PT, since Brazil's is pt-BR); then the bare language, which takes any voice of it.
    static func candidates(for tag: String) -> [String] {
        let parts = tag.replacingOccurrences(of: "_", with: "-").split(separator: "-").map(String.init)
        guard let language = parts.first?.lowercased(), !language.isEmpty else { return [] }
        let rest = parts.dropFirst()
        let script = rest.first { $0.count == 4 }?.lowercased()
        let region = rest.first { $0.count == 2 || ($0.count == 3 && Int($0) != nil) }?.uppercased()
        var out: [String] = []
        if let region { out.append("\(language)-\(region)") }
        switch (language, script) {
        case ("zh", "hant"?): out += ["zh-TW", "zh-HK"]
        case ("zh", "hans"?): out += ["zh-CN"]
        default: break
        }
        if region == nil, script == nil, let home = homeRegions[language] { out.append("\(language)-\(home)") }
        out.append(language)
        return out
    }

    /// A bare app language whose home region isn't the one another catalog of it already names.
    static let homeRegions: [String: String] = ["pt": "PT"]

    /// The voice among `voices` (their BCP 47 tags) for one candidate: the same tag, any case; for a bare language, any voice
    /// of it, the first by its tag so it's the same each time. Nil when there's none.
    static func voice(for candidate: String, among voices: [String]) -> String? {
        if candidate.contains("-") { return voices.first { $0.caseInsensitiveCompare(candidate) == .orderedSame } }
        let language = candidate.lowercased()
        return voices.filter { $0.split(separator: "-").first.map { $0.lowercased() == language } ?? false }.sorted().first
    }

    /// Reads `text` aloud; nothing for a line with no words, or with no voice for the app's language.
    func hear(_ text: String) {
        let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty, let voice else { return }
        speaker.speak(words, voice: voice)
    }

    func stop() { speaker.stop() }
}

/// The Hear button under an answer: reads it aloud on a tap, and stops when the answer's screen goes. Not drawn where the
/// watch has no voice for the app's language.
struct HearButton: View {
    let text: String
    @ObservedObject var hear: WatchHear

    var body: some View {
        if hear.canHear {
            Button { hear.hear(text) } label: {
                Label(Words.Watch.hear, systemImage: "speaker.wave.2")
            }
            .onDisappear { hear.stop() }
        }
    }
}
