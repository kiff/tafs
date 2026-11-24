import XCTest

/// A link in an answer opens only through `PageLinks.ask` (docs/chat.md, "The confirm"): after a long-press, tafs stays in front
/// and nothing opens, and a system opener it offers (Open Link, Call…) either isn't there or still asks first. A tap on a link
/// that won't open asks with Cancel alone. The sample taf's seeded answer carries a `tel:` and an https link
/// (`-sampleReplyLinks YES`), so this runs with no server.
final class LinkLongPressUITests: TafsUITestCase {
    @MainActor
    func testALinkInAnAnswerOpensOnlyThroughTheQuestion() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-screen", "chat", "-sampleReplyLinks", "YES"]
        launch(app)

        // The sample chat opens on the Support taf's seeded answer, which `-sampleReplyLinks` ends with the two links. Nothing is
        // sent: a new answer would carry them again.
        let telLabel = "tel:+15551234", webLabel = "https://shop.example/orders/5098"
        let tels = app.links.matching(NSPredicate(format: "label == %@", telLabel))
        let webs = app.links.matching(NSPredicate(format: "label == %@", webLabel))
        XCTAssertTrue(tels.firstMatch.waitForExistence(timeout: 30), "the tel: link in the answer")
        XCTAssertTrue(webs.firstMatch.exists, "the https link in the answer")

        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        /// tafs is still in front, and nothing else opened: not Safari, not a call.
        func stillHere(_ what: String) {
            XCTAssertEqual(app.state, .runningForeground, "\(what): tafs left the front")
            XCTAssertNotEqual(safari.state, .runningForeground, "\(what): Safari opened")
        }
        /// Away from any message: near the top, under the navigation bar, so it can't land on a link or a row. A long-press
        /// on the answer shows the app's own menu (Report this answer) over a dimmed screen; on iOS 26 a tap that high can land
        /// in the status bar and leave it up, and the next link has no point to press. So the menu must be gone: if it isn't,
        /// the dimmed screen is tapped lower, over the thread, which only dismisses it while it's shown.
        func menuShown() -> Bool { app.buttons["Report this answer"].exists || app.menuItems["Report this answer"].exists }
        /// Whether the answer's menu has gone, within 3 s.
        func menuGone() -> Bool {
            let end = Date().addingTimeInterval(3)
            while menuShown() && Date() < end { RunLoop.current.run(until: Date().addingTimeInterval(0.25)) }
            return !menuShown()
        }
        func dismiss() {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.05)).tap()
            if !menuGone() { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15)).tap() }
            XCTAssertTrue(menuGone(), "the answer's menu closed")
        }

        // Every element the tree has for a link is pressed: if iOS exposes one link twice, each copy must still go through
        // the question.
        for (links, what, question) in [(tels, "long-press on tel:", "This link won't open"), (webs, "long-press on https", nil)] {
            for (index, link) in links.allElementsBoundByIndex.enumerated() {
                let step = "\(what) (\(index + 1) of \(links.count))"
                if !link.isHittable { attach(app, "\(step): not hittable") }
                XCTAssertTrue(link.isHittable, "\(step): the link has a point to press")
                link.press(forDuration: 1.2)
                attach(app, step)
                stillHere(step)
                // A system opener the long-press offers must go through the question, never around it: one that isn't
                // offered at all is the expected case. Matched in English, as the runner's simulators are en_US: in another
                // language a pass here proves nothing.
                for name in ["Open Link", "Open", "Open in Safari", "Call", "Send Message", "FaceTime"] {
                    let item = app.buttons[name].exists ? app.buttons[name] : app.menuItems[name]
                    guard item.exists else { continue }
                    // A clean https link in a plain answer opens as before, so its opener isn't a way around the question:
                    // it's left untapped. The sample's answer has no page turn.
                    guard let question else { break }
                    item.tap()
                    stillHere("\(step), \(name)")
                    XCTAssertTrue(app.alerts[question].waitForExistence(timeout: 5), "\(step), \(name): opened without the question")
                    app.alerts[question].buttons["Cancel"].tap()
                    break
                }
                // Whatever else the long-press showed (a selection's Copy, or nothing), it goes before the next step.
                dismiss()
            }
        }

        // A tap on a link that won't open asks, with Cancel alone.
        tels.firstMatch.tap()
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
