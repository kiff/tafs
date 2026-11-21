import XCTest
@testable import TafsWatch

/// Not cut off by a pause (row 44, A; `VoiceModel`, behind `WatchVoice.on`): the watch records until ✓, the limit, or about
/// 8 s with nothing said; the phone makes the text; the words go to Check before sending; and the file never stays.
@MainActor
final class VoiceModelTests: XCTestCase {
    private var folder: URL!

    private final class Mic: Microphone {
        var allow = true
        var levels: [Float]
        var started: URL?
        var stopped = 0
        init(levels: [Float]) { self.levels = levels }
        func allowed() async -> Bool { allow }
        func start(_ file: URL) throws {
            started = file
            try Data("audio".utf8).write(to: file)
        }
        func level() -> Float { levels.count > 1 ? levels.removeFirst() : (levels.first ?? -160) }
        func stop() { stopped += 1 }
    }

    private final class Phone: VoiceSending {
        var reply: WatchReply?
        var sent: [(URL, String)] = []
        var cancelled: [String] = []
        var fileWasThere = false
        init(_ reply: WatchReply?) { self.reply = reply }
        func transcribe(_ file: URL, id: String) async -> WatchReply? {
            sent.append((file, id))
            fileWasThere = FileManager.default.fileExists(atPath: file.path)
            return reply
        }
        func cancelTranscribe(_ id: String) { cancelled.append(id) }
    }

    override func setUp() {
        super.setUp()
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("voice-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: folder)
        super.tearDown()
    }

    private func model(_ mic: Mic, _ phone: Phone, heard: @escaping (String) -> Void = { _ in }) -> VoiceModel {
        VoiceModel(mic: mic, phone: phone, folder: folder, tick: 1, pause: { _ in }, onWords: heard)
    }

    private func files() -> [String] { (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? [] }

    /// A pause doesn't end it: speech, 5 s of quiet, speech again, then 8 s of quiet ends it, and the words come back.
    func testAPauseDoesntEndItEightSecondsOfQuietDoes() async {
        let speaking: Float = -20, quiet: Float = -60
        let mic = Mic(levels: [speaking, speaking] + Array(repeating: quiet, count: 5) + [speaking] + Array(repeating: quiet, count: 20))
        let phone = Phone(WatchReply(.ok, text: " Refund order 1042 "))
        var words: [String] = []
        let model = model(mic, phone) { words.append($0) }
        await model.start()
        XCTAssertEqual(model.elapsed, 16, "the last word at 8 s, then 8 s of quiet")
        XCTAssertEqual(mic.stopped, 1)
        XCTAssertEqual(words, ["Refund order 1042"])
        XCTAssertEqual(model.state, .heard)
        XCTAssertTrue(phone.fileWasThere)
        XCTAssertEqual(files(), [], "the watch's copy goes once the text is back")
    }

    func testTheLimitEndsIt() async {
        let mic = Mic(levels: [-10])
        let model = model(mic, Phone(WatchReply(.ok, text: "a long one")))
        await model.start()
        XCTAssertEqual(model.elapsed, WatchVoice.limit)
    }

    func testTheQuietRule() {
        var rule = QuietRule()
        XCTAssertFalse(rule.ends(level: -60, at: 7))
        XCTAssertTrue(rule.ends(level: -60, at: 8), "nothing said in the first 8 s ends it")
        rule = QuietRule()
        XCTAssertFalse(rule.ends(level: -30, at: 6))
        XCTAssertFalse(rule.ends(level: -60, at: 13))
        XCTAssertTrue(rule.ends(level: -60, at: 14))
        var talking = QuietRule()
        XCTAssertTrue(talking.ends(level: -10, at: WatchVoice.limit), "the limit ends it, even mid-word")
    }

    /// The microphone not allowed: nothing recorded or sent, and the watch says how.
    func testNoMicrophoneRecordsNothing() async {
        let mic = Mic(levels: [-20])
        mic.allow = false
        let phone = Phone(nil)
        let model = model(mic, phone)
        await model.start()
        XCTAssertEqual(model.state, .failed(Words.Watch.micNeeded))
        XCTAssertNil(mic.started)
        XCTAssertTrue(phone.sent.isEmpty)
    }

    /// The phone's refusal is its words; a phone from before is "update tafs on your phone"; no answer, not in reach.
    /// The file goes each time.
    func testEachFailureSaysWhyAndTheFileGoes() async {
        for (reply, line) in [(WatchReply(.failed, line: Words.Watch.noOfflineSpeech), Words.Watch.noOfflineSpeech),
                              (WatchReply(.notYet), Words.Watch.updatePhone),
                              (WatchReply(.ok, text: "  "), Words.Watch.didntCatch),
                              (nil, Words.Watch.phoneNotReachable)] {
            let model = model(Mic(levels: [-60]), Phone(reply))
            await model.start()
            XCTAssertEqual(model.state, .failed(line))
            XCTAssertEqual(files(), [])
        }
    }

    /// Cancel while the phone is at it: the transfer is called off, the file goes, and a late answer changes nothing.
    func testCancelCallsTheTransferOffAndDropsALateAnswer() async {
        final class HeldPhone: VoiceSending {
            var held: CheckedContinuation<WatchReply?, Never>?
            var cancelled: [String] = []
            func transcribe(_ file: URL, id: String) async -> WatchReply? { await withCheckedContinuation { held = $0 } }
            func cancelTranscribe(_ id: String) { cancelled.append(id) }
        }
        let phone = HeldPhone()
        var words: [String] = []
        let model = VoiceModel(mic: Mic(levels: [-60]), phone: phone, folder: folder, tick: 1, pause: { _ in }) { words.append($0) }
        let started = Task { await model.start() }
        while phone.held == nil { await Task.yield() }
        XCTAssertEqual(model.state, .transcribing)
        model.cancel()
        XCTAssertEqual(phone.cancelled.count, 1)
        XCTAssertEqual(files(), [])
        phone.held?.resume(returning: WatchReply(.ok, text: "too late"))
        await started.value
        XCTAssertEqual(words, [])
        XCTAssertEqual(model.state, .ready)
    }
}
