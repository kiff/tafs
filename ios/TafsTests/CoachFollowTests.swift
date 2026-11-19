import XCTest
@testable import Tafs

/// W3 on Apple Watch (docs/watch-app.md, "W3"): once the watch is put away, the iPhone posts Coach's answer; never when the
/// watch read it, or is still reading.
@MainActor
final class CoachFollowTests: XCTestCase {
    private let ticket = WatchCoachTicket(chat: "taf-setup", session: "s1", sent: "m1")
    private let rae = WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false)
    private var t = Date(timeIntervalSinceReferenceDate: 813_667_500)

    /// Coach's thread, read in turn: the last again once they run out.
    private final class Thread {
        var replies: [WatchReply]
        var reads = 0
        init(_ replies: [WatchReply]) { self.replies = replies }
        func read() -> WatchReply {
            reads += 1
            return replies.count > 1 ? replies.removeFirst() : replies[0]
        }
    }

    private func follow(_ thread: Thread, eachWait: @escaping () -> Void = {}) async -> (posted: [WatchCoachAnswer], did: Bool) {
        var posted: [WatchCoachAnswer] = []
        let did = await CoachFollow.follow(ticket, read: { _ in thread.read() },
                                           wait: { self.t.addTimeInterval(1.5); eachWait() }, now: { self.t },
                                           post: { posted.append($0) })
        return (posted, did)
    }

    func testAWatchPutAwayGetsCoachsAnswerFromTheIPhone() async {
        let thread = Thread([WatchReply(.ok, answering: true), WatchReply(.ok, answering: true),
                             WatchReply(.ok, coach: rae, answering: false)])
        let (posted, did) = await follow(thread)
        XCTAssertTrue(did)
        XCTAssertEqual(posted, [rae])
        XCTAssertEqual(thread.reads, 3, "no read after the answer")
        XCTAssertTrue(CoachFollow.watchLastRead.isEmpty, "nothing kept once it's done")
    }

    func testAWatchStillReadingGetsNoNotification() async {
        let thread = Thread([WatchReply(.ok, answering: true), WatchReply(.ok, coach: rae, answering: false)])
        let (posted, did) = await follow(thread) { CoachFollow.watchReading(self.ticket, at: self.t) }
        XCTAssertFalse(did)
        XCTAssertTrue(posted.isEmpty, "the watch shows the answer itself")
    }

    func testAnAnswerTheWatchReadItselfIsNeverPosted() async {
        let thread = Thread([WatchReply(.ok, answering: true)])
        var waits = 0
        let (posted, did) = await follow(thread) {
            waits += 1
            if waits == 2 { CoachFollow.watchHas(self.ticket) }
        }
        XCTAssertFalse(did)
        XCTAssertTrue(posted.isEmpty)
        XCTAssertEqual(thread.reads, 1, "it stops reading at once")
        XCTAssertTrue(CoachFollow.watchHad.isEmpty)
    }

    /// Past the follow, nothing is posted: the answer is in Coach's chat. A read that fails is read again.
    func testNoAnswerWithinTheFollowPostsNothing() async {
        let thread = Thread([WatchReply(.failed, line: "tafs couldn't reach KIFF."), WatchReply(.ok, coach: rae, answering: true)])
        let (posted, did) = await follow(thread)
        XCTAssertFalse(did)
        XCTAssertTrue(posted.isEmpty)
        XCTAssertEqual(thread.reads, CoachFollow.reads)
        XCTAssertLessThanOrEqual(Double(CoachFollow.reads) * 1.5, 25, "inside the background time iOS gives")
    }

    /// "Coach" and its answer as the wrist shows it; a tap opens Coach's chat.
    func testTheNotificationIsCoachAndItsAnswer() {
        let plain = CoachFollow.content(rae)
        XCTAssertEqual(plain.title, Words.Coach.title)
        XCTAssertEqual(plain.body, "Rae is paused.")
        XCTAssertEqual(plain.userInfo["open"] as? String, "coach")
        let cut = CoachFollow.content(WatchCoachAnswer(text: "Rae is paused", more: true, approveOnPhone: false))
        XCTAssertEqual(cut.body, "Rae is paused\n" + Words.Watch.moreOnPhone)
        let card = CoachFollow.content(WatchCoachAnswer(text: "", more: false, approveOnPhone: true))
        XCTAssertEqual(card.body, Words.Watch.approveOnPhone, "a card is only approved on the phone")
    }
}
