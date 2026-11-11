package bot.tafs.app

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.views.PromiseRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * "Answer within" on a taf's page, for whoever manages it (docs/reliability.md › Its oldest waiting case): the promise as tafs cloud
 * keeps it, or Not set, and a menu of whole hours that sets it or clears it. tafs cloud checks the range and sends the alert.
 */
class PromiseRowTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  @Test fun withNoPromiseItSaysNotSetAndPickingHoursSetsThem() {
    val picked = mutableListOf<Int?>()
    rule.setShown { TafsTheme { PromiseRow(hours = null) { picked += it } } }
    rule.onNodeWithText("Answer within").assertExists()
    rule.onNodeWithText("You're told when a case waits longer.").assertExists()
    rule.onNodeWithText("Not set", substring = true).performClick()
    rule.waitForIdle()
    rule.onNodeWithText("4 hours").performClick()
    rule.waitForIdle()
    assertEquals(listOf<Int?>(4), picked)
  }

  @Test fun aPromiseShowsItsHoursAndNotSetClearsIt() {
    val picked = mutableListOf<Int?>()
    rule.setShown { TafsTheme { PromiseRow(hours = 24) { picked += it } } }
    rule.onNodeWithText("24 hours", substring = true).performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Not set").performClick()
    rule.waitForIdle()
    assertEquals(listOf<Int?>(null), picked)
  }
}
