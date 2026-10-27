import XCTest

/// The owner's 845 test, pinned so it can't come back: a sent message goes to the top of the
/// chat at once and stays there while the taf thinks, works and answers; the work block sits
/// under it with the answer below, and it folds in place to "Worked for" when the run ends.
/// The sample taf streams its answer as a real one does, so this runs with no server; it is
/// slowed so each part lasts longer than a slow simulator takes to look.
final class ChatPinUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testASentMessageGoesToTheTopAndTheWorkSitsUnderIt() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-screen", "chat", "-sampleReplySeconds", "20"]
        app.launch()

        let field = app.descendants(matching: .any)["chat-field"]
        XCTAssertTrue(field.waitForExistence(timeout: 30), "the chat's field")
        let words = "Which refunds went out this morning?"
        field.tap()
        field.typeText(words)
        app.buttons["chat-send"].tap()

        let thread = app.descendants(matching: .any)["chat-thread"]
        let sent = app.staticTexts.matching(identifier: "owner-message").matching(NSPredicate(format: "label == %@", words)).firstMatch
        XCTAssertTrue(sent.waitForExistence(timeout: 3), "the sent message")
        /// How far the sent message is from the top of the thread.
        func fromTop() -> CGFloat { sent.frame.minY - thread.frame.minY }
        /// Where things are, and what the pin says, for a failure.
        func seen() -> String { "\(Int(fromTop())) pt down; message \(sent.frame), thread \(thread.frame); \(thread.value ?? "")" }
        let atTop: CGFloat = 90

        // At the top within a moment of Send, before any answer: never at the end of the run.
        XCTAssertTrue(within(1.0) { fromTop() < atTop }, "the sent message reached the top at once: \(seen())")

        // While the taf thinks and works: still at the top, one work block under it.
        let work = app.descendants(matching: .any)["work-block"]
        XCTAssertTrue(work.waitForExistence(timeout: 10), "the work block")
        XCTAssertLessThan(fromTop(), atTop, "it stayed at the top while the taf worked: \(seen())")
        XCTAssertGreaterThanOrEqual(work.frame.minY, sent.frame.maxY - 1, "the work block is under the message")
        let workAt = work.frame.minY

        // While it writes: the answer streams below the work block, and nothing above moved.
        let answer = app.descendants(matching: .any)["answer-streaming"]
        XCTAssertTrue(answer.waitForExistence(timeout: 60), "the streaming answer")
        XCTAssertGreaterThanOrEqual(answer.frame.minY, work.frame.maxY - 1, "the answer streams below the work block")
        XCTAssertLessThan(fromTop(), atTop, "it stayed at the top while the answer streamed: \(seen())")

        // Done: the block folds in place to "Worked for", still under the message, above the answer.
        let summary = app.descendants(matching: .any)["work-summary"]
        XCTAssertTrue(summary.waitForExistence(timeout: 120), "Worked for")
        XCTAssertGreaterThanOrEqual(summary.frame.minY, sent.frame.maxY - 1, "Worked for is under the message")
        XCTAssertLessThan(abs(summary.frame.minY - workAt), 24, "it folded in place, without moving")
        XCTAssertLessThan(fromTop(), atTop, "the message is still at the top: \(seen())")

        // Back from another app: the chat shows its messages, not a blank screen (845, item 3).
        XCUIDevice.shared.press(.home)
        RunLoop.current.run(until: Date().addingTimeInterval(2))
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
        XCTAssertTrue(within(3.0) { sent.exists && thread.frame.intersects(sent.frame) && sent.isHittable },
                      "the sent message is on screen after coming back")
        XCTAssertTrue(summary.exists && thread.frame.intersects(summary.frame), "Worked for is on screen after coming back")
    }

    /// Whether `condition` holds within `seconds`, checked every tenth of a second.
    @MainActor
    private func within(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        repeat {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        } while Date() < end
        return condition()
    }
}
