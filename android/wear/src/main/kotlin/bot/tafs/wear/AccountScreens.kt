package bot.tafs.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import bot.tafs.watchlink.EventLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.WatchReply
import java.util.Locale

/*
 * Wear v2, step (vi) (docs/watch-wear.md, Version 2): a held call on its own screen, the hand-off to the phone for approving,
 * a calendar day, the Account page, and Sign out on this watch alone. Behind WATCH_V2, as every v2 screen.
 */

/** A screen over the pages, opened from one of them: the pager stays where it was underneath. */
internal sealed interface Over {
  /** A held call, by its hold id: drawn from Needs you's read as it stands, so an answered one closes by itself. */
  data class HeldCall(val holdId: String) : Over

  /** Approve on your phone, tapped: the phone has Needs you open. */
  data object Handoff : Over

  data object Calendar : Over

  /** Sign out, asked once more. */
  data object SignOut : Over
}

/** The screen [over] names. [go] opens another; [close] goes back to the pages. */
@Composable
internal fun OverScreen(
  over: Over,
  shown: WatchModel.Shown,
  calendar: WatchModel.Reading<WatchModel.DayRead>?,
  act: Actions,
  go: (Over) -> Unit,
  close: () -> Unit,
) {
  BackHandler(onBack = if (over == Over.Calendar) ({ act.closeCalendar(); close() }) else close)
  when (over) {
    is Over.HeldCall -> {
      val call = (shown as? WatchModel.Shown.Read)?.needsYou?.calls?.firstOrNull { it.holdId == over.holdId }
      // Answered, refused or gone since: there's nothing left to show.
      if (call == null) LaunchedEffect(over) { close() }
      else HeldCallScreen(call, act, handoff = { go(Over.Handoff) }, close = close)
    }
    Over.Handoff -> HandoffScreen(ok = {
      close()
      // Back on the Inbox: the list as the phone has it now.
      act.tryAgain()
    })
    Over.Calendar -> CalendarScreen(calendar, act, close = { act.closeCalendar(); close() })
    Over.SignOut -> SignOutScreen(confirm = { act.signOut(); close() }, cancel = close)
  }
}

/** A held call in the Inbox, in a line or two: its taf, what it wants and the amount. A tap opens it whole. */
@Composable
internal fun HeldCallRow(call: HeldLine, open: () -> Unit) {
  Card(onClick = open, modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(call.taf, style = MaterialTheme.typography.labelSmall, color = Look.held)
      Text(call.wants, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
      call.amount?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
  }
}

/**
 * A held call, as the phone's Needs you shows it: its taf, what it wants, KIFF's amount and the reason, and that nothing is sent
 * until it's approved. **Approve on your phone** opens Needs you there and says so; approving is never on the watch. **Refuse**
 * only where Needs you lets the wrist refuse it, through v1's path (the watch unlocked, the phone signing).
 */
@Composable
private fun HeldCallScreen(call: HeldLine, act: Actions, handoff: () -> Unit, close: () -> Unit) {
  ScalingLazyColumn(Modifier.fillMaxWidth(), state = rememberScalingLazyListState(initialCenterItemIndex = 0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { ListHeader { Text(Words.NeedsYou.title, color = Look.held) } }
    item { Text(call.taf, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    item { Text(call.wants, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    call.amount?.let { item { Text(it, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) } }
    call.reason?.let { item { Text(it, color = Look.held, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) } }
    item { Text(Words.Watch.nothingSentUntil, style = MaterialTheme.typography.bodySmall, color = Look.dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    item {
      Button(
        onClick = {
          act.openOnPhone("needs-you")
          handoff()
        },
        modifier = Modifier.fillMaxWidth(),
        colors = heldButton(),
      ) { Text(Words.Watch.approveOnPhone) }
    }
    if (call.refusable) item {
      Button(
        onClick = {
          act.refuse(call.holdId)
          // What came of it shows on the page, in the phone's words.
          close()
        },
        modifier = Modifier.fillMaxWidth(),
        colors = dangerQuietButton(),
      ) { Text(Words.NeedsYou.refuse) }
    }
    item { Button(onClick = close, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.close) } }
  }
}

/**
 * Approve on your phone, tapped: "Check your phone", and that Needs you is open there. The phone opens Needs you's list
 * (`tafs://open/needs-you`), not the one call, so the line doesn't say it does.
 */
@Composable
private fun HandoffScreen(ok: () -> Unit) {
  ScalingLazyColumn(Modifier.fillMaxWidth(), state = rememberScalingLazyListState(initialCenterItemIndex = 0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { Text(Words.Watch.checkPhoneTitle, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    item { Text(Words.Watch.checkPhoneSub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) }
    item { Button(onClick = ok, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.ok) } }
  }
}

/** The door to the calendar's day: on the Tafs page, and on Brief where the brief is on. */
internal fun ScalingLazyListScope.calendarDoor(open: () -> Unit) = item {
  Button(onClick = open, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.calendarToday) }
}

/**
 * A calendar day, read from the phone when it opens (today, in the account's zone): tafs cloud's line for it, then each event in
 * the order tafs cloud gives them, with its time, its taf, its words and how many times. Previous and Next day read again.
 */
@Composable
private fun CalendarScreen(calendar: WatchModel.Reading<WatchModel.DayRead>?, act: Actions, close: () -> Unit) {
  LaunchedEffect(Unit) { act.readCalendar(null) }
  ScalingLazyColumn(Modifier.fillMaxWidth(), state = rememberScalingLazyListState(initialCenterItemIndex = 0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    val read = (calendar as? WatchModel.Reading.Read)?.value
    item { ListHeader { Text(read?.let { if (it.today) Words.Calendar.today else dayTitle(it.day.day) } ?: Words.Calendar.today) } }
    when (calendar) {
      null, WatchModel.Reading.Asking -> line(Words.Calendar.reading)
      WatchModel.Reading.SignedOut -> line(Words.Watch.signInOnPhone)
      is WatchModel.Reading.PhoneUnreachable -> {
        line(calendar.why.line)
        item { Button(onClick = { act.readCalendar(null) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.tryAgain) } }
      }
      WatchModel.Reading.OpenOnPhone -> item {
        Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.openOnPhone) }
      }
      is WatchModel.Reading.Read -> {
        val day = calendar.value.day
        // tafs cloud's line: what's to come, or that the account is halted and nothing runs.
        item {
          Text(
            day.words, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            color = if (day.halted) Look.danger else Look.text, style = MaterialTheme.typography.bodySmall,
          )
        }
        for (event in day.events) item { EventRow(event) }
        item {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { act.readCalendar(shift(day.day, -1)) }, modifier = Modifier.weight(1f), colors = neutralButton()) { Text(Words.Calendar.previousDay, maxLines = 2) }
            Button(onClick = { act.readCalendar(shift(day.day, 1)) }, modifier = Modifier.weight(1f), colors = neutralButton()) { Text(Words.Calendar.nextDay, maxLines = 2) }
          }
        }
      }
    }
    item { Spacer(Modifier.height(4.dp)) }
    item { Button(onClick = close, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.close) } }
  }
}

/** One event: its time, then its taf, its words, and "×3" when it happens more than once that day. */
@Composable
private fun EventRow(event: EventLine) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
    Text(event.time, style = MaterialTheme.typography.labelMedium, color = Look.dim)
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      if (event.taf.isNotBlank()) Text(event.taf, style = MaterialTheme.typography.titleSmall)
      Text(event.words, style = MaterialTheme.typography.bodySmall)
      if (event.count > 1) Text(Words.Calendar.times(event.count), style = MaterialTheme.typography.labelSmall, color = Look.dim)
    }
  }
}

/** The day before or after [day] (`YYYY-MM-DD`): a date in the account's zone, moved by whole days. */
internal fun shift(day: String, days: Long): String = java.time.LocalDate.parse(day).plusDays(days).toString()

/** A day other than today, as the watch's language writes a weekday, day and month. */
private fun dayTitle(day: String): String {
  val locale = Locale.forLanguageTag(Res.language)
  val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
  return java.time.LocalDate.parse(day).format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * The Account page: who is signed in, the account's name and the plan's line as the phone reads them; the watch's own language and
 * version; Open on phone; Sign out on this watch; and the trust line. Sign out works whatever the phone answered: it's the watch's.
 */
internal fun ScalingLazyListScope.accountItems(account: WatchModel.Reading<WatchReply.AccountPage>?, act: Actions, signOut: () -> Unit) {
  when (account) {
    null, WatchModel.Reading.Asking -> line(Words.Watch.askingPhone)
    WatchModel.Reading.SignedOut -> line(Words.Watch.signInOnPhone)
    is WatchModel.Reading.PhoneUnreachable -> {
      line(account.why.line)
      item { Button(onClick = act.readAccount, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.tryAgain) } }
    }
    WatchModel.Reading.OpenOnPhone -> Unit
    is WatchModel.Reading.Read -> {
      val page = account.value
      if (page.email != null || page.account != null) item { AccountRow(Words.Watch.signedIn, listOfNotNull(page.email, page.account)) }
      page.plan?.let { item { AccountRow(Words.Settings.plan, listOf(it)) } }
    }
  }
  item { AccountRow(Words.Settings.languageTitle, listOf(languageName())) }
  item { AccountRow(Words.Settings.version, listOf(BuildConfig.VERSION_NAME)) }
  item { Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.openOnPhone) } }
  item {
    Button(onClick = signOut, modifier = Modifier.fillMaxWidth(), colors = dangerQuietButton()) {
      Text(Words.Settings.signOut)
    }
  }
  line(Words.Watch.trust)
}

@Composable
private fun AccountRow(label: String, values: List<String>) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = Look.dim)
    for (value in values) Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}

/** The language the watch's lines are in, in its own name ("Español"). */
private fun languageName(): String {
  val locale = Locale.forLanguageTag(Res.language)
  return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

/** Sign out, asked once more: what it does, and that the phone stays signed in. */
@Composable
private fun SignOutScreen(confirm: () -> Unit, cancel: () -> Unit) {
  ScalingLazyColumn(Modifier.fillMaxWidth(), state = rememberScalingLazyListState(initialCenterItemIndex = 0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { Text(Words.Watch.signOutQ, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    item { Text(Words.Watch.signOutSub, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) }
    item {
      Button(onClick = confirm, modifier = Modifier.fillMaxWidth(), colors = dangerButton()) {
        Text(Words.Settings.signOut)
      }
    }
    item { Button(onClick = cancel, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.cancel) } }
  }
}

/** Signed out on this watch: that the phone still is, and Sign in, which reads the phone again. Nothing else is drawn. */
@Composable
internal fun SignedOutHereScreen(act: Actions) {
  ScalingLazyColumn(Modifier.fillMaxWidth(), state = rememberScalingLazyListState(initialCenterItemIndex = 0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    line(Words.Watch.signedOutHere)
    item { Button(onClick = act.signIn, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.SignIn.withPhone) } }
  }
}

/** [WatchSignIn] in the watch's private preferences: one flag, nothing of the account. */
class PreferencesWatchSignIn(context: android.content.Context) : WatchSignIn {
  private val prefs = context.getSharedPreferences("tafs.sign_in", android.content.Context.MODE_PRIVATE)

  override fun signedOut(): Boolean = prefs.getBoolean(SIGNED_OUT, false)

  override fun signOut() {
    prefs.edit().putBoolean(SIGNED_OUT, true).apply()
  }

  override fun signIn() {
    prefs.edit().clear().apply()
  }

  private companion object {
    const val SIGNED_OUT = "signed_out"
  }
}

/** Whether the watch is signed out here alone (v2's Account): a flag in the watch's preferences, or a field in a test. */
interface WatchSignIn {
  fun signedOut(): Boolean

  fun signOut()

  fun signIn()
}
