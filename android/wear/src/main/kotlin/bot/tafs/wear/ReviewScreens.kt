package bot.tafs.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/** v2's limit on one recording: listening stops by itself here, and the words go to the review (docs/watch-wear.md). */
internal const val LISTEN_LIMIT_MS = 30_000L

/** How long "Deleted. Nothing was sent." stays before the microphone's screen closes by itself. */
internal const val DELETED_SHOWN_MS = 2_000L

/**
 * How far the first line sits from the round face's top, as a share of the face: from there down the face is wide enough for a
 * short line, so nothing is cut where it curves (the Galaxy Watch 5 test, W8). About 24 dp on the 40 mm face, 27 on the 44 mm.
 */
internal const val TOP_INSET_SHARE = 0.12f

/** The limit on one recording, short enough for the top of a round face: "30 s". TalkBack reads it in full ("30 s at most"). */
internal fun limitShort(): String = "${LISTEN_LIMIT_MS / 1000} s"

/**
 * v2's microphone, full face (mockup v2.1, Recording and Check before sending): Start, then Stop with the timer and a ring that
 * fills to 30 s, the words as they're heard; then the review, where nothing has left the watch until Send. Sending, Sent, Not sure
 * it arrived and Not sent are v1's.
 */
@Composable
internal fun VoiceScreenV2(voice: WatchModel.Voice, act: Actions, now: () -> Long = System::currentTimeMillis, probe: String? = null) {
  when (voice) {
    is WatchModel.Voice.Ready -> RecordScreen(heard = null, seconds = 0, act = act, start = { act.start(voice.replyTo) })
    is WatchModel.Voice.Listening -> {
      var at by remember(voice.since) { mutableLongStateOf(now()) }
      LaunchedEffect(voice.since) {
        while (true) {
          delay(250)
          at = now()
        }
      }
      val seconds = (voice.since?.let { ((at - it) / 1000).toInt() } ?: 0).coerceIn(0, (LISTEN_LIMIT_MS / 1000).toInt())
      RecordScreen(heard = voice.heard, seconds = seconds, act = act, start = null)
    }
    is WatchModel.Voice.Review -> ReviewScreen(voice.text, voice.audio, act, probe)
    WatchModel.Voice.Deleted -> {
      LaunchedEffect(Unit) {
        delay(DELETED_SHOWN_MS)
        act.closeVoice()
      }
      Centered(Modifier.background(Color.Black).clickable(onClick = act.closeVoice)) {
        T(Words.Watch.deletedNothingSent, 15.sp, Look.text, FontWeight.Medium, Modifier.width(150.dp), lines = 3, lineHeight = 18.sp)
      }
    }
    is WatchModel.Voice.Sent -> SentScreenV2(voice.ask, act)
    else -> VoiceScreen(voice, act)
  }
}

/**
 * The recording screen: one control in three states you can't mistake, Start, then Stop with its timer, then the review. Its top
 * line is short ("● Recording · 30 s") and sits below the face's top curve.
 */
@Composable
private fun RecordScreen(heard: String?, seconds: Int, act: Actions, start: (() -> Unit)?) {
  BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
    val heardWidth = (maxWidth * HEARD_WIDTH_SHARE).coerceAtMost(150.dp)
    Column(Modifier.align(Alignment.TopCenter).padding(top = maxHeight * TOP_INSET_SHARE), horizontalAlignment = Alignment.CenterHorizontally) {
      val state = if (start != null) Words.Watch.ready else "● ${Words.Watch.recording}"
      T("$state · ${limitShort()}", 11.sp, if (start != null) Look.dim else Look.recording, lines = 1,
        modifier = Modifier.semantics { contentDescription = "$state · ${Words.Watch.limit}" }.testTag(RECORD_STATE))
      Spacer(Modifier.height(2.dp))
      Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
        // The ring fills to the limit.
        Canvas(Modifier.fillMaxSize()) {
          val stroke = size.minDimension * 0.06f
          drawCircle(Look.neutral, radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
          drawArc(
            Look.send, startAngle = -90f, sweepAngle = 360f * seconds / (LISTEN_LIMIT_MS / 1000f), useCenter = false,
            topLeft = Offset(stroke, stroke),
            size = Size(size.width - 2 * stroke, size.height - 2 * stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
          )
        }
        RecordButton(recording = start == null, onClick = start ?: act.stop)
      }
      T("%d:%02d / 0:%02d".format(seconds / 60, seconds % 60, LISTEN_LIMIT_MS / 1000), 13.sp, Look.text, lines = 1)
      Spacer(Modifier.height(2.dp))
      // The words as they're heard; before Start, what to do. Two lines: a third would meet the face's curve at its sides. Their
      // width follows the face: on the 40 mm Watch 5 the face is ~151 dp across at the second line, so 150 dp ran to its edge
      // (mac12's #1439 note); 44 mm keeps 150.
      T(heard?.takeIf { it.isNotEmpty() } ?: Words.Watch.tapStart, 12.sp, if (heard.isNullOrEmpty()) Look.dim else Look.send,
        modifier = Modifier.width(heardWidth).testTag(RECORD_HEARD), lines = 2, lineHeight = 15.sp)
    }
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) { QuietButton(Words.Common.cancel, act.closeVoice) }
  }
}

/**
 * The one control, in two looks you can't mistake at a glance (mockup v2.1): before, a round button in the brand's colours with the
 * microphone and Start; while recording, a solid red one with a white square, Stop, and a halo around it.
 */
@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
  val label = if (recording) Words.Chat.stop else Words.Coach.start
  Box(
    Modifier.size(62.dp)
      .then(if (recording) Modifier.border(5.dp, Look.recording.copy(alpha = 0.28f), CircleShape) else Modifier)
      .padding(if (recording) 5.dp else 0.dp)
      .clip(CircleShape)
      .then(if (recording) Modifier.background(Look.recording) else Modifier.background(Brush.linearGradient(Look.brand)))
      .clickable(onClick = onClick)
      .semantics { role = Role.Button; contentDescription = label }
      .testTag(if (recording) RECORD_STOP else RECORD_START),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      if (recording) {
        Box(Modifier.size(14.dp).background(Color.White, RoundedCornerShape(3.dp)))
      } else {
        Glyph(Modifier.size(20.dp)) { micGlyph(Color.White) }
      }
      Spacer(Modifier.height(2.dp))
      Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clearAndSetSemantics {})
    }
  }
}

/** Test tags for the two looks of the recording control, and the line above it. */
internal const val RECORD_START = "record-start"
internal const val RECORD_STOP = "record-stop"
internal const val RECORD_STATE = "record-state"
internal const val RECORD_HEARD = "record-heard"

/** The heard words' width on the recording screen, as a share of the face's. */
internal const val HEARD_WIDTH_SHARE = 0.68f

/** The review's foot: the height its pinned actions take, and the fade the words scroll into above them. */
internal val REVIEW_FOOT: Dp = 68.dp

/** Test tags for the review's actions, pinned at its foot. */
internal const val REVIEW_DELETE = "review-delete"
internal const val REVIEW_SEND = "review-send"
internal const val REVIEW_AGAIN = "review-again"

/**
 * Check before sending: the words in full, scrolled with the crown and never cut, and pinned at the foot, in reach without
 * scrolling, Delete (✕), Send (→, the big one, at the bottom centre) and Re-record (↻), round icon buttons that follow the
 * face's curve (the Galaxy Watch 5 test, W9). The words scroll on their own, starting below the top curve, and the last line of a
 * long message rests above the buttons. Each button is named for TalkBack in the watch's words.
 */
@Composable
private fun ReviewScreen(text: String, audio: VoiceAudio?, act: Actions, probe: String?) {
  BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
    val top = maxHeight * TOP_INSET_SHARE
    ScalingLazyColumn(
      Modifier.fillMaxSize(), state = rememberScalingLazyListState(initialCenterItemIndex = 0),
      // From below the top curve to above the foot, so the end of a long message can rest clear of the buttons.
      autoCentering = null,
      contentPadding = PaddingValues(top = top, bottom = REVIEW_FOOT),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      item { ListHeader { Text(Words.Watch.review) } }
      // The recording, on the watch only: Play and Pause from where it stopped, and where it is.
      val playback = act.playback
      if (audio != null && playback != null) item { PlayRow(audio, playback) }
      // Inset from the round edge, so no line of a long transcript is cut where the face curves (qa-kiff's device read, #1409).
      item { Text(text, style = MaterialTheme.typography.bodyMedium, color = Look.send, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) }
      probe?.let { item { Text(it, fontSize = 9.sp, color = Look.faint, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) } }
    }
    // Words scrolled past the top curve, or down into the foot, fade out there rather than being cut by the face's edge.
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(top).background(Brush.verticalGradient(listOf(Color.Black, Color.Transparent))))
    Box(
      Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(REVIEW_FOOT)
        .background(Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black)),
    )
    ReviewActions(act, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
  }
}

/**
 * The review's three actions, in a row that follows the face's bottom curve: Send lowest, in the middle, the biggest and in Send's
 * orange; Delete (its ✕ red) and Re-record (grey) either side of it, lifted so they stay inside the round face.
 */
@Composable
private fun ReviewActions(act: Actions, modifier: Modifier = Modifier) {
  Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    RoundIcon(Words.Chat.delete, act.delete, REVIEW_DELETE, Modifier.padding(bottom = 14.dp), content = Look.danger) { crossGlyph(it) }
    RoundIcon(Words.Common.send, act.send, REVIEW_SEND, size = 48.dp, container = Look.send, content = Look.onSend) { sendGlyph(it) }
    RoundIcon(Words.Watch.rerecord, act.rerecord, REVIEW_AGAIN, Modifier.padding(bottom = 14.dp)) { againGlyph(it) }
  }
}

/** A round icon button, named [label] for TalkBack; its glyph is drawn and carries no word. */
@Composable
private fun RoundIcon(
  label: String,
  onClick: () -> Unit,
  tag: String,
  modifier: Modifier = Modifier,
  size: Dp = 40.dp,
  container: Color = Look.neutral,
  content: Color = Look.text,
  glyph: DrawScope.(Color) -> Unit,
) {
  FilledIconButton(
    onClick = onClick,
    modifier = modifier.size(size).semantics { contentDescription = label }.testTag(tag),
    colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
  ) { Glyph(Modifier.size(size * 0.46f)) { glyph(content) } }
}

/** Play ↔ Pause (▶ and ‖), with the position against the length (0:03 / 0:12). */
@Composable
private fun PlayRow(audio: VoiceAudio, playback: Playback) {
  val playing by playback.playing.collectAsState()
  var at by remember(audio) { mutableLongStateOf(playback.positionMs()) }
  LaunchedEffect(playing, audio) {
    at = playback.positionMs()
    while (playing) {
      delay(200)
      at = playback.positionMs()
    }
  }
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
    RoundIcon(
      if (playing) Words.Taf.pause else Words.Watch.play, { playback.toggle(audio) }, if (playing) PLAYBACK_PAUSE else PLAYBACK_PLAY, size = 36.dp,
    ) { if (playing) pauseGlyph(it) else playGlyph(it) }
    Text("${clock(at)} / ${clock(audio.durationMs)}", fontSize = 12.sp, color = Look.text)
  }
}

private fun clock(ms: Long): String = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }

internal const val PLAYBACK_PLAY = "playback-play"
internal const val PLAYBACK_PAUSE = "playback-pause"

// MARK: The review's glyphs, drawn with shapes: ✕, →, ↻, ▶ and ‖

private fun DrawScope.crossGlyph(color: Color) {
  val d = size.minDimension
  val a = d * 0.24f
  val c = center
  val sw = d * 0.12f
  drawLine(color, Offset(c.x - a, c.y - a), Offset(c.x + a, c.y + a), sw, StrokeCap.Round)
  drawLine(color, Offset(c.x + a, c.y - a), Offset(c.x - a, c.y + a), sw, StrokeCap.Round)
}

private fun DrawScope.sendGlyph(color: Color) {
  val d = size.minDimension
  val c = center
  val sw = d * 0.12f
  val head = Path().apply {
    moveTo(c.x + d * 0.04f, c.y - d * 0.28f)
    lineTo(c.x + d * 0.32f, c.y)
    lineTo(c.x + d * 0.04f, c.y + d * 0.28f)
  }
  drawLine(color, Offset(c.x - d * 0.32f, c.y), Offset(c.x + d * 0.28f, c.y), sw, StrokeCap.Round)
  drawPath(head, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** ↻: a clockwise circle, open at the top right, with its arrowhead. */
private fun DrawScope.againGlyph(color: Color) {
  val d = size.minDimension
  val c = center
  val sw = d * 0.11f
  val r = d * 0.32f
  val start = -40f
  val sweep = 290f
  drawArc(color, start, sweep, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
  // The arrowhead at the arc's end, pointing on, clockwise.
  val end = Math.toRadians((start + sweep).toDouble())
  val out = Offset(cos(end).toFloat(), sin(end).toFloat())
  val on = Offset(-out.y, out.x)
  val p = Offset(c.x + r * out.x, c.y + r * out.y)
  val a = d * 0.17f
  val head = Path().apply {
    moveTo(p.x + on.x * a, p.y + on.y * a)
    lineTo(p.x + out.x * a, p.y + out.y * a)
    lineTo(p.x - out.x * a, p.y - out.y * a)
    close()
  }
  drawPath(head, color)
}

private fun DrawScope.playGlyph(color: Color) {
  val d = size.minDimension
  val c = center
  val tri = Path().apply {
    moveTo(c.x - d * 0.22f, c.y - d * 0.32f)
    lineTo(c.x + d * 0.32f, c.y)
    lineTo(c.x - d * 0.22f, c.y + d * 0.32f)
    close()
  }
  drawPath(tri, color)
}

private fun DrawScope.pauseGlyph(color: Color) {
  val d = size.minDimension
  val c = center
  val w = d * 0.18f
  val h = d * 0.62f
  for (x in listOf(c.x - d * 0.24f, c.x + d * 0.06f)) drawRoundRect(color, Offset(x, c.y - h / 2), Size(w, h), CornerRadius(w / 3, w / 3))
}

/** Quick reply: ready-made lines, each to the review, as words heard are. */
@Composable
internal fun QuickReplyScreen(pick: (String) -> Unit, close: () -> Unit) {
  ScalingLazyColumn(Modifier.fillMaxSize(), state = rememberScalingLazyListState(initialCenterItemIndex = 0),
    verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { ListHeader { Text(Words.Watch.quickReply) } }
    for (line in quickReplies()) item { Button(onClick = { pick(line) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(line) } }
    item { Button(onClick = close, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.cancel) } }
  }
}

/** The quick replies, in the watch's language. */
internal fun quickReplies(): List<String> =
  listOf(Words.Watch.Qr.needsMeToday, Words.Watch.Qr.tellMeMore, Words.Watch.Qr.notNow, Words.Watch.Qr.thanks)

/**
 * v2's "Sent" (W2, the owner's Watch 5 test: nothing showed that Coach had it, so it felt broken). It stays until the answer:
 * tafs cloud's state for the ask in its own words (stateLine: "Sent", then "Working"), the check green and Coach's blue ring turning
 * while it's at work, and the answer opens in its place the moment the ask ends (WatchModel.took). A tap goes back to Coach's
 * home; the ask goes on, and its answer comes as the result's alert.
 */
@Composable
internal fun SentScreenV2(ask: bot.tafs.watchlink.AskLine, act: Actions) {
  Centered(Modifier.background(Color.Black).clickable(onClick = act.closeVoice).testTag(SENT_V2)) {
    val mark: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
      Box(Modifier.size(44.dp).border(BorderStroke(3.dp, Look.done), CircleShape)) {
        Glyph(Modifier.fillMaxSize()) { checkGlyph(Look.done, 0.07f) }
      }
    }
    if (ask.ended) Box(Modifier.size(64.dp), contentAlignment = Alignment.Center, content = mark)
    else Box(Modifier.testTag(SENT_WORKING)) { SpinnerAround(64.dp, Look.coach, mark) }
    Spacer(Modifier.height(10.dp))
    T(Words.Watch.sent, 18.sp, Look.text, FontWeight.SemiBold, lines = 1)
    Spacer(Modifier.height(4.dp))
    T(stateLine(ask), 13.sp, Look.dim, modifier = Modifier.width(150.dp), lines = 2, lineHeight = 16.sp)
  }
}

internal const val SENT_V2 = "sent-v2"
internal const val SENT_WORKING = "sent-working"
