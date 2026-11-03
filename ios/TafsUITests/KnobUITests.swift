import XCTest
import UIKit

/// The same remote handles more services than fit around a single ring.
final class KnobUITests: XCTestCase {
    @MainActor
    func testConnectionFilterAndRemoteWalkTheRecipe() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-screen", "knob"]
        app.launch()
        let showing = app.buttons["knob.showing"]
        XCTAssertTrue(showing.waitForExistence(timeout: 30))
        showing.tap()
        let service = app.buttons["Service 12 · 2 stops"]
        XCTAssertTrue(service.waitForExistence(timeout: 5))
        service.tap()
        let next = app.buttons["knob.next"]
        if !next.isHittable { app.swipeUp() }
        next.tap()
        let face = app.buttons["knob.face"]
        XCTAssertTrue(face.label.contains("WHEN · SERVICE 12"))
        next.tap()
        XCTAssertTrue(face.label.contains("WHERE · SERVICE 12"), "empty What ring is skipped")
        next.tap()
        XCTAssertTrue(face.label.contains("WHEN · SERVICE 12"), "remote wraps within this service")
        if !showing.isHittable { app.swipeDown() }
        showing.tap()
        app.buttons["This taf"].tap()
        if !next.isHittable { app.swipeUp() }
        next.tap() // Email, the taf's own door.
        next.tap() // Add a door, always last on When.
        next.tap()
        XCTAssertTrue(face.label.contains("WHAT · 1 · CHECK"))
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Universal dial, This taf"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
    @MainActor
    func testRemoteStaysAboveTheTabBarBeforeAndAfterScrolling() {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-screen", "knob"]
        app.launch()
        XCTAssertTrue(app.buttons["knob.next"].waitForExistence(timeout: 30))
        let keys = ["back", "lower", "power", "raise", "next"].map { app.buttons["knob." + $0] }
        func check() {
            for key in keys {
                XCTAssertTrue(key.exists)
                XCTAssertTrue(app.frame.contains(key.frame), "the whole remote is inside the window")
            }
            XCTAssertTrue(app.buttons["knob.next"].isHittable)
            let tabBar = app.tabBars.firstMatch
            if UIDevice.current.userInterfaceIdiom == .phone, tabBar.exists {
                XCTAssertLessThanOrEqual(keys.map { $0.frame.maxY }.max()!, tabBar.frame.minY,
                                         "the remote is above the floating tab bar")
            }
        }
        check()
        let before = app.buttons["knob.next"].frame
        app.scrollViews.firstMatch.swipeUp()
        check()
        XCTAssertEqual(app.buttons["knob.next"].frame.minY, before.minY, accuracy: 2)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Dial remote above the tab bar"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    func testDisabledPauseAndFullAccessibilityWordsForLongLabels() {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-screen", "knob", "-knob-no-pause", "-knob-long-labels"]
        app.launch()
        let showing = app.buttons["knob.showing"]
        XCTAssertTrue(showing.waitForExistence(timeout: 30))
        XCTAssertFalse(app.buttons["knob.power"].isEnabled)
        showing.tap()
        app.buttons["Service 12 · 2 stops"].tap()
        app.buttons["knob.next"].tap()
        let face = app.buttons["knob.face"]
        XCTAssertTrue(face.label.contains("A VERY LONG CONNECTION EVENT NAME THAT MUST REMAIN READABLE WITH VOICEOVER"))
        XCTAssertTrue(face.label.contains("Waiting for the next event from the connected business application"))
    }

}
