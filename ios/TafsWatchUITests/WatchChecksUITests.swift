import XCTest

/// The Apple Watch's checks that a simulator can show (docs/watch-app.md, "The demo phone"), run unattended: the watch
/// app as shipped, launched with `-watch-demo`, so it asks a phone of fixed answers (`WatchDemo`) instead of a signed-in iPhone.
/// Each test is one row of docs/parity.md's Watches table, read in the app's English words. What only a real watch can show (dictation, Hear's
/// speaker, the system's passcode prompt) stays the owner's check on a real watch.
final class WatchChecksUITests: XCTestCase {
    private let wait: TimeInterval = 15

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    /// The app, opened on the demo phone, with any of the demo's other switches.
    private func launch(_ more: String...) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-watch-demo", "-AppleLanguages", "(en)", "-AppleLocale", "en_GB"] + more
        app.launch()
        return app
    }

    /// Opens a page from Coach's home's Menu, as the person does: Coach, Inbox, Tafs or Account.
    private func open(_ page: String, in app: XCUIApplication) {
        let menu = app.buttons["Menu"]
        if !menu.waitForExistence(timeout: wait) { app.swipeUp() }
        XCTAssertTrue(menu.waitForExistence(timeout: wait), "Menu on Coach's home")
        menu.tap()
        let item = app.buttons[page]
        XCTAssertTrue(item.waitForExistence(timeout: wait), "\(page) in the Menu")
        item.tap()
    }

    /// The last of the buttons with this label: a confirmation's own button, over the screen that asked.
    private func confirm(_ label: String, in app: XCUIApplication) {
        let all = app.buttons.matching(NSPredicate(format: "label == %@", label))
        XCTAssertTrue(all.firstMatch.waitForExistence(timeout: wait), "\(label) to confirm")
        all.element(boundBy: max(all.count - 1, 0)).tap()
    }

    private func shows(_ text: String, in app: XCUIApplication, _ why: String = "") {
        let found = app.staticTexts[text].waitForExistence(timeout: wait) || app.buttons[text].waitForExistence(timeout: 1)
        XCTAssertTrue(found, why.isEmpty ? "shows \"\(text)\"" : why)
    }

    private func showsText(containing text: String, in app: XCUIApplication) {
        let match = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
        let found = match.waitForExistence(timeout: wait)
            || app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch.waitForExistence(timeout: 1)
        XCTAssertTrue(found, "shows a line with \"\(text)\"")
    }

    /// Coach's home with Ask Coach, never an error or an endless wait, and the unread line under it.
    func testCoachsHomeOpensWithAskCoachAndWhatWaits() {
        let app = launch()
        shows("Ask Coach", in: app)
        shows("Coach gets your words as text, never your voice.", in: app)
        shows("2 unread in Inbox", in: app, "the held calls, counted under Ask Coach (row 1)")
        shows("Menu", in: app)
    }

    /// The Menu lists the pages in order, with Halt now apart at the bottom.
    func testTheMenuListsThePages() {
        let app = launch()
        XCTAssertTrue(app.buttons["Menu"].waitForExistence(timeout: wait))
        app.buttons["Menu"].tap()
        for page in ["Coach", "Inbox", "Tafs", "Account"] {
            XCTAssertTrue(app.buttons[page].waitForExistence(timeout: wait), "\(page) in the Menu")
        }
        shows("Halt now", in: app)
    }

    /// Rows 1 and 4: the Inbox lists each held call with its taf and what it asks; one opened says to approve on the
    /// phone, with no Approve on the watch.
    func testTheInboxListsTheHeldCallsAndApproveIsThePhones() {
        let app = launch()
        open("Inbox", in: app)
        shows("Refunds", in: app)
        shows("Late parcels", in: app)
        app.staticTexts["Refunds"].tap()
        shows("Approve on your phone", in: app)
        XCTAssertFalse(app.buttons["Approve"].exists, "never an Approve on the watch")
        shows("Refuse", in: app)
    }

    /// Row 2: Refuse, confirmed, says it was refused once the phone has KIFF's answer, and the call leaves Needs you.
    func testRefuseSaysRefusedNothingWasSent() {
        let app = launch()
        open("Inbox", in: app)
        XCTAssertTrue(app.staticTexts["Refunds"].waitForExistence(timeout: wait))
        app.staticTexts["Refunds"].tap()
        XCTAssertTrue(app.buttons["Refuse"].waitForExistence(timeout: wait))
        app.buttons["Refuse"].tap()
        confirm("Refuse", in: app)
        shows("Refused. Nothing was sent.", in: app)
    }

    /// Row 15: each taf with its state in the phone's words, a paused one saying it still answers.
    func testYourTafsListsEachTafInThePhonesWords() {
        let app = launch()
        open("Tafs", in: app)
        shows("Refunds", in: app)
        shows("Late parcels", in: app)
        shows("Lead follow-up", in: app)
        showsText(containing: "It still answers what you send", in: app)
    }

    /// Rows 14 and 3: a taf's page, What it does, then Its settings › Halt this taf › Halt now, and it's halted, with
    /// Resume left to the phone.
    func testATafsPageAndHaltThisTaf() {
        let app = launch()
        open("Tafs", in: app)
        XCTAssertTrue(app.staticTexts["Refunds"].waitForExistence(timeout: wait))
        app.staticTexts["Refunds"].tap()
        shows("What it does", in: app)
        let settings = app.buttons["Its settings"]
        if !settings.waitForExistence(timeout: wait) { app.swipeUp() }
        XCTAssertTrue(settings.waitForExistence(timeout: wait), "Its settings on the taf's page")
        settings.tap()
        XCTAssertTrue(app.buttons["Halt this taf"].waitForExistence(timeout: wait))
        app.buttons["Halt this taf"].tap()
        confirm("Halt now", in: app)
        shows("Halted Refunds.", in: app)
    }

    /// Row 3 without a passcode: Halt asks nothing of the phone and says to set a screen lock.
    func testWithoutAPasscodeHaltSaysToSetOne() {
        let app = launch("-watch-demo-no-passcode")
        open("Tafs", in: app)
        XCTAssertTrue(app.staticTexts["Refunds"].waitForExistence(timeout: wait))
        app.staticTexts["Refunds"].tap()
        let settings = app.buttons["Its settings"]
        if !settings.waitForExistence(timeout: wait) { app.swipeUp() }
        settings.tap()
        XCTAssertTrue(app.buttons["Halt this taf"].waitForExistence(timeout: wait))
        app.buttons["Halt this taf"].tap()
        confirm("Halt now", in: app)
        shows("To refuse or halt from this watch, set a screen lock on it and unlock it.", in: app)
    }

    /// Rows 17 and 18: Account says who is signed in; Sign out, confirmed, signs out this watch alone.
    func testAccountAndSignOutOnThisWatch() {
        let app = launch()
        open("Account", in: app)
        shows("Signed in", in: app)
        shows("owner@example.com", in: app)
        let signOut = app.buttons["Sign out"]
        if !signOut.waitForExistence(timeout: wait) { app.swipeUp() }
        XCTAssertTrue(signOut.waitForExistence(timeout: wait), "Sign out on Account")
        signOut.tap()
        shows("Sign out on this watch?", in: app)
        confirm("Sign out", in: app)
        shows("Signed out on this watch. Your phone stays signed in.", in: app)
    }

    /// Row 30: the phone out of reach says so, with Try again, never a blank page.
    func testThePhoneOutOfReachSaysSo() {
        let app = launch("-watch-demo-unreachable")
        open("Tafs", in: app)
        shows("Your phone isn't in reach. Bring it closer, then try again.", in: app)
        shows("Try again", in: app)
    }

    /// The phone in reach but tafs cloud not: the watch says the phone couldn't reach tafs cloud (build 4024's line).
    func testThePhoneThatCantReachTafsCloudSaysSo() {
        let app = launch("-watch-demo-cloud-down")
        open("Tafs", in: app)
        shows("Your phone couldn't reach tafs cloud. Try again.", in: app)
    }
}
