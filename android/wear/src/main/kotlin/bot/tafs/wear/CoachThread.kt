package bot.tafs.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.Text
import bot.tafs.watchlink.AskLine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * W4, Ask Coach as one conversation (the owner-approved Watch Coach mockup): today's turns from this watch, oldest first, above
 * the microphone, so the newest sits just over it. Each turn is the person's words on the right, with when they were sent, and
 * Coach's answer on the left: its line once the ask has ended (a tap opens the answer card, with Hear and Reply), or tafs cloud's
 * state for it while it's at work. Memory only (WatchModel.thread).
 */
internal fun ScalingLazyListScope.coachThread(turns: List<Pair<WatchModel.Said, AskLine>>, act: Actions) {
  turns.forEach { (said, ask) -> item(key = ask.id) { CoachTurn(said, ask, onOpen = { act.open(ask) }) } }
}

@Composable
internal fun CoachTurn(said: WatchModel.Said, ask: AskLine, onOpen: () -> Unit) {
  Column(Modifier.fillMaxWidth().testTag(THREAD_TURN + ask.id), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(timeOf(said.at), fontSize = 10.sp, color = Look.faint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
      said.text, fontSize = 13.sp, lineHeight = 16.sp, color = Look.onMineGround, maxLines = 4, overflow = TextOverflow.Ellipsis,
      modifier = Modifier.align(Alignment.End).widthIn(max = 150.dp)
        .background(Look.mineGround, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
    )
    val answer = if (ask.ended) (ask.line ?: ask.words).ifBlank { Words.Watch.moreOnPhone } else stateLine(ask)
    Text(
      answer, fontSize = 13.sp, lineHeight = 16.sp, color = if (ask.ended) Look.onCoachGround else Look.coach, maxLines = 5,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.align(Alignment.Start).widthIn(max = 156.dp)
        .background(Look.coachGround, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
        .then(if (ask.ended) Modifier.clickable(onClick = onOpen) else Modifier)
        .padding(horizontal = 10.dp, vertical = 6.dp)
        .testTag(if (ask.ended) THREAD_ANSWER + ask.id else THREAD_WORKING + ask.id)
        // TalkBack hears whose words these are, as VoiceOver does on the Apple Watch (mac12's W4 note).
        .semantics { contentDescription = Words.Coach.title + ": " + answer },
    )
  }
}

/** When something was said, in the watch's own short time ("09:14"). */
internal fun timeOf(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
  DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone).format(Instant.ofEpochMilli(at))

internal const val THREAD_TURN = "thread-turn-"
internal const val THREAD_ANSWER = "thread-answer-"
internal const val THREAD_WORKING = "thread-working-"
