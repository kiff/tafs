import SwiftUI
import XCTest
@testable import Tafs

/// The hardware keyboard's shortcuts on iPad (kiff/tafs#210), as the issue lists them.
final class KeyboardTests: XCTestCase {
    private func combo(_ c: KeyCommand) -> String {
        (c.modifiers.contains(.command) ? "⌘" : "") + {
            switch c.key.character {
            case KeyEquivalent.return.character: "↩"
            case KeyEquivalent.escape.character: "Esc"
            default: String(c.key.character)
            }
        }()
    }

    func testEachShortcutIsTheIssues() {
        XCTAssertEqual(combo(.send), "⌘↩")
        XCTAssertEqual(combo(.close), "Esc")
        XCTAssertEqual(combo(.newTaf), "⌘n")
        XCTAssertEqual(combo(.tafs), "⌘1")
        XCTAssertEqual(combo(.needsYou), "⌘2")
        XCTAssertEqual(combo(.record), "⌘3")
        XCTAssertEqual(combo(.account), "⌘,")
        XCTAssertEqual(combo(.stop), "⌘.")
        XCTAssertEqual(combo(.coach), "⌘k")
    }

    func testNoTwoShareAKeyAndEachHasATitleForTheList() {
        let combos = KeyCommand.allCases.map(combo)
        XCTAssertEqual(Set(combos).count, combos.count, "\(combos)")
        XCTAssertTrue(KeyCommand.allCases.allSatisfy { !$0.title.isEmpty })
    }

    /// The bar hides only for this app's keyboard. The simulator's "Sign in to Apple Account"
    /// alert raised its own keyboard over the Record tab; iOS told the app, `RootView` took the
    /// bar away, and `bar.Account` was gone until the alert closed (main runs 43572094803 and
    /// 43578782140, `testTheBarOpensEachTab`).
    @MainActor
    func testTheBarHidesOnlyForThisAppsOwnKeyboard() {
        func note(_ local: Bool?) -> Notification {
            Notification(name: UIResponder.keyboardWillShowNotification, object: nil,
                         userInfo: local.map { local -> [AnyHashable: Any] in [UIResponder.keyboardIsLocalUserInfoKey: local] })
        }
        XCTAssertTrue(RootView.ownKeyboard(note(true)))
        XCTAssertFalse(RootView.ownKeyboard(note(false)), "another's keyboard: a system alert's, an app beside this one")
        XCTAssertTrue(RootView.ownKeyboard(note(nil)), "a notification that doesn't say is this app's")
    }

    /// Both of the bar's keyboard handlers ask whose keyboard it is, and the bar's items carry
    /// their `bar.<title>` identifiers whatever the tab.
    func testTheBarsKeyboardHandlersAskWhoseKeyboardItIs() throws {
        let tafs = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        func source(_ path: String) throws -> String { try String(contentsOf: tafs.appendingPathComponent(path), encoding: .utf8) }
        let root = try source("App/TafsApp.swift")
        XCTAssertTrue(root.contains("keyboardWillShowNotification)) { if Self.ownKeyboard($0) { keyboardUp = true } }"))
        XCTAssertTrue(root.contains("keyboardWillHideNotification)) { if Self.ownKeyboard($0) { keyboardUp = false } }"))
        XCTAssertEqual(root.components(separatedBy: "keyboardUp = ").count - 1, 3, "its declaration and those two: nothing else sets it")
        let bar = try source("Views/TafsBar.swift")
        XCTAssertTrue(bar.contains(".accessibilityIdentifier(\"bar.\" + item.title)\n    }"), "the item's last modifier, unconditional")
    }
}
