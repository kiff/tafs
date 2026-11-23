package bot.tafs.app

import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.design.AndroidWords
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Card's "this would be the 6th call" (`{nth}`): Turkmen, Urdu, Hindi, Bengali and Mongolian are written by the app from
 * CLDR's ordinal classes, since ICU writes English's "6th" for some of them and Hindi's in the masculine; every other language
 * stays ICU's. Through [AndroidWords], so the figures are the ones the app writes (Bengali's own digits).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrdinalsTest {
  private val words = AndroidWords(ApplicationProvider.getApplicationContext())
  private val ns = listOf(1L, 2, 3, 4, 6, 9, 10, 21)

  private fun ordinals(language: String) = ns.map { words.ordinal(it, language) }

  @Test fun turkmenTakesNjyForFewElseNji() =
    assertEquals(listOf("1-nji", "2-nji", "3-nji", "4-nji", "6-njy", "9-njy", "10-njy", "21-nji"), ordinals("tk"))

  @Test fun urduTakesWeinAfterEveryNumber() =
    assertEquals(ns.map { "${it}ویں" }, ordinals("ur"))

  @Test fun hindiIsFeminineForACallAndZeroStaysBare() {
    assertEquals(listOf("1ली", "2री", "3री", "4थी", "6ठी", "9वीं", "10वीं", "21वीं"), ordinals("hi"))
    assertEquals("0", words.ordinal(0, "hi"))
  }

  @Test fun bengaliTakesItsClassSuffixOnItsOwnDigits() =
    assertEquals(listOf("১ম", "২য়", "৩য়", "৪র্থ", "৬ষ্ঠ", "৯ম", "১০ম", "২১তম"), ordinals("bn"))

  @Test fun mongolianTakesErAfterEveryNumber() =
    assertEquals(ns.map { "$it-р" }, ordinals("mn"))

  @Test fun everyOtherLanguageStaysIcus() {
    assertEquals(listOf("1st", "2nd", "3rd", "4th", "6th", "9th", "10th", "21st"), ordinals("en"))
    assertEquals("21.", words.ordinal(21, "de"))
  }
}
