import XCTest
@testable import Tafs

/// Row 11 on Apple Watch (docs/watch-app.md, "An answer you can't miss, with the app put away"): tafs cloud's push for a
/// watch request posts its answer once, as W3 does, only for a request this iPhone sent, never one the watch showed.
@MainActor
final class WatchAskPushTests: XCTestCase {
    private let suite = "WatchAskPushTests"
    private var pushes: WatchAskPushes!
    private let done = WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false)
    private var t = Date(timeIntervalSinceReferenceDate: 813_667_500)

    override func setUp() {
        super.setUp()
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        pushes = WatchAskPushes(defaults: defaults)
    }

    override func tearDown() {
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    private func push(_ id: String, reply: WatchReply, phoneInUse: Bool = false) async -> (posted: [WatchCoachAnswer], reads: Int) {
        var posted: [WatchCoachAnswer] = []
        var reads = 0
        await WatchAskPush.handle(id, pushes: pushes, read: { _ in reads += 1; return reply }, now: { self.t },
                                  phoneInUse: { phoneInUse }, post: { answer, ticket in
                                      XCTAssertEqual(ticket.askID, id)
                                      posted.append(answer)
                                  })
        return (posted, reads)
    }

    func testAnEndedRequestThisIPhoneSentIsPostedOnce() async {
        pushes.sent("ask_1", at: t)
        let first = await push("ask_1", reply: WatchReply(.ok, coach: done, answering: false))
        XCTAssertEqual(first.posted, [done])
        // The same request pushed again (another change, or W3's follow after it): nothing more.
        let again = await push("ask_1", reply: WatchReply(.ok, coach: done, answering: false))
        XCTAssertTrue(again.posted.isEmpty)
        XCTAssertEqual(again.reads, 0, "not even read")
        XCTAssertFalse(pushes.claimPost("ask_1", now: t), "W3's follow posts nothing after it")
    }

    func testARequestStillAtWorkWaitsForTheNextPush() async {
        pushes.sent("ask_1", at: t)
        let working = await push("ask_1", reply: WatchReply(.ok, answering: true))
        XCTAssertTrue(working.posted.isEmpty)
        let failed = await push("ask_1", reply: WatchReply(.failed, line: "tafs couldn't reach KIFF."))
        XCTAssertTrue(failed.posted.isEmpty)
        let ended = await push("ask_1", reply: WatchReply(.ok, coach: done, answering: false))
        XCTAssertEqual(ended.posted, [done])
    }

    /// Another phone's request (an Android's watch, or an iPad), or one past the day: never read, never posted.
    func testARequestThisIPhoneDidntSendIsNeverRead() async {
        let other = await push("ask_other", reply: WatchReply(.ok, coach: done, answering: false))
        XCTAssertTrue(other.posted.isEmpty)
        XCTAssertEqual(other.reads, 0)
        pushes.sent("ask_old", at: t)
        t.addTimeInterval(WatchAskPushes.kept + 1)
        let old = await push("ask_old", reply: WatchReply(.ok, coach: done, answering: false))
        XCTAssertEqual(old.reads, 0)
    }

    func testAnAnswerTheWatchReadIsNeverPosted() async {
        pushes.sent("ask_1", at: t)
        pushes.seen("ask_1", now: t)
        let pushed = await push("ask_1", reply: WatchReply(.ok, coach: done, answering: false))
        XCTAssertTrue(pushed.posted.isEmpty)
        XCTAssertEqual(pushed.reads, 0)
    }

    /// Its own id: what the watch read is kept in `CoachFollow` for the run of the tests.
    func testAWatchStillReadingOrTafsOpenGetsNoBanner() async {
        pushes.sent("ask_reading", at: t)
        let ticket = WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "ask_reading", sent: nil)
        CoachFollow.watchReading(ticket, at: t.addingTimeInterval(-2))
        let reading = await push("ask_reading", reply: WatchReply(.ok, coach: done, answering: false))
        XCTAssertTrue(reading.posted.isEmpty, "the watch shows it itself")
        t.addTimeInterval(CoachFollow.watchAway + 1)
        let open = await push("ask_reading", reply: WatchReply(.ok, coach: done, answering: false), phoneInUse: true)
        XCTAssertTrue(open.posted.isEmpty, "Coach's chat on the iPhone shows it")
        let away = await push("ask_reading", reply: WatchReply(.ok, coach: done, answering: false))
        XCTAssertEqual(away.posted, [done], "put away, iPhone not in use: posted")
    }

    /// A held call: the request stays at work with its line, and is posted with "Approve on your phone".
    func testAHeldCallIsPosted() async {
        pushes.sent("ask_1", at: t)
        let held = KeptWatchReading.reply(TafsCloud.WatchAsk(id: "ask_1", state: "working", line: "Refund order 4417 waits for you."))
        XCTAssertFalse(held.answering ?? true)
        let pushed = await push("ask_1", reply: held)
        XCTAssertEqual(pushed.posted.first?.approveOnPhone, true)
    }

    /// W3's follow and a request it didn't keep (from before this build) post as before.
    func testARequestNotKeptIsW3sAlone() {
        XCTAssertTrue(pushes.claimPost("ask_unknown", now: t))
        XCTAssertTrue(pushes.claimPost("ask_unknown", now: t), "nothing kept for it: W3 decides, as before")
    }

    func testOnlyIdsAreKeptForADayAtMostFifty() {
        for i in 0..<(WatchAskPushes.most + 10) { pushes.sent("ask_\(i)", at: t.addingTimeInterval(Double(i))) }
        let defaults = UserDefaults(suiteName: suite)!
        let kept = try! JSONDecoder().decode([String: WatchAskPushes.Entry].self, from: defaults.data(forKey: WatchAskPushes.key)!)
        XCTAssertEqual(kept.count, WatchAskPushes.most)
        XCTAssertNil(kept["ask_0"], "the oldest go first")
        XCTAssertNotNil(kept["ask_59"])
    }
}
