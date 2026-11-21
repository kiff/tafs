import XCTest
@testable import Tafs

/// A door draft that's used up (tafs cloud's 410 `draft_used`, tafs#1678) says so in tafs cloud's words, in the ok
/// colour, as Android draws it: a door was made from it, so nothing went wrong. Any other refusal stays an error.
final class DoorDraftUsedTests: XCTestCase {
    func testOnlyDraftUsedReadsAsDone() {
        var used = TafsCloud.CloudError(status: 410, message: "gone")
        used.code = "draft_used"
        used.words = "Door made from this draft."
        XCTAssertTrue(DoorDraftCard.isUsed(used))
        var lapsed = TafsCloud.CloudError(status: 404, message: "not found")
        lapsed.code = "draft_lapsed"
        XCTAssertFalse(DoorDraftCard.isUsed(lapsed), "a lapsed draft is still an error: ask Coach again")
        XCTAssertFalse(DoorDraftCard.isUsed(TafsCloud.CloudError(status: 500, message: "down")))
        XCTAssertFalse(DoorDraftCard.isUsed(URLError(.notConnectedToInternet)))
    }
}
