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
        // The row names both files the export made, and the system's share sheet offers them.
        let made = app.buttons["export-files"]
        XCTAssertTrue(made.waitForExistence(timeout: 20), "the files the export made")
        XCTAssertTrue(made.label.contains("tafs-chats.json") && made.label.contains("tafs-chats.txt"), "both files: \(made.label)")
        XCTAssertFalse(app.staticTexts["export-failed"].exists, "nothing failed")
        // The sheet's own elements differ between iOS versions: any of its marks will do.
        let sheet = app.descendants(matching: .any).matching(NSPredicate(format:
            "identifier == 'ActivityListView' OR label == 'Save to Files' OR label == 'Copy' OR label == 'AirDrop'")).firstMatch
        XCTAssertTrue(sheet.waitForExistence(timeout: 30), "the share sheet")
    }
}
