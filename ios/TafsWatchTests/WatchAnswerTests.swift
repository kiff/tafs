import XCTest
@testable import TafsWatch

/// A Refuse or a Halt from the wrist, and what came of it: a failure the phone sends no words for names where it went. A
/// Halt goes to tafs cloud and a Refuse to KIFF.
final class WatchAnswerTests: XCTestCase {
    func testAHaltWithNoWordsSaysThePhoneCouldntReachTafsCloud() {
        XCTAssertEqual(WatchAnswer.from(WatchReply(.failed)), .failed(Words.Watch.phoneCouldNotReachCloud))
        XCTAssertEqual(WatchAnswer.from(PhoneReach.reply(WatchReply(.failed))), .failed(Words.Watch.phoneCouldNotReachCloud))
    }

    func testARefuseWithNoWordsSaysKiffWasntReached() {
        let answer = WatchAnswer.from(WatchReply(.failed), notDone: Words.Alert.notRefused, unreached: Words.Alert.tafsCouldNotReachKiff)
        XCTAssertEqual(answer, .failed(Words.Alert.notRefused + "\n" + Words.Alert.tafsCouldNotReachKiff))
    }

    /// The phone's own words always win over the fallback.
    func testThePhonesWordsAreShownAsSent() {
        XCTAssertEqual(WatchAnswer.from(WatchReply(.failed, line: "Halt didn't go.")), .failed("Halt didn't go."))
        XCTAssertEqual(WatchAnswer.from(WatchReply(.ok, line: "Halted.")), .done("Halted."))
    }
}
