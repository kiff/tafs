package bot.tafs.app

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import bot.tafs.app.design.MarkdownBlock
import bot.tafs.app.design.inlineMarkdown
import bot.tafs.app.design.isNumericColumn
import bot.tafs.app.design.parseMarkdown
import bot.tafs.app.design.tableCells
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
  // MARK: Blocks

  @Test fun consecutiveLinesAreOneParagraphWithItsLineBreaks() {
    assertEquals(listOf(MarkdownBlock.Paragraph("one\ntwo")), parseMarkdown("one\ntwo"))
  }

  @Test fun aBlankLineEndsAParagraph() {
    assertEquals(listOf(MarkdownBlock.Paragraph("one"), MarkdownBlock.Paragraph("two")), parseMarkdown("one\n\ntwo"))
  }

  @Test fun bulletsNumbersAndHeadingsAreTheirOwnBlocks() {
    val blocks = parseMarkdown("# Title\n- a\n• b\n1. first\n2) second\nplain")
    assertEquals(
      listOf(
        MarkdownBlock.Heading("Title"),
        MarkdownBlock.Bullet("a"),
        MarkdownBlock.Bullet("b"),
        MarkdownBlock.Numbered(1, "first"),
        MarkdownBlock.Numbered(2, "second"),
        MarkdownBlock.Paragraph("plain"),
      ),
      blocks,
    )
  }

  @Test fun aCodeBlockKeepsItsLinesAsWritten() {
    assertEquals(listOf(MarkdownBlock.Code("  a\nb")), parseMarkdown("```\n  a\nb\n```"))
  }

  @Test fun anOpenCodeBlockIsDrawnAsCode() {
    assertEquals(listOf(MarkdownBlock.Code("a")), parseMarkdown("```\na"))
  }

  @Test fun aRuleDrawsNothing() {
    assertEquals(listOf(MarkdownBlock.Paragraph("a"), MarkdownBlock.Paragraph("b")), parseMarkdown("a\n---\nb"))
  }

  @Test fun aTableIsItsHeaderAndRows() {
    val blocks = parseMarkdown("| Name | Amount |\n|---|---:|\n| Refund | 40.00 |\n| Credit | 25.00 |")
    assertEquals(
      listOf(MarkdownBlock.Table(listOf(listOf("Name", "Amount"), listOf("Refund", "40.00"), listOf("Credit", "25.00")))),
      blocks,
    )
  }

  @Test fun aHeaderWithNoRuleUnderItIsText() {
    val blocks = parseMarkdown("| a | b |")
    assertEquals(listOf(MarkdownBlock.Paragraph("| a | b |")), blocks)
  }

  @Test fun aTableEndsTheParagraphBeforeIt() {
    val blocks = parseMarkdown("Here:\n| a | b |\n|---|---|\n| 1 | 2 |")
    assertEquals(MarkdownBlock.Paragraph("Here:"), blocks[0])
    assertTrue(blocks[1] is MarkdownBlock.Table)
  }

  @Test fun anEscapedPipeStaysInItsCell() {
    assertEquals(listOf("a|b", "c"), tableCells("| a\\|b | c |"))
  }

  @Test fun aColumnOfAmountsIsNumeric() {
    val rows = listOf(listOf("Name", "Amount"), listOf("Refund", "\$40.00"), listOf("Credit", "20.00 USD"))
    assertTrue(isNumericColumn(1, rows))
    assertTrue(!isNumericColumn(0, rows))
  }

  // MARK: Inline

  @Test fun boldItalicAndCodeAreStyledAndTheirMarksGo() {
    val text = inlineMarkdown("a **bold** and *slanted* and `code`")
    assertEquals("a bold and slanted and code", text.text)
    assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && text.text.substring(it.start, it.end) == "bold" })
    assertTrue(text.spanStyles.any { it.item.fontStyle == FontStyle.Italic && text.text.substring(it.start, it.end) == "slanted" })
    assertTrue(text.spanStyles.any { it.item.fontFamily != null && text.text.substring(it.start, it.end) == "code" })
  }

  @Test fun aLinkShowsItsTextOnly() {
    val text = inlineMarkdown("see [the receipt](https://kiff.dev/r/1) now")
    assertEquals("see the receipt now", text.text)
    assertEquals(1, text.getLinkAnnotations(0, text.length).size)
  }

  @Test fun aMarkNeverClosedShowsAsWritten() {
    assertEquals("a **b", inlineMarkdown("a **b").text)
    assertEquals("a `b", inlineMarkdown("a `b").text)
  }

  @Test fun snakeCaseIsNotItalic() {
    val text = inlineMarkdown("pay_invoice and grant_access")
    assertEquals("pay_invoice and grant_access", text.text)
    assertTrue(text.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
  }

  @Test fun anAsteriskBeforeASpaceIsNotAMark() {
    assertEquals("2 * 3 * 4", inlineMarkdown("2 * 3 * 4").text)
  }

  @Test fun anEscapedMarkIsLiteral() {
    assertEquals("*not bold*", inlineMarkdown("\\*not bold\\*").text)
  }
}
