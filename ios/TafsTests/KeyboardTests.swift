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
        XCTAssertEqual(combo(.stop), "⌘.")
        XCTAssertEqual(combo(.coach), "⌘k")
    }

    func testNoTwoShareAKeyAndEachHasATitleForTheList() {
        let combos = KeyCommand.allCases.map(combo)
        XCTAssertEqual(Set(combos).count, combos.count, "\(combos)")
        XCTAssertTrue(KeyCommand.allCases.allSatisfy { !$0.title.isEmpty })
    }
}
