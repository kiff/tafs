package bot.tafs.app

import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.UnsureCall
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's headline and the badge count all Needs you holds, so Home never says nothing waits while Needs you shows something. */
class NeedsYouCountTest {
  @Test fun didThisHappenItemsCountAsWaiting() {
    val state = AppState(mode = Mode.kiff, unsure = listOf(UnsureCall("p1"), UnsureCall("p2")))
    assertEquals(2, state.needsYouCount)
  }
}
