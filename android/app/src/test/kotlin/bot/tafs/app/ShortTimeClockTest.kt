package bot.tafs.app

import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.model.AccountTime
import bot.tafs.app.model.Res
import bot.tafs.app.model.messageTime
import bot.tafs.app.model.shortTime
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * One clock style on a screen (kiff/tafs#2195): a decision line's time ("Approved once by you · …") and a chat stamp follow the
 * same 12- or 24-hour setting, the device's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShortTimeClockTest {
  private val at = Instant.parse("2026-11-26T22:22:00Z")

  @Before fun words() {
    RuntimeEnvironment.setQualifiers("en")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    AccountTime.use("UTC")
  }

  @After fun reset() {
    AccountTime.is24Hour = null
    AccountTime.accountZone = null
  }

  @Test fun onATwelveHourDeviceTheDecisionLineMatchesTheChatStamp() {
    AccountTime.is24Hour = false
    assertEquals(messageTime(at, is24 = false), shortTime(at))
    assertEquals("10:22 PM", shortTime(at).replace(' ', ' '))
  }

  @Test fun onATwentyFourHourDeviceBothRead2222() {
    AccountTime.is24Hour = true
    assertEquals("22:22", shortTime(at))
    assertEquals("22:22", messageTime(at, is24 = true))
  }

  @Test fun onATwentyFourHourDeviceTheLanguagesOwnSeparatorMatchesTheChatStamp() {
    // Finnish writes 22.22: the decision line follows the language's pattern, as the chat stamp does.
    RuntimeEnvironment.setQualifiers("fi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    AccountTime.is24Hour = true
    assertEquals(messageTime(at, is24 = true), shortTime(at))
  }

  @Test fun beforeTheSettingIsReadTimesStay24Hour() {
    AccountTime.is24Hour = null
    assertEquals("22:22", shortTime(at))
  }
}
