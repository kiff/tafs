import XCTest
@testable import Tafs

/// A table in a taf's Markdown is drawn as a table (kiff/tafs#211, 6.5), never as pipes.
final class MarkdownTableTests: XCTestCase {
    private func texts(_ rows: [[AttributedString]]) -> [[String]] {
        rows.map { $0.map { String($0.characters) } }
    }

    func testATableIsOneBlockWithItsHeaderFirst() throws {
        let blocks = MarkdownBlock.parse("Today's refunds:\n| Order | Amount |\n|---|--:|\n| T-3002 | $20.00 |\n| T-3003 | $8.50 |\n\nAll within the Card.")
        XCTAssertEqual(blocks.count, 3)
        XCTAssertEqual(blocks[0].kind, .paragraph)
        guard case .table(let rows) = blocks[1].kind else { return XCTFail("\(blocks[1].kind)") }
        XCTAssertEqual(texts(rows), [["Order", "Amount"], ["T-3002", "$20.00"], ["T-3003", "$8.50"]])
        XCTAssertEqual(blocks[2].kind, .paragraph)
        XCTAssertTrue(MarkdownTable.isNumeric(column: 1, of: rows))
        XCTAssertFalse(MarkdownTable.isNumeric(column: 0, of: rows), "an order id isn't an amount")
        let totals = [["Total"], ["20.00 USD"], ["8 EUR"]].map { $0.map { AttributedString($0) } }
        XCTAssertTrue(MarkdownTable.isNumeric(column: 0, of: totals), "an amount may carry its currency code")
    }

    func testCellsKeepTheirInlineMarkdownAndEscapedPipes() throws {
        let blocks = MarkdownBlock.parse("| Charge | Note |\n| :-- | :-- |\n| `ch_1` | **refunded** a \\| b |")
        guard case .table(let rows) = blocks.first?.kind else { return XCTFail("\(blocks)") }
        XCTAssertEqual(texts(rows), [["Charge", "Note"], ["ch_1", "refunded a | b"]], "no Markdown symbol shows")
    }

    func testPipesWithoutARuleLineAreText() {
        let blocks = MarkdownBlock.parse("| not a table |\nshe said yes | no")
        XCTAssertEqual(blocks.map(\.kind), [.paragraph])
        XCTAssertEqual(blocks.first.map { String($0.text.characters) }, "| not a table |\nshe said yes | no")
    }

    func testABulletWithAPipeStaysABullet() {
        let blocks = MarkdownBlock.parse("- refund | return")
        XCTAssertEqual(blocks.map(\.kind), [.bullet])
    }

    func testATableReadsAloudRowByRow() throws {
        guard case .table(let rows) = MarkdownBlock.parse("| Order | Amount |\n|---|---|\n| T-3002 | $20.00 |").first?.kind else {
            return XCTFail("no table")
        }
        XCTAssertEqual(MarkdownTable.spoken(rows), "Order: T-3002, Amount: $20.00")
        XCTAssertEqual(MarkdownTable.spoken([rows[0]]), "Order, Amount", "a header with no rows yet reads its columns")
    }
}
