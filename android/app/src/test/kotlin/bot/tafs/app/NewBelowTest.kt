package bot.tafs.app

import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.model.Chat
import bot.tafs.app.model.Res
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dot on the way to the end of a chat (as on the iPad): something new arrived below what's on screen while the thread
 * wasn't following it. Seeing the end clears it; anything that arrives while the thread follows it never sets it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NewBelowTest {
  @Test fun somethingArrivingBelowWhileNotFollowingSetsIt() {
    assertTrue(Chat.newBelow(was = false, atBottom = false, following = false, arrived = true))
  }

  @Test fun theEndInViewClearsIt() {
    assertFalse(Chat.newBelow(was = true, atBottom = true, following = false, arrived = false))
    assertFalse(Chat.newBelow(was = true, atBottom = true, following = false, arrived = true))
  }

  @Test fun whileTheThreadFollowsNothingIsNew() {
    assertFalse(Chat.newBelow(was = false, atBottom = false, following = true, arrived = true))
  }

  @Test fun itStaysUntilTheEndIsSeen() {
    assertTrue(Chat.newBelow(was = true, atBottom = false, following = false, arrived = false))
    assertTrue(Chat.newBelow(was = true, atBottom = false, following = true, arrived = false))
  }

  /** With the phone's animations off, the working line counts the time instead of a still spinner. */
  @Test fun theTimeAtWorkReadsInSecondsThenMinutes() {
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    assertEquals("0 s", Chat.elapsed(400))
    assertEquals("12 s", Chat.elapsed(12_900))
    assertEquals("1 min 05 s", Chat.elapsed(65_000))
    assertEquals("0 s", Chat.elapsed(-5))
  }
}
