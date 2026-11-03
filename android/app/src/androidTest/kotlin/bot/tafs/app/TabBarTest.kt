package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The bar under every screen: Account, the three tabs, then Coach for whoever may use it. */
class TabBarTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val log = mutableListOf<String>()

  private fun show(coach: Boolean) = rule.setShown {
    TafsTheme {
      TabBar(
        Tab.Tafs, AppState(mode = Mode.kiff, ready = true),
        onTab = { log += "tab ${it.name}" }, onAccount = { log += "account" }, onCoach = if (coach) ({ log += "coach" }) else null,
      )
    }
  }

  @Test fun accountAndCoachOpenWhatTheyOpenAndTheTabsSwitch() {
    show(coach = true)
    rule.onNodeWithText(ACCOUNT).performClick()
    rule.onNodeWithText("Record").performClick()
    rule.onNodeWithText(COACH).assertIsDisplayed().performClick()
    rule.waitForIdle()
    assertEquals(listOf("account", "tab Record", "coach"), log)
  }

  @Test fun someoneWhoCantUseCoachHasFourPlaces() {
    show(coach = false)
    assertEquals(0, rule.onAllNodes(hasText(COACH)).fetchSemanticsNodes().size)
    rule.onNodeWithText(ACCOUNT).assertIsDisplayed()
  }
}
