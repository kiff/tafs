import XCTest
@testable import Tafs

/// The Mac's menu bar and window (docs/mac-app.md).
@MainActor
final class MacMenuTests: XCTestCase {
    private func model() -> AppModel { AppModel() }

    /// Every global shortcut is in the menu bar once, and no screen's own shortcut is.
    func testTheMenuBarHasEachGlobalShortcutOnce() {
        let inMenus = MacMenu.file + MacMenu.app + MacMenu.view
        XCTAssertEqual(Set(inMenus), Set(KeyCommand.global))
        XCTAssertEqual(inMenus.count, KeyCommand.global.count, "none twice")
        for own in [KeyCommand.send, .close, .stop] {
            XCTAssertFalse(inMenus.contains(own), "\(own) is its screen's")
        }
        XCTAssertEqual(Set(KeyCommand.global).union([.send, .close, .stop]), Set(KeyCommand.allCases), "a new shortcut is placed")
    }

    func testAMenuItemOpensItsTab() {
        let model = model()
        for (command, tab) in [(KeyCommand.tafs, Tab.tafs), (.needsYou, .needsYou), (.record, .record), (.account, .account)] {
            model.requestedTab = nil
            model.run(command)
            XCTAssertEqual(model.requestedTab, tab)
        }
    }

    func testNewTafAndCoachOpenCoachMode() {
        for command in [KeyCommand.newTaf, .coach] {
            let model = model()
            XCTAssertFalse(model.coachOpen)
            model.run(command)
            XCTAssertTrue(model.coachOpen, "\(command)")
        }
    }

    /// Send, Close and Stop are their screen's buttons: the menu bar does nothing for them.
    func testAScreensOwnShortcutDoesNothingFromTheMenuBar() {
        let model = model()
        for command in [KeyCommand.send, .close, .stop] {
            model.run(command)
            XCTAssertFalse(model.allows(command))
        }
        XCTAssertNil(model.requestedTab)
        XCTAssertFalse(model.coachOpen)
    }

    /// Signed out, nothing in the menu bar can be used.
    func testSignedOutEveryItemIsOff() {
        let model = model()
        XCTAssertFalse(model.signedIn)
        XCTAssertTrue(KeyCommand.allCases.allSatisfy { !model.allows($0) })
    }

    /// In the sample, the tabs open and Coach's items stay off: Coach needs the KIFF account.
    func testInTheSampleTheTabsOpenAndCoachIsOff() async {
        let model = model()
        await model.startSample()
        XCTAssertTrue(model.signedIn)
        for command in [KeyCommand.tafs, .needsYou, .record, .account] { XCTAssertTrue(model.allows(command), "\(command)") }
        XCTAssertFalse(model.allows(.newTaf))
        XCTAssertFalse(model.allows(.coach))
    }

    /// The window never goes narrower than an iPad held upright.
    func testTheWindowsSmallestSize() {
        XCTAssertGreaterThanOrEqual(MacWindow.minimumSize.width, 744)
        XCTAssertGreaterThanOrEqual(MacWindow.minimumSize.height, 600)
    }
}
