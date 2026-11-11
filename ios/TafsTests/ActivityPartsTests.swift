import XCTest
@testable import Tafs

/// A taf on tafs cloud shows its Activity through `TafActivityList`, so its work (health and its
/// fixes, skipped firings, requests) and its runs (Run now) are drawn there, after what waits for
/// the owner and before the runs it kept. The older `activity(_:)` view is the sample's alone.
@MainActor
final class ActivityPartsTests: XCTestCase {
    func testACloudTafsActivityDrawsItsWorkAndItsRuns() {
        let parts = TafActivityList.parts(cloud: true)
        XCTAssertEqual(parts, [.held, .cardUnits, .work, .runs, .retainedRuns])
        XCTAssertLessThan(parts.firstIndex(of: .held)!, parts.firstIndex(of: .work)!, "what waits for the owner first")
        XCTAssertLessThan(parts.firstIndex(of: .work)!, parts.firstIndex(of: .retainedRuns)!, "its health before the runs it kept")
    }

    func testATafNotOnTafsCloudHasNoWorkToDraw() {
        XCTAssertFalse(TafActivityList.parts(cloud: false).contains(.work))
        XCTAssertFalse(TafActivityList.parts(cloud: false).contains(.runs))
    }
}
