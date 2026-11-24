import Foundation
import Speech

/// The phone's half of the watch's own recording (row 44, A; `WatchVoice`, behind its switch; as Wear OS's `VoiceInbox` and
/// `OnDeviceTranscriber`, tafs#1596). The file arrives over WatchConnectivity and is moved here at once; `transcribe` turns it
/// into text with the phone's **on-device** recognizer only, and only the text goes back. **Deleted:** as soon as it's read,
/// whatever the outcome, and otherwise `WatchVoice.keepFor` after it arrived, even if no request ever comes; anything left
/// from before is swept when the phone's link starts. Nothing of it goes to a server.
actor WatchVoiceInbox {
    static let shared = WatchVoiceInbox()

    /// How long `transcribe` waits for a file that hasn't arrived yet: it's sent just before its request.
    static let arrival: TimeInterval = 20

    nonisolated let folder: URL
    private let transcriber: WatchTranscribing
    private let on: Bool
    private let keepFor: TimeInterval
    private let pause: @Sendable (TimeInterval) async -> Void
    private var arrived: [String: URL] = [:]
    private var waiting: [String: [CheckedContinuation<URL?, Never>]] = [:]

    init(folder: URL? = nil, transcriber: WatchTranscribing = OnDeviceWatchTranscriber(), on: Bool = WatchVoice.on,
         keepFor: TimeInterval = WatchVoice.keepFor,
         pause: @escaping @Sendable (TimeInterval) async -> Void = { try? await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }) {
        self.folder = folder ?? FileManager.default.temporaryDirectory.appendingPathComponent("watch-voice", isDirectory: true)
        self.transcriber = transcriber
        self.on = on
        self.keepFor = keepFor
        self.pause = pause
    }

    /// WatchConnectivity's file, moved here before its delegate method returns (it deletes it after). Nil, and nothing kept,
    /// for a file that isn't a recording or with the switch off.
    nonisolated func place(_ file: URL, metadata: [String: Any]?) -> String? {
        guard on, let id = (metadata?[WatchVoice.metadataKey] as? String)?.lowercased(), WatchVoice.isID(id) else { return nil }
        let kept = folder.appendingPathComponent(id)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: kept)
        do {
            try FileManager.default.moveItem(at: file, to: kept)
            // Readable while the iPhone is locked, as it usually is in a pocket while the watch is talked to; it lives for
            // seconds, and goes once read.
            try? (kept as NSURL).setResourceValue(URLFileProtection.completeUntilFirstUserAuthentication, forKey: .fileProtectionKey)
            return id
        } catch {
            return nil
        }
    }

    /// The file is here: whoever waits on it takes it, and it goes after `keepFor` whatever happens.
    func arrived(_ id: String) {
        let url = folder.appendingPathComponent(id)
        arrived[id] = url
        resume(id, with: url)
        let keepFor = keepFor, pause = pause
        Task { [weak self] in
            await pause(keepFor)
            await self?.erase(id)
        }
    }

    /// The recording's text, by its id: the phone's on-device recognizer, then the file is erased.
    func transcribe(_ id: String) async -> WatchReply {
        // Only a recording's id names a file here: nothing else can reach outside the folder.
        guard WatchVoice.isID(id) else { return WatchReply(.refused) }
        guard on else { return WatchReply(.notYet) }
        guard let url = await file(id) else { return WatchReply(.failed, line: Words.Watch.recordingNotReceived) }
        let reply = await transcriber.transcribe(url)
        erase(id)
        return reply
    }

    func erase(_ id: String) {
        guard WatchVoice.isID(id) else { return }
        arrived[id] = nil
        resume(id, with: nil)
        try? FileManager.default.removeItem(at: folder.appendingPathComponent(id))
    }

    /// Anything a process that ended left behind.
    nonisolated func sweep() {
        try? FileManager.default.removeItem(at: folder)
    }

    private func file(_ id: String) async -> URL? {
        if let url = arrived[id] { return url }
        let pause = pause
        Task { [weak self] in
            await pause(Self.arrival)
            await self?.resume(id, with: nil)
        }
        return await withCheckedContinuation { waiting[id, default: []].append($0) }
    }

    private func resume(_ id: String, with url: URL?) {
        let waiters = waiting.removeValue(forKey: id) ?? []
        waiters.forEach { $0.resume(returning: url) }
    }
}

/// Turns a recording into text on the phone.
protocol WatchTranscribing: Sendable {
    func transcribe(_ url: URL) async -> WatchReply
}

/// The phone's own recognizer, **on-device only** (`requiresOnDeviceRecognition`), in the language the person dictates in
/// on the phone, else the app's: with no on-device model for it, it says so, and nothing goes to a server.
struct OnDeviceWatchTranscriber: WatchTranscribing {
    func transcribe(_ url: URL) async -> WatchReply {
        // iOS asks only while tafs is open on the phone: never allowed, or not asked yet, the watch says how.
        guard SFSpeechRecognizer.authorizationStatus() == .authorized else {
            return WatchReply(.failed, line: Words.Voice.speechRecognitionOffTafsTurn)
        }
        let last = UserDefaults.standard.string(forKey: Dictation.lastLanguageKey)
        let recognizer = last.flatMap { SFSpeechRecognizer(locale: Locale(identifier: $0)) }
            ?? SFSpeechRecognizer(locale: Locale(identifier: KIFFClient.appLanguage))
        guard let recognizer, recognizer.supportsOnDeviceRecognition else {
            return WatchReply(.failed, line: Words.Watch.noOfflineSpeech)
        }
        let request = SFSpeechURLRecognitionRequest(url: url)
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = false
        var task: SFSpeechRecognitionTask?
        let heard = await Self.firstText(within: Self.limit) { answer in
            task = recognizer.recognitionTask(with: request) { result, error in
                if let result, result.isFinal { answer(result.bestTranscription.formattedString) }
                if error != nil { answer(nil) }
            }
        }
        // No answer within the limit: the task goes too. The recognizer and its task live until then.
        if heard == nil { task?.cancel() }
        withExtendedLifetime((recognizer, task)) {}
        guard let text = heard?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
            return WatchReply(.failed, line: Words.Watch.didntCatch)
        }
        return WatchReply(.ok, text: text)
    }

    /// The most the recognizer is given for 30 s of speech: past it, nothing was heard, and the task is cancelled, so the
    /// phone never holds a task the watch has stopped waiting for.
    static let limit: TimeInterval = 20

    /// The first of the recognizer's final text, its error (nil) and the limit (nil), taken once.
    static func firstText(within limit: TimeInterval, _ start: (@escaping @Sendable (String?) -> Void) -> Void) async -> String? {
        await withCheckedContinuation { done in
            let once = OnceText(done)
            start { once.resume($0) }
            DispatchQueue.global().asyncAfter(deadline: .now() + limit) { once.resume(nil) }
        }
    }

    /// The recognizer's first final result or error, taken once.
    private final class OnceText: @unchecked Sendable {
        private let lock = NSLock()
        private var done: CheckedContinuation<String?, Never>?
        init(_ done: CheckedContinuation<String?, Never>) { self.done = done }
        func resume(_ text: String?) {
            lock.lock()
            let waiting = done
            done = nil
            lock.unlock()
            waiting?.resume(returning: text)
        }
    }
}
