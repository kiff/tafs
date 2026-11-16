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

    func testTheWordsShownAreTheWordsSent() async {
        let answer = WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false)
        let phone = Phone(WatchReply(.ok, coach: answer))
        let model = CoachModel(phone: phone, clock: { self.now })
        model.draft = "  Pause Rae \n"
        await model.send()
        XCTAssertEqual(phone.asked, [WatchRequest(.coach, made: now, text: "Pause Rae")])
        XCTAssertEqual(model.state, .answered(answer))
        XCTAssertEqual(model.draft, "", "sent")
    }

    func testNothingIsSentWithoutWords() async {
        let phone = Phone(nil)
        let model = CoachModel(phone: phone, clock: { self.now })
        model.draft = "   "
        await model.send()
        XCTAssertTrue(phone.asked.isEmpty)
        XCTAssertEqual(model.state, .writing)
    }

    func testWordsOverTheLimitCantBeSent() async {
        let phone = Phone(nil)
        let model = CoachModel(phone: phone, clock: { self.now })
        model.draft = String(repeating: "a", count: WatchRequest.coachLimit + 1)
        XCTAssertFalse(model.canSend)
        await model.send()
        XCTAssertTrue(phone.asked.isEmpty)
    }

    func testARefusalKeepsTheWordsAndSaysWhy() async {
        let model = CoachModel(phone: Phone(WatchReply(.failed, line: "Before you talk to Coach, allow AI use in tafs on your phone.")), clock: { self.now })
        model.draft = "Pause Rae"
        await model.send()
        XCTAssertEqual(model.state, .failed("Before you talk to Coach, allow AI use in tafs on your phone."))
        XCTAssertEqual(model.draft, "Pause Rae", "kept, to send once allowed")
    }

    func testTheWordsAndTheAnswerGoWhenTheAppLeavesTheScreen() async {
        let model = CoachModel(phone: Phone(WatchReply(.ok, line: "The rest is on your phone.")), clock: { self.now })
        model.draft = "Pause Rae"
        await model.send()
        XCTAssertEqual(model.state, .answered(nil))
        model.draft = "Something"
        model.leave()
        XCTAssertEqual(model.draft, "")
        XCTAssertEqual(model.state, .writing)
    }
}
