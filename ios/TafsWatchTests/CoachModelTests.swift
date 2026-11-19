import XCTest
@testable import TafsWatch

/// Coach from the wrist (docs/watch-app.md, "Coach"): the words shown, then sent as text.
@MainActor
final class CoachModelTests: XCTestCase {
    private let now = Date(timeIntervalSinceReferenceDate: 813_667_500)

    private final class Phone: PhoneAsking {
        var reply: WatchReply?
        var asked: [WatchRequest] = []
        init(_ reply: WatchReply?) { self.reply = reply }
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            return reply
        }
    }

    /// A phone that holds its reply until told: a Send in flight.
    private final class HeldPhone: PhoneAsking {
        var asked: [WatchRequest] = []
        var held: CheckedContinuation<WatchReply?, Never>?
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            return await withCheckedContinuation { held = $0 }
        }
        func answer(_ reply: WatchReply?) {
            held?.resume(returning: reply)
            held = nil
        }
    }

    nonisolated private static let firstID = UUID(uuidString: "6F1C2A4E-0B8D-4E57-9A3C-2D5E8F7B1C90")!

    /// Ids in turn: the first, then new ones.
    private final class IDs {
        var made = 0
        func next() -> UUID { made += 1; return made == 1 ? CoachModelTests.firstID : UUID() }
    }

    func testTheWordsShownAreTheWordsSent() async {
        let answer = WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false)
        let phone = Phone(WatchReply(.ok, coach: answer))
        let ids = IDs()
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: ids.next)
        model.draft = "  Pause Rae \n"
        await model.send()
        XCTAssertEqual(phone.asked, [WatchRequest(.coach, made: now, text: "Pause Rae", message: "6f1c2a4e-0b8d-4e57-9a3c-2d5e8f7b1c90")])
        XCTAssertEqual(model.state, .answered(answer))
        XCTAssertEqual(model.draft, "", "sent")
    }

    /// The same words sent again after no answer came go under the same id, so Coach acts on them once
    /// (docs/chat.md, "One message, one id"). Other words, or the next message once answered, get another.
    func testTheSameWordsSentAgainKeepTheirIdUntilAnswered() async {
        let phone = Phone(nil)
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next)
        model.draft = "Pause Rae"
        await model.send()
        await model.send()
        XCTAssertEqual(phone.asked.map(\.message), [Self.firstID.uuidString.lowercased(), Self.firstID.uuidString.lowercased()],
                       "the phone didn't answer: sent again, the same message")
        model.draft = "Pause Rae now"
        await model.send()
        XCTAssertNotEqual(phone.asked.last?.message, Self.firstID.uuidString.lowercased(), "other words, another message")
        let changed = phone.asked.last?.message
        phone.reply = WatchReply(.ok, line: "The rest is on your phone.")
        await model.send()
        XCTAssertEqual(phone.asked.last?.message, changed, "the same words again: the same id")
        model.draft = "Pause Rae now"
        await model.send()
        XCTAssertNotEqual(phone.asked.last?.message, changed, "answered: the next is a new message")
    }

    func testNothingIsSentWithoutWords() async {
        let phone = Phone(nil)
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked())
        model.draft = "   "
        await model.send()
        XCTAssertTrue(phone.asked.isEmpty)
        XCTAssertEqual(model.state, .writing)
    }

    func testWordsOverTheLimitCantBeSent() async {
        let phone = Phone(nil)
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked())
        model.draft = String(repeating: "a", count: WatchRequest.coachLimit + 1)
        XCTAssertFalse(model.canSend)
        await model.send()
        XCTAssertTrue(phone.asked.isEmpty)
    }

    func testARefusalKeepsTheWordsAndSaysWhy() async {
        let model = CoachModel(phone: Phone(WatchReply(.failed, line: "Before you talk to Coach, allow AI use in tafs on your phone.")), clock: { self.now }, lock: Unlocked())
        model.draft = "Pause Rae"
        await model.send()
        XCTAssertEqual(model.state, .failed("Before you talk to Coach, allow AI use in tafs on your phone."))
        XCTAssertEqual(model.draft, "Pause Rae", "kept, to send once allowed")
    }

    func testTheWordsAndTheAnswerGoWhenTheAppLeavesTheScreen() async {
        let model = CoachModel(phone: Phone(WatchReply(.ok, line: "The rest is on your phone.")), clock: { self.now }, lock: Unlocked())
        model.draft = "Pause Rae"
        await model.send()
        XCTAssertEqual(model.state, .answered(nil))
        model.draft = "Something"
        model.leave()
        XCTAssertEqual(model.draft, "")
        XCTAssertEqual(model.state, .writing)
    }

    /// With no passcode, nothing goes to Coach, and the words stay to send once one is set.
    func testWithNoPasscodeCoachSendsNothing() async {
        let phone = Phone(WatchReply(.ok, line: "The rest is on your phone."))
        let model = CoachModel(phone: phone, clock: { self.now }, lock: NoPasscode())
        model.draft = "Pause Rae until Monday"
        await model.send()
        XCTAssertTrue(phone.asked.isEmpty, "nothing reaches the phone")
        XCTAssertEqual(model.state, .failed(Words.Watch.needsLock))
        XCTAssertEqual(model.draft, "Pause Rae until Monday")
    }

    // MARK: v2, step (iii): Check before sending (docs/watch-app.md)

    /// Words from the system's input or a Quick reply wait in Check before sending: nothing reaches the phone before Send.
    /// Blank is nothing, and the words are cut at what Coach takes.
    func testWordsWaitInTheReviewUntilSend() async {
        let phone = Phone(WatchReply(.ok, line: "The rest is on your phone."))
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next)
        model.review("   \n")
        XCTAssertFalse(model.reviewing, "blank is nothing")
        model.review(String(repeating: "a", count: WatchRequest.coachLimit + 5))
        XCTAssertTrue(model.reviewing)
        XCTAssertEqual(model.draft.count, WatchRequest.coachLimit, "cut at what Coach takes")
        model.review("  Pause Rae ")
        XCTAssertEqual(model.draft, "Pause Rae", "Re-record: the new words take the old ones' place")
        XCTAssertTrue(phone.asked.isEmpty, "nothing leaves the watch before Send")
        await model.sendReviewed()
        XCTAssertEqual(phone.asked, [WatchRequest(.coach, made: now, text: "Pause Rae", message: "6f1c2a4e-0b8d-4e57-9a3c-2d5e8f7b1c90")])
        XCTAssertFalse(model.reviewing)
        XCTAssertEqual(model.state, .answered(nil))
    }

    /// A Send that doesn't reach Coach keeps the words in the review, to send again under the same id.
    func testAFailedSendStaysInTheReviewWithItsId() async {
        let phone = Phone(nil)
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next)
        model.review(Words.Watch.Qr.needsMeToday)
        await model.sendReviewed()
        XCTAssertTrue(model.reviewing)
        XCTAssertEqual(model.draft, Words.Watch.Qr.needsMeToday)
        XCTAssertEqual(model.state, .failed(Words.Watch.phoneNotReachable))
        await model.sendReviewed()
        XCTAssertEqual(Set(phone.asked.map(\.message)).count, 1, "the same words, the same message")
    }

    /// With no passcode, Send from the review sends nothing and keeps the words.
    func testTheReviewsSendAsksForTheLock() async {
        let phone = Phone(WatchReply(.ok, line: "The rest is on your phone."))
        let model = CoachModel(phone: phone, clock: { self.now }, lock: NoPasscode())
        model.review("Pause Rae")
        await model.sendReviewed()
        XCTAssertTrue(phone.asked.isEmpty)
        XCTAssertTrue(model.reviewing)
        XCTAssertEqual(model.state, .failed(Words.Watch.needsLock))
    }

    /// Delete: the words are gone, and nothing was sent.
    func testDeleteSendsNothing() async {
        let phone = Phone(WatchReply(.ok, line: "The rest is on your phone."))
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked())
        model.review("Pause Rae")
        model.delete()
        XCTAssertEqual(model.state, .deleted)
        XCTAssertEqual(model.draft, "")
        XCTAssertFalse(model.reviewing)
        await model.sendReviewed()
        XCTAssertTrue(phone.asked.isEmpty)
        model.leave()
        XCTAssertEqual(model.state, .writing)
    }

    /// While a Send is in flight, Delete and Re-record do nothing: the words shown are the words on their way, never
    /// "Deleted. Nothing was sent." (agentgabo's read).
    func testDeleteAndReRecordWaitWhileASendIsInFlight() async {
        let phone = HeldPhone()
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next)
        model.review("Pause Rae")
        let sending = Task { await model.sendReviewed() }
        while phone.held == nil { await Task.yield() }
        XCTAssertEqual(model.state, .sending)
        model.delete()
        XCTAssertNotEqual(model.state, .deleted, "the words may already have reached Coach")
        model.review("Pause Ivo")
        XCTAssertEqual(model.draft, "Pause Rae", "the words shown are the words sent")
        XCTAssertTrue(model.reviewing)
        phone.answer(WatchReply(.ok, line: "The rest is on your phone."))
        await sending.value
        XCTAssertEqual(phone.asked.map(\.text), ["Pause Rae"])
        XCTAssertEqual(model.state, .answered(nil))
        XCTAssertFalse(model.reviewing)
    }

    /// Quick reply: four lines, in Wear OS's order, each its own words.
    func testQuickReplyIsFourLinesInWearsOrder() {
        XCTAssertEqual(CoachModel.quickReplies, [Words.Watch.Qr.needsMeToday, Words.Watch.Qr.tellMeMore, Words.Watch.Qr.notNow,
                                                 Words.Watch.Qr.thanks])
        XCTAssertEqual(Set(CoachModel.quickReplies).count, 4)
        XCTAssertFalse(CoachModel.quickReplies.contains(where: \.isEmpty))
    }
}
