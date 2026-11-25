import XCTest
@testable import TafsWatch

/// The account wipe (`WatchAccountMark`, as Wear OS's): whose data the watch keeps, by the phone's mark on every reply and
/// push. Another account's mark, or none once one was kept, wipes everything kept before anything is drawn; nothing kept is
/// drawn before this run heard the kept mark live; and one account's kept message is never sent as another's.
@MainActor
final class WatchAccountMarkTests: XCTestCase {
    private let now = Date(timeIntervalSinceReferenceDate: 813_667_500)
    private static let a = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private static let b = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    /// The kept mark, in memory, and how many times the files went.
    private final class Box {
        var mark: String?
        var at: Date?
        var cleared = 0
        init(_ mark: String? = nil) { self.mark = mark }
    }

    private func account(_ box: Box, now: Date? = nil) -> WatchAccountMark {
        let clock = now ?? self.now
        return WatchAccountMark(store: .init(load: { box.mark }, save: { box.mark = $0 }, loadAt: { box.at }, saveAt: { box.at = $0 }),
                                clearStored: { box.cleared += 1 }, now: { clock })
    }

    /// The watch's file for Keep for later, in memory.
    private final class Drafts: DraftStore {
        var kept: CoachModel.Kept?
        init(_ kept: CoachModel.Kept? = nil) { self.kept = kept }
        func read() -> CoachModel.Kept? { kept }
        func write(_ kept: CoachModel.Kept) { self.kept = kept }
        func clear() { kept = nil }
    }

    /// A phone that takes any Send, and says which it was asked.
    private final class Phone: PhoneAsking {
        var asked: [WatchRequest] = []
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            return WatchReply(.ok, ticket: WatchCoachTicket(chat: "taf-setup", session: "s1", sent: "m1"))
        }
    }

    /// The Inbox's file, in memory.
    private final class Store: InboxStore {
        var kept: [InboxLine]
        init(_ kept: [InboxLine]) { self.kept = kept }
        func lines() -> [InboxLine] { kept }
        func write(_ lines: [InboxLine]) { kept = lines }
        func dismissed() -> Set<String> { [] }
        func writeDismissed(_ ids: Set<String>) {}
        func clear() { kept = [] }
    }

    private static let loaded = NeedsYouModel.State.loaded(WatchNeedsYou(count: 0, calls: [], at: Date(timeIntervalSinceReferenceDate: 0)))

    // MARK: The mark

    /// The phone's marks are 32 hex digits; anything else is no mark.
    func testAMarkIsThePhonesOwnShape() {
        XCTAssertTrue(WatchMark.isMark(WatchMark.make()))
        XCTAssertNotEqual(WatchMark.make(), WatchMark.make(), "new at each sign-in")
        XCTAssertFalse(WatchMark.isMark(nil))
        XCTAssertFalse(WatchMark.isMark(""))
        XCTAssertFalse(WatchMark.isMark("t_01"), "never an account's own id")
        XCTAssertFalse(WatchMark.isMark(Self.a.uppercased()))
    }

    /// The first mark this watch hears wipes what it kept from before marks (no one's it can name), once; the same mark
    /// again confirms it and wipes nothing.
    func testTheFirstMarkWipesOnceAndTheSameMarkConfirms() {
        let box = Box()
        let mark = account(box)
        var wiped = 0
        mark.onWipe { wiped += 1 }
        XCTAssertFalse(mark.mayDraw, "nothing kept is drawn before the phone is heard")
        XCTAssertFalse(mark.heard(Self.a))
        XCTAssertEqual(box.mark, Self.a)
        XCTAssertEqual(box.cleared, 1)
        XCTAssertEqual(wiped, 1)
        XCTAssertTrue(mark.confirmed)
        XCTAssertTrue(mark.heard(Self.a))
        XCTAssertEqual(box.cleared, 1, "the same account: nothing goes")
    }

    /// A new run whose kept mark is the phone's: confirmed by a live reply, and what was kept comes in.
    func testAKeptMarkIsConfirmedByALiveReplyOnly() {
        let box = Box(Self.a)
        let mark = account(box)
        var confirmed = 0
        mark.onConfirm { confirmed += 1 }
        XCTAssertTrue(mark.heard(Self.a, live: false), "a push: the same account")
        XCTAssertFalse(mark.mayDraw, "but a push may have waited: it never lets what's kept be drawn")
        XCTAssertEqual(confirmed, 0)
        XCTAssertTrue(mark.heard(Self.a))
        XCTAssertTrue(mark.mayDraw)
        XCTAssertEqual(confirmed, 1)
        mark.heard(Self.a)
        XCTAssertEqual(confirmed, 1, "once per run")
        XCTAssertEqual(box.cleared, 0)
    }

    /// Signed in as A, then B on the phone: everything kept goes before B's answer is used, and B's mark is kept.
    func testAnotherAccountsMarkWipesEverythingFirst() {
        let box = Box(Self.a)
        let mark = account(box)
        mark.heard(Self.a)
        var order: [String] = []
        mark.onWipe { order.append("wipe, kept \(box.mark ?? "none")") }
        XCTAssertFalse(mark.heard(Self.b))
        XCTAssertEqual(order, ["wipe, kept \(Self.a)"], "wiped while A's mark was still the kept one, before B's is")
        XCTAssertEqual(box.mark, Self.b)
        XCTAssertEqual(box.cleared, 1)
        XCTAssertTrue(mark.mayDraw, "B's answer is live: B's from here")
    }

    /// A push or the context with another account's mark wipes too, and doesn't confirm: the next live reply does.
    func testAPushFromAnotherAccountWipesWithoutConfirming() {
        let box = Box(Self.a)
        let mark = account(box)
        mark.heard(Self.a)
        mark.heard(Self.b, live: false)
        XCTAssertEqual(box.cleared, 1)
        XCTAssertEqual(box.mark, Self.b)
        XCTAssertFalse(mark.mayDraw)
        mark.heard(Self.b)
        XCTAssertTrue(mark.mayDraw)
        XCTAssertEqual(box.cleared, 1)
    }

    /// A data push that waited on the watch since before a switch never brings the old account back: with B kept from
    /// the context, a thread's push marked A isn't used, nothing is wiped, and B stays kept.
    func testADataPushFromTheOldAccountIsDroppedNotKept() {
        let box = Box(Self.a)
        let mark = account(box)
        mark.heard(Self.a)
        mark.heardAccount(Self.b, at: now.addingTimeInterval(60))
        XCTAssertEqual(box.mark, Self.b)
        XCTAssertEqual(box.cleared, 1)
        XCTAssertFalse(mark.ours(Self.a), "A's push: dropped")
        XCTAssertEqual(box.mark, Self.b, "and B stays kept")
        XCTAssertEqual(box.cleared, 1, "with no wipe")
        XCTAssertTrue(mark.ours(Self.b))
        XCTAssertFalse(mark.ours(nil), "a push with no mark is no one's")
    }

    /// Account changes are taken in the order the phone made them, whichever WatchConnectivity hands over first: the
    /// context B at t2, then an older push A at t1, leaves B. A live reply counts as the newest word too.
    func testAnOlderAccountChangeIsIgnored() {
        let box = Box(Self.a)
        let mark = account(box, now: now)
        let t1 = now.addingTimeInterval(100)
        let t2 = now.addingTimeInterval(200)
        mark.heardAccount(Self.b, at: t2)
        XCTAssertEqual(box.mark, Self.b)
        mark.heardAccount(Self.a, at: t1)
        XCTAssertEqual(box.mark, Self.b, "the older change is ignored")
        XCTAssertEqual(box.cleared, 1)
        mark.heardAccount(nil, at: t2)
        XCTAssertEqual(box.mark, Self.b, "not newer either")
        // Kept across runs: a new run ignores the same old change.
        let next = account(box, now: now)
        next.heardAccount(Self.a, at: t1)
        XCTAssertEqual(box.mark, Self.b)
        // A sign-out after it applies.
        next.heardAccount(nil, at: t2.addingTimeInterval(1))
        XCTAssertNil(box.mark)
        XCTAssertEqual(box.cleared, 2)
    }

    /// A live reply is the phone's account now: an account change made before it, handled after, is ignored.
    func testAnAccountChangeOlderThanALiveReplyIsIgnored() {
        let box = Box(Self.a)
        let mark = account(box, now: now)
        mark.heard(Self.b)
        XCTAssertEqual(box.mark, Self.b)
        mark.heardAccount(Self.a, at: now.addingTimeInterval(-30))
        XCTAssertEqual(box.mark, Self.b)
        XCTAssertTrue(mark.mayDraw)
    }

    /// No mark once one was kept (the phone signed out, or a reply that doesn't say whose it is) fails closed: everything
    /// goes, and nothing is drawn. With none kept, there's nothing to wipe.
    func testNoMarkAfterOneWasKeptWipes() {
        let box = Box(Self.a)
        let mark = account(box)
        mark.heard(Self.a)
        XCTAssertFalse(mark.heard(nil as String?))
        XCTAssertNil(box.mark)
        XCTAssertEqual(box.cleared, 1)
        XCTAssertFalse(mark.mayDraw)
        mark.heard(reply: WatchReply(.signedOut))
        XCTAssertEqual(box.cleared, 1, "nothing kept, nothing more to wipe")
        mark.heard("not a mark")
        XCTAssertEqual(box.cleared, 1)
        mark.heard(reply: nil)
        XCTAssertFalse(mark.mayDraw, "no reply (the phone not in reach) says nothing")
    }

    /// The reply's own mark is what's heard.
    func testAReplysMarkIsHeard() {
        let box = Box(Self.a)
        let mark = account(box)
        var reply = WatchReply(.ok, tafs: [])
        reply.mark = Self.b
        mark.heard(reply: reply)
        XCTAssertEqual(box.mark, Self.b)
        XCTAssertEqual(box.cleared, 1)
    }

    // MARK: A's kept message is never sent as B

    /// A message kept for later under A: once the phone answers as B, it's deleted unsent, never a turn of B's Coach.
    func testAsKeptMessageIsNeverSentAsB() async {
        let box = Box(Self.a)
        let mark = account(box)
        let drafts = Drafts(CoachModel.Kept(text: "Refund Ana", message: "m-a", ticket: nil, mark: Self.a))
        let phone = Phone()
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), drafts: drafts, pause: {}, linkPause: { _ in },
                               account: mark)
        XCTAssertNil(model.kept, "not drawn before the phone is heard")
        // The phone answers as B. The wipe (the app's) would delete it; this checks the model's own rule too.
        mark.heard(Self.b)
        await model.afterRead(Self.loaded)
        XCTAssertTrue(phone.asked.filter { $0.kind == .coachSend }.isEmpty, "A's words never go as B")
        XCTAssertNil(drafts.kept, "deleted, unsent")
        XCTAssertNil(model.kept)
    }

    /// The same message under A, with the phone answering as A: sent, as before.
    func testAsKeptMessageGoesUnderA() async {
        let box = Box(Self.a)
        let mark = account(box)
        let drafts = Drafts(CoachModel.Kept(text: "Refund Ana", message: "m-a", ticket: nil, mark: Self.a))
        let phone = Phone()
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), drafts: drafts, pause: {}, linkPause: { _ in },
                               account: mark)
        mark.heard(Self.a)
        model.accountConfirmed()
        XCTAssertEqual(model.kept?.text, "Refund Ana", "drawn once the kept mark is heard")
        await model.afterRead(Self.loaded)
        XCTAssertEqual(phone.asked.filter { $0.kind == .coachSend }.map(\.message), ["m-a"])
    }

    /// Before this run heard any mark, a kept message is neither shown nor sent; one kept before marks (none of its own)
    /// is never sent.
    func testAKeptMessageWaitsForTheMarkAndOneWithNoMarkNeverGoes() async {
        let box = Box(Self.a)
        let mark = account(box)
        let drafts = Drafts(CoachModel.Kept(text: "Refund Ana", message: "m-0", ticket: nil))
        let phone = Phone()
        let model = CoachModel(phone: phone, clock: { self.now }, lock: Unlocked(), drafts: drafts, pause: {}, linkPause: { _ in },
                               account: mark)
        await model.sendKept()
        XCTAssertTrue(phone.asked.isEmpty, "not before the phone is heard")
        mark.heard(Self.a)
        model.accountConfirmed()
        XCTAssertNil(model.kept, "no mark of its own: never drawn")
        await model.sendKept()
        XCTAssertTrue(phone.asked.filter { $0.kind == .coachSend }.isEmpty)
    }

    // MARK: Nothing kept is drawn before the mark is heard

    /// Kept threads are read and written only once the mark is confirmed.
    func testThreadsWaitForTheMark() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("threads")
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        var may = true
        let threads = WatchThreads(url: url, lock: Unlocked(), clock: { self.now }, mayDraw: { may })
        let line = WatchChatLine(id: "m1", mine: false, at: now, text: "A's line", more: false)
        threads.keep("coach", [line])
        may = false
        XCTAssertEqual(threads.lines("coach"), [], "not drawn before the phone is heard")
        threads.keep("coach", [])
        may = true
        XCTAssertEqual(threads.lines("coach"), [line], "and not written over meanwhile")
    }

    /// The Inbox's kept lines aren't shown until the mark is confirmed, then come in.
    func testTheInboxWaitsForTheMark() {
        let line = InboxLine(id: InboxLine.heldID("h_1"), kind: .held, title: "Refunds", line: "Refund order 4502", at: now)
        let store = Store([line])
        var may = false
        let inbox = InboxModel(store: store, lock: Unlocked(), clock: { self.now }, mayDraw: { may })
        XCTAssertEqual(inbox.lines, [], "not drawn before the phone is heard")
        XCTAssertEqual(store.kept, [line], "and not thrown away either")
        may = true
        inbox.checkLock()
        XCTAssertEqual(inbox.lines, [line])
    }
}
