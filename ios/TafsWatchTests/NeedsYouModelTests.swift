import XCTest
@testable import TafsWatch

/// Needs you on the wrist (docs/watch-app.md): read from the phone, kept in memory only, and
/// never "nothing is waiting" when the phone didn't say so.
@MainActor
final class NeedsYouModelTests: XCTestCase {
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

    private var page: WatchNeedsYou {
        WatchNeedsYou(count: 3, calls: [.init(id: "ce_1", taf: "Rae", what: "Refund order 4502, €480.00",
                                              reason: "Over €150.00 each", answerBy: "10:51")], at: now)
    }

    func testItAsksForNeedsYouMadeNow() async {
        let phone = Phone(WatchReply(.ok, needsYou: page))
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: Unlocked())
        XCTAssertEqual(model.state, .loading)
        await model.load()
        XCTAssertEqual(phone.asked, [WatchRequest(.needsYou, made: now)])
        XCTAssertEqual(model.state, .loaded(page))
    }

    /// Approve on your phone: the phone is asked to open the held call, by its id alone (with the account mark, as the person's
    /// act); nothing is signed or answered, and nothing on the watch changes.
    func testApproveOnYourPhoneSendsTheHoldsIDAlone() async {
        let phone = Phone(WatchReply(.ok, needsYou: page))
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: Unlocked())
        await model.load()
        phone.reply = WatchReply(.ok)
        await model.approveOnPhone("ce_1")
        XCTAssertEqual(phone.asked.last, WatchRequest(.openOnPhone, made: now, hold: "ce_1"))
        XCTAssertTrue(WatchRequest.Kind.openOnPhone.carriesMark)
        XCTAssertTrue(WatchRequest(.openOnPhone, made: now, hold: "ce_1").isComplete)
        XCTAssertFalse(WatchRequest(.openOnPhone, made: now, hold: "ce_1", taf: "taf-1").isComplete, "a call or a chat, not both")
        XCTAssertFalse(WatchRequest(.openOnPhone, made: now, hold: "not an id").isComplete)
        let sent = String(decoding: WatchMessage.encode(phone.asked.last!), as: UTF8.self)
        XCTAssertFalse(sent.contains("€") || sent.contains("480") || sent.contains("approve"), sent)
        XCTAssertEqual(model.state, .loaded(page), "nothing on the watch changes")
        XCTAssertEqual(model.answers, [:])
    }

    /// The phone's push of its held calls: a call on screen it no longer lists goes at once, with its Refuse; a new one is read
    /// live, amount and all; an older push, or one with the app put away, changes nothing and reads nothing.
    func testThePhonesPushTakesAnAnsweredCallOffTheScreen() async {
        let two = WatchNeedsYou(count: 3, calls: [
            .init(id: "ce_1", taf: "Rae", what: "Refund order 4502, €480.00", reason: "Over €150.00 each", answerBy: "10:51"),
            .init(id: "ce_2", taf: "Rae", what: "Refund order 4503, €90.00", reason: "Over €50.00 each", answerBy: "10:55"),
        ], at: now)
        let phone = Phone(WatchReply(.ok, needsYou: two))
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: Unlocked())
        await model.load()
        let one = WatchNeedsYou(count: 2, calls: [two.calls[0]], at: now + 5)
        WatchPushes.shared.deliver(WatchPush(.needsYou, holds: WatchHolds(WatchNeedsYou(count: 2, calls: [two.calls[1]], at: now - 5)), at: now - 5))
        XCTAssertEqual(model.state, .loaded(two), "an older push changes nothing")
        WatchPushes.shared.deliver(WatchPush(.needsYou, holds: WatchHolds(one), at: now + 5))
        XCTAssertEqual(model.state, .loaded(one))
        XCTAssertEqual(phone.asked.count, 1, "nothing read for a call that went")
        let three = WatchNeedsYou(count: 3, calls: [two.calls[0], .init(id: "ce_3", taf: "Rae", what: "Refund order 4510, €75.00",
                                                                          reason: "Over €50.00 each", answerBy: "11:02")], at: now + 9)
        phone.reply = WatchReply(.ok, needsYou: three)
        WatchPushes.shared.deliver(WatchPush(.needsYou, holds: WatchHolds(three), at: now + 9))
        for _ in 0..<100 where phone.asked.count < 2 { await Task.yield() }
        XCTAssertEqual(phone.asked.count, 2, "a new call is read live")
        for _ in 0..<100 where model.state != .loaded(three) { await Task.yield() }
        XCTAssertEqual(model.state, .loaded(three))
        model.leave()
        WatchPushes.shared.deliver(WatchPush(.needsYou, holds: WatchHolds(two), at: now + 20))
        XCTAssertEqual(model.state, .loading)
        XCTAssertEqual(phone.asked.count, 2, "put away: nothing read")
    }

    /// Row 28: once the phone answers that it opened the held call, "Opened on your phone" shows on that call for a moment;
    /// not reached, nothing is said and the button stays.
    func testApproveOnYourPhoneSaysItOpened() async {
        WatchOpenedNotice.shared.reset()
        defer { WatchOpenedNotice.shared.reset() }
        let phone = Phone(nil)
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: Unlocked())
        await model.approveOnPhone("ce_1")
        XCTAssertNil(WatchOpenedNotice.shared.key, "not reached: nothing said")
        phone.reply = WatchReply(.notYet)
        await model.approveOnPhone("ce_1")
        XCTAssertNil(WatchOpenedNotice.shared.key, "an older phone: nothing said")
        phone.reply = WatchReply(.ok)
        await model.approveOnPhone("ce_1")
        XCTAssertEqual(WatchOpenedNotice.shared.key, "ce_1", "on that call")
    }

    func testThePhoneOutOfReachIsSaidAsSuch() async {
        let model = NeedsYouModel(phone: Phone(nil), clock: { self.now }, lock: Unlocked())
        await model.load()
        XCTAssertEqual(model.state, .unreachable(.notReachable))
    }

    func testSignedOutShowsNothingOfTheAccount() async {
        let phone = Phone(WatchReply(.ok, needsYou: page))
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: Unlocked())
        await model.load()
        phone.reply = WatchReply(.signedOut)
        await model.load()
        XCTAssertEqual(model.state, .signedOut, "what was read goes with the sign-out")
    }

    func testAFailureIsThePhonesWords() async {
        let model = NeedsYouModel(phone: Phone(WatchReply(.failed, line: "tafs couldn't reach KIFF.")), clock: { self.now }, lock: Unlocked())
        await model.load()
        XCTAssertEqual(model.state, .failed("tafs couldn't reach KIFF."))
    }

    func testWhatWasReadGoesWhenTheAppLeavesTheScreen() async {
        let model = NeedsYouModel(phone: Phone(WatchReply(.ok, needsYou: page)), clock: { self.now }, lock: Unlocked())
        await model.load()
        model.leave()
        XCTAssertEqual(model.state, .loading)
    }

    func testRefusedShowsOnlyOnceThePhoneSaysSo() async {
        let phone = Phone(WatchReply(.ok, needsYou: page))
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: Unlocked())
        await model.load()
        phone.reply = WatchReply(.ok, line: "Refused. Nothing was sent.")
        await model.refuse("ce_1")
        XCTAssertEqual(phone.asked.last, WatchRequest(.refuse, made: now, hold: "ce_1"))
        XCTAssertEqual(model.answers["ce_1"], .done("Refused. Nothing was sent."))
    }

    func testARefuseThatDidntGoSaysNotRefusedAndWhy() async {
        let phone = Phone(WatchReply(.failed, line: "KIFF didn't take the refusal."))
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: Unlocked())
        await model.refuse("ce_1")
        XCTAssertEqual(model.answers["ce_1"], .failed(Words.Alert.notRefused + "\nKIFF didn't take the refusal."))
        phone.reply = nil
        await model.refuse("ce_1")
        XCTAssertEqual(model.answers["ce_1"], .failed(Words.Alert.notRefused + "\n" + Words.Watch.phoneNotReachable))
    }

    func testAlreadyAnsweredIsSaid() async {
        let model = NeedsYouModel(phone: Phone(WatchReply(.answered, line: "Already answered, or it ended.")), clock: { self.now }, lock: Unlocked())
        await model.refuse("ce_1")
        XCTAssertEqual(model.answers["ce_1"], .done("Already answered, or it ended."))
    }

    func testTheWatchNeverAsksToApprove() {
        // The contract has no approval to ask for: only these six kinds decode on either side.
        let kinds = ["needs_you", "tafs", "refuse", "halt", "halt_all", "coach"]
        for kind in kinds {
            XCTAssertNotNil(WatchRequest.Kind(rawValue: kind), kind)
        }
        for kind in ["approve", "approve_once", "extra_room", "install"] {
            XCTAssertNil(WatchRequest.Kind(rawValue: kind), kind)
        }
    }

    /// A watch with no passcode can be anyone's wrist: Refuse asks nothing of the phone and says
    /// to set a screen lock, as on Wear OS.
    func testWithNoPasscodeRefuseSendsNothing() async {
        let phone = Phone(WatchReply(.ok, line: "Refused. Nothing was sent."))
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: NoPasscode())
        await model.refuse("ce_1")
        XCTAssertTrue(phone.asked.isEmpty, "nothing reaches the phone")
        XCTAssertEqual(model.answers["ce_1"], .failed(Words.Watch.needsLock))
    }

    // MARK: Your brief (S2), behind a switch that's off

    private var wristBrief: WatchBrief {
        WatchBrief(title: "Your brief", when: "Morning brief · 08:00", urgent: "Urgent",
                   decisions: [.init(id: "hold:ce_1", question: "Refund order 4502, €480.00?", taf: "Rae", deadline: "by 10:51",
                                     urgent: true, cantWait: nil, refKind: "hold", refID: "ce_1", holdID: "ce_1"),
                               .init(id: "hold:ce_9", question: "Refund order 4400, €90.00?", taf: "Rae", deadline: nil,
                                     urgent: false, cantWait: nil, refKind: "hold", refID: "ce_9", holdID: "ce_9")],
                   more: nil, done: nil, doneCases: [], rest: nil)
    }

    /// A phone that answers each kind as it's asked.
    private final class Phones: PhoneAsking {
        var replies: [WatchRequest.Kind: WatchReply] = [:]
        var asked: [WatchRequest.Kind] = []
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request.kind)
            return replies[request.kind]
        }
    }

    func testTheBriefIsOffAndOffChangesNothing() async {
        XCTAssertFalse(NeedsYouModel.briefOnWatch, "built and switched off, until the owner's yes")
        let phone = Phones()
        phone.replies = [.needsYou: WatchReply(.ok, needsYou: page), .brief: WatchReply(.ok, brief: wristBrief)]
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: Unlocked())
        await model.load()
        XCTAssertEqual(phone.asked, [.needsYou], "off: the watch asks what it always has")
        XCTAssertNil(model.brief)
        XCTAssertEqual(model.state, .loaded(page))
    }

    func testOnTheBriefIsReadAfterNeedsYouAndRefuseIsOfferedOnlyWhereNeedsYouListsTheCall() async {
        let phone = Phones()
        phone.replies = [.needsYou: WatchReply(.ok, needsYou: page), .brief: WatchReply(.ok, brief: wristBrief)]
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: Unlocked(), briefOn: true)
        await model.load()
        XCTAssertEqual(phone.asked, [.needsYou, .brief])
        XCTAssertEqual(model.brief, wristBrief)
        XCTAssertTrue(model.refusable(wristBrief.decisions[0]), "Needs you lists ce_1")
        XCTAssertFalse(model.refusable(wristBrief.decisions[1]), "Needs you doesn't list ce_9: Approve on your phone only")
        XCTAssertEqual(model.beyondBrief, 2, "ce_1 is the brief's; Needs you's 2 others aren't held calls the brief names")
        model.leave()
        XCTAssertNil(model.brief, "kept in memory only")
    }

    /// Matched by id, never by number: Needs you holds A and B, the brief names held call C (answered meanwhile)
    /// and a step. A and B stay counted, so neither is out of reach.
    func testBeyondTheBriefIsMatchedByIdNeverByNumber() async {
        let call = { (id: String) in WatchNeedsYou.Call(id: id, taf: "Rae", what: "Refund", reason: "Over €150.00 each", answerBy: "10:51") }
        let needsYou = WatchNeedsYou(count: 2, calls: [call("A"), call("B")], at: now)
        let brief = WatchBrief(title: "Your brief", when: nil, urgent: nil,
                               decisions: [.init(id: "hold:C", question: "C?", taf: nil, deadline: nil, urgent: false, cantWait: nil,
                                                 refKind: "hold", refID: "C", holdID: "C"),
                                           .init(id: "step:s", question: "S?", taf: nil, deadline: nil, urgent: false, cantWait: nil,
                                                 refKind: "step", refID: "s", holdID: nil)],
                               more: nil, done: nil, doneCases: [], rest: nil)
        let phone = Phones()
        phone.replies = [.needsYou: WatchReply(.ok, needsYou: needsYou), .brief: WatchReply(.ok, brief: brief)]
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: Unlocked(), briefOn: true)
        await model.load()
        XCTAssertEqual(model.beyondBrief, 2, "A and B, which the brief doesn't name")
        XCTAssertFalse(model.refusable(brief.decisions[0]), "C isn't in Needs you's read: no Refuse")
    }

    func testOnWithNoBriefOrAnOlderPhoneNeedsYouStays() async {
        let phone = Phones()
        phone.replies = [.needsYou: WatchReply(.ok, needsYou: page), .brief: WatchReply(.ok)]
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: Unlocked(), briefOn: true)
        await model.load()
        XCTAssertNil(model.brief)
        XCTAssertEqual(model.state, .loaded(page))
        phone.replies[.brief] = WatchReply(.refused)
        await model.load()
        XCTAssertNil(model.brief, "a phone that doesn't know the read: Needs you")
        XCTAssertEqual(model.state, .loaded(page))
    }

    func testOnSignedOutShowsNothingOfTheAccount() async {
        let phone = Phones()
        phone.replies = [.needsYou: WatchReply(.ok, needsYou: page), .brief: WatchReply(.signedOut)]
        let model = NeedsYouModel(phone: phone, clock: { self.now }, lock: Unlocked(), briefOn: true)
        await model.load()
        XCTAssertEqual(model.state, .signedOut)
        XCTAssertNil(model.brief)
    }
}
