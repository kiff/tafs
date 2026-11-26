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
