import XCTest

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
}
