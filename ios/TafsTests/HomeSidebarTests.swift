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
        XCTAssertTrue(AppModel.relevantNow(held: false, step: false, knock: false, lastRun: nil, lastChange: now.addingTimeInterval(-3600), now: now),
                      "changed an hour ago (tafs cloud's last_change)")
        XCTAssertFalse(AppModel.relevantNow(held: false, step: false, knock: false, lastRun: nil, lastChange: now.addingTimeInterval(-30 * 3600), now: now))
        // last_change is read leniently: an ISO time with or without fractions; anything else is none.
        XCTAssertNotNil(TafsCloud.HomeTaf.time("2026-11-14T02:44:11Z"))
        XCTAssertNotNil(TafsCloud.HomeTaf.time("2026-11-14T02:44:11.123Z"))
        XCTAssertNil(TafsCloud.HomeTaf.time("yesterday"))
        let home = { (lastChange: String) throws -> TafsCloud.Home in
            try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(#"{"tafs":[{"id":"t1","name":"Rae","job":"j","kiff_agent":"rae","status":"active","next_run":null,"last_run":null,"color":0,"state":"idle","waiting":0,"card":{"ids":[],"active":true,"expires":null,"tools":[],"rules":[],"used":{},"usage_unknown":[]},"last_change":\#(lastChange)}],"others":[],"removing":[]}"#.utf8))
        }
        XCTAssertNotNil(try home(#""2026-11-14T02:44:11Z""#).tafs.first?.cloudTaf?.lastChange)
        XCTAssertNil(try home("7").tafs.first?.cloudTaf?.lastChange, "another shape: none, and Home still reads")
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

    /// The owner's iPad: the Overview's calendar button at the top right was a second Calendar beside the
    /// sidebar's item. As the whole app's sidebar, the Overview draws none; the phone keeps its own button.
    func testTheOverviewHasNoCalendarButtonBesideTheSidebarsCalendar() throws {
        let file = try source()
        XCTAssertTrue(file.contains("private var calendarInSidebar: Bool { tab != nil && signedInLayout }"))
        XCTAssertTrue(file.contains("OverviewView(showsCalendar: !calendarInSidebar) {"))
        XCTAssertTrue(file.contains("if signedInLayout {\n                        screenItem(.calendar, Words.Calendar.heading, \"calendar\")"),
                      "the sidebar's own Calendar item, under the same condition")

        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let overview = try String(contentsOf: views.appendingPathComponent("ShareViews.swift"), encoding: .utf8)
        let body = String(overview[try XCTUnwrap(overview.range(of: "struct OverviewView: View {")).upperBound...].prefix(2200))
        let gate = try XCTUnwrap(body.range(of: "if showsCalendar {"))
        let button = try XCTUnwrap(body.range(of: ".accessibilityIdentifier(\"overview.calendar\")"))
        XCTAssertLessThan(gate.lowerBound, button.lowerBound, "the button only when the sidebar doesn't have Calendar")

        // The phone has no Overview: its calendar opens from Home's bar, as before.
        let home = try String(contentsOf: views.appendingPathComponent("HomeView.swift"), encoding: .utf8)
        XCTAssertTrue(home.contains(".accessibilityIdentifier(\"home.calendar\")"))
    }

    /// CI's iPad screenshots of Calendar: `-screen calendar` opens it on today in Day mode, `-screen
    /// calendar-week` in Week mode on today's week, only with `-screenshots YES`. The iPad's sidebar selects
    /// its Calendar item; the phone opens it as Home's button does.
    func testTheCalendarScreenshotArguments() throws {
        let defaults = UserDefaults.standard
        defer { defaults.removeObject(forKey: "screenshots"); defaults.removeObject(forKey: "screen") }
        let today = CalendarMath.today(in: AccountTime.zone)
        defaults.set("calendar", forKey: "screen")
        defaults.set(false, forKey: "screenshots")
        XCTAssertNil(Screenshots.calendar, "only for a screenshot")
        defaults.set(true, forKey: "screenshots")
        XCTAssertEqual(Screenshots.calendar, CalendarState(mode: .day, selected: today))
        defaults.set("calendar-week", forKey: "screen")
        XCTAssertEqual(Screenshots.calendar, CalendarState(mode: .week, selected: today), "today's week: the Today pill already today")
        defaults.set("taf", forKey: "screen")
        XCTAssertNil(Screenshots.calendar)

        XCTAssertTrue(try source().contains("if Screenshots.calendar != nil { opened = .calendar }"), "the sidebar's Calendar item")
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let home = try String(contentsOf: views.appendingPathComponent("HomeView.swift"), encoding: .utf8)
        XCTAssertTrue(home.contains("case \"calendar\", \"calendar-week\": showingCalendar = true"), "the phone, as Home's button")
        let calendar = try String(contentsOf: views.appendingPathComponent("CalendarView.swift"), encoding: .utf8)
        XCTAssertTrue(calendar.contains("model.calendarState ?? Screenshots.calendar ?? CalendarState(selected: today)"))
    }

    /// A tab asked for before the sidebar drew opens its screen, not the tafs list over it: an alert that
    /// opened the app on Needs you landed on the Overview (the iPad's `-tab needs-you` shot showed it).
    func testATabAskedForAtLaunchOpensItsScreen() throws {
        let file = try source()
        let task = String(file[try XCTUnwrap(file.range(of: "if Screenshots.calendar != nil { opened = .calendar }")).upperBound...].prefix(400))
        XCTAssertTrue(task.contains("selection = tab.flatMap { HomeSelection.of($0.wrappedValue) } ?? opened"))
        XCTAssertEqual(HomeSelection.of(.needsYou), .needsYou)
        XCTAssertNil(HomeSelection.of(.tafs), "the tafs tab: the list, as before")
    }
}
