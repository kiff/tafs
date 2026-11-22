import XCTest

/// K5, the end-to-end pass of row 39: the welcome's one tick, the age line per country, and Continue
/// opening KIFF's sign-in in the system's sheet. It runs only from `.github/workflows/ios-k5.yml`, which sets `K5=1` for
/// the test runner, so an ordinary UI test run skips it. Each step keeps a screenshot in the result. It stops on KIFF's page: the sign-up's email code and GitHub
/// sign-in are the device pass's.
final class K5UITests: TafsUITestCase {
    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["K5"] == "1", "only from the K5 workflow (K5=1)")
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

        // KIFF's sheet is private (`VendorSignIn.kiffSheet()`, ephemeral), and iOS asks "“tafs” Wants to Use “kiff.dev” to
        // Sign In" only for a sheet that shares Safari's. Its ask is taken if it comes; the sheet is what's waited for.
        // A screen is kept along the way, so a run that stops says where: still on Continue, or the sheet loading.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.alerts.firstMatch.buttons["Continue"]
        let sheet = XCUIApplication(bundleIdentifier: "com.apple.SafariViewService")
        let page = sheet.webViews.firstMatch
        let started = Date()
        var kept: Set<Int> = []
        while !page.exists, Date().timeIntervalSince(started) < 60 {
            if allow.exists {
                keep("system-ask")
                allow.tap()
            }
            let waited = Int(Date().timeIntervalSince(started))
            for mark in [5, 20, 40] where waited >= mark && !kept.contains(mark) {
                kept.insert(mark)
                keep("after-continue-\(mark)s")
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        XCTAssertTrue(page.exists, "KIFF's sign-in page in the sheet, within 60 s of Continue")
        // A page is not a way in: on 21 Nov KIFF's page drew its title and one line, and no way to sign in. So the test
        // waits for the four ways the owner keeps on: Apple, Google, GitHub, and email with a password. Email-code sign-in
        // stays off, so a way to sign in with a code sent by email is a finding, never a pass. (A new sign-up's one-time
        // check of the address isn't a sign-in, and isn't on this page.)
        func control(_ words: String) -> XCUIElement {
            page.descendants(matching: .any).matching(NSPredicate(format: "(elementType == %d OR elementType == %d) AND label MATCHES[c] %@",
                                                                  XCUIElement.ElementType.button.rawValue, XCUIElement.ElementType.link.rawValue,
                                                                  ".*(\(words)).*")).firstMatch
        }
        let drawn = control("google").waitForExistence(timeout: 30)
        keep("kiff-sign-in")
        XCTAssertTrue(drawn, "KIFF's sign-in page draws its ways to sign in, within 30 s")
        for (name, words) in Self.ways {
            XCTAssertTrue(control(words).exists, "KIFF's sign-in page offers \(name)")
        }
        XCTAssertTrue(page.textFields.firstMatch.exists, "KIFF's sign-in page has an email field")
        XCTAssertTrue(page.secureTextFields.firstMatch.exists, "KIFF's sign-in page offers email with a password")
        XCTAssertFalse(control(Self.emailCodeWords).exists, "no sign-in with a code sent by email: email-code sign-in stays off")
    }

    /// The four ways the owner keeps on (21 Nov), by the words on their controls.
    static let ways: [(String, String)] = [("Apple", "apple"), ("Google", "google"), ("GitHub", "github")]
    /// A control that signs in with a code sent by email instead of a password.
    static let emailCodeWords = "email me a code|send me a code|sign in with a code|use a code|code by email|magic link"

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
