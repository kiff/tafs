package bot.tafs.app.design

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One block of a message, its text still as Markdown inline syntax. */
sealed interface MarkdownBlock {
  data class Paragraph(val text: String) : MarkdownBlock
  data class Heading(val text: String) : MarkdownBlock
  data class Bullet(val text: String) : MarkdownBlock
  data class Numbered(val n: Int, val text: String) : MarkdownBlock
  data class Code(val text: String) : MarkdownBlock

  /** A table's rows, the header first, each cell still as Markdown. */
  data class Table(val rows: List<List<String>>) : MarkdownBlock
}

private val headingRe = Regex("""^#{1,6}\s+(.+)$""")
private val bulletRe = Regex("""^[-*•+]\s+(.+)$""")
private val numberedRe = Regex("""^(\d{1,3})[.)]\s+(.+)$""")

/**
 * Splits Markdown into blocks, line by line (kiff/tafs#58): paragraphs, bullet and numbered
 * lists, headings, code blocks and tables. Consecutive plain lines are one paragraph, with their
 * line breaks kept; a blank line ends it.
 */
fun parseMarkdown(source: String): List<MarkdownBlock> {
  val out = mutableListOf<MarkdownBlock>()
  var paragraph = mutableListOf<String>()
  var code: MutableList<String>? = null
  var table = mutableListOf<String>()

  fun flushTable() {
    // A header, its |---| line and the rows; anything less is text, as written.
    if (table.size >= 2 && isTableRule(table[1])) {
      out += MarkdownBlock.Table((listOf(table[0]) + table.drop(2)).map(::tableCells))
    } else {
      paragraph.addAll(table)
    }
    table = mutableListOf()
  }

  fun flush() {
    flushTable()
    if (paragraph.isNotEmpty()) out += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
    paragraph = mutableListOf()
  }

  for (raw in source.replace("\r\n", "\n").split("\n")) {
    val line = raw.trim()
    if (line.startsWith("```")) {
      val open = code
      if (open != null) {
        out += MarkdownBlock.Code(open.joinToString("\n"))
        code = null
      } else {
        flush()
        code = mutableListOf()
      }
      continue
    }
    if (code != null) {
      code.add(raw)
      continue
    }
    if (line.isEmpty()) {
      flush()
      continue
    }
    // A table's lines go together, and become one block once it ends: a line that starts one (a
    // pipe first, two cells or more) ends the paragraph before it.
    if (line.contains("|") && (table.isNotEmpty() || (line.startsWith("|") && tableCells(line).size >= 2))) {
      if (table.isEmpty() && paragraph.isNotEmpty()) {
        out += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
        paragraph = mutableListOf()
      }
      table.add(line)
      continue
    }
    flushTable()
    val heading = headingRe.find(line)
    val bullet = bulletRe.find(line)
    val numbered = numberedRe.find(line)
    when {
      heading != null -> { flush(); out += MarkdownBlock.Heading(heading.groupValues[1]) }
      bullet != null -> { flush(); out += MarkdownBlock.Bullet(bullet.groupValues[1]) }
      numbered != null -> { flush(); out += MarkdownBlock.Numbered(numbered.groupValues[1].toIntOrNull() ?: 1, numbered.groupValues[2]) }
      // A rule: nothing to draw.
      line.length >= 3 && line.all { it == '-' || it == '*' || it == '_' } -> flush()
      else -> paragraph.add(line)
    }
  }
  code?.let { out += MarkdownBlock.Code(it.joinToString("\n")) }
  flush()
  return out
}

/** A table row's cells: the text between its pipes, without the outer ones, trimmed. A pipe written as \| stays in its cell. */
fun tableCells(line: String): List<String> {
  var row = line.trim()
  if (row.startsWith("|")) row = row.drop(1)
  if (row.endsWith("|") && !row.endsWith("\\|")) row = row.dropLast(1)
  val marker = "\u001F"
  return row.replace("\\|", marker).split("|").map { it.replace(marker, "|").trim() }
}

/** The line under a table's header: a run of dashes per column, colons allowed. */
fun isTableRule(line: String): Boolean {
  val cells = tableCells(line)
  return cells.size >= 2 && cells.all { c -> c.isNotEmpty() && c.contains("-") && c.all { it == '-' || it == ':' } }
}

/** A column whose cells below the header are all amounts or numbers. */
fun isNumericColumn(column: Int, rows: List<List<String>>): Boolean {
  val body = rows.drop(1).mapNotNull { it.getOrNull(column) }.filter { it.isNotEmpty() }
  if (body.isEmpty()) return false
  val allowed = "0123456789.,-+%$€£¥ "
  return body.all { cell ->
    // An amount may carry a currency code ("20.00 USD"); any other letter makes it text.
    val bare = cell.replace(Regex("""\b[A-Z]{3}\b"""), "")
    bare.any(Char::isDigit) && bare.all { it in allowed }
  }
}

/**
 * Bold, italic, links and inline code; the text as written when it isn't Markdown. A mark that is
 * never closed shows as written.
 */
fun inlineMarkdown(text: String, linkColor: Color = Theme.blue, codeBackground: Color = Theme.raised): AnnotatedString =
  buildAnnotatedString { appendInline(text, linkColor, codeBackground) }

private val linkRe = Regex("""^\[([^\]]*)]\(([^)]*)\)""")

private fun AnnotatedString.Builder.appendInline(text: String, linkColor: Color, codeBackground: Color) {
  var i = 0
  while (i < text.length) {
    val c = text[i]
    when {
      c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_[]()#+-.!|" -> { append(text[i + 1]); i += 2 }
      text.startsWith("**", i) -> {
        val end = text.indexOf("**", i + 2)
        if (end > i + 2) {
          withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(text.substring(i + 2, end), linkColor, codeBackground) }
          i = end + 2
        } else { append("**"); i += 2 }
      }
      c == '`' -> {
        val end = text.indexOf('`', i + 1)
        if (end > i + 1) {
          withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(text.substring(i + 1, end)) }
          i = end + 1
        } else { append(c); i += 1 }
      }
      (c == '*' || c == '_') && opensItalic(text, i) -> {
        val end = closesItalic(text, i)
        if (end > i + 1) {
          withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(text.substring(i + 1, end), linkColor, codeBackground) }
          i = end + 1
        } else { append(c); i += 1 }
      }
      c == '[' -> {
        val m = linkRe.find(text.substring(i))
        if (m != null) {
          val url = m.groupValues[2]
          withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
            append(m.groupValues[1])
          }
          i += m.value.length
        } else { append(c); i += 1 }
      }
      else -> { append(c); i += 1 }
    }
  }
}

/** An italic mark opens before a word, never inside one (snake_case) and never before a space. */
private fun opensItalic(text: String, i: Int): Boolean {
  val next = text.getOrNull(i + 1) ?: return false
  if (next.isWhitespace() || next == text[i]) return false
  if (text[i] == '_') {
    val before = text.getOrNull(i - 1)
    if (before != null && before.isLetterOrDigit()) return false
  }
  return true
}

private fun closesItalic(text: String, from: Int): Int {
  val mark = text[from]
  var j = from + 1
  while (j < text.length) {
    if (text[j] == mark && !text[j - 1].isWhitespace() && (mark == '*' || text.getOrNull(j + 1)?.isLetterOrDigit() != true)) return j
    j += 1
  }
  return -1
}

/** A message's Markdown, drawn as text: no Markdown symbol shows as written. */
@Composable
fun MarkdownText(text: String, size: Int = 15, color: Color = Theme.ink, modifier: Modifier = Modifier) {
  val blocks = parseMarkdown(text)
  // Lines about 1.4 times the text's size apart: the font's own 1.2, and a fifth more.
  val style = scaled(size, color = color).copy(lineHeight = (size * 1.4f).sp)
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    blocks.forEach { block ->
      when (block) {
        is MarkdownBlock.Paragraph -> Text(inlineMarkdown(block.text), style = style)
        is MarkdownBlock.Heading -> Text(inlineMarkdown(block.text), style = style.copy(fontSize = (size + 1).sp, fontWeight = FontWeight.SemiBold))
        is MarkdownBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("•", style = style)
          Text(inlineMarkdown(block.text), style = style, modifier = Modifier.weight(1f, fill = false))
        }
        is MarkdownBlock.Numbered -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("${block.n}.", style = style.copy(fontFeatureSettings = "tnum"))
          Text(inlineMarkdown(block.text), style = style, modifier = Modifier.weight(1f, fill = false))
        }
        is MarkdownBlock.Code -> Text(
          block.text,
          style = scaled(size - 2, color = color, mono = true),
          modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Theme.raised).padding(8.dp),
        )
        is MarkdownBlock.Table -> MarkdownTable(block.rows, size)
      }
    }
  }
}

/**
 * A Markdown table, drawn: the header in bold, a line under it and between rows, a column of
 * numbers aligned on the right. A table wider than the message scrolls sideways.
 */
@Composable
private fun MarkdownTable(rows: List<List<String>>, size: Int) {
  val columns = rows.maxOfOrNull { it.size } ?: 0
  Row(Modifier.horizontalScroll(rememberScrollState())) {
    for (c in 0 until columns) {
      val trailing = isNumericColumn(c, rows)
      Column(horizontalAlignment = if (trailing) Alignment.End else Alignment.Start) {
        rows.forEachIndexed { r, row ->
          Box(Modifier.padding(vertical = 6.dp, horizontal = 8.dp)) {
            Text(
              inlineMarkdown(row.getOrElse(c) { "" }),
              style = scaled(size - 1, if (r == 0) FontWeight.SemiBold else FontWeight.Normal, Theme.ink),
              maxLines = 1,
              softWrap = false,
              textAlign = if (trailing) TextAlign.End else TextAlign.Start,
            )
          }
          if (r < rows.size - 1) Box(Modifier.fillMaxWidth().padding(0.dp).background(Theme.line).padding(vertical = if (r == 0) 0.5.dp else 0.25.dp))
        }
      }
    }
  }
}
