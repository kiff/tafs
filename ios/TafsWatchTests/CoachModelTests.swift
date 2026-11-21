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

    /// W2's phone: Send answered with a ticket (or as given), then each read in turn, the last again once they run out.
    private final class TwoStepPhone: PhoneAsking {
        var asked: [WatchRequest] = []
        var send: WatchReply?
        var reads: [WatchReply?]
        /// Called as each read is asked, before it's answered.
        var onRead: (() -> Void)?
        init(send: WatchReply? = WatchReply(.ok, ticket: CoachModelTests.ticket), reads: [WatchReply?]) {
            self.send = send
            self.reads = reads
        }
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            guard request.kind == .coachRead else { return send }
            onRead?()
            return reads.count > 1 ? reads.removeFirst() : reads.first ?? nil
        }
    }

    nonisolated private static let ticket = WatchCoachTicket(chat: "taf-setup", session: "s1", sent: "m1")
    nonisolated private static let firstID = UUID(uuidString: "6F1C2A4E-0B8D-4E57-9A3C-2D5E8F7B1C90")!
    nonisolated private static let rae = WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false)

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
        let phone = TwoStepPhone(reads: [WatchReply(.ok, coach: Self.rae, answering: false)])
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        model.review("   \n")
        XCTAssertFalse(model.reviewing, "blank is nothing")
        model.review(String(repeating: "a", count: WatchRequest.coachLimit + 5))
        XCTAssertTrue(model.reviewing)
        XCTAssertEqual(model.draft.count, WatchRequest.coachLimit, "cut at what Coach takes")
        model.review("  Pause Rae ")
        XCTAssertEqual(model.draft, "Pause Rae", "Re-record: the new words take the old ones' place")
        XCTAssertTrue(phone.asked.isEmpty, "nothing leaves the watch before Send")
        await model.sendReviewed()
        XCTAssertEqual(phone.asked, [WatchRequest(.coachSend, made: now, text: "Pause Rae", message: "6f1c2a4e-0b8d-4e57-9a3c-2d5e8f7b1c90"),
                                     WatchRequest(.coachRead, made: now, ticket: Self.ticket)])
        XCTAssertFalse(model.reviewing)
        XCTAssertEqual(model.state, .answered(Self.rae))
    }

    /// The next Send of the same visit replies to the last request (its ticket goes with it, for `reply_to`); once the app
    /// has left the screen, a Send replies to nothing.
    func testTheNextSendRepliesToTheLastRequestUntilTheAppLeaves() async {
        let asked = WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "wa_1", sent: nil)
        let phone = TwoStepPhone(send: WatchReply(.ok, ticket: asked), reads: [WatchReply(.ok, coach: Self.rae, answering: false)])
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        model.review("Pause Rae")
        await model.sendReviewed()
        model.review("Until Monday")
        await model.sendReviewed()
        let sends = phone.asked.filter { $0.kind == .coachSend }
        XCTAssertNil(sends[0].ticket, "the first replies to nothing")
        XCTAssertEqual(sends[1].ticket, asked, "the next replies to it")
        model.leave()
        model.review("What needs me today?")
        await model.sendReviewed()
        XCTAssertNil(phone.asked.filter { $0.kind == .coachSend }.last?.ticket, "a new visit starts a new conversation")
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
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        model.review("Pause Rae")
        let sending = Task { await model.sendReviewed() }
        while phone.held == nil { await Task.yield() }
        XCTAssertEqual(model.state, .sending)
        model.delete()
        XCTAssertNotEqual(model.state, .deleted, "the words may already have reached Coach")
        model.review("Pause Ivo")
        XCTAssertEqual(model.draft, "Pause Rae", "the words shown are the words sent")
        XCTAssertTrue(model.reviewing)
        phone.answer(WatchReply(.ok, ticket: Self.ticket))
        // Sent, and Coach's answer read: still nothing deleted or re-recorded.
        while phone.asked.count < 2 || phone.held == nil { await Task.yield() }
        XCTAssertEqual(model.state, .sent)
        XCTAssertFalse(model.canSend)
        model.delete()
        model.review("Pause Ivo")
        XCTAssertEqual(model.state, .sent)
        phone.answer(WatchReply(.ok, coach: Self.rae, answering: false))
        await sending.value
        XCTAssertEqual(phone.asked.map(\.text), ["Pause Rae", nil])
        XCTAssertEqual(model.state, .answered(Self.rae))
        XCTAssertFalse(model.reviewing)
    }

    /// W2: "Sent" as soon as tafs cloud has the words, "Coach is answering" while its chat says so, then the answer,
    /// kept for the Inbox once.
    func testSendSaysSentThenAnsweringThenTheAnswer() async {
        let partial = WatchCoachAnswer(text: "Pausing", more: false, approveOnPhone: false)
        let phone = TwoStepPhone(reads: [WatchReply(.ok, answering: false), WatchReply(.ok, coach: partial, answering: true),
                                         WatchReply(.ok, coach: Self.rae, answering: false)])
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        var seen: [CoachModel.State] = []
        phone.onRead = { seen.append(model.state) }
        var kept: [(WatchCoachAnswer?, String)] = []
        model.onAnswer = { kept.append(($0, $1)) }
        model.review("Pause Rae")
        await model.sendReviewed()
        XCTAssertEqual(seen, [.sent, .sent, .answering], "Sent before Coach starts, then Coach is answering")
        XCTAssertEqual(model.state, .answered(Self.rae))
        XCTAssertEqual(model.asked, "Pause Rae", "the words that left stay above the answer")
        XCTAssertEqual(kept.map(\.0), [Self.rae])
        XCTAssertEqual(kept.map(\.1), ["6f1c2a4e-0b8d-4e57-9a3c-2d5e8f7b1c90"])
    }

    /// W2: past `answerReads`, what Coach wrote so far, with the rest on the phone; never "not sent", since it was.
    func testPastTheReadsTheRestIsOnThePhone() async {
        let partial = WatchCoachAnswer(text: "Pausing", more: false, approveOnPhone: false)
        let phone = TwoStepPhone(reads: [nil, WatchReply(.failed, line: "tafs couldn't reach KIFF."),
                                         WatchReply(.ok, coach: partial, answering: true)])
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        model.review("Pause Rae")
        await model.sendReviewed()
        XCTAssertEqual(phone.asked.filter { $0.kind == .coachRead }.count, CoachModel.answerReads)
        XCTAssertEqual(model.state, .answered(WatchCoachAnswer(text: "Pausing", more: true, approveOnPhone: false)))
    }

    /// W2: leaving the screen stops the reads, and nothing is kept for the Inbox from them.
    func testLeavingStopsTheReads() async {
        let phone = TwoStepPhone(reads: [WatchReply(.ok, answering: true)])
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        var kept = 0
        model.onAnswer = { _, _ in kept += 1 }
        var reads = 0
        phone.onRead = {
            reads += 1
            if reads == 2 { model.leave() }
        }
        model.review("Pause Rae")
        await model.sendReviewed()
        XCTAssertEqual(reads, 2)
        XCTAssertEqual(model.state, .writing)
        XCTAssertNil(model.asked)
        XCTAssertEqual(kept, 0)
    }

    /// W2: a phone from before the two steps refuses `coach_send`; the same words then go v1's way, under the same id.
    func testAPhoneFromBeforeTheTwoStepsIsSentV1sWay() async {
        let v1 = V1Phone(refusing: .coachSend, answer: WatchReply(.ok, coach: Self.rae))
        let older = CoachModel(phone: v1, clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        older.review("Pause Rae")
        await older.sendReviewed()
        XCTAssertEqual(v1.asked.map(\.kind), [.coachSend, .coach])
        XCTAssertEqual(Set(v1.asked.map(\.message)), ["6f1c2a4e-0b8d-4e57-9a3c-2d5e8f7b1c90"])
        XCTAssertEqual(older.state, .answered(Self.rae))
        XCTAssertFalse(older.reviewing)
    }

    // MARK: W4, Ask Coach as one conversation

    /// Each Send is a turn, oldest first: what was said and when, then Sent, Coach is answering and the answer.
    func testTheConversationKeepsEachTurnAndItsAnswer() async {
        let ivo = WatchCoachAnswer(text: "Ivo is paused until Monday.", more: false, approveOnPhone: false)
        let phone = TwoStepPhone(reads: [WatchReply(.ok, coach: Self.rae, answering: false)])
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        var seen: [CoachModel.Turn.Answer] = []
        phone.onRead = { seen.append(model.turns.last!.answer) }
        model.review("Pause Rae")
        await model.sendReviewed()
        XCTAssertEqual(model.turns.map(\.said), ["Pause Rae"])
        XCTAssertEqual(seen, [.sent], "Sent before Coach answers")
        XCTAssertEqual(model.turns[0].answer, .answered(Self.rae))
        XCTAssertEqual(model.turns[0].at, now)
        model.closeAsk()
        phone.reads = [WatchReply(.ok, answering: true), WatchReply(.ok, coach: ivo, answering: false)]
        seen = []
        model.review("Pause Ivo")
        await model.sendReviewed()
        XCTAssertEqual(model.turns.map(\.said), ["Pause Rae", "Pause Ivo"], "oldest first")
        XCTAssertEqual(seen, [.sent, .answering])
        XCTAssertEqual(model.turns.map(\.answer), [.answered(Self.rae), .answered(ivo)])
    }

    /// Closing the ask while Coach is at it keeps the conversation, and the answer still lands in it and in the Inbox.
    func testClosingTheAskKeepsTheAnswerOnItsWay() async {
        let phone = TwoStepPhone(reads: [WatchReply(.ok, answering: true), WatchReply(.ok, coach: Self.rae, answering: false)])
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        var kept: [WatchCoachAnswer?] = []
        model.onAnswer = { answer, _ in kept.append(answer) }
        var reads = 0
        phone.onRead = {
            reads += 1
            if reads == 2 { model.closeAsk() }
        }
        model.review("Pause Rae")
        await model.sendReviewed()
        XCTAssertEqual(model.turns.map(\.answer), [.answered(Self.rae)])
        XCTAssertEqual(kept, [Self.rae])
        model.closeAsk()
        XCTAssertEqual(model.state, .writing, "the next ask starts afresh")
        XCTAssertNil(model.asked)
        XCTAssertEqual(model.turns.count, 1, "the conversation stays")
    }

    /// Closing the ask while the words are still on their way to the phone keeps them, so a failure can be sent again.
    func testClosingTheAskWhileSendingKeepsTheWords() async {
        let phone = HeldPhone()
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        model.review("Pause Rae")
        let sending = Task { await model.sendReviewed() }
        while phone.held == nil { await Task.yield() }
        model.closeAsk()
        phone.answer(nil)
        await sending.value
        XCTAssertEqual(model.state, .failed(Words.Watch.phoneNotReachable))
        XCTAssertEqual(model.draft, "Pause Rae")
        XCTAssertTrue(model.reviewing)
        XCTAssertTrue(model.turns.isEmpty, "nothing was sent")
    }

    /// Put away or signed out: the conversation goes. Nothing of it was ever kept on the watch.
    func testLeavingForgetsTheConversation() async {
        let phone = TwoStepPhone(reads: [WatchReply(.ok, coach: Self.rae, answering: false)])
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        model.review("Pause Rae")
        await model.sendReviewed()
        XCTAssertEqual(model.turns.count, 1)
        model.leave()
        XCTAssertTrue(model.turns.isEmpty)
    }

    /// Coach's side of a turn: its words, else where the rest is.
    func testATurnsLineIsCoachsWordsElseWhereTheRestIs() {
        XCTAssertEqual(CoachTurnRow.line(Self.rae), "Rae is paused.")
        XCTAssertEqual(CoachTurnRow.line(nil), Words.Watch.moreOnPhone)
        XCTAssertEqual(CoachTurnRow.line(WatchCoachAnswer(text: "", more: false, approveOnPhone: true)), Words.Watch.approveOnPhone)
    }

    /// VoiceOver hears Coach's side with when the answer came, in the account's zone (dev-android on #1590, point 2), and
    /// without a time from a phone too old to send one.
    func testCoachsSideIsHeardWithWhenItCame() {
        let paris = TimeZone(identifier: "Europe/Paris")!
        var answer = Self.rae
        answer.at = Date(timeIntervalSince1970: 1_795_000_000)
        let time = ChatTime.time(answer.at!, zone: paris)
        XCTAssertEqual(CoachTurnRow.spoken(answer, zone: paris), Words.Coach.title + ": Rae is paused., " + time)
        XCTAssertNotEqual(ChatTime.time(answer.at!, zone: TimeZone(identifier: "Asia/Tokyo")!), time, "the zone decides the time")
        XCTAssertEqual(CoachTurnRow.spoken(Self.rae, zone: paris), Words.Coach.title + ": Rae is paused.")
    }

    /// A phone that refuses one kind, as one from before it would, and answers the rest.
    private final class V1Phone: PhoneAsking {
        var asked: [WatchRequest] = []
        let refusing: WatchRequest.Kind
        let answer: WatchReply
        init(refusing: WatchRequest.Kind, answer: WatchReply) { self.refusing = refusing; self.answer = answer }
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            return request.kind == refusing ? WatchReply(.refused) : answer
        }
    }

    // MARK: Chatting with a taf (docs/watch-ask.md)

    /// A taf's chat sends its words naming the taf, and reads the answer as Coach's is read; the Inbox isn't told, since
    /// the watch keeps nothing of a taf's chat.
    func testATafChatsSendNamesTheTafAndKeepsNothing() async {
        let phone = TwoStepPhone(reads: [WatchReply(.ok, coach: Self.rae, answering: false)])
        let model = CoachModel(phone: phone, taf: "taf-0a1b2c3d4e5f60718293", clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        var told = 0
        model.onAnswer = { _, _ in told += 1 }
        model.review("Refund A-1052 too")
        await model.sendReviewed()
        XCTAssertEqual(phone.asked.first, WatchRequest(.coachSend, made: now, taf: "taf-0a1b2c3d4e5f60718293", text: "Refund A-1052 too",
                                                       message: "6f1c2a4e-0b8d-4e57-9a3c-2d5e8f7b1c90"))
        XCTAssertEqual(model.state, .answered(Self.rae))
        XCTAssertEqual(model.turns.map(\.said), ["Refund A-1052 too"])
        XCTAssertEqual(told, 0, "nothing of a taf's chat goes to the Inbox")
    }

    /// A phone from before chatting with a taf: the words stay in the review, and never go to Coach by v1's send.
    func testATafChatNeverFallsBackToCoachOnAnOlderPhone() async {
        let phone = V1Phone(refusing: .coachSend, answer: WatchReply(.ok, coach: Self.rae))
        let model = CoachModel(phone: phone, taf: "taf-0a1b2c3d4e5f60718293", clock: { self.now }, lock: Unlocked(), newID: IDs().next, pause: {})
        model.review("Hi")
        await model.sendReviewed()
        XCTAssertEqual(model.state, .failed(Words.Watch.openOnPhone))
        XCTAssertEqual(phone.asked.map(\.kind), [.coachSend], "no v1 Coach send")
        XCTAssertTrue(model.reviewing, "the words stay")
        model.draft = "Hi"
        await model.send()
        XCTAssertEqual(phone.asked.map(\.kind), [.coachSend], "v1's send is Coach's, never a taf's")
    }

    /// Quick reply: four lines, in Wear OS's order, each its own words.
    func testQuickReplyIsFourLinesInWearsOrder() {
        XCTAssertEqual(CoachModel.quickReplies, [Words.Watch.Qr.needsMeToday, Words.Watch.Qr.tellMeMore, Words.Watch.Qr.notNow,
                                                 Words.Watch.Qr.thanks])
        XCTAssertEqual(Set(CoachModel.quickReplies).count, 4)
        XCTAssertFalse(CoachModel.quickReplies.contains(where: \.isEmpty))
    }
}
