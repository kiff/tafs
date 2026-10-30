package bot.tafs.app.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The KIFF account's time zone, once read: every time the app shows is in it, and a Card's day ends at its midnight. */
object AccountTime {
  @Volatile var accountZone: ZoneId? = null

  /** Uses the account's zone (UTC when KIFF names none), or with none this device's again. */
  fun use(id: String?) {
    accountZone = id?.let { runCatching { ZoneId.of(it) }.getOrNull() ?: ZoneId.of("UTC") }
  }
}

/** The time every screen draws in: the KIFF account's, else this device's (signed out, or the sample). */
val zone: ZoneId get() = AccountTime.accountZone ?: ZoneId.systemDefault()

/** The time of day, as 14:05, as iOS's `shortTime`. */
fun shortTime(at: Instant): String = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(at.atZone(zone))

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
    today -> "Today · $date"
    today.minusDays(1) -> "Yesterday · $date"
    else -> DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()).format(day) + " · " + date
  }
}

/** Three months on, as a new Card's end. */
fun threeMonthsOn(now: Instant): Instant = now.atZone(zone).plusMonths(3).toInstant()
