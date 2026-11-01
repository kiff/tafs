import XCTest

/// Export my chats (kiff/tafs#424): the row prepares the person's own chats and offers
/// tafs-chats.json and tafs-chats.txt in the system's share and save sheet. The sample's
/// export stands in for tafs cloud's, so this runs with no server.
final class ExportChatsUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testTheRowOffersBothFiles() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-screen", "export"]
        app.launch()

        let row = app.buttons["export-chats"]
        XCTAssertTrue(row.waitForExistence(timeout: 30), "Export my chats")
        row.tap()
        // The system's share sheet opens with the files, and the row names both.
        let sheet = app.otherElements["ActivityListView"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 15), "the share sheet")
        let made = app.buttons["export-files"]
        XCTAssertTrue(made.waitForExistence(timeout: 10), "the files the export made")
        XCTAssertTrue(made.label.contains("tafs-chats.json") && made.label.contains("tafs-chats.txt"), "both files: \(made.label)")
        XCTAssertFalse(app.staticTexts["export-failed"].exists, "nothing failed")
    }
}
