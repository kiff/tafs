import Foundation
#if os(iOS)
import AVFoundation
import Speech
#endif

/// Speaking a message instead of typing it (kiff/tafs#208), in a taf's chat and in Coach
/// mode. The words go into the message box as they're spoken, and the owner sends them
/// as if typed: dictation never sends anything. Recognition runs on the device only, so no
/// audio leaves the phone; only the text the owner sends reaches tafs cloud.
@MainActor
final class Dictation: ObservableObject {
    enum State: Equatable {
        case idle
        case starting
        case listening
        /// Dictation can't run: why, and how to turn it on, in the owner's words.
        case blocked(String)
    }

    /// The languages it understands, Spanish and English to start.
    enum Language: String, CaseIterable {
        case english = "en-US"
        case spanish = "es-ES"

        var label: String { self == .english ? "EN" : "ES" }
        var name: String { self == .english ? "English" : "Spanish" }
        var locale: Locale { Locale(identifier: rawValue) }

        /// The device's language when it's one of these, else English.
        static var device: Language {
            Locale.preferredLanguages.first?.lowercased().hasPrefix("es") == true ? .spanish : .english
        }
    }

    /// The line the system shows with its microphone and speech prompts (Info.plist).
    static let purpose = "tafs turns your voice into text on this device. Your voice isn't sent anywhere."

    @Published private(set) var state: State = .idle
    @Published private(set) var language: Language

    private let engine: SpeechEngine
    /// What the box held when dictation started: the words go after it.
    private var before = ""
    private var write: ((String) -> Void)?

    init(engine: SpeechEngine? = nil, language: Language = .device) {
        #if os(iOS)
        self.engine = engine ?? DeviceSpeech()
        #else
        self.engine = engine ?? NoSpeech()
        #endif
        self.language = language
    }

    var listening: Bool { state == .listening || state == .starting }

    /// Starts dictating into the box, or stops. `text` is the box as it is now; `write`
    /// puts the box's new text back. `hints` are words the owner is likely to say.
    func toggle(text: String, hints: [String], write: @escaping (String) -> Void) async {
        if listening {
            stop()
        } else {
            await start(text: text, hints: hints, write: write)
        }
    }

    func start(text: String, hints: [String], write: @escaping (String) -> Void) async {
        guard !listening else { return }
        state = .starting
        if let why = await engine.authorize() {
            state = .blocked(why)
            return
        }
        if let why = engine.unavailable(language) {
            state = .blocked(why)
            return
        }
        before = text
        self.write = write
        do {
            try engine.start(language: language, hints: Self.cleaned(hints), onText: { [weak self] heard in
                Task { @MainActor in self?.heard(heard) }
            }, onEnd: { [weak self] why in
                Task { @MainActor in self?.ended(why) }
            })
            state = .listening
        } catch {
            engine.stop()
            state = .blocked("Dictation couldn't start: \(error.localizedDescription)")
        }
    }

    /// Stops listening. What was heard stays in the box, unsent.
    func stop() {
        guard listening else { return }
        engine.stop()
        state = .idle
        write = nil
    }

    /// Switches the language while dictating: listening starts again in it, after what was
    /// already heard.
    func switchLanguage(text: String, hints: [String], write: @escaping (String) -> Void) async {
        let next: Language = language == .english ? .spanish : .english
        let wasListening = listening
        if wasListening { stop() }
        language = next
        if wasListening { await start(text: text, hints: hints, write: write) }
    }

    /// The blocked message is read: the mic goes back to waiting.
    func dismiss() {
        if case .blocked = state { state = .idle }
    }

    private func heard(_ words: String) {
        guard state == .listening, let write else { return }
        write(Self.joined(before, words))
    }

    private func ended(_ why: String?) {
        guard listening else { return }
        engine.stop()
        write = nil
        state = why.map { .blocked($0) } ?? .idle
    }

    /// The box's text before dictation, then what was heard, with one space between.
    static func joined(_ before: String, _ heard: String) -> String {
        let words = heard.trimmingCharacters(in: .whitespaces)
        guard !words.isEmpty else { return before }
        guard !before.isEmpty else { return words }
        return before.last?.isWhitespace == true ? before + words : before + " " + words
    }

    /// The hints, each once, without blanks, at most 100: the recognizer weighs fewer better.
    static func cleaned(_ hints: [String]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for h in hints {
            let t = h.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !t.isEmpty, seen.insert(t.lowercased()).inserted else { continue }
            out.append(t)
            if out.count == 100 { break }
        }
        return out
    }
}

/// What turns speech into text: the device's recognizer, or a stand-in in tests.
@MainActor
protocol SpeechEngine: AnyObject {
    /// Asks for microphone and speech permission: nil when given, else why not and how to
    /// turn it on.
    func authorize() async -> String?
    /// Whether recognition runs on this device in `language`: nil when it does, else why not.
    func unavailable(_ language: Dictation.Language) -> String?
    /// Starts listening: what it hears so far goes to `onText`, and its end to `onEnd`, with
    /// why it stopped when that wasn't the owner.
    func start(language: Dictation.Language, hints: [String], onText: @escaping @Sendable (String) -> Void,
               onEnd: @escaping @Sendable (String?) -> Void) throws
    func stop()
}

/// No speech on this platform (the Mac is out of v1's scope).
@MainActor
final class NoSpeech: SpeechEngine {
    func authorize() async -> String? { "Dictation isn't available here." }
    func unavailable(_ language: Dictation.Language) -> String? { "Dictation isn't available here." }
    func start(language: Dictation.Language, hints: [String], onText: @escaping @Sendable (String) -> Void,
               onEnd: @escaping @Sendable (String?) -> Void) throws {}
    func stop() {}
}

#if os(iOS)
/// Apple's speech recognition, made to run on the device: no audio leaves the phone.
///
/// Speech and the microphone call back on their own threads. In Swift 6 a closure written
/// in a main-actor method is the main actor's, and called elsewhere it stops the app, so each
/// callback handed to them is made in a nonisolated function, never here.
@MainActor
final class DeviceSpeech: SpeechEngine {
    private let audio = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    func authorize() async -> String? {
        switch await Self.speechPermission() {
        case .authorized: break
        case .restricted: return "Speech recognition is restricted on this device, so dictation can't run."
        default: return "Speech recognition is off for tafs. Turn it on in Settings › Privacy & Security › Speech Recognition."
        }
        return await Self.microphonePermission() ? nil : "The microphone is off for tafs. Turn it on in Settings › tafs › Microphone."
    }

    func unavailable(_ language: Dictation.Language) -> String? {
        guard let recognizer = SFSpeechRecognizer(locale: language.locale), recognizer.supportsOnDeviceRecognition else {
            return "Dictation in \(language.name) isn't available on this device."
        }
        return recognizer.isAvailable ? nil : "Dictation isn't available right now. Try again in a moment."
    }

    func start(language: Dictation.Language, hints: [String], onText: @escaping @Sendable (String) -> Void,
               onEnd: @escaping @Sendable (String?) -> Void) throws {
        guard let recognizer = SFSpeechRecognizer(locale: language.locale) else {
            throw DictationError.unavailable
        }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement, options: .duckOthers)
        try session.setActive(true, options: .notifyOthersOnDeactivation)

        let request = SFSpeechAudioBufferRecognitionRequest()
        // On the device only: never sent to a server.
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = true
        request.addsPunctuation = true
        request.contextualStrings = hints
        self.request = request

        let input = audio.inputNode
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0), block: Self.feeding(request))
        audio.prepare()
        try audio.start()
        task = recognizer.recognitionTask(with: request, resultHandler: Self.results(onText: onText, onEnd: onEnd))
    }

    func stop() {
        if audio.isRunning { audio.stop() }
        audio.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.finish()
        request = nil
        task = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    nonisolated private static func speechPermission() async -> SFSpeechRecognizerAuthorizationStatus {
        await withCheckedContinuation { done in
            SFSpeechRecognizer.requestAuthorization { done.resume(returning: $0) }
        }
    }

    nonisolated private static func microphonePermission() async -> Bool {
        await withCheckedContinuation { done in
            AVAudioSession.sharedInstance().requestRecordPermission { done.resume(returning: $0) }
        }
    }

    /// The microphone's sound, to the request, on the audio thread.
    nonisolated private static func feeding(_ request: SFSpeechAudioBufferRecognitionRequest) -> AVAudioNodeTapBlock {
        nonisolated(unsafe) let request = request
        return { buffer, _ in request.append(buffer) }
    }

    /// What the recognizer heard so far, and its end, on the recognizer's thread.
    nonisolated private static func results(onText: @escaping @Sendable (String) -> Void,
                                            onEnd: @escaping @Sendable (String?) -> Void)
        -> @Sendable (SFSpeechRecognitionResult?, (any Error)?) -> Void {
        { result, error in
            if let result {
                onText(result.bestTranscription.formattedString)
                if result.isFinal { onEnd(nil) }
            } else if error != nil {
                onEnd("Dictation stopped. Tap the mic to start again.")
            }
        }
    }
}

enum DictationError: LocalizedError {
    case unavailable
    var errorDescription: String? { "Speech recognition isn't available for this language." }
}
#endif
