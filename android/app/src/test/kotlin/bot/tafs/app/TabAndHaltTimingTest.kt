package bot.tafs.app

import bot.tafs.app.views.HALT_POLLS_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two small rules from the live check on Android 39: the tab bar leaves a taf's screen, and a halt shows soon. */
class TabAndHaltTimingTest {
  @Test fun tafsTappedAgainGoesBackToTheList() {
    assertTrue(tappedAgainGoesToStart(Tab.Tafs, Tab.Tafs))
    assertFalse("another tab is a tab of its own", tappedAgainGoesToStart(Tab.Tafs, Tab.NeedsYou))
    assertFalse(tappedAgainGoesToStart(Tab.NeedsYou, Tab.NeedsYou))
  }

  @Test fun aHaltIsReadAgainSoonThenEveryTwoSeconds() {
    assertEquals(listOf(500L, 1_000L, 2_000L), HALT_POLLS_MS.take(3))
    val total = HALT_POLLS_MS.sum()
    assertTrue("about two minutes in all: $total", total in 110_000L..130_000L)
  }
}
