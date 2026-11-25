package bot.tafs.wear

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.compose.material3.TextButtonDefaults
import bot.tafs.watchlink.AskLine

/*
 * The microphone's screens (docs/watch-wear.md, Ask tafs): the microphone first, listening, sending, sent, the result with Hear
 * and Reply, "not sure it arrived" with Retry, and a Halt until the phone confirms it. Every word is the watch's own, from
 * app/strings, or tafs cloud's as it comes; the glyphs are drawn and carry none. Their colours are Look's (Look.kt), by meaning.
 */

// MARK: Glyphs, drawn with shapes

internal fun DrawScope.micGlyph(color: Color, scale: Float = 1f) {
  val d = size.minDimension * scale
  val cx = size.width / 2
  val cy = size.height / 2
  val sw = d * 0.075f
  val bw = d * 0.30f
  val bh = d * 0.50f
  val top = cy - d * 0.42f
  drawRoundRect(color, topLeft = Offset(cx - bw / 2, top), size = Size(bw, bh), cornerRadius = CornerRadius(bw / 2, bw / 2))
  val cr = d * 0.27f
  val cradleCy = top + bh - bw / 2
  drawArc(color, 0f, 180f, false, Offset(cx - cr, cradleCy - cr), Size(cr * 2, cr * 2), style = Stroke(sw, cap = StrokeCap.Round))
  val stemBottom = cy + d * 0.40f
  drawLine(color, Offset(cx, cradleCy + cr), Offset(cx, stemBottom), strokeWidth = sw, cap = StrokeCap.Round)
  drawLine(color, Offset(cx - d * 0.16f, stemBottom), Offset(cx + d * 0.16f, stemBottom), strokeWidth = sw, cap = StrokeCap.Round)
}

private fun DrawScope.speakerGlyph(color: Color) {
  val d = size.minDimension
  val cx = size.width / 2
  val cy = size.height / 2
  val sw = d * 0.075f
  val left = cx - d * 0.36f
  val cone = Path().apply {
    moveTo(left, cy - d * 0.12f)
    lineTo(left + d * 0.16f, cy - d * 0.12f)
    lineTo(left + d * 0.38f, cy - d * 0.32f)
    lineTo(left + d * 0.38f, cy + d * 0.32f)
    lineTo(left + d * 0.16f, cy + d * 0.12f)
    lineTo(left, cy + d * 0.12f)
    close()
  }
  drawPath(cone, color)
  for ((i, r) in listOf(d * 0.20f, d * 0.36f).withIndex()) {
    val ox = left + d * 0.38f
    drawArc(
      color.copy(alpha = if (i == 0) 1f else 0.85f), -45f, 90f, false, Offset(ox - r + d * 0.06f, cy - r), Size(r * 2, r * 2),
      style = Stroke(sw, cap = StrokeCap.Round),
    )
  }
}

internal fun DrawScope.checkGlyph(color: Color, strokeShare: Float = 0.08f) {
  val d = size.minDimension
  val ox = (size.width - d) / 2
  val oy = (size.height - d) / 2
  val tick = Path().apply {
    moveTo(ox + 0.30f * d, oy + 0.52f * d)
    lineTo(ox + 0.44f * d, oy + 0.65f * d)
    lineTo(ox + 0.71f * d, oy + 0.37f * d)
  }
  drawPath(tick, color, style = Stroke(width = strokeShare * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A question mark, drawn: an arc and a dot. */
private fun DrawScope.questionGlyph(color: Color) {
  val d = size.minDimension
  val cx = size.width / 2
  val cy = size.height / 2
  val sw = d * 0.11f
  val r = d * 0.17f
  drawArc(color, 180f, 270f, false, Offset(cx - r, cy - d * 0.30f), Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
  drawLine(color, Offset(cx, cy - d * 0.30f + r * 2), Offset(cx, cy + d * 0.08f), strokeWidth = sw, cap = StrokeCap.Round)
  drawCircle(color, radius = sw * 0.62f, center = Offset(cx, cy + d * 0.27f))
}

@Composable
internal fun Glyph(modifier: Modifier, draw: DrawScope.() -> Unit) = Canvas(modifier) { draw() }

// MARK: Pieces

@Composable
internal fun T(
  text: String,
  size: TextUnit,
  color: Color = Look.text,
  weight: FontWeight = FontWeight.Normal,
  modifier: Modifier = Modifier,
  lines: Int = 2,
  lineHeight: TextUnit = TextUnit.Unspecified,
) {
  Text(
    text, modifier = modifier, color = color, fontSize = size, fontWeight = weight, textAlign = TextAlign.Center,
    maxLines = lines, lineHeight = lineHeight, overflow = TextOverflow.Ellipsis,
  )
}

/** The big microphone, in Send's orange (the person's own words): about 45 % of a 192 dp face. */
@Composable
internal fun BigMic(onClick: () -> Unit, size: Dp = 86.dp) {
  FilledIconButton(
    onClick = onClick,
    modifier = Modifier.size(size).semantics { contentDescription = Words.Watch.ask },
    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Look.send, contentColor = Look.onSend),
  ) {
    Glyph(Modifier.size(size * 0.5f)) { micGlyph(Look.onSend) }
  }
}

@Composable
private fun Dot(color: Color) {
  Canvas(Modifier.size(5.dp)) { drawCircle(color) }
}

@Composable
internal fun StatusLine(text: String, dot: Color?, color: Color = Look.dim, onClick: (() -> Unit)? = null) {
  Row(
    (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 1.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (dot != null) Dot(dot)
    T(text, 11.sp, color, lines = 1, lineHeight = 14.sp)
  }
}

@Composable
internal fun SpinnerAround(size: Dp, color: Color, content: @Composable BoxScope.() -> Unit) {
  Box(Modifier.size(size), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(
      modifier = Modifier.fillMaxSize(),
      colors = ProgressIndicatorDefaults.colors(indicatorColor = color, trackColor = Look.neutral),
      strokeWidth = 4.dp,
    )
    content()
  }
}

@Composable
private fun RoundAction(label: String, onClick: () -> Unit, color: Color, glyph: DrawScope.() -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    FilledIconButton(
      onClick = onClick,
      modifier = Modifier.size(48.dp).semantics { contentDescription = label },
      colors = IconButtonDefaults.filledIconButtonColors(containerColor = Look.neutral, contentColor = color),
    ) { Glyph(Modifier.size(24.dp), glyph) }
    Spacer(Modifier.height(3.dp))
    T(label, 11.sp, Look.dim, lines = 1)
  }
}

@Composable
internal fun QuietButton(label: String, onClick: () -> Unit) {
  TextButton(
    onClick = onClick,
    modifier = Modifier.height(30.dp).width(110.dp),
    colors = TextButtonDefaults.textButtonColors(contentColor = Look.dim),
  ) { T(label, 13.sp, Look.dim, FontWeight.Medium, lines = 1) }
}

// MARK: Screens

/** Home's top: the microphone owns the face, with one small status block under it. */
@Composable
internal fun AskHome(status: List<Pair<String, Color?>>, listen: () -> Unit, open: List<() -> Unit> = emptyList(), label: String = Words.Watch.ask) {
  Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    BigMic(listen)
    Spacer(Modifier.height(5.dp))
    T(label, 14.sp, Look.text, FontWeight.Medium, lines = 1)
    Spacer(Modifier.height(6.dp))
    Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      status.forEachIndexed { i, (line, dot) -> StatusLine(line, dot, onClick = open.getOrNull(i)) }
    }
  }
}

/** The microphone's screen for [voice], full face. */
@Composable
internal fun VoiceScreen(voice: WatchModel.Voice, act: Actions) {
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    when (voice) {
      is WatchModel.Voice.Listening -> Listening(voice.heard, act.closeVoice)
      is WatchModel.Voice.Sending -> Centered {
        SpinnerAround(64.dp, Look.send) {
          Box(Modifier.size(44.dp).background(Look.surface, CircleShape), contentAlignment = Alignment.Center) {
            Glyph(Modifier.size(24.dp)) { micGlyph(Look.send) }
          }
        }
        Spacer(Modifier.height(12.dp))
        T(Words.Watch.sending, 15.sp, Look.text, FontWeight.Medium, lines = 1)
      }
      is WatchModel.Voice.Sent -> Centered(Modifier.clickable(onClick = act.closeVoice)) {
        // v1: "Sent", then the wrist can drop and the microphone comes back by itself (v2 stays until the answer, SentScreenV2).
        androidx.compose.runtime.LaunchedEffect(voice) {
          kotlinx.coroutines.delay(WatchActivity.SENT_SHOWN_MS)
          act.closeVoice()
        }
        Box(Modifier.size(60.dp).border(BorderStroke(3.dp, Look.done), CircleShape)) {
          Glyph(Modifier.fillMaxSize()) { checkGlyph(Look.done, 0.07f) }
        }
        Spacer(Modifier.height(10.dp))
        T(Words.Watch.sent, 18.sp, Look.text, FontWeight.SemiBold, lines = 1)
        Spacer(Modifier.height(4.dp))
        T(stateLine(voice.ask), 12.sp, Look.dim, modifier = Modifier.width(150.dp), lines = 1)
      }
      // No receipt yet: one calm linking screen while the watch sends the same words again by itself (row 30, call 6); it ends
      // on its own when the phone answers. Cancel stays, and Keep for later for Coach's.
      is WatchModel.Voice.Linking -> Centered(Modifier.testTag(SEND_LINKING)) {
        androidx.compose.runtime.LaunchedEffect(voice.clientId) { act.keepTrying() }
        SpinnerAround(64.dp, Look.send) {
          Box(Modifier.size(44.dp).background(Look.surface, CircleShape), contentAlignment = Alignment.Center) {
            Glyph(Modifier.size(24.dp)) { micGlyph(Look.send) }
          }
        }
        Spacer(Modifier.height(10.dp))
        T(Words.Watch.linking, 13.sp, Look.text, modifier = Modifier.width(150.dp), lines = 3, lineHeight = 16.sp)
        Spacer(Modifier.height(4.dp))
        act.keep.takeIf { voice.tafId == null }?.let { QuietButton(Words.Watch.keepForLater, it) }
        QuietButton(Words.Common.cancel, act.closeVoice)
      }
      is WatchModel.Voice.Unsure -> Centered {
        // v2 adds Keep for later, never for a message to a taf (nothing of a taf's chat is kept on the watch); the face makes
        // room for it without the mark.
        val keep = act.keep.takeIf { voice.tafId == null }
        if (keep == null) {
          Box(Modifier.size(34.dp).border(BorderStroke(2.5.dp, Look.held), CircleShape), contentAlignment = Alignment.Center) {
            Glyph(Modifier.size(18.dp)) { questionGlyph(Look.held) }
          }
          Spacer(Modifier.height(8.dp))
        }
        T(Words.Watch.notSure, 15.sp, Look.text, FontWeight.SemiBold, Modifier.width(150.dp), lines = 1)
        Spacer(Modifier.height(3.dp))
        T(Words.Watch.checkPhone, 12.sp, Look.dim, modifier = Modifier.width(140.dp), lineHeight = 15.sp)
        Spacer(Modifier.height(10.dp))
        RoundIcon(Words.Watch.retry, act.retry, RETRY, size = 48.dp, container = Look.send, content = Look.onSend) { againGlyph(it) }
        Spacer(Modifier.height(4.dp))
        keep?.let { QuietButton(Words.Watch.keepForLater, it) }
        QuietButton(Words.Common.cancel, act.closeVoice)
      }
      is WatchModel.Voice.NotSent -> Centered {
        T(voice.words ?: Words.Watch.openOnPhone, 14.sp, Look.text, modifier = Modifier.width(150.dp), lines = 4, lineHeight = 17.sp)
        Spacer(Modifier.height(10.dp))
        if (voice.words != null) QuietButton(Words.Watch.openOnPhone) { act.openOnPhone("") }
        QuietButton(Words.Common.close, act.closeVoice)
      }
      // The phone turning the recording into text, on the phone itself (row 44, A): Cancel deletes it.
      is WatchModel.Voice.Transcribing -> Centered {
        SpinnerAround(64.dp, Look.send) {
          Box(Modifier.size(44.dp).background(Look.surface, CircleShape), contentAlignment = Alignment.Center) {
            Glyph(Modifier.size(24.dp)) { micGlyph(Look.send) }
          }
        }
        Spacer(Modifier.height(10.dp))
        T(Words.Watch.transcribing, 14.sp, Look.text, modifier = Modifier.width(150.dp).testTag(TRANSCRIBING), lines = 3, lineHeight = 17.sp)
        QuietButton(Words.Common.cancel, act.closeVoice)
      }
      WatchModel.Voice.HeardNothing -> Centered(Modifier.clickable(onClick = act.listen)) {
        Box(Modifier.size(48.dp).background(Look.surface, CircleShape), contentAlignment = Alignment.Center) {
          Glyph(Modifier.size(26.dp)) { micGlyph(Look.send) }
        }
        Spacer(Modifier.height(10.dp))
        T(Words.Watch.didntCatch, 14.sp, Look.text, modifier = Modifier.width(150.dp), lines = 3, lineHeight = 17.sp)
        QuietButton(Words.Common.close, act.closeVoice)
      }
      WatchModel.Voice.NeedsLock -> Centered {
        T(Words.Watch.needsLock, 13.sp, Look.text, modifier = Modifier.width(150.dp), lines = 5, lineHeight = 16.sp)
        Spacer(Modifier.height(8.dp))
        QuietButton(Words.Common.close, act.closeVoice)
      }
      // v2's own screens (ReviewScreens.kt): never shown by v1.
      is WatchModel.Voice.Ready, is WatchModel.Voice.Review, WatchModel.Voice.Deleted -> Unit
    }
  }
}

@Composable
internal fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
}

@Composable
private fun Listening(heard: String, cancel: () -> Unit) {
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.align(Alignment.TopCenter).padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
          val r = size.minDimension / 2
          drawCircle(Look.send.copy(alpha = 0.10f), radius = r)
          drawCircle(Look.send.copy(alpha = 0.16f), radius = r * 0.88f)
          val ringR = r * 0.74f
          drawCircle(Brush.sweepGradient(Look.brand + Look.brand.first(), center = center), radius = ringR, style = Stroke(width = r * 0.11f))
          drawCircle(Color(0xFF141416), radius = ringR - r * 0.055f)
        }
        Glyph(Modifier.size(42.dp)) { micGlyph(Look.send) }
      }
      Spacer(Modifier.height(4.dp))
      T(heard, 15.sp, Look.send, FontWeight.Medium, Modifier.width(150.dp), lines = 3, lineHeight = 18.sp)
    }
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { QuietButton(Words.Common.cancel, cancel) }
  }
}

/** An ask's state in one line: the taf it went to and tafs cloud's word for where it is ("Calendar · Working"). */
internal fun stateLine(ask: AskLine): String = listOfNotNull(ask.tafName, ask.words).joinToString(" · ")

/**
 * The result card: the taf, its line, Hear and Reply, then Close. [timing] only on an internal build. It scrolls (crown or swipe)
 * so a long answer reads to its end, and only Close, or the system's back, closes it: a tap on the words never does (W3, the
 * owner's Watch 5 test: the answer flashed past and couldn't be read).
 */
@Composable
internal fun ResultScreen(ask: AskLine, act: Actions, timing: String? = null, whole: WatchModel.Whole? = null) {
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    // A ScalingLazyColumn, so the crown or bezel scrolls it as a swipe does (a plain scrolling column takes no rotary input).
    androidx.wear.compose.foundation.lazy.ScalingLazyColumn(
      Modifier.fillMaxSize().testTag(RESULT_SCREEN),
      state = androidx.wear.compose.foundation.lazy.rememberScalingLazyListState(initialCenterItemIndex = 0),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 22.dp, bottom = 28.dp),
      autoCentering = null,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      item { T(ask.tafName ?: "", 12.sp, Look.coach, FontWeight.Medium, lines = 1) }
      // tafs cloud's checked line (up to 140 characters); with none, its word for the state. Whole, however many lines.
      item {
        val text = Plain.of((whole as? WatchModel.Whole.Read)?.text ?: (ask.line ?: ask.words)).ifBlank { Words.Watch.moreOnPhone }
        T(text, 15.sp, Look.onCoachGround, FontWeight.SemiBold, Modifier.width(156.dp).testTag(RESULT_TEXT), lines = Int.MAX_VALUE, lineHeight = 18.sp)
      }
      // tafs cloud says the answer goes on past its line (`more`): Read more reads the whole (`full=1`), shown here and heard by
      // Hear; with none, the rest is on the phone.
      if (ask.more) when (whole) {
        null -> item { QuietButton(Words.Watch.readMore) { act.readWhole(ask.id) } }
        WatchModel.Whole.Reading -> item { T(Words.Common.loading, 12.sp, Look.dim, modifier = Modifier.width(150.dp), lines = 1) }
        WatchModel.Whole.OnPhone -> item { T(Words.Watch.moreOnPhone, 12.sp, Look.dim, modifier = Modifier.width(150.dp), lines = 2) }
        is WatchModel.Whole.Read -> Unit
      }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          RoundAction(Words.Watch.hear, { act.hear(ask) }, Look.coach) { speakerGlyph(Look.coach) }
          RoundAction(Words.Watch.reply, { act.reply(ask) }, Look.send) { micGlyph(Look.send, 0.95f) }
        }
      }
      timing?.let { item { T(it, 8.sp, Look.faint, modifier = Modifier.width(140.dp), lines = 2) } }
      item { QuietButton(Words.Common.close, act.closeResult) }
    }
  }
}

internal const val RESULT_SCREEN = "result-screen"
internal const val RESULT_TEXT = "result-text"

/** A Halt sent, until the phone answers: never "halted" before tafs cloud says so. */
@Composable
internal fun HaltingScreen(taf: String?) {
  Centered(Modifier.background(Color.Black)) {
    SpinnerAround(52.dp, Look.danger) {
      Canvas(Modifier.size(16.dp)) { drawRoundRect(Look.danger, cornerRadius = CornerRadius(3.dp.toPx())) }
    }
    Spacer(Modifier.height(10.dp))
    T(if (taf.isNullOrEmpty()) Words.Watch.haltingAll else Words.Watch.halting(taf), 15.sp, Look.text, FontWeight.SemiBold, Modifier.width(150.dp), lines = 2)
  }
}

/** Halted, or another word from the phone for a Halt or a Refuse that went through. */
@Composable
internal fun DoneScreen(words: String, dismiss: () -> Unit) {
  Centered(Modifier.background(Color.Black).clickable(onClick = dismiss).semantics { role = Role.Button }) {
    Canvas(Modifier.size(56.dp)) {
      drawCircle(Look.done)
      checkGlyph(Look.onDone, 0.08f)
    }
    Spacer(Modifier.height(10.dp))
    T(words, 16.sp, Look.text, FontWeight.SemiBold, Modifier.width(150.dp), lines = 2)
  }
}

internal const val TRANSCRIBING = "transcribing"

/** A send's linking screen, while the watch sends it again by itself. */
internal const val SEND_LINKING = "send-linking"
