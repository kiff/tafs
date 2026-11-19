package bot.tafs.app.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.design.tapping
import bot.tafs.app.model.ErrorWords
import bot.tafs.app.model.Words
import bot.tafs.app.model.zone
import bot.tafs.app.watch.WatchDay
import bot.tafs.app.watch.WatchDays
import bot.tafs.app.watch.WatchDaysFeature
import bot.tafs.app.watch.WatchDaysStore
import bot.tafs.app.watch.WatchTurn
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException

/*
 * From your watch (docs/android-app.md, From your watch): the watch's Coach conversations, a day at a time, in Coach's
 * earlier-conversations sheet. Read only: replying is a Coach chat's. Nothing of it is kept on the phone.
 */

/** tafs cloud's watch days, where the switch is on; null, and nothing of them drawn or asked, where it's off. */
val LocalWatchDays = staticCompositionLocalOf<WatchDaysStore?> { null }

/**
 * A day's title: Today ("calendar.today") for the account's today as tafs cloud gives it, else the phone's own today; any other
 * day as the platform writes a weekday, day and month in the app's language ("Wednesday 18 November").
 */
internal fun watchDayTitle(date: LocalDate, today: LocalDate?, phoneToday: LocalDate = LocalDate.now()): String {
  if (date == (today ?: phoneToday)) return Words.Calendar.today
  val locale = Locale.getDefault()
  val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
  return DateTimeFormatter.ofPattern(pattern, locale).format(date)
}

/** When a turn was said: the platform's short time in the app's language, in the account's zone, as the day is. */
internal fun watchTurnTime(at: Instant): String =
  DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(at.atZone(zone))

/**
 * "From your watch", and under it a row per day tafs cloud lists, newest first as it sends them: the day and how many requests.
 * Nothing at all, and no call, with the switch off ([on]) or no store; nothing either, never an error, when tafs cloud's switch
 * is off (404), it has no day, or the read fails.
 */
@Composable
internal fun WatchDaysSection(store: WatchDaysStore?, on: Boolean = WatchDaysFeature.on, onOpen: (WatchDay, LocalDate?) -> Unit) {
  if (!on || store == null) return
  var read by remember(store) { mutableStateOf<WatchDays?>(null) }
  LaunchedEffect(store) {
    read = try { store.days() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
  }
  val shown = read?.takeIf { it.days.isNotEmpty() } ?: return
  Text(Words.Coach.Chats.fromYourWatch, style = scaled(15, FontWeight.SemiBold, Theme.dim), modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
  shown.days.forEach { day ->
    Row(
      Modifier.fillMaxWidth().heightIn(min = 52.dp).tapping { onOpen(day, shown.today) }.padding(vertical = 8.dp).testTag("watch.day"),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(watchDayTitle(day.date, shown.today), style = scaled(15, FontWeight.SemiBold, Theme.ink), maxLines = 2, modifier = Modifier.weight(1f))
      Text(day.count.toString(), style = scaled(15, color = Theme.dim), modifier = Modifier.testTag("watch.day.count"))
      Text("›", style = scaled(20, color = Theme.dim))
    }
  }
}

/**
 * One day's turns, in tafs cloud's order (oldest first), read only: each "⌚ Sent from your watch" with when, what was said, then
 * the taf that took it and its line, else tafs cloud's words for the state. A reply sits indented under the turn before it. No
 * box and no send: replying is a Coach chat's. A read that fails says so in Coach's words, with Try again.
 */
@Composable
internal fun WatchDayView(store: WatchDaysStore, day: WatchDay, today: LocalDate?, onBack: () -> Unit, onClose: () -> Unit) {
  var attempt by remember(day) { mutableIntStateOf(0) }
  var turns by remember(day) { mutableStateOf<List<WatchTurn>?>(null) }
  var failed by remember(day) { mutableStateOf<String?>(null) }
  // Keyed on the day: a day left, or another opened, cancels this read, so a late answer is never drawn.
  LaunchedEffect(day, attempt) {
    failed = null
    turns = null
    try { turns = store.day(day.date).orEmpty() } catch (e: CancellationException) { throw e } catch (e: Exception) {
      failed = Words.Coach.conversationNotLoaded(ErrorWords.of(e))
    }
  }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(Words.Coach.Chats.back, style = scaled(17, color = Theme.amber), modifier = Modifier.heightIn(min = 44.dp).tapping(onClick = onBack).padding(top = 10.dp, end = 12.dp))
      Text(watchDayTitle(day.date, today), style = scaled(22, FontWeight.Bold, Theme.ink, display = true), modifier = Modifier.weight(1f))
      Text(Words.Common.done, style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).tapping(onClick = onClose).padding(top = 10.dp))
    }
    val shown = turns
    val error = failed
    when {
      error != null -> {
        Text(error, style = scaled(14, color = Theme.no), modifier = Modifier.padding(top = 16.dp))
        OutlineButton(Words.Common.tryAgain, { attempt++ }, Modifier.fillMaxWidth().padding(top = 8.dp))
      }
      shown == null -> Text(Words.Common.loading, style = scaled(15, color = Theme.dim), modifier = Modifier.padding(vertical = 16.dp))
      else -> shown.forEach { WatchTurnView(it) }
    }
  }
}

/** One turn: the watch's label and the time, the person's words, then the taf that took it and what came of it. */
@Composable
private fun WatchTurnView(turn: WatchTurn) {
  val shape = RoundedCornerShape(14.dp)
  Column(
    Modifier.fillMaxWidth().padding(start = if (turn.replyTo != null) 16.dp else 0.dp, top = 10.dp, bottom = 6.dp).testTag("watch.turn"),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(Words.Coach.Chats.sentFromYourWatch, style = scaled(12, FontWeight.SemiBold, Theme.dim))
      turn.saidAt?.let { Text("· " + watchTurnTime(it), style = scaled(12, color = Theme.dim)) }
    }
    Text(
      turn.said,
      style = scaled(15, color = Theme.ink),
      modifier = Modifier.fillMaxWidth().clip(shape).background(Theme.panel).border(BorderStroke(1.dp, Theme.line), shape).padding(horizontal = 14.dp, vertical = 10.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      turn.tafName?.let { Text(it, style = scaled(13, FontWeight.SemiBold, Theme.soft)) }
      Text(turn.answer, style = scaled(13, color = Theme.soft), modifier = Modifier.weight(1f, fill = false))
    }
  }
}
