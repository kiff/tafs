import XCTest
@testable import TafsWatch

/// Watch row 24, step 1: the complication's link opens Coach's home, listening, once per tap, and nothing else does.
@MainActor
final class ComplicationLinkTests: XCTestCase {
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

    func testOnlyTheComplicationsOwnLinkIsTaken() {
        XCTAssertTrue(ComplicationLink.isAsk(ComplicationLink.ask))
        XCTAssertTrue(ComplicationLink.isAsk(URL(string: "tafs-watch://ask/")!))
        for other in ["tafs-watch://halt", "tafs-watch://ask/now", "tafs-watch://ask?words=refund", "https://ask", "tafs://ask"] {
            XCTAssertFalse(ComplicationLink.isAsk(URL(string: other)!), other)
        }
    }

    /// Each tap is counted, so Coach's home listens once per tap. Nothing is sent: the person still reviews and sends.
    func testEachTapAsksOnceAndSendsNothing() {
        final class Phone: PhoneAsking {
            var asked: [WatchRequest] = []
            func ask(_ request: WatchRequest) async -> WatchReply? { asked.append(request); return nil }
        }
        let phone = Phone()
        let coach = CoachModel(phone: phone, lock: Unlocked())
        coach.askFromComplication()
        coach.askFromComplication()
        XCTAssertEqual(coach.askRequests, 2)
        XCTAssertTrue(phone.asked.isEmpty)
        XCTAssertEqual(coach.state, .writing)
    }

    /// A tap that opens the app cold is kept until Coach's home appears, and is taken once: listening starts once per tap,
    /// never again on the next appearance.
    func testAPendingTapIsTakenOnce() {
        final class Phone: PhoneAsking {
            func ask(_ request: WatchRequest) async -> WatchReply? { nil }
        }
        let coach = CoachModel(phone: Phone(), lock: Unlocked())
        XCTAssertFalse(coach.takeAskPending(), "no tap, nothing to take")
        coach.askFromComplication()
        XCTAssertTrue(coach.askPending)
        XCTAssertTrue(coach.takeAskPending())
        XCTAssertFalse(coach.askPending)
        XCTAssertFalse(coach.takeAskPending(), "taken once")
        coach.askFromComplication()
        XCTAssertTrue(coach.takeAskPending(), "the next tap is its own")
    }
}
