import XCTest
@testable import TafsWatch

/// v2's Sign out on this watch (docs/watch-app.md, "Sign out on this watch"): the Inbox goes, one flag is kept across restarts,
/// and Sign in with your phone takes it away.
@MainActor
final class WatchSignInTests: XCTestCase {
    private let now = Date(timeIntervalSinceReferenceDate: 813_667_500)

    /// The flag in memory, as a restart reads it.
    private final class Flag: WatchSignInStore {
        var on = false
        func signedOut() -> Bool { on }
        func set(_ signedOut: Bool) { on = signedOut }
    }

    /// The Inbox's store in memory.
    private final class Kept: InboxStore {
        var kept: [InboxLine] = []
        var gone: Set<String> = []
        func lines() -> [InboxLine] { kept }
        func write(_ lines: [InboxLine]) { kept = lines }
        func dismissed() -> Set<String> { gone }
        func writeDismissed(_ ids: Set<String>) { gone = ids }
        func clear() { kept = []; gone = [] }
    }

    private func inbox(_ store: Kept) -> InboxModel {
        let inbox = InboxModel(store: store, lock: Unlocked(), clock: { self.now })
        let call = WatchNeedsYou.Call(id: "hold_1", taf: "Rae", what: "Refund order 4502", summary: "Refund order 4502",
                                      reason: "Over the limit", answerBy: "10:51")
        inbox.heard(.loaded(WatchNeedsYou(count: 1, calls: [call], at: now)))
        return inbox
    }

    func testSignOutForgetsTheInboxAndKeepsOnlyTheFlag() {
        let flag = Flag()
        let kept = Kept()
        let inbox = inbox(kept)
        XCTAssertFalse(inbox.lines.isEmpty)
        let signIn = WatchSignIn(store: flag)
        XCTAssertFalse(signIn.signedOutHere)
        signIn.signOut(inbox: inbox)
        XCTAssertTrue(signIn.signedOutHere)
        XCTAssertTrue(inbox.lines.isEmpty)
        XCTAssertTrue(kept.kept.isEmpty, "nothing of the Inbox stays on the watch")
        XCTAssertTrue(kept.gone.isEmpty, "the ids taken away go too")
        XCTAssertTrue(WatchSignIn(store: flag).signedOutHere, "kept across restarts")
    }

    /// Signed out here, the phone's pushes keep coming (it isn't told), and none is taken: a held call isn't put in the Inbox
    /// or its file, and nothing is counted. An account change is still heard, since it can only wipe. Signed in again with the
    /// phone, the next push comes in.
    func testSignedOutHereThePhonesPushesKeepNothing() {
        let flag = Flag()
        let kept = Kept()
        let inbox = InboxModel(store: kept, lock: Unlocked(), clock: { self.now },
                               mayDraw: { WatchSignIn.takesData(signedOutHere: flag.on) })
        let signIn = WatchSignIn(store: flag)
        signIn.signOut(inbox: inbox)
        let call = WatchNeedsYou.Call(id: "hold_2", taf: "Rae", what: "Refund order 4503, €90.00", summary: "Refund order 4503",
                                      reason: "Over the limit", answerBy: "10:55")
        let holds = WatchHolds(WatchNeedsYou(count: 1, calls: [call], at: now))
        WatchPushes.shared.deliver(WatchPush(.needsYou, holds: holds, at: now))
        XCTAssertEqual(inbox.lines, [])
        XCTAssertEqual(kept.kept, [], "nothing kept")
        XCTAssertNil(inbox.waiting)
        XCTAssertFalse(WatchSignIn.takesData(signedOutHere: true), "the phone's data pushes are dropped (`PhoneLink`)")
        XCTAssertTrue(flag.on, "and the watch stays signed out")
        signIn.signIn()
        WatchPushes.shared.deliver(WatchPush(.needsYou, holds: holds, at: now))
        XCTAssertEqual(inbox.lines.map(\.id), ["held:hold_2"], "signed in again here: it comes in")
    }

    /// A phone that takes each ask, as the real one does once it has asked its owner.
    private final class AskedPhone: PhoneAsking {
        var asked: [WatchRequest] = []
        var reply: WatchReply? = WatchReply(.ok)
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            return reply
        }
    }

    /// Row 18: signing this watch in again needs the phone owner's Allow. The ask carries a one-time id and nothing of the
    /// account; the watch waits, still signed out. A Deny, an answer for another id, an Allow past ten minutes, or one with no
    /// mark the phone makes, leaves it signed out. Only the Allow for its own id signs it in, under the phone's mark, with an
    /// empty Inbox.
    func testSigningInAgainNeedsThePhonesAllow() async {
        final class Clock { var now = Date(timeIntervalSinceReferenceDate: 813_667_500) }
        let clock = Clock()
        let flag = Flag()
        let kept = Kept()
        let inbox = InboxModel(store: kept, lock: Unlocked(), clock: { clock.now })
        let phone = AskedPhone()
        final class Marks { var heard: [String] = [] }
        let marks = Marks()
        let signIn = WatchSignIn(store: flag, phone: phone, clock: { clock.now }, account: { mark, _ in marks.heard.append(mark) })
        signIn.signOut(inbox: inbox)
        let mark = String(repeating: "a", count: 32)

        await signIn.askPhone()
        XCTAssertEqual(phone.asked.count, 1)
        let first = phone.asked[0]
        XCTAssertEqual(first.kind, .signInWatch)
        XCTAssertTrue(WatchVoice.isID(first.message), "a one-time id")
        XCTAssertNil(first.mark, "nothing of the account goes")
        XCTAssertNil(first.text)
        XCTAssertTrue(signIn.signedOutHere, "asking signs nothing in")
        XCTAssertEqual(signIn.asking, .waiting)

        // Pushes and account changes the phone sends meanwhile: still signed out.
        WatchPushes.shared.deliver(WatchPush(.account, at: clock.now, mark: mark))
        WatchPushes.shared.deliver(WatchPush(.inbox, reply: WatchReply(.ok, marks: WatchInboxMarks()), at: clock.now, mark: mark))
        XCTAssertTrue(signIn.signedOutHere)

        // Another id's Allow: nothing.
        WatchPushes.shared.deliver(WatchPush(.signIn, signIn: WatchSignInAnswer(id: UUID().uuidString.lowercased(), allowed: true),
                                             at: clock.now, mark: mark))
        XCTAssertTrue(signIn.signedOutHere)
        XCTAssertEqual(signIn.asking, .waiting)

        // Deny: still signed out, and it says so.
        WatchPushes.shared.deliver(WatchPush(.signIn, signIn: WatchSignInAnswer(id: first.message!, allowed: false), at: clock.now, mark: mark))
        XCTAssertTrue(signIn.signedOutHere)
        XCTAssertEqual(signIn.asking, .denied)
        // The same id's Allow after a Deny: that ask is over.
        WatchPushes.shared.deliver(WatchPush(.signIn, signIn: WatchSignInAnswer(id: first.message!, allowed: true), at: clock.now, mark: mark))
        XCTAssertTrue(signIn.signedOutHere)

        // An Allow past ten minutes: the ask has lapsed.
        await signIn.askPhone()
        let second = phone.asked[1].message!
        XCTAssertNotEqual(second, first.message)
        clock.now += WatchSignIn.answerWithin + 1
        WatchPushes.shared.deliver(WatchPush(.signIn, signIn: WatchSignInAnswer(id: second, allowed: true), at: clock.now, mark: mark))
        XCTAssertTrue(signIn.signedOutHere)

        // An Allow with no mark the phone makes: nothing.
        await signIn.askPhone()
        let third = phone.asked[2].message!
        WatchPushes.shared.deliver(WatchPush(.signIn, signIn: WatchSignInAnswer(id: third, allowed: true), at: clock.now, mark: "not a mark"))
        XCTAssertTrue(signIn.signedOutHere)
        XCTAssertTrue(marks.heard.isEmpty)

        // The Allow for its own id, in time: signed in, under the phone's mark, with nothing kept from before.
        await signIn.askPhone()
        let fourth = phone.asked[3].message!
        clock.now += 60
        WatchPushes.shared.deliver(WatchPush(.signIn, signIn: WatchSignInAnswer(id: fourth, allowed: true), at: clock.now, mark: mark))
        XCTAssertFalse(signIn.signedOutHere)
        XCTAssertFalse(flag.on)
        XCTAssertEqual(signIn.asking, .idle)
        XCTAssertEqual(marks.heard, [mark], "the phone's mark is heard, so another account's wipes first")
        XCTAssertEqual(inbox.lines, [])
        XCTAssertEqual(kept.kept, [], "an empty start")
    }

    /// The phone out of reach, signed out, or from before the ask: the watch says so and stays signed out; no Allow can come
    /// for an ask the phone didn't take.
    func testAnAskThePhoneDidntTakeLeavesItSignedOut() async {
        let flag = Flag()
        flag.on = true
        let phone = AskedPhone()
        let signIn = WatchSignIn(store: flag, phone: phone, clock: { self.now })
        phone.reply = nil
        await signIn.askPhone()
        XCTAssertEqual(signIn.asking, .unreached(PhoneFailure.notReachable.waiting))
        phone.reply = WatchReply(.signedOut)
        await signIn.askPhone()
        XCTAssertEqual(signIn.asking, .unreached(Words.Watch.signInOnPhone))
        phone.reply = WatchReply(.notYet)
        await signIn.askPhone()
        let id = phone.asked.last!.message!
        XCTAssertEqual(signIn.asking, .unreached(Words.Watch.phoneCouldNotReachCloud))
        WatchPushes.shared.deliver(WatchPush(.signIn, signIn: WatchSignInAnswer(id: id, allowed: true), at: now,
                                             mark: String(repeating: "a", count: 32)))
        XCTAssertTrue(signIn.signedOutHere, "no ask in flight: nothing signs it in")
        XCTAssertTrue(WatchRequest(.signInWatch, made: now, message: id).isComplete)
        XCTAssertFalse(WatchRequest(.signInWatch, made: now, message: "not an id").isComplete)
        XCTAssertFalse(WatchRequest.Kind.signInWatch.isRead, "never sent twice")
        // The phone's notifications are off: it says so, in its words, and nothing waits.
        phone.reply = WatchReply(.failed, line: "Allow notifications for tafs in Settings, then try again.")
        await signIn.askPhone()
        XCTAssertEqual(signIn.asking, .unreached("Allow notifications for tafs in Settings, then try again."))
    }

    /// An ask no one answers (the question missed, or the phone put down) lapses after `answerWithin`: the wait goes and the
    /// button is back (it's there while waiting too), and an Allow after that is ignored. Asking again sends a new id.
    func testAnUnansweredAskLapses() async {
        final class Clock { var now = Date(timeIntervalSinceReferenceDate: 813_667_500) }
        let clock = Clock()
        let flag = Flag()
        flag.on = true
        let phone = AskedPhone()
        let signIn = WatchSignIn(store: flag, phone: phone, clock: { clock.now },
                                 sleep: { _ in try? await Task.sleep(nanoseconds: 3_600_000_000_000) })
        await signIn.askPhone()
        let first = phone.asked[0].message!
        XCTAssertEqual(signIn.asking, .waiting)
        clock.now += 60
        signIn.lapse()
        XCTAssertEqual(signIn.asking, .waiting, "not yet")
        clock.now += WatchSignIn.answerWithin
        signIn.lapse()
        XCTAssertEqual(signIn.asking, .idle, "lapsed: the wait goes")
        WatchPushes.shared.deliver(WatchPush(.signIn, signIn: WatchSignInAnswer(id: first, allowed: true), at: clock.now,
                                             mark: String(repeating: "a", count: 32)))
        XCTAssertTrue(signIn.signedOutHere, "a late Allow signs nothing in")
        await signIn.askPhone()
        XCTAssertNotEqual(phone.asked[1].message, first, "asked again with a new id")
        XCTAssertEqual(signIn.asking, .waiting)
    }

    func testSignInWithYourPhoneTakesTheFlagAway() {
        let flag = Flag()
        flag.on = true
        let signIn = WatchSignIn(store: flag)
        XCTAssertTrue(signIn.signedOutHere)
        signIn.signIn()
        XCTAssertFalse(signIn.signedOutHere)
        XCTAssertFalse(flag.on)
        XCTAssertFalse(WatchSignIn(store: flag).signedOutHere)
    }

    /// The flag on disk is an empty file: there or not, and nothing else.
    func testTheFlagIsAnEmptyFile() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("tafs.signed_out_here")
        let store = WatchSignIn.FileStore(url: url)
        XCTAssertFalse(store.signedOut())
        store.set(true)
        XCTAssertTrue(store.signedOut())
        XCTAssertEqual(try Data(contentsOf: url).count, 0)
        store.set(false)
        XCTAssertFalse(store.signedOut())
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }
}
