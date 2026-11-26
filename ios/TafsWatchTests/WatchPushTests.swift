import XCTest
@testable import TafsWatch

/// Kept and pushed (docs/watch-app.md): the phone hands the watch each change tafs cloud pushed it, queued, and the thread on
/// the wrist moves with no read of its own; a Send the phone didn't answer goes again by itself, under the same id.
@MainActor
final class WatchPushTests: XCTestCase {
    // Kept threads are on (`WatchThreads.on`): each test starts and ends with none kept on this simulator, so no Coach or taf
    // chat opens on what another test said.
    override func setUp() async throws {
        try await super.setUp()
        await MainActor.run { WatchThreads.shared.clear() }
    }

    override func tearDown() async throws {
        await MainActor.run { WatchThreads.shared.clear() }
        try await super.tearDown()
    }

    private let now = Date(timeIntervalSinceReferenceDate: 813_667_500)

    /// The phone's push of its held calls carries only what the Privacy page lets the watch keep: the count, and each call's
    /// id, taf, what it asks with its amount taken out, and when it lapses. Never its amount, why it was held, or its time in
    /// words; and it comes through `transferUserInfo` whole.
    func testTheHeldCallsPushCarriesNoAmountOrDetail() {
        let call = WatchNeedsYou.Call(id: "ce_1", taf: "Rae", what: "Refund order 4502, €480.00", summary: "Refund order 4502",
                                      reason: "Over €150.00 each", answerBy: "10:51", ends: now + 600)
        let bare = WatchNeedsYou.Call(id: "ce_2", taf: nil, what: "€90.00", summary: "  ", reason: "Over €50.00 each", answerBy: "10:55")
        let push = WatchPush(.needsYou, holds: WatchHolds(WatchNeedsYou(count: 4, calls: [call, bare], at: now)), at: now,
                             mark: String(repeating: "a", count: 32))
        XCTAssertEqual(push.holds, WatchHolds(count: 4, calls: [.init(id: "ce_1", taf: "Rae", line: "Refund order 4502", ends: now + 600),
                                                                .init(id: "ce_2", taf: nil, line: nil, ends: nil)], at: now))
        let json = String(decoding: WatchMessage.encode(push), as: UTF8.self)
        for word in ["€", "480", "150", "90.00", "Over", "10:51", "10:55", "what", "reason", "answerBy", "summary"] {
            XCTAssertFalse(json.contains(word), "\(word) in \(json)")
        }
        XCTAssertEqual(WatchPush.from(push.userInfo), push)
        XCTAssertTrue(push.holds!.sameAs(WatchHolds(WatchNeedsYou(count: 4, calls: [call, bare], at: now + 60))), "read again, the same")
    }
    nonisolated private static let ticket = WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "ask_1", sent: nil)
    nonisolated private static let rae = WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false)

    /// A phone that takes the Send with a ticket and answers each read "still at work", so only a push can finish it.
    private final class SlowPhone: PhoneAsking {
        var asked: [WatchRequest] = []
        var send: [PhoneReach] = [.reply(WatchReply(.ok, ticket: WatchPushTests.ticket))]
        var chat: [WatchChatLine] = []
        /// What each read of the answer says: still at work, or (nil) no reply, so only a push moves the turn.
        var read: WatchReply? = WatchReply(.ok, answering: true)
        func ask(_ request: WatchRequest) async -> WatchReply? { await reach(request).reply }
        func reach(_ request: WatchRequest) async -> PhoneReach {
            asked.append(request)
            switch request.kind {
            case .coachRead: return read.map(PhoneReach.reply) ?? .failed(.noAnswer)
            case .tafChat: return .reply(WatchReply(.ok, chat: chat))
            case .coachSend: return send.count > 1 ? send.removeFirst() : send.first ?? .failed(.notReachable)
            default: return .reply(WatchReply(.ok))
            }
        }
        var reads: Int { asked.filter { $0.kind == .coachRead }.count }
    }

    func testAPushTravelsAsUserInfoAndNothingElseIsOne() {
        let push = WatchPush(.ask, ask: "ask_1", reply: WatchReply(.ok, coach: Self.rae, answering: false), at: now)
        XCTAssertEqual(WatchPush.from(push.userInfo), push)
        XCTAssertNil(WatchPush.from(["other": Data()]))
        XCTAssertNil(WatchPush.from([WatchPush.key: Data("{}".utf8)]))
    }

    /// The answer pushed while the watch reads: the turn is answered at once, told to the Inbox, and the reads stop.
    func testAnAnswerPushedFinishesTheTurnAndStopsTheReads() async {
        let phone = SlowPhone()
        var told: [String] = []
        var pauses = 0
        let pushed = WatchPush(.ask, ask: "ask_1", reply: WatchReply(.ok, coach: Self.rae, answering: false), at: now)
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), pause: {
            pauses += 1
            if pauses == 2 { WatchPushes.shared.deliver(pushed) }
        }, linkPause: { _ in })
        model.onAnswer = { _, id in told.append(id) }
        model.review("Pause Rae")
        await model.sendReviewed()
        XCTAssertEqual(model.turns.last?.answer, .answered(Self.rae))
        XCTAssertEqual(model.state, .answered(Self.rae))
        XCTAssertEqual(told.count, 1)
        XCTAssertEqual(phone.reads, 1, "one read before the push, none after")
        // The same push again changes nothing, and tells the Inbox nothing more.
        WatchPushes.shared.deliver(pushed)
        XCTAssertEqual(told.count, 1)
    }

    /// "Answering" pushed: the turn says so. A push for a request this watch didn't send changes nothing.
    func testAnsweringPushedAndOtherRequestsIgnored() async {
        let phone = SlowPhone()
        // The phone's reads get no reply: only the push can say "answering".
        phone.read = nil
        var pauses = 0
        var seen: [CoachModel.Turn.Answer?] = []
        var model: CoachModel!
        model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), pause: {
            pauses += 1
            if pauses == 1 {
                // Another request's answer: not this watch's turn, so nothing moves.
                WatchPushes.shared.deliver(WatchPush(.ask, ask: "ask_other", reply: WatchReply(.ok, coach: WatchPushTests.rae, answering: false), at: Date()))
                seen.append(model.turns.last?.answer)
                WatchPushes.shared.deliver(WatchPush(.ask, ask: "ask_1", reply: WatchReply(.ok, answering: true), at: Date()))
                seen.append(model.turns.last?.answer)
                seen.append(model.state == .answering ? .answering : nil)
                model.leave()
            }
        }, linkPause: { _ in })
        model.review("Pause Rae")
        await model.sendReviewed()
        XCTAssertEqual(seen, [.sent, .answering, .answering])
    }

    /// A taf's chat changed on the phone: the chat on the wrist reads it again; another taf's, or Coach's, doesn't.
    func testAChatPushReadsThatTafsChatAgain() async {
        let phone = SlowPhone()
        let line = WatchChatLine(id: "m1", mine: false, at: now, text: "Done.", more: false)
        phone.chat = [line]
        let chat = CoachModel(phone: phone, taf: "taf-1", clock: { self.now }, lock: Unlocked(), linkPause: { _ in })
        let coach = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), linkPause: { _ in })
        WatchPushes.shared.deliver(WatchPush(.chat, taf: "taf-2", at: now))
        WatchPushes.shared.deliver(WatchPush(.chat, taf: "taf-1", at: now))
        for _ in 0..<50 where chat.history.isEmpty { await Task.yield() }
        XCTAssertEqual(chat.history, [line])
        XCTAssertEqual(phone.asked.filter { $0.kind == .tafChat }.map(\.taf), ["taf-1"])
        XCTAssertTrue(coach.history.isEmpty)
    }

    /// The owner's call 6: no Try again. A Send the phone didn't answer goes again by itself, under the same id, with
    /// "Linking tafs" on screen meanwhile; once through, it's sent as any other.
    func testASendGoesAgainByItselfUnderTheSameId() async {
        let phone = SlowPhone()
        phone.send = [.failed(.noAnswer), .failed(.notReachable), .reply(WatchReply(.ok, ticket: Self.ticket))]
        var sawLinking = false
        var model: CoachModel!
        model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), pause: { model.leave() },
                           linkPause: { _ in sawLinking = sawLinking || model.linking })
        model.review("Pause Rae")
        await model.sendReviewed()
        let sends = phone.asked.filter { $0.kind == .coachSend }
        XCTAssertEqual(sends.count, 3)
        XCTAssertEqual(Set(sends.map(\.message)).count, 1, "one message, one id")
        XCTAssertTrue(sawLinking)
        XCTAssertFalse(model.linking)
    }

    /// tafs missing from the phone isn't tried again: only installing it helps.
    func testNoAppIsNotTriedAgain() async {
        let phone = SlowPhone()
        phone.send = [.failed(.noApp)]
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), linkPause: { _ in })
        model.review("Pause Rae")
        await model.sendReviewed()
        XCTAssertEqual(phone.asked.filter { $0.kind == .coachSend }.count, 1)
        XCTAssertEqual(model.state, .failed(Words.Watch.phoneNoApp))
    }

    /// View on phone asks the phone to open this chat: Coach's, or the taf's by its id.
    func testViewOnPhoneAsksThePhoneToOpenThisChat() async {
        let phone = SlowPhone()
        await CoachModel(phone: phone, lock: Unlocked()).openOnPhone()
        await CoachModel(phone: phone, taf: "taf-1", lock: Unlocked()).openOnPhone()
        XCTAssertEqual(phone.asked.map(\.kind), [.openOnPhone, .openOnPhone])
        XCTAssertEqual(phone.asked.map(\.taf), [nil, "taf-1"])
        XCTAssertTrue(phone.asked.allSatisfy { $0.isComplete && $0.kind.isRead })
    }
}
