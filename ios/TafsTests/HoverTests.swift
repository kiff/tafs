import XCTest
@testable import Tafs

/// The pointer over a button on the Mac (docs/mac-app.md, "The pointer").
final class HoverTests: XCTestCase {
    /// Only the Mac build shows it: an iPad with a trackpad keeps its own pointer effects.
    func testOnlyTheMacShowsThePointerOverAButton() {
        XCTAssertTrue(Hover.shows(true, mac: true))
        XCTAssertFalse(Hover.shows(true, mac: false))
        XCTAssertFalse(Hover.shows(false, mac: true))
    }

    /// Under the pointer a button changes a little, less than when it's pressed, so a press still
    /// shows under the pointer.
    func testThePointerShowsLessThanAPress() {
        XCTAssertEqual(Hover.primaryOpacity(pressed: false, hovered: false), 1)
        XCTAssertLessThan(Hover.primaryOpacity(pressed: false, hovered: true), 1)
        XCTAssertLessThan(Hover.primaryOpacity(pressed: true, hovered: true), Hover.primaryOpacity(pressed: false, hovered: true))
        XCTAssertEqual(Hover.outlineOpacity(pressed: false, hovered: false), 0)
        XCTAssertGreaterThan(Hover.outlineOpacity(pressed: false, hovered: true), 0)
        XCTAssertGreaterThan(Hover.outlineOpacity(pressed: true, hovered: true), Hover.outlineOpacity(pressed: false, hovered: true))
    }
}
