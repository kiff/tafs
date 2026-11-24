import XCTest
@testable import TafsWatch

/// Watch row 24, step 1: the complication's link opens Coach's home, listening, once per tap, and nothing else does.
@MainActor
final class ComplicationLinkTests: XCTestCase {
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
}
