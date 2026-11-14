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
            guard alert.label.contains("Apple Account") else { return false }
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

    /// Types `text` into `field` once it has the keyboard. The tap that gives it focus can be
    /// taken by the monitor closing an alert: then the field is tapped once more.
    @MainActor
    func typeInto(_ field: XCUIElement, text: String) {
        field.tap()
        if !hasFocus(field) {
            field.tap()
        }
        XCTAssertTrue(hasFocus(field), "the field has the keyboard")
        field.typeText(text)
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
