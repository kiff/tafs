import XCTest
@testable import Tafs

/// How the repeating reads slow down and spread (docs/scale.md): a chat or Setup read slower as the reads go on, a list read while its
/// screen is open slower while nothing changes, every wait up to a fifth shorter and never longer.
final class PollPaceTests: XCTestCase {
    func testAWaitIsUpToAFifthShorterNeverLonger() {
        XCTAssertEqual(PollPace.jittered(1_000, unit: 1.0), 1_000)
        XCTAssertEqual(PollPace.jittered(1_000, unit: 0.8), 800)
        XCTAssertEqual(PollPace.jittered(1_000, unit: 1.7), 1_000, "never longer")
        XCTAssertEqual(PollPace.jittered(1_000, unit: 0.1), 800, "never shorter than four fifths")
        for _ in 0..<200 {
            let wait = PollPace.jittered(4_000_000_000)
            XCTAssertTrue((3_200_000_000...4_000_000_000).contains(wait))
        }
    }

    /// Following a run with no stream (Android 1.22): 2.5 s for the first minute, 5 s to the third, 10 s to the sixth, then 15 s.
    func testFollowingARunSlowsDownOverTime() {
        let seconds: [TimeInterval] = [0, 59, 60, 179, 180, 359, 360, 1_800]
        let waits: [UInt64] = seconds.map { PollPace.followDelay(after: $0) }
        let expected: [UInt64] = [2_500_000_000, 2_500_000_000, 5_000_000_000, 5_000_000_000, 10_000_000_000, 10_000_000_000, 15_000_000_000, 15_000_000_000]
        XCTAssertEqual(waits, expected)
        let scaled: UInt64 = PollPace.followDelay(after: 0, scale: 0.001)
        XCTAssertEqual(scaled, 2_500_000, "tests shorten it")
    }

    /// A card waiting on its grant keeps Setup's base pace past the budget: it isn't slowed to 8×.
    func testACardWaitingOnItsGrantKeepsTheBasePace() {
        XCTAssertEqual(PollPace.setupWait(base: 3, afterReads: 70, cardWaits: true), 3)
        XCTAssertEqual(PollPace.setupWait(base: 3, afterReads: 70, cardWaits: false), 24)
        XCTAssertEqual(PollPace.setupWait(base: 1, afterReads: 0, cardWaits: false), 1)
    }

    func testSetupNeverReadsEverySecondForLong() {
        let reads: [Int] = [0, 9, 10, 29, 30, 59, 60, 1_000]
        let factors: [UInt64] = reads.map { PollPace.setupFactor(afterReads: $0) }
        let expected: [UInt64] = [1, 1, 2, 2, 4, 4, 8, 8]
        XCTAssertEqual(factors, expected)
    }

    func testAListReadWhileOpenWaitsLongerWhileNothingChangesAndStartsOverOnAChange() {
        let base: UInt64 = 60, cap: UInt64 = 300
        var wait = base
        var seen: [UInt64] = []
        for _ in 0..<5 { wait = PollPace.quietWait(after: wait, changed: false, base: base, cap: cap); seen.append(wait) }
        XCTAssertEqual(seen, [120, 240, 300, 300, 300], "doubles up to the cap")
        XCTAssertEqual(PollPace.quietWait(after: 300, changed: true, base: base, cap: cap), 60, "a change starts over")
    }

    /// The People screen reads only while the app is in front, and slows down while nothing changes (the 20 s loop with no end is gone).
    func testThePeopleScreenNoLongerPollsEveryTwentySecondsForever() throws {
        let source = (try? String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Tafs/Views/PeopleViews.swift"), encoding: .utf8)) ?? ""
        XCTAssertFalse(source.contains("20_000_000_000"))
        XCTAssertTrue(source.contains(".task(id: scenePhase)"))
        XCTAssertTrue(source.contains("guard scenePhase == .active else { return }"))
        XCTAssertTrue(source.contains("PollPace.quietWait"))
    }

    /// Coming back to the app reads a chat's thread once, at once, so a run that ended while the phone was away shows now.
    func testComingBackToTheAppReadsTheThreadAtOnce() throws {
        for (file, call) in [("Tafs/Views/TafChatView.swift", "await model.loadChat(tafID)"), ("Tafs/Views/SetupChatView.swift", "await model.loadSetupThread()")] {
            let source = (try? String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
                .appendingPathComponent(file), encoding: .utf8)) ?? ""
            XCTAssertTrue(source.contains(".onChange(of: scenePhase)"), file)
            XCTAssertTrue(source.contains("if phase == .active { Task { \(call) } }"), file)
        }
    }
}
