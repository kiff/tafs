package bot.tafs.app.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The KIFF account's time zone, once read: every time the app shows is in it, and a Card's day ends at its midnight. */
object AccountTime {
  @Volatile var accountZone: ZoneId? = null

  /**
   * Whether this device counts 24 hours (its clock setting), read when the app starts and each time it comes back; null before
   * it's read (tests, the watch), where times stay 24-hour as before.
   */
  @Volatile var is24Hour: Boolean? = null

  /** Uses the account's zone (UTC when KIFF names none), or with none this device's again. */
  fun use(id: String?) {
    accountZone = id?.let { runCatching { ZoneId.of(it) }.getOrNull() ?: ZoneId.of("UTC") }
  }
}

/** The time every screen draws in: the KIFF account's, else this device's (signed out, or the sample). */
val zone: ZoneId get() = AccountTime.accountZone ?: ZoneId.systemDefault()

/**
 * The time of day, in the device's 12- or 24-hour setting as the chat's stamps are (kiff/tafs#2195): "14:05", or "2:05 PM" where
 * the device counts 12 hours. So a decision line, "next run at" and "halted since" never show a second clock style beside the
 * chat's. A line already stored with its time keeps the style it was written in.
 */
fun shortTime(at: Instant): String =
  AccountTime.is24Hour?.let { messageTime(at, is24 = it) }
    // Not read yet (tests, the watch): 24-hour, as before.
    ?: DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(at.atZone(zone))

/** A date as "28 Oct 2026", as iOS's abbreviated date style. */
fun shownDate(at: Instant): String = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(at.atZone(zone))

/**
 * When something ends: the time alone today, with its date on a later day, as the chat's
 * timestamps are ("00:00", "1 Nov 2026, 00:00").
 */
fun endsWords(at: Instant, today: LocalDate = LocalDate.now(zone)): String =
  if (localDate(at) == today) shortTime(at) else shownDate(at) + ", " + shortTime(at)

fun localDate(at: Instant): LocalDate = at.atZone(zone).toLocalDate()

/** "Today · 28 Oct", "Yesterday · 27 Oct", "Monday · 26 Oct". */
fun dayTitle(day: LocalDate, today: LocalDate = LocalDate.now(zone)): String {
  val date = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(day)
  return when (day) {
    today -> Words.Calendar.todayDate(date)
    today.minusDays(1) -> Words.Calendar.yesterdayDate(date)
    else -> DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()).format(day) + " · " + date
  }
}

/**
 * A chat message's time, under it (row 44, C; docs/chat.md): the device's 12- or 24-hour setting, in the app's language, in the
 * account's zone. The rule is `ChatTime`'s, which the watch shares.
 */
fun messageTime(at: Instant, is24: Boolean): String = bot.tafs.watchlink.ChatTime.time(at.atZone(zone), is24, Res.locale, ::bestPattern)

/** The header where a chat's day starts: Today, Yesterday, then the date in the language's order ("Wed, Nov 18"). */
fun chatDay(day: LocalDate, today: LocalDate = LocalDate.now(zone)): String =
  bot.tafs.watchlink.ChatTime.day(day, today, Words.Calendar.today, Words.Chat.Day.yesterday, Res.locale, ::bestPattern)

private fun bestPattern(skeleton: String): String = android.text.format.DateFormat.getBestDateTimePattern(Res.locale, skeleton)

/** Three months on, as a new Card's end. */
fun threeMonthsOn(now: Instant): Instant = now.atZone(zone).plusMonths(3).toInstant()
