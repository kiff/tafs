import XCTest

/// K5, the end-to-end pass of row 39: the welcome's one tick, the age line per country, and Continue
/// opening KIFF's sign-in in the system's sheet. It runs only in the K5 build (`TAFS_K5`, `.github/workflows/ios-k5.yml`,
/// which sets `K5=1` for the test runner): every other build ships the welcome without the tick, so there's nothing
/// to walk. Each step keeps a screenshot in the result. It stops on KIFF's page: the sign-up's email code and GitHub
/// sign-in are the device pass's.
final class K5UITests: TafsUITestCase {
    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["K5"] == "1", "only in the K5 build (TAFS_K5)")
    }

    /// The age line, by the device's region: 18 in India, Brazil and Colombia (app/strings/ages.json), the line every
    /// country reads elsewhere.
    @MainActor
    func testTheAgeLineFollowsTheCountry() throws {
        let rows: [(region: String, line: String)] = [
            ("IN", "You must be at least 18."),
            ("BR", "You must be at least 18."),
            ("CO", "You must be at least 18."),
            ("US", "You must be at least 16, or your country's age of digital consent if it's higher."),
            ("FR", "You must be at least 16, or your country's age of digital consent if it's higher."),
        ]
        // The dispatch's extra country (`region`), kept as a screenshot with the line it shows.
        let extra = ProcessInfo.processInfo.environment["K5_REGION"].map { $0.uppercased() } ?? ""
        if extra.count == 2 {
            let app = welcome(region: extra)
            XCTAssertTrue(app.staticTexts["onboarding.age"].waitForExistence(timeout: 30), "the age line in \(extra)")
            keep("age-\(extra)")
            app.terminate()
        }
        for row in rows {
            let app = welcome(region: row.region)
            let age = app.staticTexts["onboarding.age"]
            XCTAssertTrue(age.waitForExistence(timeout: 30), "the age line in \(row.region)")
            keep("age-\(row.region)")
            XCTAssertEqual(age.label, row.line, "the age line in \(row.region)")
            app.terminate()
        }
    }

    /// Continue waits on the tick; ticked, it opens KIFF's sign-in in the system's sheet.
    @MainActor
    func testTheTickThenContinueOpensKiffsSignIn() throws {
        let app = welcome(region: "US")
        let tick = app.buttons["onboarding.tick"]
        let next = app.buttons["onboarding.continue"]
        XCTAssertTrue(tick.waitForExistence(timeout: 30), "the terms' tick")
        keep("welcome")
        XCTAssertFalse(next.isEnabled, "Continue waits on the tick")
        tick.tap()
        XCTAssertTrue(next.isEnabled, "ticked, Continue opens the sign-in")
        keep("ticked")
        next.tap()

        // iOS asks before the app opens a sign-in sheet: "“tafs” Wants to Use “kiff.dev” to Sign In".
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.alerts.firstMatch.buttons["Continue"]
        XCTAssertTrue(allow.waitForExistence(timeout: 15), "iOS's ask before the sign-in sheet")
        keep("system-ask")
        allow.tap()

        let sheet = XCUIApplication(bundleIdentifier: "com.apple.SafariViewService")
        XCTAssertTrue(sheet.webViews.firstMatch.waitForExistence(timeout: 30), "KIFF's sign-in page in the sheet")
        // The page's own words take a moment after the web view shows.
        RunLoop.current.run(until: Date().addingTimeInterval(5))
        keep("kiff-sign-in")
    }

    /// The app signed out, as on a fresh install, in English with `region` as the device's country.
    @MainActor
    private func welcome(region: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_\(region)"]
        launch(app)
        return app
    }

    /// Keeps the screen as it is now in the test's result, named for the step.
    @MainActor
    private func keep(_ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "k5-\(name)"
        shot.lifetime = .keepAlways
        add(shot)
    }
}
