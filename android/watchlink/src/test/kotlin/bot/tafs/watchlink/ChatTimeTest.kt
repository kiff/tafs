package bot.tafs.watchlink

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimeTest {
  private val today = LocalDate.of(2026, 11, 20)
  private val at = ZonedDateTime.of(2026, 11, 20, 14, 5, 0, 0, ZoneId.of("Europe/Paris"))
  // The patterns Android gives for these skeletons in English and German.
  private val en = mapOf("Hm" to "HH:mm", "hm" to "h:mm a", "EEEMMMd" to "EEE, MMM d", "EEEMMMdy" to "EEE, MMM d, y")
  private val de = mapOf("Hm" to "HH:mm", "hm" to "h:mm a", "EEEMMMd" to "EEE, d. MMM", "EEEMMMdy" to "EEE, d. MMM y")

  @Test fun theTimeFollowsTheDevicesTwelveOrTwentyFourHours() {
    assertEquals("14:05", ChatTime.time(at, is24 = true, Locale.ENGLISH, en::getValue))
    assertEquals("2:05 PM", ChatTime.time(at, is24 = false, Locale.ENGLISH, en::getValue))
  }

  @Test fun todayAndYesterdayAreTheAppsWords() {
    assertEquals("Today", ChatTime.day(today, today, "Today", "Yesterday", Locale.ENGLISH, en::getValue))
    assertEquals("Gestern", ChatTime.day(today.minusDays(1), today, "Heute", "Gestern", Locale.GERMAN, de::getValue))
  }

  @Test fun anOlderDayIsTheDateInTheLanguagesOrderWithTheYearOnlyWhenItIsntThisOne() {
    assertEquals("Wed, Nov 18", ChatTime.day(LocalDate.of(2026, 11, 18), today, "Today", "Yesterday", Locale.ENGLISH, en::getValue))
    assertEquals("Mi., 18. Nov.", ChatTime.day(LocalDate.of(2026, 11, 18), today, "Heute", "Gestern", Locale.GERMAN, de::getValue))
    assertEquals("Wed, Dec 31, 2025", ChatTime.day(LocalDate.of(2025, 12, 31), today, "Today", "Yesterday", Locale.ENGLISH, en::getValue))
  }

  @Test fun aPatternJavaTimeCantReadFallsBackToTheShortForm() {
    assertEquals("2:05 PM", ChatTime.time(at, is24 = false, Locale.US) { "h:mm {" }.replace('\u202F', ' '))
  }

  @Test fun aHeaderGoesOverTheFirstMessageAndEachNewDay() {
    assertTrue(ChatTime.startsDay(null, today))
    assertFalse(ChatTime.startsDay(today, today))
    assertTrue(ChatTime.startsDay(today.minusDays(1), today))
  }
}
