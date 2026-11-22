import XCTest

/// Every UI test's base. In CI the simulator's springboard can raise "Sign in to Apple Account"
/// in the middle of a test. XCTest's default handler looks for a `cancel-button` it doesn't
/// have and spends about two minutes on each try, taking the taps and the keyboard from the
/// test. `launch(_:)` puts one interruption monitor in place first that closes that alert by its
/// own button, and then lets it run.
class TafsUITestCase: XCTestCase {
    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    /// Launches `app` with the monitor in place, then lets the monitor close an alert already up.
    @MainActor
    func launch(_ app: XCUIApplication) {
        // Monitors run before XCTest's default handler, at the test's next interaction while
        // an alert is in the way, and stay for the whole test, across a relaunch.
        _ = addUIInterruptionMonitor(withDescription: "Sign in to Apple Account") { alert in
            // Only this alert: any other (a permission prompt, where the last button is "Allow")
            // is left to the test and XCTest's own handling.
            // Springboard's title has a non-breaking space between "Apple" and "Account" (U+00A0).
            let title = alert.label.replacingOccurrences(of: "\u{00A0}", with: " ")
            guard title.contains("Apple Account") else { return false }
            // The alert's own way out, in this order; else its last button.
            for title in ["Not Now", "Cancel", "Later", "Close"] {
                let button = alert.buttons.matching(NSPredicate(format: "label == %@ OR identifier == %@", title, title)).firstMatch
                if button.exists {
                    button.tap()
                    return true
                }
            }
            let count = alert.buttons.count
            guard count > 0 else { return false }
            alert.buttons.element(boundBy: count - 1).tap()
            return true
        }
        // As with Reduce Motion: nothing animates on its own, so the app goes idle between steps. An endless
        // animation kept it busy, and XCTest waited a minute before each step until a test ran out of time.
        if !app.launchArguments.contains("-reduceMotion") { app.launchArguments += ["-reduceMotion", "YES"] }
        app.launch()
        letTheMonitorRun(app)
    }

    /// A monitor only fires when the test next interacts with the app (Apple's docs): a tap on
    /// the status bar's far end, where the app has nothing to hit, is that interaction. With
    /// nothing in the way it's a status bar tap, which at most scrolls a list to its top.
    @MainActor
    func letTheMonitorRun(_ app: XCUIApplication) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.005)).tap()
    }

    /// Types `text` into `field` once it has the keyboard, with no alert up. Springboard's "Sign in to
    /// Apple Account" can come up at any moment: closing it, at the test's next interaction, takes the
    /// keyboard from the field. When it came between the focus check and the typing, `typeText` found no
    /// focus and the test failed after minutes of waiting (ReportAnswerUITests, ChatPinUITests in CI).
    /// So an alert already up is closed first, and the field is tapped again until it has the keyboard
    /// with none up, three times at most.
    ///
    /// `tolerateLostKeyboard`: the alert can also come in the last instant, inside `typeText`'s own check for interruptions:
    /// XCTest closes it, its Cancel takes the keyboard, and the event fails with "Neither element nor any descendant has keyboard
    /// focus" (main's route walk, 22 Nov, at a different route each run). A caller that reads back what was typed, and types
    /// again when it isn't whole, passes true: that one failure is then expected, and a persistent one still fails the caller.
    @MainActor
    func typeInto(_ field: XCUIElement, text: String, tolerateLostKeyboard: Bool = false) {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for _ in 0..<3 {
            if springboard.alerts.firstMatch.exists { letTheMonitorRun(XCUIApplication()) }
            field.tap()
            if hasFocus(field), !springboard.alerts.firstMatch.exists { break }
        }
        XCTAssertTrue(hasFocus(field), "the field has the keyboard")
        guard tolerateLostKeyboard else {
            field.typeText(text)
            return
        }
        let options = XCTExpectedFailure.Options()
        options.isStrict = false
        XCTExpectFailure("a system alert took the keyboard; the caller reads back what was typed and tries again", options: options) {
            field.typeText(text)
        }
    }

    /// Whether `field` takes the keyboard within five seconds of a tap (a slow simulator's
    /// moment): its own focus where it says, else the keyboard being up.
    @MainActor
    private func hasFocus(_ field: XCUIElement) -> Bool {
        let keyboard = XCUIApplication().keyboards.firstMatch
        func focused() -> Bool {
            if let focus = field.value(forKey: "hasKeyboardFocus") as? Bool { return focus }
            return keyboard.exists
        }
        let end = Date().addingTimeInterval(5)
        repeat {
            if focused() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        } while Date() < end
        return focused()
    }
}
