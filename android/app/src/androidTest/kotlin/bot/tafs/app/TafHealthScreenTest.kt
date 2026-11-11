package bot.tafs.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.HealthFix
import bot.tafs.app.kiff.TafHealth
import bot.tafs.app.views.HealthLines
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * A taf's health on its page (docs/reliability.md › Its health): each of tafs cloud's lines, and under a line its fix. Coach opens
 * with tafs cloud's words; Add credits opens Plan & usage. The app decides no level and no fix.
 */
class TafHealthScreenTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val say = "Refunds' check finds work too big for one run. Narrow what it reads."
  private val failing = TafHealth(
    level = "failing",
    lines = listOf("Its last 2 checks found work too big for one run, so nothing ran.", "Waiting for credits since 09:00.", "Two of its last ten runs failed."),
    fixes = listOf(
      HealthFix(line = 0, kind = "coach", words = "Ask Coach to narrow what it reads", say = say),
      HealthFix(line = 1, kind = "credits", words = "Add credits"),
      HealthFix(line = 2, kind = "something_new", words = "Do the new thing"),
    ),
  )

  @Test fun eachLineShowsWithItsFixAndTheFixesDoWhatTafsCloudSays() {
    val coach = mutableListOf<String>()
    var plan = 0
    rule.setShown { TafsTheme { HealthLines(failing, canUseCoach = true, onCoach = { coach += it }, onPlan = { plan++ }) } }
    failing.lines.forEach { rule.onNodeWithText(it).assertExists() }
    rule.onNodeWithText("Ask Coach to narrow what it reads").performClick()
    rule.waitForIdle()
    assertEquals(listOf(say), coach)
    rule.onNodeWithText("Add credits").performClick()
    rule.waitForIdle()
    assertEquals(1, plan)
    // A kind the app doesn't know draws no button: its line still shows.
    rule.onAllNodesWithText("Do the new thing").assertCountEquals(0)
  }

  @Test fun someoneWhoCantUseCoachGetsNoCoachButtonButStillTheLine() {
    rule.setShown { TafsTheme { HealthLines(failing, canUseCoach = false, onCoach = {}, onPlan = {}) } }
    rule.onNodeWithText(failing.lines[0]).assertExists()
    rule.onAllNodesWithText("Ask Coach to narrow what it reads").assertCountEquals(0)
    rule.onNodeWithText("Add credits").assertExists()
  }

  @Test fun aHealthyTafWithNoLinesShowsNothing() {
    rule.setShown { TafsTheme { HealthLines(TafHealth("healthy"), canUseCoach = true, onCoach = {}, onPlan = {}) } }
    rule.onAllNodesWithText("Add credits").assertCountEquals(0)
  }
}
