import SwiftUI
import XCTest
@testable import Tafs

/// A door's key, shown once, is covered whenever the app isn't in front (the app switcher's snapshot) and while the screen
/// is captured (recorded, mirrored or shared), as Android's `FLAG_SECURE` keeps it off the recents and a recording.
final class IssuedKeyCoverTests: XCTestCase {
    func testTheKeyShowsOnlyInFrontAndUncaptured() {
        XCTAssertTrue(IssuedKeyView.shows(phase: .active, captured: false))
        XCTAssertFalse(IssuedKeyView.shows(phase: .inactive, captured: false), "the app switcher's snapshot")
        XCTAssertFalse(IssuedKeyView.shows(phase: .background, captured: false))
        XCTAssertFalse(IssuedKeyView.shows(phase: .active, captured: true), "a recording or a mirrored screen")
    }
}
