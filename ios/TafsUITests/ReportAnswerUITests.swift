import XCTest

/// Report this answer (kiff/tafs#421, Google Play's rule for content an AI generates): an
/// answer's menu opens the sheet, a reason and a note go with Send, and the sheet thanks the
/// person; reporting it again says it was reported already. The sample's answer stands in for
/// a taf's, so this runs with no server.
final class ReportAnswerUITests: TafsUITestCase {
    @MainActor
    func testAnAnswersMenuReportsIt() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-screen", "chat"]
        launch(app)

        let answer = app.descendants(matching: .any).matching(identifier: "taf-message").firstMatch
        XCTAssertTrue(answer.waitForExistence(timeout: 30), "the taf's answer")
        report(app, answer, reason: "wrong", note: "It named the wrong order")
        XCTAssertTrue(done(app, "Thanks. Someone at KIFF will read your report."), "the thanks")
        app.buttons["Done"].tap()

        report(app, answer, reason: "other", note: nil)
        XCTAssertTrue(done(app, "You've already reported this answer."), "reported again")
        app.buttons["Done"].tap()
        XCTAssertTrue(answer.waitForExistence(timeout: 5), "back in the chat")
    }

    /// The person's own message has no Report this answer.
    @MainActor
    func testTheirOwnMessageHasNoReport() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-screen", "chat"]
        launch(app)
        let field = app.descendants(matching: .any)["chat-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 30), "the chat's field")
        typeInto(field, text: "What did you do today?")
        app.buttons["chat-send"].tap()
        let mine = app.staticTexts.matching(identifier: "owner-message").firstMatch
        XCTAssertTrue(mine.waitForExistence(timeout: 10), "the sent message")
        mine.press(forDuration: 1.2)
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        XCTAssertFalse(app.buttons["Report this answer"].exists, "never on the person's own words")
    }

    @MainActor
    private func report(_ app: XCUIApplication, _ answer: XCUIElement, reason: String, note: String?) {
        // A slow simulator can take the press before the menu is ready: press again, up to
        // three times, until the menu shows. Shown means its item can be tapped: while the menu
        // still animates in, the item exists with no hit point, and a tap then fails.
        // Springboard's "Sign in to Apple Account" can also come up just as the item is tapped:
        // the monitor's Cancel closes the menu with it, and the tap lands on nothing (main at
        // ada9c47). So an alert already up is closed first, and the menu is opened and its item
        // tapped again until the sheet shows, three times at most.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let item = app.buttons["Report this answer"]
        let send = app.buttons["report-send"]
        // No assertion inside the loop: with continueAfterFailure off, a failed attempt would end
        // the test before the next one. Each check is asserted after it.
        var menuShown = false
        var sheet = false
        for _ in 0..<3 where !sheet {
            if springboard.alerts.firstMatch.exists { letTheMonitorRun(app) }
            var shown = false
            // The last report's sheet can still be sliding away after Done, covering the answer, and a
            // press then fails the test (tafs#1611's run): wait until the sheet's Send is gone. Never
            // `isHittable` on the answer: where XCTest can't compute its hit point, asking fails the test
            // (#1614's first run) though the press itself works.
            _ = gone(send, within: 10)
            for _ in 0..<3 where !shown {
                pressOnScreen(app, answer)
                shown = item.waitForExistence(timeout: 5) && hittable(item, within: 5)
            }
            if !shown { continue }   // this attempt didn't get the menu: try again
            menuShown = true
            item.tap()
            sheet = send.waitForExistence(timeout: 5)
        }
        XCTAssertTrue(menuShown, "Report this answer in the answer's menu")
        XCTAssertTrue(sheet, "the sheet")
        XCTAssertFalse(send.isEnabled, "Send waits for a reason")
        // The tap on a reason lands before Send updates on a slow simulator: wait for it, as for the sheet.
        // Springboard's "Sign in to Apple Account" can also take that tap: the monitor closes the alert and
        // the reason stays unpicked (#1618's run). So an alert already up is closed first, and the reason is
        // tapped again until Send takes it, three times at most, with the assertion after.
        let pick = app.buttons["report-reason-\(reason)"]
        var picked = false
        for _ in 0..<3 where !picked {
            if springboard.alerts.firstMatch.exists { letTheMonitorRun(app) }
            pick.tap()
            picked = waitUntilEnabled(send, timeout: 5)
        }
        XCTAssertTrue(picked, "a reason is enough to send")
        if let note {
            let field = app.descendants(matching: .any)["report-note"]
            typeInto(field, text: note)
        }
        XCTAssertTrue(waitUntilEnabled(send, timeout: 5), "still enabled with the note")
        send.tap()
    }

    /// Whether `element` is enabled within `timeout` seconds.
    @MainActor
    private func waitUntilEnabled(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: element)
        return XCTWaiter().wait(for: [enabled], timeout: timeout) == .completed
    }

    @MainActor
    private func done(_ app: XCUIApplication, _ words: String) -> Bool {
        let shown = app.staticTexts["report-done"]
        return shown.waitForExistence(timeout: 10) && shown.label == words
    }

    /// A long press on the part of `element` that's on screen, clear of the bars: a press at the centre of an answer that
    /// runs under the navigation bar or the compose bar lands on the bar, and the menu never opens (on iOS 27.2 the test
    /// failed there twice in three runs at one head, and passed on the third). With no such part, the element's own press.
    @MainActor
    private func pressOnScreen(_ app: XCUIApplication, _ element: XCUIElement) {
        let window = app.windows.firstMatch.frame
        let visible = element.frame.intersection(window.insetBy(dx: 0, dy: 120))
        guard !visible.isNull, visible.height >= 12, visible.width >= 12 else { return element.press(forDuration: 1.2) }
        app.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: visible.midX - window.minX, dy: visible.midY - window.minY))
            .press(forDuration: 1.2)
    }

    /// Whether `element` is gone within `seconds`: no longer in the app's tree.
    @MainActor
    private func gone(_ element: XCUIElement, within seconds: TimeInterval) -> Bool {
        let away = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: element)
        return XCTWaiter().wait(for: [away], timeout: seconds) == .completed
    }

    /// Whether `element` can be tapped within `seconds`: it exists and has a hit point.
    @MainActor
    private func hittable(_ element: XCUIElement, within seconds: TimeInterval) -> Bool {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: element)
        return XCTWaiter().wait(for: [ready], timeout: seconds) == .completed
    }
}
