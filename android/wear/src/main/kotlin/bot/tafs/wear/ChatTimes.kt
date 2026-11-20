package bot.tafs.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import bot.tafs.watchlink.ChatTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * When each message in a chat on the watch was sent (row 44, C; docs/watch-wear.md): the small time under it, in the watch's
 * 12- or 24-hour setting and its language, and a header where a day starts (Today, Yesterday, then the date). The rule is
 * `ChatTime`'s, which the phone shares; the watch's own zone, since the watch has no account.
 */
internal object WatchChatTime {
  fun time(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String = ChatTime.time(at.atZone(zone), Res.is24Hour, Res.locale, ::pattern)

  fun day(day: LocalDate, today: LocalDate = LocalDate.now()): String =
    ChatTime.day(day, today, Words.Calendar.today, Words.Chat.Day.yesterday, Res.locale, ::pattern)

  fun dayOf(at: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate = at.atZone(zone).toLocalDate()

  /** tafs cloud's time for a message (ISO 8601), or none when it sent none or one that doesn't read. */
  fun instantOf(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

  private fun pattern(skeleton: String): String = android.text.format.DateFormat.getBestDateTimePattern(Res.locale, skeleton)
}

/** The header over a day's first message. */
@Composable
internal fun DayHeader(words: String) {
  Text(words, fontSize = 11.sp, color = Look.dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag(DAY_HEADER))
}

/** A message's time, small, under it on its own side. */
@Composable
internal fun MessageTime(words: String, modifier: Modifier = Modifier, inset: androidx.compose.ui.unit.Dp = 8.dp) {
  Text(words, fontSize = 10.sp, color = Look.faint, maxLines = 1, softWrap = false, modifier = modifier.padding(horizontal = inset).testTag(MESSAGE_TIME))
}

internal const val DAY_HEADER = "day-header"
internal const val MESSAGE_TIME = "message-time"
