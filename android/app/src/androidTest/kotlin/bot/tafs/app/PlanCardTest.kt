package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Balance
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.UsageWords
import bot.tafs.app.views.SettingsSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Settings opens with the Plan card: the plan in tafs cloud's one line (`plan_line`), and a tap opens Plan & usage. */
class PlanCardTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private var opened = 0

  private val noPlan = KiffClient.json.decodeFromString<Balance>(
    """{"balance":{"state":"waiting","why":"no_plan","meter_pct":null,"words":"No plan","warning":"Choose a plan to start your tafs."},
       "plan_line":"No plan · choose one to start","plan_tone":"waiting","home_line":"Choose a plan to start your tafs."}""",
  )

  private fun show(state: AppState) = rule.setShown {
    TafsTheme {
      SettingsSheet(
        state, onSignIn = {}, onSignOut = { true }, onUsage = { opened += 1 },
        onTurnOnAlerts = {}, onAlertSound = {}, onDevices = {}, onPeople = {}, onUseDeviceZone = {}, onDeleteAccount = {}, onClose = {},
      )
    }
  }

  @Test fun thePlanCardSaysThePlanAndOpensPlanAndUsage() {
    show(AppState(mode = Mode.kiff, ready = true, balance = noPlan))
    rule.onNodeWithText("No plan · choose one to start").performScrollTo().assertIsDisplayed().performClick()
    rule.waitForIdle()
    assertEquals(1, opened)
  }

  @Test fun theSampleShowsNoPlanCardButStillOpensPlanAndUsage() {
    show(AppState(mode = Mode.sample, ready = true, balance = noPlan))
    assertEquals("no Plan card in the sample", 0, rule.onAllNodes(androidx.compose.ui.test.hasText("No plan", substring = true)).fetchSemanticsNodes().size)
    assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("PLAN")).fetchSemanticsNodes().size)
    rule.onNodeWithText(UsageWords.TITLE).performScrollTo().assertIsDisplayed().performClick()
    rule.waitForIdle()
    assertEquals(1, opened)
  }

  @Test fun beforeTheBalanceIsReadTheCardStillOpensPlanAndUsage() {
    show(AppState(mode = Mode.kiff, ready = true))
    rule.onNodeWithText(UsageWords.TITLE).performScrollTo().assertIsDisplayed().performClick()
    rule.waitForIdle()
    assertEquals(1, opened)
  }
}
