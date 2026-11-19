import SwiftUI
import UIKit
import XCTest
@testable import TafsWatch

/// W7 (docs/watch-app.md, "W7"): the palette is Wear OS's, value for value, so both watches read alike.
final class WatchLookTests: XCTestCase {
    private func rgb(_ color: Color) -> [Int] {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        return [r, g, b].map { Int(($0 * 255).rounded()) }
    }

    func testThePaletteIsWearsValueForValue() {
        // android/wear/src/main/kotlin/bot/tafs/wear/Look.kt (#1451): send, coach, coachGround, held, done, danger.
        XCTAssertEqual(rgb(WatchLook.send), [0xE8, 0x53, 0x1D])
        XCTAssertEqual(rgb(WatchLook.coach), [0x7F, 0xA8, 0xFF])
        XCTAssertEqual(rgb(WatchLook.coachGround), [0x1E, 0x22, 0x31])
        XCTAssertEqual(rgb(WatchLook.onCoachGround), [0xE6, 0xEC, 0xFF])
        XCTAssertEqual(rgb(WatchLook.held), [0xFF, 0xB5, 0x47])
        XCTAssertEqual(rgb(WatchLook.done), [0x3F, 0xCF, 0x7E])
        XCTAssertEqual(rgb(WatchLook.danger), [0xFF, 0x5A, 0x5F])
        // W4's own bubble for your words (#1446), the same on both watches.
        XCTAssertEqual(rgb(WatchLook.mineGround), [0x3A, 0x2A, 0x22])
        XCTAssertEqual(rgb(WatchLook.onMineGround), [0xFF, 0xE6, 0xD6])
    }
}
