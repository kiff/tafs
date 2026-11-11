import XCTest
@testable import Tafs

/// A taf on tafs cloud shows its Activity tab through `TafActivityList` (`TafDetailView.pane`), so
/// its runs (Run now) and its work (health and its fixes, its oldest waiting case, Answer within,
/// skipped firings, requests) are drawn there, after what waits for the owner and before the runs
/// it kept, in Android's order. The older `activity(_:)` view is the sample's alone.
@MainActor
final class ActivityPartsTests: XCTestCase {
    /// The route that broke: a cloud taf's Activity tab must be the list that carries its work.
    func testACloudTafsActivityTabIsTheListWithItsRunsAndWork() {
        XCTAssertEqual(TafDetailView.pane(section: 1, kiff: true, canChat: true), .activityList)
        XCTAssertEqual(TafActivityList.parts(cloud: true), [.held, .cardUnits, .runs, .work, .retainedRuns],
                       "what waits for the owner, then its runs and Run now, then how it's doing, then the runs it kept")
    }

    func testTheOtherTabsAndTheSample() {
        XCTAssertEqual(TafDetailView.pane(section: 0, kiff: true, canChat: true), .chat)
        XCTAssertEqual(TafDetailView.pane(section: 2, kiff: true, canChat: true), .scroll, "the Card")
        XCTAssertEqual(TafDetailView.pane(section: 3, kiff: true, canChat: true), .scroll, "Setup")
        XCTAssertEqual(TafDetailView.pane(section: 1, kiff: false, canChat: true), .scroll, "the sample's Activity")
        XCTAssertFalse(TafActivityList.parts(cloud: false).contains(.work))
        XCTAssertFalse(TafActivityList.parts(cloud: false).contains(.runs))
    }
}
