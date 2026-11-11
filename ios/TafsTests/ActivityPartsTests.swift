import XCTest
@testable import Tafs

/// A taf on tafs cloud shows its Activity tab through `TafActivityList` (`TafDetailView.pane`), so
/// its work (health and its fixes, its oldest waiting case, Answer within, skipped firings,
/// requests) and its runs (Run now) are drawn there, after what waits for the owner and before the
/// runs it kept, in Android's order. The older `activity(_:)` view is the sample's alone.
@MainActor
final class ActivityPartsTests: XCTestCase {
    /// The route that broke: a cloud taf's Activity tab must be the list that carries its work.
    func testACloudTafsActivityTabIsTheListWithItsRunsAndWork() {
        XCTAssertEqual(TafDetailView.pane(section: 1, kiff: true, canChat: true), .activityList)
        XCTAssertEqual(TafActivityList.parts(cloud: true), [.held, .cardUnits, .work, .runs, .retainedRuns],
                       "what needs the owner first, then how it's doing, then its runs and Run now, then the runs it kept")
    }

    /// As on Android 51: for whoever manages the taf, its doors' knocks come first, before its held
    /// calls; someone who doesn't manage it, and the sample, get none there.
    func testKnocksComeFirstForWhoeverManagesTheTaf() {
        XCTAssertEqual(TafActivityList.parts(cloud: true, manages: true), [.knocks, .held, .cardUnits, .work, .runs, .retainedRuns])
        XCTAssertFalse(TafActivityList.parts(cloud: true, manages: false).contains(.knocks), "only whoever manages it answers a knock")
        XCTAssertFalse(TafActivityList.parts(cloud: false, manages: true).contains(.knocks), "the sample has no doors there")
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
