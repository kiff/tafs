import XCTest
@testable import Tafs

/// The phone's half of the watch's recording (row 44, A; `WatchVoiceInbox`): only a recording is taken, its text comes
/// from the on-device recognizer, and it's deleted once read, or after `keepFor` whatever happens.
final class WatchVoiceInboxTests: XCTestCase {
    private var folder: URL!
    private var source: URL!

    private struct Transcriber: WatchTranscribing {
        let reply: WatchReply
        func transcribe(_ url: URL) async -> WatchReply {
            XCTAssertTrue(FileManager.default.fileExists(atPath: url.path), "the file is there while it's read")
            return reply
        }
    }

    override func setUp() {
        super.setUp()
        let base = FileManager.default.temporaryDirectory.appendingPathComponent("voice-test-\(UUID().uuidString)")
        folder = base.appendingPathComponent("inbox")
        source = base.appendingPathComponent("from-watch.m4a")
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        try? Data("audio".utf8).write(to: source)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: folder.deletingLastPathComponent())
        super.tearDown()
    }

    private let id = "0f8b6c1e-4c1a-4f8e-9a51-2a3d4e5f6a7b"

    private func inbox(_ reply: WatchReply = WatchReply(.ok, text: "Chase the overdue invoices"), on: Bool = true,
                       pause: @escaping @Sendable (TimeInterval) async -> Void = { _ in try? await Task.sleep(nanoseconds: 50_000_000_000) })
        -> WatchVoiceInbox {
        WatchVoiceInbox(folder: folder, transcriber: Transcriber(reply: reply), on: on, pause: pause)
    }

    func testTheTextComesBackAndTheFileGoes() async {
        let inbox = inbox()
        XCTAssertEqual(inbox.place(source, metadata: [WatchVoice.metadataKey: id]), id)
        await inbox.arrived(id)
        let reply = await inbox.transcribe(id)
        XCTAssertEqual(reply, WatchReply(.ok, text: "Chase the overdue invoices"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.appendingPathComponent(id).path))
    }

    /// The request can come before the file: it waits for it.
    func testTheRequestWaitsForItsFile() async {
        let inbox = inbox()
        let asked = Task { await inbox.transcribe(id) }
        try? await Task.sleep(nanoseconds: 50_000_000)
        _ = inbox.place(source, metadata: [WatchVoice.metadataKey: id])
        await inbox.arrived(id)
        let reply = await asked.value
        XCTAssertEqual(reply.outcome, .ok)
    }

    /// A failure is the phone's words, and the file goes all the same.
    func testAFailureStillDeletesTheFile() async {
        let inbox = inbox(WatchReply(.failed, line: Words.Watch.noOfflineSpeech))
        _ = inbox.place(source, metadata: [WatchVoice.metadataKey: id])
        await inbox.arrived(id)
        let reply = await inbox.transcribe(id)
        XCTAssertEqual(reply, WatchReply(.failed, line: Words.Watch.noOfflineSpeech))
        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.appendingPathComponent(id).path))
    }

    /// No request ever comes: the file goes after `keepFor` on its own.
    func testAnUnaskedFileGoesOnItsOwn() async {
        let inbox = inbox(pause: { _ in })
        _ = inbox.place(source, metadata: [WatchVoice.metadataKey: id])
        await inbox.arrived(id)
        for _ in 0..<100 where FileManager.default.fileExists(atPath: folder.appendingPathComponent(id).path) {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.appendingPathComponent(id).path))
    }

    /// A file that never arrives: the watch is told, not left waiting.
    func testAFileThatNeverArrivesIsSaid() async {
        let reply = await inbox(pause: { _ in }).transcribe(id)
        XCTAssertEqual(reply, WatchReply(.failed, line: Words.Watch.recordingNotReceived))
    }

    /// Only a recording, by a UUID id, is taken: no metadata, another key, or a path is nothing kept.
    func testOnlyARecordingIsTaken() {
        let inbox = inbox()
        XCTAssertNil(inbox.place(source, metadata: nil))
        XCTAssertNil(inbox.place(source, metadata: ["other": id]))
        XCTAssertNil(inbox.place(source, metadata: [WatchVoice.metadataKey: "../../Documents/x"]))
        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.path))
    }

    /// Switched off: nothing is taken, and the watch keeps the system's dictation.
    func testOffTakesNothing() async {
        let inbox = inbox(on: false)
        XCTAssertNil(inbox.place(source, metadata: [WatchVoice.metadataKey: id]))
        let reply = await inbox.transcribe(id)
        XCTAssertEqual(reply.outcome, .notYet)
        XCTAssertFalse(WatchVoice.on)
    }

    /// The relay: a reader without it is "not yet", and an id that isn't a recording's is refused before any read.
    @MainActor
    func testTheRelayTakesOnlyARecordingsID() async {
        struct Reader: WatchReading {
            var signedIn: Bool { true }
            func needsYou(now: Date) async throws -> WatchNeedsYou { WatchNeedsYou(count: 0, calls: [], at: now) }
            func tafs() async throws -> [WatchTaf] { [] }
            func refuse(hold: String, now: Date) async -> NotificationRefuse.Outcome { .gone }
            func halt(taf: String?) async -> HaltAll.Outcome { .done("") }
            func coach(text: String, messageID: UUID) async -> WatchReply { WatchReply(.ok) }
            func tafPage(_ id: String) async throws -> WatchTafPage? { nil }
            func calendar(day: String?) async throws -> WatchCalendarDay { throw URLError(.badURL) }
            func account() async throws -> WatchAccount { throw URLError(.badURL) }
        }
        let now = Date()
        let good = await WatchRelay.answer(WatchMessage.encode(WatchRequest(.transcribe, made: now, message: id)), now: now, reading: Reader())
        XCTAssertEqual(good.outcome, .notYet)
        let bad = await WatchRelay.answer(WatchMessage.encode(WatchRequest(.transcribe, made: now, message: "x/../y")), now: now, reading: Reader())
        XCTAssertEqual(bad.outcome, .refused)
    }
}
