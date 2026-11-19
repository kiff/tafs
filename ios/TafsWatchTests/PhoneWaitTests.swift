import XCTest
@testable import TafsWatch

/// A phone ask always ends (mac-air's simulator test, 19 Nov): with the phone's reply, its error, or the limit.
@MainActor
final class PhoneWaitTests: XCTestCase {
    /// WatchConnectivity neither replies nor fails: past the limit the ask ends with nothing, so the watch says the phone
    /// isn't in reach instead of waiting with no words.
    func testAnAskWithNoReplyAndNoErrorEndsAtTheLimit() async {
        let started = Date()
        let answer: String? = await PhoneWait.first(within: 0.2) { _ in }
        XCTAssertNil(answer)
        XCTAssertLessThan(Date().timeIntervalSince(started), 5)
    }

    /// A reply before the limit is the answer, and the limit firing later changes nothing.
    func testTheReplyBeforeTheLimitIsTheAnswer() async {
        let answer: String? = await PhoneWait.first(within: 0.3) { reply in reply("ok") }
        XCTAssertEqual(answer, "ok")
        try? await Task.sleep(nanoseconds: 500_000_000)
    }

    /// A reply and an error both coming: only the first is taken.
    func testOnlyTheFirstAnswerIsTaken() async {
        let answer: String? = await PhoneWait.first(within: 1) { reply in
            reply("first")
            reply(nil)
            reply("third")
        }
        XCTAssertEqual(answer, "first")
    }

    func testTheLimitIsLongerThanAnAskTakesAndShortOnAWrist() {
        XCTAssertEqual(PhoneWait.limit, 20)
    }
}
