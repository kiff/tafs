import XCTest
@testable import TafsWatch

/// Watch v2's pages (docs/watch-app.md, "Version 2"): behind a switch that's off, Coach first, Brief only with its own
/// switch, the Inbox's count from what the phone read, and Halt in the menu only while a taf runs.
@MainActor
final class WatchPagesTests: XCTestCase {
    private let now = Date(timeIntervalSinceReferenceDate: 813_667_500)

    /// On since 19 Nov, every step in and Sign out's privacy text live.
    func testVersionTwoIsOn() {
        XCTAssertTrue(WatchV2.on)
    }

    /// Coach is home, then the Inbox, Your tafs and Account; Brief comes after Your tafs only with its switch on.
    func testThePagesInOrderWithBriefOnlyWithItsSwitch() {
        XCTAssertEqual(WatchPage.pages(briefOn: false), [.coach, .inbox, .tafs, .account])
        XCTAssertEqual(WatchPage.pages(briefOn: true), [.coach, .inbox, .tafs, .brief, .account])
        XCTAssertEqual(WatchPage.pages(), WatchPage.pages(briefOn: NeedsYouModel.briefOnWatch))
        XCTAssertEqual(WatchPage.pages().first, .coach)
    }

    /// Each page by its name: the phone's own word where the phone has the page.
    func testEachPageIsNamedAsThePhoneNamesIt() {
        XCTAssertEqual(WatchPage.coach.title, Words.Coach.title)
        XCTAssertEqual(WatchPage.inbox.title, Words.Watch.Page.inbox)
        XCTAssertEqual(WatchPage.tafs.title, Words.Home.title)
        XCTAssertEqual(WatchPage.brief.title, Words.Watch.Page.brief)
        XCTAssertEqual(WatchPage.account.title, Words.Settings.title)
        XCTAssertEqual(Set(WatchPage.allCases.map(\.title)).count, WatchPage.allCases.count, "no two pages share a name")
    }

    /// The Inbox's count on Coach's home is the held calls the phone read; no line before the phone answers, and none
    /// while nothing waits (as on Wear OS and in the mockup): never "0 unread".
    func testTheInboxCountIsWhatThePhoneRead() {
        let call = WatchNeedsYou.Call(id: "ce_1", taf: "Rae", what: "Refund order 4502", reason: "Over the limit", answerBy: "10:51")
        XCTAssertEqual(WatchPage.unread(.loaded(WatchNeedsYou(count: 3, calls: [call, call], at: now))), 2,
                       "the calls the Inbox shows, not steps answered on the phone")
        XCTAssertNil(WatchPage.unread(.loaded(WatchNeedsYou(count: 0, calls: [], at: now))), "nothing waits: no line")
        XCTAssertNil(WatchPage.unread(.loading))
        XCTAssertNil(WatchPage.unread(.unreachable))
        XCTAssertNil(WatchPage.unread(.signedOut))
        XCTAssertNil(WatchPage.unread(.failed("Couldn't reach KIFF")))
    }

    /// With the Inbox kept (step iv), Coach's home counts its unread lines, and no line with none; with no passcode, the calls
    /// the phone read now, as before.
    func testTheCountIsTheInboxsUnreadWhenItsKept() {
        let call = WatchNeedsYou.Call(id: "ce_1", taf: "Rae", what: "Refund order 4502", reason: "Over the limit", answerBy: "10:51")
        let state = NeedsYouModel.State.loaded(WatchNeedsYou(count: 1, calls: [call], at: now))
        XCTAssertEqual(WatchPage.unread(state, inboxKept: true, unreadLines: 3), 3)
        XCTAssertNil(WatchPage.unread(state, inboxKept: true, unreadLines: 0), "all read: no line")
        XCTAssertEqual(WatchPage.unread(state, inboxKept: false, unreadLines: 3), 1, "not kept: what the phone answered now")
    }

    /// Halt is in the menu only while a taf the phone listed still runs; before the read, or with all halted, it isn't.
    func testTheMenuOffersHaltOnlyWhileATafRuns() {
        let running = WatchTaf(id: "depl_1", name: "Rae", line: "Ready", halted: false)
        let halted = WatchTaf(id: "depl_2", name: "Ivo", line: "Halted", halted: true)
        XCTAssertTrue(WatchPage.offersHalt(.loaded([running, halted])))
        XCTAssertFalse(WatchPage.offersHalt(.loaded([halted])))
        XCTAssertFalse(WatchPage.offersHalt(.loaded([])))
        XCTAssertFalse(WatchPage.offersHalt(.loading))
        XCTAssertFalse(WatchPage.offersHalt(.signedOut))
    }
}
