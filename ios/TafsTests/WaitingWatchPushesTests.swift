import XCTest
@testable import Tafs

/// Row 18 on the iPhone: an Allow on the watch's sign-in ask that launches a killed tafs is made before WatchConnectivity has
/// finished activating. It's held (`WaitingWatchPushes`) and sent once activation completes, rather than dropped.
final class WaitingWatchPushesTests: XCTestCase {
    private let t = Date(timeIntervalSinceReferenceDate: 813_667_500)

    func testAnAllowMadeBeforeActivationIsHeldAndTakenOnce() {
        var waiting = WaitingWatchPushes()
        let allow = WatchPush(.signIn, signIn: WatchSignInAnswer(id: "w_0000000a", allowed: true), at: t)
        waiting.add(allow)
        XCTAssertEqual(waiting.take(), [allow])
        XCTAssertEqual(waiting.take(), [], "taken once: activation sends it, and nothing is sent twice")
    }

    func testTheNewestOfEachKindRequestAndTafIsKept() {
        var waiting = WaitingWatchPushes()
        let deny = WatchPush(.signIn, signIn: WatchSignInAnswer(id: "w_0000000a", allowed: false), at: t)
        let allow = WatchPush(.signIn, signIn: WatchSignInAnswer(id: "w_0000000b", allowed: true), at: t + 5)
        waiting.add(deny)
        waiting.add(allow)
        // An older one arriving late never replaces the newer.
        waiting.add(deny)
        let chatA = WatchPush(.chat, taf: "rae", at: t)
        let chatB = WatchPush(.chat, taf: "max", at: t)
        waiting.add(chatA)
        waiting.add(chatB)
        XCTAssertEqual(waiting.take(), [allow, chatA, chatB])
    }

    func testAtMostSixteenAreHeldTheOldestGoingFirst() {
        var waiting = WaitingWatchPushes()
        for n in 0..<20 { waiting.add(WatchPush(.chat, taf: "taf\(n)", at: t + Double(n))) }
        let held = waiting.take()
        XCTAssertEqual(held.count, WaitingWatchPushes.most)
        XCTAssertEqual(held.first?.taf, "taf4")
        XCTAssertEqual(held.last?.taf, "taf19")
    }
}
