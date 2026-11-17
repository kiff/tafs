package bot.tafs.app

import bot.tafs.app.app.AppState
import bot.tafs.app.app.COACH_AGENT
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.Access
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coach asked about a taf from its page (a health line's fix, a delivery still not made) is offered only to whoever may use Coach
 * and manages the taf, as on the iPad. Someone who only uses it gets no button, rather than a working one tafs cloud then refuses.
 */
class CoachOnTafTest {
  private fun state(coach: Boolean) = AppState(
    mode = Mode.kiff,
    access = mapOf(
      COACH_AGENT to Access(use = coach),
      "managed" to Access(use = true, manage = true),
      "used" to Access(use = true),
    ),
  )

  @Test fun onlyWhoeverMayUseCoachAndManagesTheTafIsOffered() {
    assertTrue(state(coach = true).mayAskCoachAbout("managed"))
    assertFalse("uses the taf, doesn't manage it", state(coach = true).mayAskCoachAbout("used"))
    assertFalse("may not use Coach", state(coach = false).mayAskCoachAbout("managed"))
    assertFalse("a taf it doesn't know", state(coach = true).mayAskCoachAbout("other"))
  }
}
