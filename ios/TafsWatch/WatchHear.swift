import AVFoundation
import SwiftUI

/// What speaks a line aloud: the watch's own speech, or a test's stand-in.
@MainActor
protocol Speaking: AnyObject {
    /// Speaks `text` in `language` (a BCP 47 language code), stopping whatever it was saying.
    func speak(_ text: String, language: String)
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

    func speak(_ text: String, language: String) {
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        // A language the watch has no voice for gets its own default voice: the words are still on screen.
        utterance.voice = AVSpeechSynthesisVoice(language: language)
        synthesizer.speak(utterance)
    }

    func stop() { synthesizer.stopSpeaking(at: .immediate) }
}

/// Hear (docs/watch-app.md, "Where watchOS differs"; Wear OS's `Speaker`): Coach's answer read aloud on the watch, in the app's
/// language. Nothing is sent anywhere and nothing is kept. A speech that fails leaves the line on screen as it is. It stops
/// when its screen goes.
@MainActor
final class WatchHear: ObservableObject {
    private let speaker: Speaking
    /// The language the app speaks in: its words' language, so the voice reads them as written.
    private let language: () -> String

    init(speaker: Speaking? = nil, language: (() -> String)? = nil) {
        self.speaker = speaker ?? SystemSpeaker()
        self.language = language ?? { WatchHear.appLanguage }
    }

    /// The app's words' language code ("ur", "pt"), as `Words` writes them.
    static var appLanguage: String { Words.locale.language.languageCode?.identifier ?? "en" }

    /// Reads `text` aloud; nothing for a line with no words.
    func hear(_ text: String) {
        let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else { return }
        speaker.speak(words, language: language())
    }

    func stop() { speaker.stop() }
}

/// The Hear button under an answer: reads it aloud on a tap, and stops when the answer's screen goes.
struct HearButton: View {
    let text: String
    @ObservedObject var hear: WatchHear

    var body: some View {
        Button { hear.hear(text) } label: {
            Label(Words.Watch.hear, systemImage: "speaker.wave.2")
        }
        .onDisappear { hear.stop() }
    }
}
