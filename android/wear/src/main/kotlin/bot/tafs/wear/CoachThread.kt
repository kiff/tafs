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
    item(key = ask.id) { CoachTurn(said, ask, onOpen = { act.open(ask) }) }
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
internal fun CoachTurn(said: WatchModel.Said, ask: AskLine, onOpen: () -> Unit) {
  Column(Modifier.fillMaxWidth().testTag(THREAD_TURN + ask.id), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    // Each message's time (row 44, C), beside it at its foot: the words' on their left, the answer's on its right once it ended.
    // Beside, not under, so a turn takes no more height and the newest stays in sight over the microphone
    // (aNewTurnBringsThePageBackToTheMicrophone).
    androidx.compose.foundation.layout.Row(
      Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.Bottom,
    ) {
      MessageTime(WatchChatTime.time(Instant.ofEpochMilli(said.at)), inset = 0.dp)
      Text(
        said.text, fontSize = 13.sp, lineHeight = 16.sp, color = Look.onMineGround, maxLines = 4, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false).widthIn(max = 150.dp)
          .background(Look.mineGround, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
    val answer = if (ask.ended) (ask.line ?: ask.words).ifBlank { Words.Watch.moreOnPhone } else stateLine(ask)
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
    Text(
      answer, fontSize = 13.sp, lineHeight = 16.sp, color = if (ask.ended) Look.onCoachGround else Look.coach, maxLines = 5,
      overflow = TextOverflow.Ellipsis,
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
  }
}

internal const val THREAD_TURN = "thread-turn-"
internal const val THREAD_ANSWER = "thread-answer-"
internal const val THREAD_WORKING = "thread-working-"
