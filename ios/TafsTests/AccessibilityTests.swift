import SwiftUI
import UIKit
import XCTest
@testable import Tafs

/// Accessibility (kiff/tafs#210, part 4): text 4.5:1 on what it sits on, a hold read as one
/// sentence, and a Card read balance first.
@MainActor
final class AccessibilityTests: XCTestCase {
    private func luminance(_ color: Color) -> Double {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        func lin(_ c: CGFloat) -> Double { let c = Double(c); return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    private func ratio(_ a: Color, _ b: Color) -> Double {
        let (x, y) = (luminance(a), luminance(b))
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    func testWhiteTextOnTheBrandsStopsTafColoursAndStatusFills() {
        let brandStops = [Color(hex: 0xB35C00), Color(hex: 0xE8003A), Theme.blue]
        for fill in brandStops + Theme.tafColors + [Theme.ok, Theme.no, Theme.coach] {
            XCTAssertGreaterThanOrEqual(ratio(.white, fill), 4.5, "\(fill)")
        }
    }

    func testColouredTextOnTheBackgroundAndPanels() {
        for text in [Theme.ink, Theme.soft, Theme.dim, Theme.amberText, Theme.ok, Theme.no] {
            XCTAssertGreaterThanOrEqual(ratio(text, Theme.background), 4.5, "\(text)")
            XCTAssertGreaterThanOrEqual(ratio(text, Theme.panel), 4.5, "\(text)")
        }
    }

    func testTheGreyedSendAnswersButtonIsReadable() {
        // Disabled: soft text on dim at 22% over a panel.
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(Theme.dim).getRed(&r, green: &g, blue: &b, alpha: &a)
        let over = Color(red: 1 - 0.22 * (1 - r), green: 1 - 0.22 * (1 - g), blue: 1 - 0.22 * (1 - b))
        XCTAssertGreaterThanOrEqual(ratio(Theme.soft, over), 4.5)
    }

    func testAHoldReadsAsOneSentence() {
        let call = HeldCall(tafID: UUID(), proposed: ProposedAction(action: "refund", summary: "Refund order 42", amount: .usd(150)),
                            reason: "Over $50 each", heldAt: Date(), expiresAt: Date(timeIntervalSince1970: 0))
        let said = HeldCallCard.sentence(call, taf: "Rae")
        XCTAssertTrue(said.hasPrefix("Rae asks: Refund order 42, "), said)
        XCTAssertTrue(said.contains("150"), said)
        XCTAssertTrue(said.contains(". Over $50 each. It lapses at "), said)
    }

    func testACardReadsItsBalanceFirst() {
        let rule = CardRule(action: "refund", label: "Refunds", perWindow: .usd(500))
        let card = TafCard.merged(id: "c", title: "Refunds", rules: [rule], expires: nil, used: ["refund": .usd(200)])
        let said = TafCardFace.spoken(card, issuedBy: "Ana", revoked: false)
        let hero = card.hero.map { "\($0.figure) \($0.words)" } ?? ""
        XCTAssertFalse(hero.isEmpty)
        XCTAssertTrue(said.hasPrefix(hero), said)
        // A Card that acts for real names no environment: "Live" read as live money (the 870 test).
        XCTAssertTrue(said.contains(". Refunds. "), said)
        XCTAssertFalse(said.contains("LIVE"), said)
        XCTAssertTrue(said.hasSuffix("Issued by Ana. No end date."), said)
    }
}
