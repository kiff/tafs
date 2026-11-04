import XCTest

/// The taf layout: the bar's tabs, and Setup's screens drawn from the sample's dial.
final class SetupUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testThemeChoiceAppliesAndSurvivesRelaunch() {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "account"]
        app.launch()
        XCTAssertTrue(app.navigationBars["Account"].waitForExistence(timeout: 30))
        let dark = app.segmentedControls.buttons["Dark"]
        for _ in 0..<4 where !dark.isHittable { app.swipeUp() }
        XCTAssertTrue(dark.waitForExistence(timeout: 10))
        dark.tap()
        XCTAssertTrue(dark.isSelected)
        app.terminate(); app.launch()
        for _ in 0..<4 where !dark.isHittable { app.swipeUp() }
        XCTAssertTrue(dark.isSelected, "the explicit appearance is kept")
        app.segmentedControls.buttons["System"].tap()
    }

    @MainActor
    func testTheBarOpensEachTab() {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs"]
        app.launch()
        for title in ["Account", "Tafs", "Needs you", "Record"] {
            XCTAssertTrue(app.buttons["bar." + title].waitForExistence(timeout: 30), "the bar has \(title)")
        }
        // The sample can't use Coach: the bar has four items.
        XCTAssertFalse(app.buttons["bar.Coach"].exists)
        app.buttons["bar.Record"].tap()
        XCTAssertTrue(app.buttons["All tafs"].waitForExistence(timeout: 5), "Record's taf chips")
        app.buttons["bar.Account"].tap()
        XCTAssertTrue(app.navigationBars["Account"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testStartsWhenShowsAnEventsFiltersBeforeItsApproval() {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-screen", "starts"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Every 4 hours, 9:00–17:00"].waitForExistence(timeout: 30), "its schedule")
        XCTAssertTrue(app.staticTexts["Approve signs on this phone; the app starts telling the taf only after that."].exists)
        app.buttons["Approve"].firstMatch.tap()
        let filters = app.staticTexts["event.filters"]
        XCTAssertTrue(filters.waitForExistence(timeout: 5))
        XCTAssertTrue(filters.label.contains("amount_over"))
        XCTAssertTrue(app.buttons["event.approve"].exists)
        app.buttons["Cancel"].tap()
        XCTAssertFalse(filters.exists)
    }

    @MainActor
    func testResultsGoToKeepsOnePlaceOn() {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-screen", "results"]
        app.launch()
        XCTAssertTrue(app.staticTexts["At least one place stays on."].waitForExistence(timeout: 30))
        for place in ["Reply to the sender", "Alert our phones", "Email me", "Notion · Support log"] {
            XCTAssertTrue(app.switches.containing(.staticText, identifier: place).firstMatch.exists
                          || app.staticTexts[place].exists, "\(place) is listed")
        }
    }
}
