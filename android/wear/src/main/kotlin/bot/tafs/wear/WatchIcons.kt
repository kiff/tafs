package bot.tafs.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme

/**
 * Icons over words (row 44, B, the owner's watch review): Coach's home names Type and Quick reply under their icons; everywhere
 * else a keyboard types, a microphone talks and ↻ retries, with the word only for TalkBack.
 */
@Composable
internal fun IconOverWord(label: String, onClick: () -> Unit, tag: String, modifier: Modifier = Modifier, glyph: DrawScope.(Color) -> Unit) {
  Button(onClick = onClick, modifier = modifier.testTag(tag), colors = neutralButton(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Glyph(Modifier.size(20.dp)) { glyph(Look.text) }
      FittedWord(label, Modifier.testTag("$tag-word"))
    }
  }
}

/**
 * [words] on two lines at most, never broken inside a word: when a word is wider than the button (German's "Schnellantwort"
 * on the 40 mm face), the size steps down from 12 sp until it fits, to 9 sp at least ([WholeWords]). IconsTest holds every
 * language to it.
 */
@Composable
private fun FittedWord(words: String, modifier: Modifier = Modifier) {
  BasicText(
    words,
    modifier.fillMaxWidth(),
    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 1.15.em, color = Look.text, textAlign = TextAlign.Center),
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
    autoSize = WholeWords,
  )
}

/** The largest size from 12 sp down to 9 sp whose lines break only between words and show every word. */
private object WholeWords : TextAutoSize {
  override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
    var size = 12f
    while (size > 9f && !fits(performLayout(constraints, text, size.sp))) size -= 0.5f
    return size.sp
  }

  override fun equals(other: Any?) = other === this
  override fun hashCode() = 1
}

/** Every word shown whole: nothing cut, and each line but the last ends at a space. */
internal fun fits(r: TextLayoutResult): Boolean {
  val text = r.layoutInput.text.text
  if (r.hasVisualOverflow || r.isLineEllipsized(r.lineCount - 1)) return false
  return (0 until r.lineCount - 1).all { line ->
    val end = r.getLineEnd(line)
    text[end - 1].isWhitespace() || text.getOrNull(end)?.isWhitespace() == true
  }
}

internal const val HOME_TYPE = "home-type"
internal const val HOME_QUICK = "home-quick"
internal const val TAF_TYPE = "taf-type"
internal const val TAF_TALK = "taf-talk"
internal const val RETRY = "retry"

/** A keyboard: its outline, two rows of keys and the space bar. */
internal fun DrawScope.keyboardGlyph(color: Color) {
  val d = size.minDimension
  val c = center
  val w = d * 0.86f
  val h = d * 0.6f
  val sw = d * 0.08f
  drawRoundRect(color, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(d * 0.1f, d * 0.1f), style = Stroke(sw))
  val key = d * 0.09f
  for (row in 0..1) for (i in 0..3) {
    val x = c.x - w / 2 + w * (0.2f + i * 0.2f)
    val y = c.y - h / 2 + h * (0.3f + row * 0.22f)
    drawRect(color, Offset(x - key / 2, y - key / 2), Size(key, key))
  }
  drawLine(color, Offset(c.x - w * 0.24f, c.y + h * 0.26f), Offset(c.x + w * 0.24f, c.y + h * 0.26f), sw, StrokeCap.Round)
}

/** Quick reply: a speech bubble with a lightning bolt in it. */
internal fun DrawScope.quickGlyph(color: Color) {
  val d = size.minDimension
  val c = center
  val sw = d * 0.08f
  val w = d * 0.84f
  val h = d * 0.64f
  val top = c.y - d * 0.4f
  val bubble = Path().apply {
    addRoundRect(androidx.compose.ui.geometry.RoundRect(c.x - w / 2, top, c.x + w / 2, top + h, CornerRadius(d * 0.16f, d * 0.16f)))
  }
  drawPath(bubble, color, style = Stroke(sw))
  val tail = Path().apply {
    moveTo(c.x - w * 0.24f, top + h)
    lineTo(c.x - w * 0.32f, top + h + d * 0.18f)
    lineTo(c.x - w * 0.04f, top + h)
  }
  drawPath(tail, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
  val bolt = Path().apply {
    moveTo(c.x + d * 0.06f, top + h * 0.16f)
    lineTo(c.x - d * 0.12f, top + h * 0.56f)
    lineTo(c.x + d * 0.01f, top + h * 0.56f)
    lineTo(c.x - d * 0.05f, top + h * 0.86f)
    lineTo(c.x + d * 0.14f, top + h * 0.42f)
    lineTo(c.x + d * 0.01f, top + h * 0.42f)
    close()
  }
  drawPath(bolt, color)
}
