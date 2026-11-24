import XCTest
@testable import Tafs

/// Sign-out takes this device's push token back from tafs cloud, tried three times; when every try fails it stops and asks
/// (`AppModel.takeBackPush`, `alertsStillOn`), so the device never goes on getting a signed-out account's alerts silently.
@MainActor
final class PushTakeBackTests: XCTestCase {
    private struct Down: LocalizedError { var errorDescription: String? { "The network is down." } }

    private func takeBack(failures: Int, error: Error = Down()) async -> (taken: Bool, why: String?, tries: Int, waits: [Int]) {
        var tries = 0
        var waits: [Int] = []
        let why = await AppModel.takeBackPush({
            tries += 1
            if tries <= failures { throw error }
        }, wait: { waits.append($0) })
        return (why == nil, why, tries, waits)
    }

    func testTakenBackAtOnceTriesOnce() async {
        let r = await takeBack(failures: 0)
        XCTAssertTrue(r.taken)
        XCTAssertEqual(r.tries, 1)
        XCTAssertEqual(r.waits, [])
    }

    func testAFailedTakeBackIsTriedAgain() async {
        let r = await takeBack(failures: 2)
        XCTAssertTrue(r.taken, "the third try took it back")
        XCTAssertEqual(r.tries, 3)
        XCTAssertEqual(r.waits, [1, 2], "1 s, then 2 s")
    }

    func testThreeFailedTriesAreSaid() async {
        let r = await takeBack(failures: 5)
        XCTAssertFalse(r.taken, "sign-out then stops and asks: Try again, or Sign out anyway")
        XCTAssertEqual(r.why, "The network is down.", "why, for the question's {why}")
        XCTAssertEqual(r.tries, 3, "never more than three")
    }

    func testA404IsTakenBackAlready() async {
        let r = await takeBack(failures: 5, error: TafsCloud.CloudError(status: 404, message: "not found"))
        XCTAssertTrue(r.taken, "tafs cloud holds nothing for this token")
        XCTAssertEqual(r.tries, 1)
    }
}
