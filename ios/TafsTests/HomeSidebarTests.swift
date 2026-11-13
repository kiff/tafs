import XCTest
@testable import Tafs

/// The iPad's one sidebar (docs/ios-app.md): the app's tab and the selection follow each other,
/// and each item is in the drive's registry.
@MainActor
final class HomeSidebarTests: XCTestCase {
    private func source() throws -> String {
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        return try String(contentsOf: views.appendingPathComponent("HomeSplitView.swift"), encoding: .utf8)
    }

    /// Each screen is its tab, and each tab but the tafs opens its screen; a taf is the Tafs tab.
    func testTabsAndScreensAgree() {
        for tab in [Tab.needsYou, .record, .account] {
            XCTAssertEqual(HomeSelection.of(tab)?.tab, tab)
        }
        XCTAssertNil(HomeSelection.of(.tafs), "the tafs keep what's open: picking a taf never jumps elsewhere")
        XCTAssertEqual(HomeSelection.taf(UUID()).tab, .tafs)
        for screen in [HomeSelection.calendar, .usage, .connectApps, .overview] { XCTAssertEqual(screen.tab, .tafs) }
    }

    /// ⌘1 from Record, Needs you or Account goes back to the taf the app opened on; from a taf, nothing moves.
    func testTheTafsTabFromAScreenGoesBack() throws {
        let file = try source()
        let start = try XCTUnwrap(file.range(of: ".onChange(of: tab?.wrappedValue) { t in"))
        let body = String(file[start.upperBound...].prefix(700))
        XCTAssertTrue(body.contains("if t == .tafs {"))
        XCTAssertTrue(body.contains("guard selection?.tab != .tafs, let back = opened"), "only from a screen")
    }

    /// The screens' items can be driven, as the rail's were and every other row is.
    func testEachScreenItemIsDrivable() throws {
        let file = try source()
        let start = try XCTUnwrap(file.range(of: "private func screenItem("))
        let body = String(file[start.upperBound...].prefix(500))
        XCTAssertTrue(body.contains(".modifier(drives(title, screen))"))
    }
}
