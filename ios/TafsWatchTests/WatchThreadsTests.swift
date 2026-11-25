import XCTest
@testable import TafsWatch

/// The owner's call 1 (docs/watch-app.md, "Kept and pushed"): a thread opens at once on its stored copy. Text only, the last
/// 30 messages of at most 20 threads, locked at rest, gone on sign-out, with no passcode, and after 30 days unused.
@MainActor
final class WatchThreadsTests: XCTestCase {
    private var now = Date(timeIntervalSinceReferenceDate: 813_667_500)
    private var url: URL!

    override func setUp() {
        super.setUp()
        url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("threads")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        super.tearDown()
    }

    private func threads(on: Bool = true, lock: WristLock = Unlocked()) -> WatchThreads {
        WatchThreads(url: url, on: on, lock: lock, clock: { self.now })
    }

    private func line(_ i: Int, mine: Bool = false) -> WatchChatLine {
        WatchChatLine(id: "m\(i)", mine: mine, at: now.addingTimeInterval(Double(i)), text: "Line \(i)", more: false)
    }

    func testOffUntilThePrivacyPageSaysSo() {
        XCTAssertFalse(WatchThreads.on)
        let off = threads(on: false)
        off.keep("coach", [line(1)])
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), "nothing written")
        XCTAssertEqual(off.lines("coach"), [])
    }

    func testAThreadOpensOnItsLastThirtyMessages() {
        let kept = threads()
        kept.keep("taf-1", (0..<40).map { line($0, mine: $0.isMultiple(of: 2)) })
        let lines = kept.lines("taf-1")
        XCTAssertEqual(lines.count, WatchThreads.mostLines)
        XCTAssertEqual(lines.first?.id, "m10")
        XCTAssertEqual(lines.last?.id, "m39", "oldest first, newest at the bottom")
        XCTAssertEqual(kept.lines("coach"), [], "each thread its own")
    }

    /// The Inbox's rule, as Wear OS keeps its threads: no amount at rest.
    func testNoAmountIsKept() {
        let kept = threads()
        kept.keep("coach", [WatchChatLine(id: "m1", mine: false, at: now, text: "Refunded €84 for order A-1039.", more: false)])
        let text = kept.lines("coach").first?.text ?? ""
        XCTAssertFalse(text.contains("84"), text)
        XCTAssertFalse(WatchAmount.hasAmount(text))
    }

    func testAtMostTwentyThreadsTheLeastUsedGo() {
        let kept = threads()
        for i in 0..<(WatchThreads.mostThreads + 2) {
            now.addTimeInterval(1)
            kept.keep("taf-\(i)", [line(i)])
        }
        XCTAssertEqual(kept.lines("taf-0"), [])
        XCTAssertEqual(kept.lines("taf-1"), [])
        XCTAssertEqual(kept.lines("taf-2").count, 1)
    }

    func testAThreadUnusedForThirtyDaysGoes() {
        let kept = threads()
        kept.keep("coach", [line(1)])
        now.addTimeInterval(WatchThreads.unused - 1)
        XCTAssertEqual(kept.lines("coach").count, 1)
        kept.keep("taf-1", [line(2)])
        now.addTimeInterval(2)
        XCTAssertEqual(kept.lines("coach"), [])
        // Gone from the file itself, not only skipped.
        let data = try? Data(contentsOf: url)
        let onDisk = data.flatMap { try? JSONDecoder().decode([WatchThreads.Thread].self, from: $0) } ?? []
        XCTAssertEqual(onDisk.map(\.key), ["taf-1"])
    }

    func testNothingStaysWithoutAPasscode() {
        threads().keep("coach", [line(1)])
        let open = threads(lock: NoPasscode())
        XCTAssertEqual(open.lines("coach"), [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), "the file goes")
        threads().keep("coach", [line(1)])
        threads(lock: NoPasscode()).checkLock()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    func testSignOutClearsEveryThread() {
        let kept = threads()
        kept.keep("coach", [line(1)])
        kept.keep("taf-1", [line(2)])
        kept.clear()
        XCTAssertEqual(kept.lines("coach"), [])
        XCTAssertEqual(kept.lines("taf-1"), [])
    }

    /// Coach's thread: what was said and answered on this visit is kept as text, and the next open shows it at once.
    func testAConversationIsKeptAndReopened() async {
        let kept = threads()
        let answer = WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false)
        let phone = TwoStep(answer)
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), pause: {}, linkPause: { _ in }, threads: kept)
        model.review("Pause Rae")
        await model.sendReviewed()
        model.leave()
        XCTAssertEqual(kept.lines("coach").map(\.text), ["Pause Rae", "Rae is paused."])
        XCTAssertEqual(kept.lines("coach").map(\.mine), [true, false])
        let again = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), threads: kept)
        XCTAssertEqual(again.history.map(\.text), ["Pause Rae", "Rae is paused."], "opens on the stored copy, before any read")
    }

    private final class TwoStep: PhoneAsking {
        let answer: WatchCoachAnswer
        init(_ answer: WatchCoachAnswer) { self.answer = answer }
        func ask(_ request: WatchRequest) async -> WatchReply? {
            switch request.kind {
            case .coachSend: return WatchReply(.ok, ticket: WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "ask_1", sent: nil))
            case .coachRead: return WatchReply(.ok, coach: answer, answering: false)
            default: return WatchReply(.ok)
            }
        }
    }
}
