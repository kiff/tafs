import XCTest
@testable import TafsWatch

/// The account's zone on the wrist (row 44, C): learned from the phone's replies, and dropped when the phone says it's
/// signed out, so a second account never reads in the first one's zone (agentgabo on #1590; Wear OS's `use(null)`).
final class WatchAccountZoneTests: XCTestCase {
    private let own = TimeZone(identifier: "Asia/Tokyo")!

    override func tearDown() {
        WatchAccountZone.forget()
        super.tearDown()
    }

    func testARepliesZoneIsLearnedAndASignOutDropsIt() {
        WatchAccountZone.forget(own: own)
        WatchAccountZone.heard(WatchReply(.ok, tafs: [], zone: "Europe/Paris"), own: own)
        XCTAssertEqual(WatchAccountZone.zone.identifier, "Europe/Paris", "the account's zone, from Your tafs")

        WatchAccountZone.heard(WatchReply(.signedOut), own: own)
        XCTAssertEqual(WatchAccountZone.zone, own, "signed out: the watch's own again")

        WatchAccountZone.heard(WatchReply(.ok, tafs: [], zone: "America/New_York"), own: own)
        XCTAssertEqual(WatchAccountZone.zone.identifier, "America/New_York", "the next account's, from its first reply")
    }

    func testNoReplyAZonelessOneOrAnUnknownZoneKeepsTheLastOne() {
        WatchAccountZone.forget(own: own)
        WatchAccountZone.heard(WatchReply(.ok, tafs: [], zone: "Europe/Paris"), own: own)
        WatchAccountZone.heard(nil, own: own)
        WatchAccountZone.heard(WatchReply(.failed, line: "No"), own: own)
        WatchAccountZone.heard(WatchReply(.ok, tafs: []), own: own)
        WatchAccountZone.heard(WatchReply(.ok, tafs: [], zone: "Nowhere/Atlantis"), own: own)
        XCTAssertEqual(WatchAccountZone.zone.identifier, "Europe/Paris", "out of reach, a failure, an older phone, an unknown name")
    }
}
