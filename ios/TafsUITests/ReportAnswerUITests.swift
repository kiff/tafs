import XCTest

/// Report this answer (kiff/tafs#421, Google Play's rule for content an AI generates): an
/// answer's menu opens the sheet, a reason and a note go with Send, and the sheet thanks the
/// person; reporting it again says it was reported already. The sample's answer stands in for
/// a taf's, so this runs with no server.
final class ReportAnswerUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testAnAnswersMenuReportsIt() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-screen", "chat"]
        app.launch()

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
        app.launch()
        let field = app.descendants(matching: .any)["chat-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 30), "the chat's field")
        field.tap()
        field.typeText("What did you do today?")
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
        // three times, until the menu shows.
        let item = app.buttons["Report this answer"]
        var shown = false
        for _ in 0..<3 where !shown {
            answer.press(forDuration: 1.2)
            shown = item.waitForExistence(timeout: 5)
        }
        XCTAssertTrue(shown, "Report this answer in the answer's menu")
        item.tap()
        let send = app.buttons["report-send"]
        XCTAssertTrue(send.waitForExistence(timeout: 5), "the sheet")
        XCTAssertFalse(send.isEnabled, "Send waits for a reason")
        app.buttons["report-reason-\(reason)"].tap()
        if let note {
            let field = app.descendants(matching: .any)["report-note"]
            field.tap()
            field.typeText(note)
        }
        XCTAssertTrue(send.isEnabled, "a reason is enough to send")
        send.tap()
    }

    @MainActor
    private func done(_ app: XCUIApplication, _ words: String) -> Bool {
        let shown = app.staticTexts["report-done"]
        return shown.waitForExistence(timeout: 10) && shown.label == words
    }
}
