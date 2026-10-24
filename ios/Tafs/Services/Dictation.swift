import Foundation
#if os(iOS)
import AVFoundation
import Speech
import UIKit
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

    /// A language the device recognizes on the device, by its locale ("fr-FR", "es-CO"):
    /// any it has, not a fixed list, so a device with French dictation dictates in French.
    struct Language: Hashable {
        let id: String

        static let english = Language(id: "en-US")
        static let spanish = Language(id: "es-ES")

        private var code: String { String(id.prefix { $0 != "-" && $0 != "_" }).lowercased() }
        /// The switch's label: "FR".
        var label: String { code.uppercased() }
        /// Its own name, as its speakers write it: "Français", "Español", "English".
        var name: String {
            let own = Locale(identifier: id).localizedString(forLanguageCode: code) ?? code
            return own.prefix(1).uppercased() + own.dropFirst()
        }

        /// The device's languages, one form each, the owner's first: for each of their
        /// preferred languages, the form they use when the device has it, else another of
        /// that language; then the rest, by code. `supported` are the forms the device
        /// recognizes on the device.
        static func ordered(supported: [String], preferred: [String] = Locale.preferredLanguages) -> [Language] {
            let have = supported.map { $0.replacingOccurrences(of: "_", with: "-") }
            var out: [Language] = []
            func add(_ id: String) {
                let l = Language(id: id)
                if !out.contains(where: { $0.code == l.code }) { out.append(l) }
            }
            for p in preferred.map({ $0.replacingOccurrences(of: "_", with: "-") }) {
                let code = Language(id: p).code
                if let exact = have.first(where: { $0.caseInsensitiveCompare(p) == .orderedSame }) {
                    add(exact)
                } else if let same = have.filter({ Language(id: $0).code == code }).sorted().first {
                    add(same)
                }
            }
            for id in have.sorted() { add(id) }
            return out
        }

        /// The owner's own languages among them, theirs first: the keyboards they set up
        /// (where dictation's languages are), then the device's languages (kiff/tafs#236,
        /// item 23). All of the device's when none of theirs is there.
        static func owners(supported: [String], keyboards: [String], preferred: [String] = Locale.preferredLanguages) -> [Language] {
            let theirs = keyboards + preferred
            let codes = Set(theirs.map { Language(id: $0.replacingOccurrences(of: "_", with: "-")).code })
            let all = ordered(supported: supported, preferred: theirs)
            let mine = all.filter { codes.contains($0.code) }
            return mine.isEmpty ? all : mine
        }
    }

    /// The line the system shows with its microphone and speech prompts (Info.plist).
    static let purpose = "tafs turns your voice into text on this device. Your voice isn't sent anywhere."

    @Published private(set) var state: State = .idle
    @Published private(set) var language: Language
    /// How loud the microphone was lately, 0 to 1, oldest first: the listening bar draws them,
    /// so it's plain that dictation is on.
    @Published private(set) var levels: [Float] = []
    static let levelCount = 48

    private let engine: SpeechEngine
    /// The languages this device dictates in, the owner's first.
    var languages: [Language] { engine.languages() }
    /// What the box held when dictation started, and each finished stretch of speech since:
    /// the words being heard go after it.
    private var before = ""
    /// The stretch of speech being heard now, as the recognizer has it so far.
    private var hearing = ""
    private var hints: [String] = []
    /// The box as it was when dictation started: cancelling puts it back.
    private var original = ""
    private var write: ((String) -> Void)?
    /// How many times the recognizer ended by itself with nothing heard since: after a few,
    /// listening stops quietly instead of starting again.
    private var quietEnds = 0

    init(engine: SpeechEngine? = nil, language: Language? = nil) {
        #if os(iOS)
        let engine = engine ?? DeviceSpeech()
        #else
        let engine = engine ?? NoSpeech()
        #endif
        self.engine = engine
        // The language the owner last dictated in, when the device still has it; only the
        // device's recognizer keeps it, never a stand-in.
        #if os(iOS)
        remembers = engine is DeviceSpeech
        #else
        remembers = false
        #endif
        let have = engine.languages()
        let last = remembers ? UserDefaults.standard.string(forKey: Self.lastLanguageKey).flatMap { id in have.first { $0.id == id } } : nil
        self.language = language ?? last ?? have.first ?? .english
    }

    /// Whether the language chosen is kept for next time.
    private let remembers: Bool

    static let lastLanguageKey = "dictation.language"

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
        let have = engine.languages()
        guard let first = have.first else {
            state = .blocked(engine.noLanguage)
            return
        }
        if !have.contains(language) { language = first }
        if let why = engine.unavailable(language) {
            state = .blocked(why)
            return
        }
        before = text
        original = text
        hearing = ""
        quietEnds = 0
        levels = []
        self.hints = Self.cleaned(hints)
        self.write = write
        do {
            try listen()
            state = .listening
        } catch {
            engine.stop()
            state = .blocked("Dictation couldn't start: \(error.localizedDescription)")
        }
    }

    private func listen() throws {
        try engine.start(language: language, hints: hints, onText: { [weak self] heard, done in
            Task { @MainActor in self?.heard(heard, done: done) }
        }, onLevel: { [weak self] level in
            Task { @MainActor in self?.heard(level: level) }
        }, onEnd: { [weak self] why in
            Task { @MainActor in self?.ended(why) }
        })
    }

    /// Stops listening. What was heard stays in the box, unsent.
    func stop() {
        guard listening else { return }
        engine.stop()
        state = .idle
        write = nil
        levels = []
    }

    /// Stops listening and puts the box back as it was before dictating: nothing heard stays.
    func cancel() {
        guard listening else { return }
        let write = write
        stop()
        write?(original)
    }

    private func heard(level: Float) {
        guard state == .listening else { return }
        levels.append(max(0, min(1, level)))
        if levels.count > Self.levelCount { levels.removeFirst(levels.count - Self.levelCount) }
    }

    /// Switches to the device's next language while dictating: listening starts again in it,
    /// after what was already heard.
    func switchLanguage(text: String, hints: [String], write: @escaping (String) -> Void) async {
        let have = engine.languages()
        guard have.count > 1 else { return }
        let next = have[((have.firstIndex(of: language) ?? -1) + 1) % have.count]
        let wasListening = listening
        if wasListening { stop() }
        language = next
        if remembers { UserDefaults.standard.set(next.id, forKey: Self.lastLanguageKey) }
        if wasListening { await start(text: text, hints: hints, write: write) }
    }

    /// The blocked message is read: the mic goes back to waiting.
    func dismiss() {
        if case .blocked = state { state = .idle }
    }

    /// What the recognizer heard of the stretch of speech now. After a pause it starts a new
    /// stretch: what was heard before stays, and the new words go after it, never over it.
    private func heard(_ words: String, done: Bool) {
        guard state == .listening, let write else { return }
        if Self.startsAfresh(hearing, words) { before = Self.joined(before, hearing) }
        hearing = words
        if !words.trimmingCharacters(in: .whitespaces).isEmpty { quietEnds = 0 }
        write(Self.joined(before, hearing))
        if done {
            before = Self.joined(before, hearing)
            hearing = ""
        }
    }

    /// The recognizer ended by itself, after a pause, at its own limit or with an error:
    /// listening goes on, after what was heard, until the owner stops it. After a few ends
    /// with nothing heard it stops: quietly after silence, saying why after an error. It never
    /// stops without a word when something went wrong (#208).
    private func ended(_ why: String?) {
        guard state == .listening else { return }
        before = Self.joined(before, hearing)
        hearing = ""
        engine.stop()
        quietEnds += 1
        if quietEnds <= 2 {
            do {
                try listen()
                return
            } catch {
                engine.stop()
                write = nil
                state = .blocked("Dictation stopped: \(error.localizedDescription) Tap the mic to start again.")
                return
            }
        }
        write = nil
        state = why.map { .blocked($0) } ?? .idle
    }

    /// Whether the recognizer's text starts a new stretch rather than revising the one being
    /// heard: shorter, and not beginning with the same word. A revision ("refund ten" to
    /// "refund 10") keeps its start.
    static func startsAfresh(_ was: String, _ now: String) -> Bool {
        let first = { (s: String) in s.split(separator: " ").first.map { $0.lowercased() } }
        guard let a = first(was) else { return false }
        return now.count < was.count && first(now) != a
    }

    /// How loud a stretch of sound is, 0 to 1: its root mean square in decibels, from a
    /// quiet room (-50 dB) to speech up close (-10 dB).
    nonisolated static func level(_ samples: UnsafeBufferPointer<Float>) -> Float {
        guard !samples.isEmpty else { return 0 }
        var sum: Float = 0
        for x in samples { sum += x * x }
        let db = 20 * log10(max(sqrtf(sum / Float(samples.count)), 1e-7))
        return max(0, min(1, (db + 50) / 40))
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
    /// The languages it recognizes on the device, the owner's first; none means it can't
    /// dictate at all.
    func languages() -> [Dictation.Language]
    /// Why it can't dictate in any language, and how to turn dictation on.
    var noLanguage: String { get }
    /// Whether recognition runs on this device in `language`: nil when it does, else why not.
    func unavailable(_ language: Dictation.Language) -> String?
    /// Starts listening: what it hears so far goes to `onText`, and its end to `onEnd`, with
    /// why it stopped when that wasn't the owner.
    /// `onText`'s flag is true when the stretch of speech it heard is done (a pause). `onLevel`
    /// is how loud the microphone is, 0 to 1, many times a second.
    func start(language: Dictation.Language, hints: [String], onText: @escaping @Sendable (String, Bool) -> Void,
               onLevel: @escaping @Sendable (Float) -> Void, onEnd: @escaping @Sendable (String?) -> Void) throws
    func stop()
}

/// No speech on this platform (the Mac is out of v1's scope).
@MainActor
final class NoSpeech: SpeechEngine {
    func authorize() async -> String? { "Dictation isn't available here." }
    func languages() -> [Dictation.Language] { [] }
    var noLanguage: String { "Dictation isn't available here." }
    func unavailable(_ language: Dictation.Language) -> String? { "Dictation isn't available here." }
    func start(language: Dictation.Language, hints: [String], onText: @escaping @Sendable (String, Bool) -> Void,
               onLevel: @escaping @Sendable (Float) -> Void, onEnd: @escaping @Sendable (String?) -> Void) throws {}
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

    /// The device's on-device languages, read once: asking each recognizer takes a moment.
    private lazy var have: [Dictation.Language] = Dictation.Language.owners(
        supported: SFSpeechRecognizer.supportedLocales().map(\.identifier).filter {
            SFSpeechRecognizer(locale: Locale(identifier: $0))?.supportsOnDeviceRecognition == true
        },
        keyboards: UITextInputMode.activeInputModes.compactMap(\.primaryLanguage).filter { $0 != "emoji" && $0 != "dictation" })

    func languages() -> [Dictation.Language] { have }

    var noLanguage: String {
        "Dictation isn't on this \(Self.device) yet. Add a keyboard with dictation on in Settings › General › Keyboard › Keyboards, wait for its language to download, then try again."
    }

    func unavailable(_ language: Dictation.Language) -> String? {
        guard let recognizer = Self.recognizer(for: language) else { return noLanguage }
        return recognizer.isAvailable ? nil : "Dictation isn't available right now. Try again in a moment."
    }

    func start(language: Dictation.Language, hints: [String], onText: @escaping @Sendable (String, Bool) -> Void,
               onLevel: @escaping @Sendable (Float) -> Void, onEnd: @escaping @Sendable (String?) -> Void) throws {
        guard let recognizer = Self.recognizer(for: language) else {
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
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0), block: Self.feeding(request, onLevel: onLevel))
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

    /// The language's recognizer, when it runs on the device.
    private static func recognizer(for language: Dictation.Language) -> SFSpeechRecognizer? {
        guard let r = SFSpeechRecognizer(locale: Locale(identifier: language.id)), r.supportsOnDeviceRecognition else { return nil }
        return r
    }

    private static var device: String { UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone" }

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

    /// The microphone's sound, to the request, and how loud it is, on the audio thread.
    nonisolated private static func feeding(_ request: SFSpeechAudioBufferRecognitionRequest,
                                            onLevel: @escaping @Sendable (Float) -> Void) -> AVAudioNodeTapBlock {
        nonisolated(unsafe) let request = request
        return { buffer, _ in
            request.append(buffer)
            onLevel(Dictation.level(buffer))
        }
    }

    /// What the recognizer heard so far, and its end, on the recognizer's thread.
    nonisolated private static func results(onText: @escaping @Sendable (String, Bool) -> Void,
                                            onEnd: @escaping @Sendable (String?) -> Void)
        -> @Sendable (SFSpeechRecognitionResult?, (any Error)?) -> Void {
        { result, error in
            if let result {
                // Metadata comes with the last result of a stretch of speech, at a pause:
                // the recognizer's next text starts a new one.
                onText(result.bestTranscription.formattedString, result.isFinal || result.speechRecognitionMetadata != nil)
                if result.isFinal { onEnd(nil) }
            } else if error != nil {
                onEnd("Dictation stopped. Tap the mic to start again.")
            }
        }
    }
}

extension Dictation {
    /// How loud a buffer from the microphone is, from its first channel.
    nonisolated static func level(_ buffer: AVAudioPCMBuffer) -> Float {
        guard let data = buffer.floatChannelData else { return 0 }
        return level(UnsafeBufferPointer(start: data[0], count: Int(buffer.frameLength)))
    }
}

enum DictationError: LocalizedError {
    case unavailable
    var errorDescription: String? { "Speech recognition isn't available for this language." }
}
#endif
