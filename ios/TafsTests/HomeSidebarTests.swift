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

    /// tafs cloud's `relevant_now` (kiff/tafs#1320) decides the sidebar whenever Home sends it, both ways, so every
    /// app lists the same tafs; only a taf Home sends no flag for (an older tafs cloud) falls back to the app's rule.
    /// Anything but a true or false is no flag, and never fails Home's read.
    func testTafsCloudsRelevantNowDecidesTheSidebar() async throws {
        let session = URLSession(configuration: { let c = URLSessionConfiguration.ephemeral; c.protocolClasses = [StubProtocol.self]; return c }())
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        defer { Keychain.delete(AppModel.tokenAccount) }
        let recent = ISO8601DateFormatter().string(from: Date().addingTimeInterval(-3600))
        func entry(_ agent: String, _ name: String, _ id: String, flag: String?, ranRecently: Bool = false) -> String {
            var e = HomeJSON.entry(agent: agent, name: name, id: id)
            if ranRecently { e = e.replacingOccurrences(of: #""last_run":null"#, with: #""last_run":"\#(recent)""#) }
            if let flag { e = e.replacingOccurrences(of: #""color":"#, with: #""relevant_now":\#(flag),"color":"#) }
            return e
        }
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([
            entry("a-1", "Quiet but flagged", "depl_1", flag: "true"),
            entry("b-2", "Ran but not flagged", "depl_2", flag: "false", ranRecently: true),
            entry("c-3", "Older tafs cloud", "depl_3", flag: nil, ranRecently: true),
            entry("d-4", "Odd flag", "depl_4", flag: #""yes""#),
        ]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, session: session)
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session)
        let model = AppModel(signer: StubSigner(), kiff: kiff, cloud: cloud)
        await model.useSession("kiff_dev_x")
        func taf(_ name: String) throws -> Taf { try XCTUnwrap(model.tafs.first { $0.name == name }, name) }
        XCTAssertTrue(model.relevantNow(try taf("Quiet but flagged")), "flagged: in the sidebar with nothing on the device's rule")
        XCTAssertFalse(model.relevantNow(try taf("Ran but not flagged")), "not flagged: out, though it ran an hour ago")
        XCTAssertTrue(model.relevantNow(try taf("Older tafs cloud")), "no flag: the app's rule, which a run an hour ago meets")
        XCTAssertFalse(model.relevantNow(try taf("Odd flag")), "a flag that isn't true or false is none; the rule finds nothing")
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

    /// The owner's iPad: the cream fills the whole detail pane. Overview's column stretches to the pane's
    /// width, and the lists in the pane hide their grey for the app's background.
    func testTheDetailPaneIsCreamEdgeToEdge() throws {
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let overview = try String(contentsOf: views.appendingPathComponent("ShareViews.swift"), encoding: .utf8)
        XCTAssertTrue(overview.contains("            .pageColumn(PageWidth.list)\n        }\n        .screenBackground()"), "the column, centred and stretched")
        let connect = try String(contentsOf: views.appendingPathComponent("ConnectAppsView.swift"), encoding: .utf8)
        XCTAssertEqual(connect.components(separatedBy: "        List {").count - 1,
                       connect.components(separatedBy: ".scrollContentBackground(.hidden)").count - 1, "every list in Connect apps")
        let taf = try String(contentsOf: views.appendingPathComponent("TafDetailView.swift"), encoding: .utf8)
        let counted = String(taf[try XCTUnwrap(taf.range(of: "struct CountedView: View {")).upperBound...].prefix(900))
        XCTAssertTrue(counted.contains(".scrollContentBackground(.hidden)"))
    }

    /// The owner's iPad: "Needs you · 3" beside "No tafs yet". With no held call, what Needs you counts (a
    /// Coach chat, a step) still shows on Overview, as a line opening Needs you.
    func testOverviewShowsWhatNeedsYouCountsBeyondHeldCalls() throws {
        XCTAssertTrue(OverviewView.showsRest(count: 3, held: 0))
        XCTAssertTrue(OverviewView.showsRest(count: 3, held: 2))
        XCTAssertFalse(OverviewView.showsRest(count: 2, held: 2), "the held calls are each listed already")
        XCTAssertFalse(OverviewView.showsRest(count: 0, held: 0))
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let overview = try String(contentsOf: views.appendingPathComponent("ShareViews.swift"), encoding: .utf8)
        XCTAssertTrue(overview.contains("if model.needsYouCount > 0 { needsYou }"))
        XCTAssertTrue(overview.contains("Button { open(.needsYou) } label:"))
    }

    /// The owner's layout (docs/parity.md, "One layout on every app"): every page is one column, centred, at most its named
    /// width, with text aligned left inside it. Each page takes its width from `PageWidth` through `pageColumn`, before its
    /// cream, so no page sets its own number and the cream still reaches both edges.
    func testEveryPageIsACentredColumnOfItsNamedWidth() throws {
        XCTAssertEqual(PageWidth.reading, 720)
        XCTAssertEqual(PageWidth.list, 980)
        XCTAssertEqual(PageWidth.gallery, 640)
        XCTAssertEqual(PageWidth.brief, 1100)
        XCTAssertEqual(PageWidth.calendar, 1180)
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let pages: [(file: String, page: String, width: String)] = [
            ("SignInView.swift", "struct SettingsView: View {", "reading"),
            ("SignInView.swift", "struct AccountView: View {", "reading"),
            ("DevicesView.swift", "struct DevicesView: View {", "reading"),
            ("PeopleViews.swift", "struct PeopleGroupsView: View {", "reading"),
            ("NotificationSettingsView.swift", "struct NotificationSettingsView: View {", "reading"),
            ("ChromeViews.swift", "struct ChromesView: View {", "reading"),
            ("NeedsYouView.swift", "struct NeedsYouView: View {", "reading"),
            ("ConnectAppsView.swift", "struct ConnectAppsView: View {", "reading"),
            ("UsageView.swift", "struct UsageView: View {", "reading"),
            ("ShareViews.swift", "struct OverviewView: View {", "list"),
            ("ShareViews.swift", "struct ShareTafSheet: View {", "list"),
            ("ConnectAppsView.swift", "struct AppConnectionsView: View {", "list"),
            ("RecipeGallery.swift", "", "gallery"),
            ("HomeRecipes.swift", "", "gallery"),
            ("BriefView.swift", "struct BriefView: View {", "brief"),
            ("CalendarView.swift", "struct CalendarView: View {", "calendar"),
        ]
        for p in pages {
            let file = try String(contentsOf: views.appendingPathComponent(p.file), encoding: .utf8)
            let from = p.page.isEmpty ? file.startIndex : try XCTUnwrap(file.range(of: p.page), p.page).upperBound
            // The page's own code: up to the next top-level type.
            let rest = file[from...]
            let body = rest.range(of: "\nstruct ").map { rest[..<$0.lowerBound] } ?? rest
            XCTAssertTrue(body.contains(".pageColumn(PageWidth.\(p.width))"), "\(p.file) \(p.page): \(p.width)")
        }
        // No page sets one of these widths itself.
        for name in try FileManager.default.contentsOfDirectory(atPath: views.path) where name.hasSuffix(".swift") {
            let file = try String(contentsOf: views.appendingPathComponent(name), encoding: .utf8)
            for n in ["720", "980", "640", "1100", "1180"] {
                XCTAssertFalse(file.contains("maxWidth: \(n),"), "\(name) sets \(n) itself")
                XCTAssertFalse(file.contains("maxWidth: \(n))"), "\(name) sets \(n) itself")
            }
        }
        // Centred, its content left inside it.
        let components = try String(contentsOf: views.deletingLastPathComponent().appendingPathComponent("Design/Components.swift"), encoding: .utf8)
        XCTAssertTrue(components.contains("frame(maxWidth: cap, alignment: .leading).frame(maxWidth: .infinity)"))
    }

    /// On Account, an explanation sits beside its action: how signing in keeps a taf's actions checked is in the sign-in
    /// section (or beside Sign in with KIFF), and How tafs works is one entry, never a section that only explains.
    func testAccountsExplanationsSitBesideTheirActions() throws {
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let file = try String(contentsOf: views.appendingPathComponent("SignInView.swift"), encoding: .utf8)
        let settings = String(file[try XCTUnwrap(file.range(of: "struct SettingsView: View {")).upperBound...])
        XCTAssertFalse(settings.contains("Section(Words.SignIn.howWorks)"), "no section that only explains")
        XCTAssertTrue(settings.contains("Section(Words.Settings.SignInCode.title) {\n                        CodeSignInRow()\n                        signInExplained\n                    }"))
        XCTAssertTrue(settings.contains("Button(Words.SignIn.withKiff) { signingIn = true }\n                        signInExplained"))
        XCTAssertEqual(settings.components(separatedBy: "NavigationLink(Words.Onboarding.howItWorks)").count - 1, 1, "How tafs works, once")
    }
}
