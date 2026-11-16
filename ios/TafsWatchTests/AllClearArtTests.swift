import XCTest
@testable import TafsWatch

/// Needs you with nothing waiting shows the approved board's calm check, and only then.
final class AllClearArtTests: XCTestCase {
    func testTheCheckShowsOnlyWhenNothingWaits() {
        XCTAssertTrue(NeedsYouScreen.allClear(0))
        XCTAssertFalse(NeedsYouScreen.allClear(1))
        XCTAssertFalse(NeedsYouScreen.allClear(12))
    }
}
