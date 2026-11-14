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

    /// The owner's second round: a taf is beside the list only while it's relevant now: an item in Needs you
    /// (a held call, a step, a knock), or a run in the last 24 hours. The rest are behind the mascot.
    func testOnlyTafsRelevantNowAreInTheSidebar() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertTrue(AppModel.relevantNow(held: true, step: false, knock: false, lastRun: nil, now: now))
        XCTAssertTrue(AppModel.relevantNow(held: false, step: true, knock: false, lastRun: nil, now: now))
        XCTAssertTrue(AppModel.relevantNow(held: false, step: false, knock: true, lastRun: nil, now: now))
        XCTAssertTrue(AppModel.relevantNow(held: false, step: false, knock: false, lastRun: now.addingTimeInterval(-23 * 3600), now: now))
        XCTAssertFalse(AppModel.relevantNow(held: false, step: false, knock: false, lastRun: now.addingTimeInterval(-25 * 3600), now: now),
                       "a run two days ago: in the list, not beside it")
        XCTAssertFalse(AppModel.relevantNow(held: false, step: false, knock: false, lastRun: nil, now: now), "never ran, nothing waiting")
    }

    /// The mascot and "tafs" opens the tafs list, which opens first; there's no Overview item beside it.
    func testTheMascotOpensTheTafsList() throws {
        let file = try source()
        XCTAssertTrue(file.contains("NavigationLink(value: HomeSelection.overview) { brand.modifier(drives(Words.App.name, .overview)) }"))
        XCTAssertTrue(file.contains("if tab == nil, !model.tafs.isEmpty {"), "the Overview item only without the whole-app sidebar")
        XCTAssertTrue(file.contains("let onList = tab != nil && !(Screenshots.enabled && Screenshots.screen != nil)"))
        XCTAssertTrue(file.contains("opened = onList ? .overview :"), "the tafs list opens first")
        XCTAssertTrue(file.contains("(own: all.own.filter(model.relevantNow), shared: all.shared.filter(model.relevantNow))"))
    }
}
