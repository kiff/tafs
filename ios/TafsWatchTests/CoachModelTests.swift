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

    private static let firstID = UUID(uuidString: "6F1C2A4E-0B8D-4E57-9A3C-2D5E8F7B1C90")!

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
}
