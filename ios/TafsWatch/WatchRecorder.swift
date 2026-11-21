import AVFoundation
import SwiftUI

/// Not cut off by a pause (row 44, A; as Wear OS's tafs#1587 and #1596), behind `WatchVoice.on` (off): the watch records
/// itself, since the system's dictation ends on its own pause rule and hands back text, never the sound. The recording ends
/// on ✓, at `WatchVoice.limit`, or after about `WatchVoice.quietEnds` with nothing said. Then it goes to the paired iPhone,
/// which turns it into text with its on-device recognizer only, and the text goes to Check before sending: nothing is sent
/// before Send. **Deleted on the watch** once the text is back, on Cancel, and when the screen closes; anything an ended
/// app left is swept when the app starts and when recording starts (`sweepLeftovers`).
@MainActor
final class VoiceModel: ObservableObject {
    enum State: Equatable {
        /// Asking for the microphone, the first time.
        case ready
        case recording
        /// "Turning it into text on your phone…", with Cancel.
        case transcribing
        case failed(String)
        /// The text is back and handed on; the screen closes.
        case heard
    }

    @Published private(set) var state: State = .ready
    @Published private(set) var elapsed: TimeInterval = 0

    private let mic: Microphone
    private let phone: VoiceSending
    private let folder: URL
    private let tick: TimeInterval
    private let pause: (TimeInterval) async -> Void
    private let onWords: (String) -> Void
    private var rule = QuietRule()
    private var id: String?
    private var stopping = false

    init(mic: Microphone = AudioRecorderMic(), phone: VoiceSending = PhoneLink.shared,
         folder: URL = FileManager.default.temporaryDirectory.appendingPathComponent("voice", isDirectory: true),
         tick: TimeInterval = 0.25,
         pause: @escaping (TimeInterval) async -> Void = { try? await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) },
         onWords: @escaping (String) -> Void) {
        self.mic = mic
        self.phone = phone
        self.folder = folder
        self.tick = tick
        self.pause = pause
        self.onWords = onWords
    }

    private var file: URL? { id.map { folder.appendingPathComponent("\($0).m4a") } }

    /// Starts listening at once, once the microphone is allowed; never behind the system's dialog.
    func start() async {
        guard state != .recording, state != .transcribing else { return }
        sweep()
        state = .ready
        guard await mic.allowed() else { state = .failed(Words.Watch.micNeeded); return }
        let id = UUID().uuidString.lowercased()
        self.id = id
        rule = QuietRule()
        elapsed = 0
        stopping = false
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try mic.start(folder.appendingPathComponent("\(id).m4a"))
        } catch {
            state = .failed(Words.Watch.micNeeded)
            return
        }
        state = .recording
        while state == .recording, !stopping {
            await pause(tick)
            guard state == .recording, !stopping else { break }
            elapsed += tick
            if rule.ends(level: mic.level(), at: elapsed) { break }
        }
        if state == .recording { await finish() }
    }

    /// ✓, the limit, or the quiet: the recording goes to the phone, and its text comes back.
    func finish() async {
        guard state == .recording, let id, let file else { return }
        stopping = true
        mic.stop()
        state = .transcribing
        let reply = await phone.transcribe(file, id: id)
        // Cancelled while the phone was at it: nothing more.
        guard state == .transcribing, self.id == id else { return }
        erase()
        switch reply?.outcome {
        case .ok?:
            guard let text = reply?.text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
                state = .failed(Words.Watch.didntCatch)
                return
            }
            state = .heard
            onWords(text)
        case .failed?:
            state = .failed(reply?.line ?? Words.Watch.didntCatch)
        case .signedOut?:
            state = .failed(Words.Watch.signInOnPhone)
        case .notYet?, .refused?:
            // A phone from before the recording: Type stays offered.
            state = .failed(Words.Watch.updatePhone)
        case .stale?, .answered?, nil:
            state = .failed(Words.Watch.phoneNotReachable)
        }
    }

    /// Cancel, or the screen closed: the microphone stops, a transfer still on its way is called off so the phone never
    /// gets a recording thrown away, and the file goes.
    func cancel() {
        stopping = true
        if state == .recording { mic.stop() }
        if let id { phone.cancelTranscribe(id) }
        erase()
        if state != .heard { state = .ready }
    }

    private func erase() {
        if let file { try? FileManager.default.removeItem(at: file) }
        id = nil
    }

    /// Anything a closed screen or an ended process left.
    private func sweep() {
        try? FileManager.default.removeItem(at: folder)
    }

    /// At the app's start: a recording an app ended mid-transfer left behind goes.
    nonisolated static func sweepLeftovers(
        folder: URL = FileManager.default.temporaryDirectory.appendingPathComponent("voice", isDirectory: true)) {
        try? FileManager.default.removeItem(at: folder)
    }
}

/// When a recording ends by itself: the limit, or about 8 s with nothing said, counted from the start or the last word.
struct QuietRule {
    /// Average power above this is someone speaking, in dBFS.
    static let speech: Float = -40

    private(set) var lastSpoke: TimeInterval = 0

    mutating func ends(level: Float, at elapsed: TimeInterval) -> Bool {
        if level > Self.speech { lastSpoke = elapsed }
        return elapsed >= WatchVoice.limit || elapsed - lastSpoke >= WatchVoice.quietEnds
    }
}

/// The watch's microphone.
@MainActor
protocol Microphone {
    func allowed() async -> Bool
    func start(_ file: URL) throws
    /// The level now, in dBFS.
    func level() -> Float
    func stop()
}

/// Sends a recording to the phone and asks for its text.
@MainActor
protocol VoiceSending {
    func transcribe(_ file: URL, id: String) async -> WatchReply?
    func cancelTranscribe(_ id: String)
}

/// `AVAudioRecorder`: AAC, 16 kHz, mono, which the phone's recognizer reads, small enough to cross the link quickly.
@MainActor
final class AudioRecorderMic: Microphone {
    private var recorder: AVAudioRecorder?

    func allowed() async -> Bool { await AVAudioApplication.requestRecordPermission() }

    func start(_ file: URL) throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .default)
        try session.setActive(true)
        let recorder = try AVAudioRecorder(url: file, settings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
        ])
        recorder.isMeteringEnabled = true
        guard recorder.record(forDuration: WatchVoice.limit + 1) else { throw CocoaError(.fileWriteUnknown) }
        self.recorder = recorder
    }

    func level() -> Float {
        guard let recorder else { return -160 }
        recorder.updateMeters()
        return recorder.averagePower(forChannel: 0)
    }

    func stop() {
        recorder?.stop()
        recorder = nil
        try? AVAudioSession.sharedInstance().setActive(false)
    }
}

/// Recording, as Wear OS's: "● Recording", the time against 0:30, and ✓ to end it; then "Turning it into text on your
/// phone…" with Cancel. A failure says why, with Try again.
struct VoiceRecordScreen: View {
    @StateObject var model: VoiceModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 8) {
            switch model.state {
            case .ready, .heard:
                ProgressView()
            case .recording:
                Label(Words.Watch.recording, systemImage: "circle.fill")
                    .font(.footnote)
                    .foregroundStyle(WatchLook.danger)
                Text(Duration.seconds(model.elapsed), format: .time(pattern: .minuteSecond))
                    .font(.title3.monospacedDigit())
                Button { Task { await model.finish() } } label: {
                    Label(Words.Common.done, systemImage: "checkmark").labelStyle(.iconOnly)
                }
                .tint(WatchLook.send)
            case .transcribing:
                ProgressView()
                Text(Words.Watch.transcribing)
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                Button(Words.Common.cancel) { model.cancel(); dismiss() }
            case .failed(let why):
                Text(why)
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                Button(Words.Common.tryAgain) { Task { await model.start() } }
            }
        }
        .task { await model.start() }
        .onChange(of: model.state) { _, state in
            if state == .heard { dismiss() }
        }
        .onDisappear { model.cancel() }
    }
}
