import XCTest

/// A link in an answer opens only through `PageLinks.ask` (docs/chat.md, "The confirm"): after a long-press, tafs stays in front
/// and nothing opens, and a system opener it offers (Open Link, Call…) either isn't there or still asks first. A tap on a link
/// that won't open asks with Cancel alone. The
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

        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        /// tafs is still in front, and nothing else opened: not Safari, not a call.
        func stillHere(_ what: String) {
            XCTAssertEqual(app.state, .runningForeground, "\(what): tafs left the front")
            XCTAssertNotEqual(safari.state, .runningForeground, "\(what): Safari opened")
        }

        for (link, what, question) in [(tel, "long-press on tel:", "This link won't open"), (web, "long-press on https", nil)] {
            link.press(forDuration: 1.2)
            attach(app, what)
            stillHere(what)
            // A system opener the long-press offers must go through the question, never around it (mac12's check): one that
            // isn't offered at all is the expected case.
            for name in ["Open Link", "Open", "Open in Safari", "Call", "Send Message", "FaceTime"] {
                let item = app.buttons[name].exists ? app.buttons[name] : app.menuItems[name]
                guard item.exists else { continue }
                // A clean https link in a plain answer opens as before, so its opener isn't a way around the question:
                // it's left untapped. The sample's answer has no page turn.
                guard let question else { break }
                item.tap()
                stillHere("\(what), \(name)")
                XCTAssertTrue(app.alerts[question].waitForExistence(timeout: 5), "\(what), \(name): opened without the question")
                app.alerts[question].buttons["Cancel"].tap()
                break
            }
            // Whatever else the long-press showed (a selection's Copy, or nothing), it goes before the next step.
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
