package bot.tafs.watchlink

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * When a chat's messages were sent, as the phone and the watch both show it (row 44, C; docs/chat.md, "When each message was
 * sent"): a small time under each message, in the device's 12- or 24-hour setting and the app's language, and a header where a
 * new day starts: Today, Yesterday, then the date in the language's own order, with the year when it isn't this one. Written
 * once here, so the two apps can't drift.
 *
 * The patterns come from the platform's skeletons (`android.text.format.DateFormat.getBestDateTimePattern`), passed in as
 * [pattern], so this file stays plain Kotlin: "Hm" or "hm" for the time, "EEEMMMd" or "EEEMMMdy" for a day.
 */
object ChatTime {
  fun timeSkeleton(is24: Boolean): String = if (is24) "Hm" else "hm"

  fun daySkeleton(day: LocalDate, today: LocalDate): String = if (day.year == today.year) "EEEMMMd" else "EEEMMMdy"

  /** "14:05", or "2:05 PM" where the device counts 12 hours. */
  fun time(at: ZonedDateTime, is24: Boolean, locale: Locale, pattern: (String) -> String): String =
    format(pattern(timeSkeleton(is24)), locale)?.format(at) ?: DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at)

  /** The header over a day's first message: [today] and [yesterday] in the app's words, else "Wed, Nov 18" in the language's order. */
  fun day(day: LocalDate, today: LocalDate, todayWord: String, yesterdayWord: String, locale: Locale, pattern: (String) -> String): String =
    when (day) {
      today -> todayWord
      today.minusDays(1) -> yesterdayWord
      else -> format(pattern(daySkeleton(day, today)), locale)?.format(day) ?: DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
    }

  /** Whether a header goes over the message on [day]: the chat's first, or the first of a new day. */
  fun startsDay(previous: LocalDate?, day: LocalDate): Boolean = previous != day

  // A platform pattern java.time can't read (a letter it doesn't take) falls back to the language's short form.
  private fun format(pattern: String, locale: Locale): DateTimeFormatter? = runCatching { DateTimeFormatter.ofPattern(pattern, locale) }.getOrNull()
}
