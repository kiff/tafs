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
import bot.tafs.watchlink.ChatTime

/**
 * W4, Ask Coach as one conversation (the owner-approved Watch Coach mockup): today's turns from this watch, oldest first, above
 * the microphone, so the newest sits just over it. Each turn is the person's words on the right, with when they were sent, and
 * Coach's answer on the left: its line once the ask has ended (a tap opens the answer card, with Hear and Reply), or tafs cloud's
 * state for it while it's at work. Memory only (WatchModel.thread).
 */
internal fun ScalingLazyListScope.coachThread(turns: List<Pair<WatchModel.Said, AskLine>>, act: Actions) {
  var previous: java.time.LocalDate? = null
  turns.forEach { (said, ask) ->
    // A header where a day starts (row 44, C).
    val day = WatchChatTime.dayOf(Instant.ofEpochMilli(said.at))
    if (ChatTime.startsDay(previous, day)) item(key = "day-$day") { DayHeader(WatchChatTime.day(day)) }
    previous = day
    // Read more opens the answer's card and reads it whole at once: one tap, not two (row 10).
    item(key = ask.id) { CoachTurn(said, ask, onOpen = { act.open(ask) }, onReadMore = { act.open(ask); act.readWhole(ask.id) }) }
  }
}

/** How many rows the thread takes above the microphone: each turn, and each day's header. */
internal fun threadItems(turns: List<Pair<WatchModel.Said, AskLine>>): Int {
  var previous: java.time.LocalDate? = null
  return turns.size + turns.count { (said, _) ->
    val day = WatchChatTime.dayOf(Instant.ofEpochMilli(said.at))
    ChatTime.startsDay(previous, day).also { previous = day }
  }
}

@Composable
internal fun CoachTurn(said: WatchModel.Said, ask: AskLine, onOpen: () -> Unit, onReadMore: () -> Unit = onOpen) {
  Column(Modifier.fillMaxWidth().testTag(THREAD_TURN + ask.id), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    // The person's words on the right, with "HH:mm ✓" at their foot, as on the Apple Watch: when they were sent, and that tafs
    // cloud took them (a turn is in the thread only once it has). Beside the words, not under them, so a turn takes no more height
    // and the newest stays in sight over the microphone (aNewTurnBringsThePageBackToTheMicrophone); a taf's chat, which has its
    // own send bar under the newest, has it under the words. The answer's time is beside it, on its right, once it ended.
    androidx.compose.foundation.layout.Row(
      Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.Bottom,
    ) {
      SentFoot(WatchChatTime.time(Instant.ofEpochMilli(said.at)), sent = true)
      Text(
        said.text, fontSize = 13.sp, lineHeight = 16.sp, color = Look.onMineGround, maxLines = 4, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false).widthIn(max = 150.dp)
          .background(Look.mineGround, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
    // Taken and not yet at work: the ✓ says it, and Coach's side waits for its first word.
    if (ask.state == AskLine.ACCEPTED) return@Column
    val answer = if (ask.ended) Plain.of(ask.line ?: ask.words).ifBlank { Words.Watch.moreOnPhone } else stateLine(ask)
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
    // The answer whole, however many lines (the owner's Watch 5 test: it was cut at five, "the computer is…", with no way on);
    // the page scrolls with the crown, and a tap opens it with Hear and Reply.
    Text(
      answer, fontSize = 13.sp, lineHeight = 16.sp, color = if (ask.ended) Look.onCoachGround else Look.coach,
      modifier = Modifier.weight(1f, fill = false).widthIn(max = 156.dp)
        .background(Look.coachGround, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
        .then(if (ask.ended) Modifier.clickable(onClick = onOpen) else Modifier)
        .padding(horizontal = 10.dp, vertical = 6.dp)
        .testTag(if (ask.ended) THREAD_ANSWER + ask.id else THREAD_WORKING + ask.id)
        // TalkBack hears whose words these are, as VoiceOver does on the Apple Watch.
        // While it works, the state line already names whose turn it is: TalkBack hears it once.
        .semantics { contentDescription = if (ask.ended) Words.Coach.title + ": " + answer else answer },
    )
    if (ask.ended) WatchChatTime.instantOf(ask.updated)?.let { MessageTime(WatchChatTime.time(it), inset = 0.dp) }
    }
    // tafs cloud says the answer goes on past its line: Read more, in the thread itself; one tap opens it whole on its card.
    if (ask.ended && ask.more) Text(Words.Watch.readMore, fontSize = 11.sp, color = Look.coach, modifier = Modifier.clickable(onClick = onReadMore).testTag(THREAD_MORE + ask.id))
  }
}

/**
 * Under the person's own message: when it was sent, in the watch's 12- or 24-hour setting, and ✓ once tafs cloud took it, named
 * "Sent" (`watch.sent`) for TalkBack, as the Apple Watch's.
 */
@Composable
internal fun SentFoot(time: String, sent: Boolean, modifier: Modifier = Modifier) {
  androidx.compose.foundation.layout.Row(modifier.testTag(SENT_FOOT), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
    MessageTime(time, inset = 0.dp)
    if (sent) Text("✓", fontSize = 10.sp, color = Look.done, modifier = Modifier.testTag(SENT_TICK).semantics { contentDescription = Words.Watch.sent })
  }
}

internal const val SENT_FOOT = "sent-foot"
internal const val SENT_TICK = "sent-tick"
internal const val THREAD_TURN = "thread-turn-"
internal const val THREAD_ANSWER = "thread-answer-"
internal const val THREAD_WORKING = "thread-working-"
internal const val THREAD_MORE = "thread-more-"
