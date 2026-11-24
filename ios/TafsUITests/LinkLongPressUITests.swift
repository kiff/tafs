import XCTest

/// A link in an answer opens only through `PageLinks.ask` (docs/chat.md, "The confirm"): a long-press offers no Open Link and
/// no preview, which would open it without the question, and a tap on a link that won't open asks with Cancel alone. The
/// sample taf's answer carries a `tel:` and an https link (`-sampleReplyLinks YES`), so this runs with no server.
final class LinkLongPressUITests: TafsUITestCase {
    @MainActor
    func testALinkInAnAnswerOpensOnlyThroughTheQuestion() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-screen", "chat", "-sampleReplyLinks", "YES"]
        launch(app)

        let field = app.descendants(matching: .any)["chat-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 30), "the chat's field")
        typeInto(field, text: "What did you do this morning?")
        app.buttons["chat-send"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["work-summary"].waitForExistence(timeout: 120), "the answer finished")

        let tel = app.links["tel:+15551234"]
        let web = app.links["https://shop.example/orders/5098"]
        XCTAssertTrue(tel.waitForExistence(timeout: 10), "the tel: link in the answer")
        XCTAssertTrue(web.exists, "the https link in the answer")

        /// Nothing on screen offers to open a link without the question: no Open Link item, no preview's Open.
        func noOpenLink(_ what: String) {
            for name in ["Open Link", "Open", "Open in Safari"] {
                XCTAssertFalse(app.buttons[name].exists || app.menuItems[name].exists, "\(what): \(name) is offered")
            }
            attach(app, what)
        }

        for (link, what) in [(tel, "long-press on tel:"), (web, "long-press on https")] {
            link.press(forDuration: 1.2)
            noOpenLink(what)
            // Whatever the long-press showed (a selection's Copy, or nothing), it goes before the next step.
            app.tap()
        }

        // A tap on a link that won't open asks, with Cancel alone.
        tel.tap()
        let blocked = app.alerts["This link won't open"]
        XCTAssertTrue(blocked.waitForExistence(timeout: 5), "the question for a link that won't open")
        XCTAssertFalse(blocked.buttons["Open"].exists, "no Open for a link that won't open")
        attach(app, "tap on tel:")
        blocked.buttons["Cancel"].tap()
        XCTAssertFalse(blocked.exists)
    }

    private func attach(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
