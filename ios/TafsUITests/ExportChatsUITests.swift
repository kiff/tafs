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
        // The system's share sheet, with the two files.
        let sheet = app.otherElements["ActivityListView"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 15), "the share sheet")
        let named = NSPredicate(format: "label CONTAINS[c] '2 documents' OR label CONTAINS[c] 'tafs-chats' OR label CONTAINS[c] '2 files'")
        XCTAssertTrue(app.descendants(matching: .any).matching(named).firstMatch.waitForExistence(timeout: 10),
                      "the sheet offers the two files")
        XCTAssertFalse(app.staticTexts["export-failed"].exists, "nothing failed")
    }
}
