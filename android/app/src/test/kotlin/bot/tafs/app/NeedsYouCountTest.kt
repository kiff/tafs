package bot.tafs.app

import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.NeedsYouCount
import bot.tafs.app.kiff.UnsureCall
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's headline and the badge count all Needs you holds, tafs cloud's count first, so Home never says nothing waits while Needs you shows something. */
class NeedsYouCountTest {
  @Test fun didThisHappenItemsCountAsWaiting() {
    val state = AppState(mode = Mode.kiff, unsure = listOf(UnsureCall("p1"), UnsureCall("p2")))
    assertEquals(2, state.needsYouCount)
    assertEquals("2 need you.", state.needsYouWords)
  }

  @Test fun tafsCloudsCountAndWordsWinOverTheAppsOwn() {
    // tafs cloud counts what the app can't see from here, a knock at a door included.
    val state = AppState(mode = Mode.kiff, unsure = listOf(UnsureCall("p1")), needsYou = NeedsYouCount(3, "3 need you."))
    assertEquals(3, state.needsYouCount)
    assertEquals("3 need you.", state.needsYouWords)
    assertEquals("Nothing needs you.", AppState(mode = Mode.kiff).needsYouWords)
  }
}
