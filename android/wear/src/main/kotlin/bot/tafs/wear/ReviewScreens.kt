package bot.tafs.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay

/** v2's limit on one recording: listening stops by itself here, and the words go to the review (docs/watch-wear.md). */
internal const val LISTEN_LIMIT_MS = 30_000L

/** How long "Deleted. Nothing was sent." stays before the microphone's screen closes by itself. */
internal const val DELETED_SHOWN_MS = 2_000L

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
    else -> VoiceScreen(voice, act)
  }
}

/** The recording screen: one control in three states you can't mistake, Start, then Stop with its timer, then the review. */
@Composable
private fun RecordScreen(heard: String?, seconds: Int, act: Actions, start: (() -> Unit)?) {
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      if (start != null) T("${Words.Watch.ready} · ${Words.Watch.limit}", 11.sp, Look.dim, lines = 1)
      else T("● ${Words.Watch.recording} · ${Words.Watch.limit}", 11.sp, Look.red, lines = 1)
      Spacer(Modifier.height(4.dp))
      Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
        // The ring fills to the limit.
        Canvas(Modifier.fillMaxSize()) {
          val stroke = size.minDimension * 0.06f
          drawCircle(Look.surfaceHi, radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
          drawArc(
            Look.amber, startAngle = -90f, sweepAngle = 360f * seconds / (LISTEN_LIMIT_MS / 1000f), useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke, stroke),
            size = androidx.compose.ui.geometry.Size(size.width - 2 * stroke, size.height - 2 * stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
          )
        }
        RecordButton(recording = start == null, onClick = start ?: act.stop)
      }
      T("%d:%02d / 0:%02d".format(seconds / 60, seconds % 60, LISTEN_LIMIT_MS / 1000), 13.sp, Look.text, lines = 1)
      Spacer(Modifier.height(2.dp))
      // The words as they're heard; before Start, what to do.
      T(heard?.takeIf { it.isNotEmpty() } ?: Words.Watch.tapStart, 12.sp, if (heard.isNullOrEmpty()) Look.dim else Look.text,
        modifier = Modifier.width(150.dp), lines = 3, lineHeight = 15.sp)
    }
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) { QuietButton(Words.Common.cancel, act.closeVoice) }
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
      .then(if (recording) Modifier.border(5.dp, Look.red.copy(alpha = 0.28f), CircleShape) else Modifier)
      .padding(if (recording) 5.dp else 0.dp)
      .clip(CircleShape)
      .then(if (recording) Modifier.background(Look.red) else Modifier.background(Brush.linearGradient(Look.brand)))
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

/** Test tags for the two looks of the recording control. */
internal const val RECORD_START = "record-start"
internal const val RECORD_STOP = "record-stop"

/** Check before sending: the words in full, scrolled with the crown and never cut, then Re-record, Delete and Send. */
@Composable
private fun ReviewScreen(text: String, audio: VoiceAudio?, act: Actions, probe: String?) {
  ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), state = rememberScalingLazyListState(initialCenterItemIndex = 0),
    verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { ListHeader { Text(Words.Watch.review) } }
    // Inset from the round edge, so no line of a long transcript is cut where the face curves (qa-kiff's device read, #1409).
    item { Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) }
    // The recording, on the watch only: Play and Pause from where it stopped, and where it is.
    val playback = act.playback
    if (audio != null && playback != null) item { PlayRow(audio, playback) }
    probe?.let { item { Text(it, fontSize = 9.sp, color = Look.faint, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) } }
    item {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
        Button(onClick = act.rerecord, modifier = Modifier.weight(1f)) { Text(Words.Watch.rerecord, maxLines = 1) }
        Button(onClick = act.delete, modifier = Modifier.weight(1f)) { Text(Words.Chat.delete, maxLines = 1) }
      }
    }
    item { Button(onClick = act.send, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.send) } }
  }
}

/** Play ↔ Pause, with the position against the length (0:03 / 0:12). */
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
    Button(onClick = { playback.toggle(audio) }, modifier = Modifier.testTag(if (playing) PLAYBACK_PAUSE else PLAYBACK_PLAY)) {
      Text(if (playing) Words.Taf.pause else Words.Watch.play, maxLines = 1)
    }
    Text("${clock(at)} / ${clock(audio.durationMs)}", fontSize = 12.sp, color = Look.text)
  }
}

private fun clock(ms: Long): String = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }

internal const val PLAYBACK_PLAY = "playback-play"
internal const val PLAYBACK_PAUSE = "playback-pause"

/** Quick reply: ready-made lines, each to the review, as words heard are. */
@Composable
internal fun QuickReplyScreen(pick: (String) -> Unit, close: () -> Unit) {
  ScalingLazyColumn(Modifier.fillMaxSize(), state = rememberScalingLazyListState(initialCenterItemIndex = 0),
    verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { ListHeader { Text(Words.Watch.quickReply) } }
    for (line in quickReplies()) item { Button(onClick = { pick(line) }, modifier = Modifier.fillMaxWidth()) { Text(line) } }
    item { Button(onClick = close, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.cancel) } }
  }
}

/** The quick replies, in the watch's language. */
internal fun quickReplies(): List<String> =
  listOf(Words.Watch.Qr.needsMeToday, Words.Watch.Qr.tellMeMore, Words.Watch.Qr.notNow, Words.Watch.Qr.thanks)
