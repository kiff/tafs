import XCTest

/// The owner's 901 test, pinned so it can't come back: a tap on a taf's icon or its name in
/// the tafs list opens that taf. On the iPad only the row's empty end did, since the row's own
/// tap gesture took the list's taps over its content. On the iPad, a tap on the taf already
/// open is a pick too, so the list settling later doesn't take the owner away from it.
final class ListTapUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private var iPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    @MainActor
    func testATapOnATafsIconOrNameOpensIt() throws {
        let app = launch()
        let rows = app.descendants(matching: .any).matching(identifier: "taf-row")
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 30), "the tafs list")
        XCTAssertGreaterThanOrEqual(rows.count, 3, "the sample's tafs")

        // Two tafs that aren't open yet, on screen: the second and third in the list (the iPad
        // opens the first by itself).
        let byIcon = name(rows.element(boundBy: 1).label), byName = name(rows.element(boundBy: 2).label)

        tap(row(app, byIcon), on: .icon)
        XCTAssertTrue(opened(app, byIcon), "a tap on \(byIcon)'s icon opens it; open: \(openName(app))")
        back(app)
        tap(row(app, byName), on: .name)
        XCTAssertTrue(opened(app, byName), "a tap on \(byName)'s name opens it; open: \(openName(app))")
    }

    /// iPad only: the iPhone's list pushes a taf, so nothing opens by itself to settle under.
    @MainActor
    func testATapOnTheTafAlreadyOpenStaysOpenWhenTheListSettles() throws {
        guard iPad else { throw XCTSkip("the sidebar is the iPad's") }
        let settles: TimeInterval = 45
        let launched = Date()
        let app = launch(["-listReorderSeconds", "\(Int(settles))"])
        let page = app.staticTexts["taf-page-name"]
        XCTAssertTrue(page.waitForExistence(timeout: 30), "the list opens on its first taf")
        let open = page.label
        let rows = app.descendants(matching: .any).matching(identifier: "taf-row")
        XCTAssertEqual(name(rows.firstMatch.label), open, "the open taf is the list's first, before it settles")

        XCTAssertLessThan(Date().timeIntervalSince(launched), settles - 3, "the tap comes before the list settles; raise -listReorderSeconds")
        tap(row(app, open), on: .name)
        // The list settles, its last taf moving to the top: waited out, not polled, since a UI
        // query asked over and over while the list moves times out on a slow simulator.
        RunLoop.current.run(until: launched.addingTimeInterval(settles + 5))
        XCTAssertNotEqual(name(rows.firstMatch.label), open, "the list settled: its last taf is at the top")
        XCTAssertEqual(openName(app), open, "the taf picked by a tap stays open when the list settles")
    }

    // MARK: Helpers

    @MainActor
    private func launch(_ more: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs"] + more
        // The sidebar shows beside the open taf in landscape.
        if iPad { XCUIDevice.shared.orientation = .landscapeLeft }
        app.launch()
        return app
    }

    private enum Part { case icon, name }

    /// A tap on the row's icon or on its name, never its empty end: where the owner's taps fell.
    /// The row's frame is its content's (the iPhone's card has 16 pt of padding inside).
    @MainActor
    private func tap(_ row: XCUIElement, on part: Part) {
        XCTAssertTrue(row.waitForExistence(timeout: 10), "the row")
        let inset: CGFloat = iPad ? 0 : 16, avatar: CGFloat = iPad ? 34 : 40
        let point = part == .icon
            ? CGVector(dx: inset + avatar / 2, dy: iPad ? row.frame.height / 2 : inset + avatar / 2)
            : CGVector(dx: inset + avatar + 12 + 20, dy: inset + 10)
        row.coordinate(withNormalizedOffset: .zero).withOffset(point).tap()
    }

    @MainActor
    private func row(_ app: XCUIApplication, _ name: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "taf-row")
            .matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
    }

    /// The taf's name, the first part of its row's label.
    private func name(_ label: String) -> String {
        label.components(separatedBy: ", ").first ?? label
    }

    @MainActor
    private func openName(_ app: XCUIApplication) -> String {
        let page = app.staticTexts["taf-page-name"]
        return page.exists ? page.label : "nothing"
    }

    @MainActor
    private func opened(_ app: XCUIApplication, _ name: String) -> Bool {
        within(10) { openName(app) == name }
    }

    /// Back to the list on the iPhone, where a taf pushes; the iPad's sidebar stays.
    @MainActor
    private func back(_ app: XCUIApplication) {
        guard !iPad else { return }
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(within(10) { !app.staticTexts["taf-page-name"].exists }, "back at the list")
    }

    /// Whether `condition` holds within `seconds`, checked every tenth of a second.
    @MainActor
    private func within(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        repeat {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        } while Date() < end
        return condition()
    }
}
