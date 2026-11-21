import XCTest
@testable import TafsWatch

/// One tap to talk (row 44, A; as Wear OS's tafs#1582): Ask goes straight to the system's dictation, and its words to
/// Check before sending, never to the phone.
@MainActor
final class WatchDictationTests: XCTestCase {
    private final class Phone: PhoneAsking {
        var asked: [WatchRequest] = []
        var reply: WatchReply?
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            return reply
        }
    }

    func testTheInputsFirstTextIsTheWordsTrimmed() {
        XCTAssertEqual(WatchDictation.heard(["  Refund order 1042 \n", "other"]), .words("Refund order 1042"))
        XCTAssertEqual(WatchDictation.heard(nil), .nothing)
        XCTAssertEqual(WatchDictation.heard([]), .nothing)
        XCTAssertEqual(WatchDictation.heard(["  "]), .nothing)
        XCTAssertEqual(WatchDictation.heard([42]), .nothing)
    }

    /// The words go to the review, and nothing is sent before Send.
    func testWordsHeardGoToTheReviewAndNothingIsSent() async {
        let phone = Phone()
        let model = CoachModel(phone: phone, lock: Unlocked())
        _ = await WatchDictation.talk(to: model) { .words("Chase the overdue invoices") }
        XCTAssertTrue(model.reviewing)
        XCTAssertEqual(model.draft, "Chase the overdue invoices")
        XCTAssertEqual(phone.asked, [])
    }

    /// Cancelled: no review; the ask opens on Type and Quick reply.
    func testNothingHeardLeavesTheAskAsItWas() async {
        let model = CoachModel(phone: Phone(), lock: Unlocked())
        _ = await WatchDictation.talk(to: model) { .nothing }
        XCTAssertFalse(model.reviewing)
        XCTAssertEqual(model.draft, "")
    }

    /// Without a passcode the watch never listens, as Wear OS without a screen lock.
    func testAWatchWithNoPasscodeNeverListens() async {
        let model = CoachModel(phone: Phone(), lock: NoPasscode())
        XCTAssertFalse(model.listensFirst)
        var listened = false
        _ = await WatchDictation.talk(to: model) { listened = true; return .words("hi") }
        XCTAssertFalse(listened)
        XCTAssertFalse(model.reviewing)
    }

    /// Words kept in the review after a failed Send come back as they were: the microphone doesn't talk over them.
    func testWordsKeptInTheReviewAreNotTalkedOver() async {
        let phone = Phone()
        let model = CoachModel(phone: phone, lock: Unlocked())
        model.review("First words")
        await model.sendReviewed()
        XCTAssertTrue(model.reviewing, "a Send the phone didn't answer keeps the words")
        XCTAssertFalse(model.listensFirst)
        var listened = false
        _ = await WatchDictation.talk(to: model) { listened = true; return .words("Other words") }
        XCTAssertFalse(listened)
        XCTAssertEqual(model.draft, "First words")
    }

    /// A second tap while the input is open is nothing: one input at a time, and the ask opens once.
    func testASecondTapWhileTheInputIsOpenIsNothing() async {
        final class Gate { var release: CheckedContinuation<Void, Never>? }
        let model = CoachModel(phone: Phone(), lock: Unlocked())
        let gate = Gate()
        let first = Task {
            await WatchDictation.talk(to: model) {
                await withCheckedContinuation { gate.release = $0 }
                return .words("First")
            }
        }
        while gate.release == nil { await Task.yield() }
        var listenedAgain = false
        let second = await WatchDictation.talk(to: model) { listenedAgain = true; return .words("Second") }
        XCTAssertFalse(second)
        XCTAssertFalse(listenedAgain)
        gate.release?.resume()
        let opened = await first.value
        XCTAssertTrue(opened)
        XCTAssertEqual(model.draft, "First")
        let third = await WatchDictation.talk(to: model) { .nothing }
        XCTAssertTrue(third, "free again once the first input closed")
    }
}
