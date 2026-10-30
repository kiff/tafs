package bot.tafs.app.kiff

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The time a signed request carries (kiff/tafs#245). planque and KIFF each refuse a signed request
 * they have already seen ("this signed request was already made"), and the time is part of what's
 * signed. Two reads made in the same second looked like a replay. Each signed request carries its
 * own millisecond, later than the last one this app signed.
 */
class SigningTime(private val clock: () -> Instant = Instant::now) {
  private var lastMs = Long.MIN_VALUE

  /** RFC 3339 with milliseconds ("2026-10-14T10:45:00.123Z"), never the same twice in this app. */
  @Synchronized
  fun next(): String {
    val ms = maxOf(clock().toEpochMilli(), lastMs + 1)
    lastMs = ms
    return FORMAT.format(Instant.ofEpochMilli(ms))
  }

  private companion object {
    // Always three digits: ISO_INSTANT would drop a zero fraction.
    val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
  }
}
