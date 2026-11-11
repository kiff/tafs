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

  @Test fun theMenuOpensUnderItsValueNotAtTheRowsStart() {
    rule.setShown { TafsTheme { PromiseRow(hours = 4) {} } }
    val label = rule.onNodeWithText("Answer within").fetchSemanticsNode().positionOnScreen.x
    val value = rule.onNodeWithText("4 hours", substring = true).fetchSemanticsNode().positionOnScreen.x
    rule.onNodeWithText("4 hours", substring = true).performClick()
    rule.waitForIdle()
    // The menu is a window of its own: on screen, its first choice starts at the value, right of the row's label.
    val choice = rule.onNodeWithText("Not set").fetchSemanticsNode().positionOnScreen.x
    org.junit.Assert.assertTrue("the menu opens at its value ($choice, value $value, label $label)", choice > label + (value - label) / 2)
  }
}
