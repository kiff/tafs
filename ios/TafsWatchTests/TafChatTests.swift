import XCTest
@testable import TafsWatch

/// Chatting with a taf from the wrist (docs/watch-app.md, "Chatting with a taf"): the owner's picks 1A and 2A, and Read more.
@MainActor
final class TafChatTests: XCTestCase {
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
    nonisolated private static let tafID = "taf-0a1b2c3d4e5f60718293"
    nonisolated private static let ticket = WatchCoachTicket(chat: WatchCoachTicket.askChat, session: "wa_9", sent: nil)
    nonisolated private static let part = WatchCoachAnswer(text: "This week: 31 refunds paid…", more: true, approveOnPhone: false)
    nonisolated private static let all = WatchCoachAnswer(text: "This week: 31 refunds paid, 4 held for you.", more: false, approveOnPhone: false)

    /// Send answered with the request's ticket, the first read with the first part, then Read more as given.
    private final class Phone: PhoneAsking {
        var asked: [WatchRequest] = []
        var full: WatchReply?
        var history: [WatchChatLine] = []
        /// What Read more on a line from before this visit is answered.
        var line: WatchReply?
        init(full: WatchReply?) { self.full = full }
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            switch request.kind {
            case .coachSend: return WatchReply(.ok, ticket: TafChatTests.ticket)
            case .coachRead: return WatchReply(.ok, coach: TafChatTests.part, answering: false)
            case .askFull: return full
            case .tafChat: return WatchReply(.ok, chat: history)
            case .lineFull: return line
            default: return WatchReply(.refused)
            }
        }
    }

    private func sent(_ phone: Phone) async -> CoachModel {
        let model = CoachModel(phone: phone, taf: Self.tafID, clock: { self.now }, lock: Unlocked(), pause: {})
        model.review("What did you do this week?")
        await model.sendReviewed()
        return model
    }

    /// A long answer shows its first part; the whole is read only on the tap, by the request's ticket, and shown in place.
    func testReadMoreReadsTheWholeAnswerOnlyOnTheTap() async throws {
        let phone = Phone(full: WatchReply(.ok, coach: Self.all, answering: false))
        let model = await sent(phone)
        XCTAssertFalse(phone.asked.contains { $0.kind == .askFull }, "nothing more is read before the tap")
        let turn = try XCTUnwrap(model.turns.first)
        XCTAssertEqual(turn.ticket, Self.ticket)
        XCTAssertEqual(TafTurnRow.foot(Self.part, whole: model.whole[turn.id]), .readMore)
        await model.readMore(turn.id)
        XCTAssertEqual(phone.asked.last, WatchRequest(.askFull, made: now, ticket: Self.ticket))
        XCTAssertEqual(model.whole[turn.id], .read(Self.all))
        XCTAssertEqual(TafTurnRow.shown(Self.part, whole: model.whole[turn.id]), Self.all.text)
        XCTAssertNil(TafTurnRow.foot(Self.part, whole: model.whole[turn.id]), "whole: nothing under it")
        // Read once: another tap reads nothing more.
        await model.readMore(turn.id)
        XCTAssertEqual(phone.asked.filter { $0.kind == .askFull }.count, 1)
    }

    /// Read more that the phone can't answer, or a phone from before it, falls back to "The rest is on your phone."
    func testReadMoreFallsBackToThePhone() async throws {
        for reply in [nil, WatchReply(.notYet), WatchReply(.failed, line: "x")] as [WatchReply?] {
            let model = await sent(Phone(full: reply))
            let turn = try XCTUnwrap(model.turns.first)
            await model.readMore(turn.id)
            XCTAssertEqual(model.whole[turn.id], .onPhone)
            XCTAssertEqual(TafTurnRow.foot(Self.part, whole: model.whole[turn.id]), .onPhone)
        }
    }

    /// Nothing read is kept: the whole answers go with the turns when the app leaves the screen.
    func testTheWholeAnswersGoWhenTheAppLeaves() async throws {
        let model = await sent(Phone(full: WatchReply(.ok, coach: Self.all, answering: false)))
        await model.readMore(try XCTUnwrap(model.turns.first).id)
        model.leave()
        XCTAssertTrue(model.whole.isEmpty)
        XCTAssertTrue(model.turns.isEmpty)
    }

    /// An answer that isn't long has no Read more; none yet says the rest is on the phone.
    func testOnlyALongAnswerOffersReadMore() {
        XCTAssertNil(TafTurnRow.foot(Self.all, whole: nil))
        XCTAssertEqual(TafTurnRow.foot(nil, whole: nil), .onPhone)
        XCTAssertEqual(TafTurnRow.foot(Self.part, whole: .reading), .reading)
    }

    /// 2A: a paused taf still takes the message; a halted one takes none ("Resume on your phone"); a phone from before the
    /// paused flag reads as open.
    func testWhatATafsChatTakesByItsState() {
        XCTAssertEqual(TafChatSend.of(WatchTaf(id: Self.tafID, name: "Refunds", line: "Working", halted: false, paused: false)), .open)
        XCTAssertEqual(TafChatSend.of(WatchTaf(id: Self.tafID, name: "Calendar", line: "Paused", halted: false, paused: true)), .paused)
        XCTAssertEqual(TafChatSend.of(WatchTaf(id: Self.tafID, name: "Overdue", line: "Halted", halted: true, paused: false)), .halted)
        XCTAssertEqual(TafChatSend.of(WatchTaf(id: Self.tafID, name: "Rae", line: nil, halted: false)), .open)
    }

    /// A taf from a phone from before `paused` still decodes.
    func testATafWithoutPausedDecodes() throws {
        let taf = try JSONDecoder().decode(WatchTaf.self, from: Data(#"{"id":"\#(Self.tafID)","name":"Rae","halted":false}"#.utf8))
        XCTAssertNil(taf.paused)
    }

    /// 1A: each taf's chat is the same one each time it's opened this visit, sends to that taf, and goes when the app leaves.
    func testEachTafsChatIsKeptForTheVisitOnly() async {
        let model = TafsModel(phone: Phone(full: nil), clock: { self.now }, lock: Unlocked())
        let taf = WatchTaf(id: Self.tafID, name: "Refunds", line: "Working", halted: false, paused: false)
        let chat = model.chat(taf)
        XCTAssertTrue(chat === model.chat(taf))
        XCTAssertEqual(chat.taf, Self.tafID)
        model.leave()
        XCTAssertFalse(chat === model.chat(taf), "a new visit, a new chat")
    }

    /// A taf's chat opens on its last turns, read through the phone for that taf; what this visit said shows once, below
    /// them. Coach's model reads none. They go when the app leaves.
    func testATafsChatOpensOnItsLastTurns() async {
        let phone = Phone(full: nil)
        let earlier = WatchChatLine(id: "m1", mine: false, at: now.addingTimeInterval(-3600), text: "Morning run: 6 refunds paid.", more: false)
        let later = WatchChatLine(id: "m2", mine: true, at: now.addingTimeInterval(60), text: "What did you do this week?", more: false)
        phone.history = [earlier, later]
        let model = CoachModel(phone: phone, taf: Self.tafID, clock: { self.now }, lock: Unlocked(), pause: {})
        await model.loadHistory()
        XCTAssertEqual(phone.asked.last, WatchRequest(.tafChat, made: now, taf: Self.tafID))
        XCTAssertEqual(model.history, [earlier, later])
        model.review("What did you do this week?")
        await model.sendReviewed()
        await model.loadHistory()
        XCTAssertEqual(model.history, [earlier], "this visit's turn shows once, as a turn")
        model.leave()
        XCTAssertTrue(model.history.isEmpty)
        let coach = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), pause: {})
        let before = phone.asked.count
        await coach.loadHistory()
        XCTAssertEqual(phone.asked.count, before, "Coach's model reads no taf's chat")
    }

    /// Row 28: View on phone says "Opened on your phone" for a moment once the phone answers that it opened the chat; not
    /// reached, nothing.
    func testViewOnPhoneSaysItOpened() async {
        WatchOpenedNotice.shared.reset()
        defer { WatchOpenedNotice.shared.reset() }
        final class Opens: PhoneAsking {
            var reply: WatchReply?
            func ask(_ request: WatchRequest) async -> WatchReply? { reply }
        }
        let phone = Opens()
        let model = CoachModel(phone: phone, taf: Self.tafID, clock: { self.now }, lock: Unlocked(), pause: {})
        await model.openOnPhone()
        XCTAssertNil(WatchOpenedNotice.shared.key)
        phone.reply = WatchReply(.ok)
        await model.openOnPhone()
        XCTAssertEqual(WatchOpenedNotice.shared.key, WatchOpenedNotice.chat)
    }

    /// Row 10, as Wear OS's `line-full`: a taf's cut line from before this visit offers Read more, and the tap reads it whole
    /// by the taf and the line's id, shown in place, once. The person's own cut line, Coach's chat, or a phone that can't give
    /// it: the rest is on the phone. Nothing read stays once the app leaves.
    func testReadMoreOnALineFromBeforeThisVisit() async {
        let phone = Phone(full: nil)
        let cut = WatchChatLine(id: "s1|1763600005.0|taf|message", mine: false, at: now.addingTimeInterval(-3600),
                                text: "This week: 31 refunds paid…", more: true)
        let mineCut = WatchChatLine(id: "s1|owner|m2", mine: true, at: now.addingTimeInterval(-3500), text: "A long ask…", more: true)
        phone.history = [cut, mineCut]
        phone.line = WatchReply(.ok, coach: Self.all)
        let model = CoachModel(phone: phone, taf: Self.tafID, clock: { self.now }, lock: Unlocked(), pause: {})
        await model.loadHistory()
        XCTAssertEqual(TafChatLineRow.foot(cut, whole: model.whole[cut.id], canRead: true), .readMore)
        XCTAssertFalse(phone.asked.contains { $0.kind == .lineFull }, "nothing is read before the tap")
        await model.readMoreLine(cut.id)
        XCTAssertEqual(phone.asked.last, WatchRequest(.lineFull, made: now, taf: Self.tafID, message: cut.id))
        XCTAssertTrue(phone.asked.last!.isComplete)
        XCTAssertEqual(model.whole[cut.id], .read(Self.all))
        XCTAssertEqual(TafChatLineRow.shown(cut, whole: model.whole[cut.id]), Self.all.text)
        XCTAssertNil(TafChatLineRow.foot(cut, whole: model.whole[cut.id], canRead: true), "whole: nothing under it")
        await model.readMoreLine(cut.id)
        XCTAssertEqual(phone.asked.filter { $0.kind == .lineFull }.count, 1, "read once")

        await model.readMoreLine(mineCut.id)
        XCTAssertEqual(model.whole[mineCut.id], .onPhone, "the person's own words are never asked for")
        XCTAssertEqual(TafChatLineRow.foot(mineCut, whole: nil, canRead: true), .onPhone)
        XCTAssertEqual(TafChatLineRow.foot(cut, whole: nil, canRead: false), .onPhone, "Coach's chat has no taf to read")
        XCTAssertEqual(phone.asked.filter { $0.kind == .lineFull }.count, 1)
        XCTAssertFalse(WatchRequest(.lineFull, made: now, taf: Self.tafID, message: "not an id!").isComplete)
        XCTAssertFalse(WatchRequest(.lineFull, made: now, message: cut.id).isComplete, "a taf is named")

        for reply in [nil, WatchReply(.notYet), WatchReply(.failed, line: "x")] as [WatchReply?] {
            phone.line = reply
            let other = CoachModel(phone: phone, taf: Self.tafID, clock: { self.now }, lock: Unlocked(), pause: {})
            await other.loadHistory()
            await other.readMoreLine(cut.id)
            XCTAssertEqual(other.whole[cut.id], .onPhone)
        }
        model.leave()
        XCTAssertNil(model.whole[cut.id], "nothing read is kept")
    }

    /// On once the privacy paragraph is live; tafs cloud's switch still gates each account.
    func testTheSwitchIsOn() {
        XCTAssertTrue(WatchTafChat.on)
    }
}
