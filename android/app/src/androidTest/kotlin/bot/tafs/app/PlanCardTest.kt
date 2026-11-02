package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.Usage
import bot.tafs.app.model.UsageWords
import bot.tafs.app.views.SettingsSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Settings opens with the Plan card: the plan in one line, and a tap opens Plan & usage. */
class PlanCardTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private var opened = 0

  private val ended = KiffClient.json.decodeFromString<Usage>(
    """{"plan":"trial","period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"},"can_add_tasks":true,"billing":{"source":""},
    "tasks":{"counted":0,"included":0,"left":0,"packs":0},"trial":{"ends_at":"2026-11-01T21:56:00Z","days_left":0,"tasks_left":0},
    "store":{"app_account_token":"acct_1","google_solo_product":"solo_monthly","packs":[]}}""",
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
    show(AppState(mode = Mode.kiff, ready = true, usage = ended))
    rule.onNodeWithText("Trial ended · Start Solo").assertIsDisplayed().performClick()
    rule.waitForIdle()
    assertEquals(1, opened)
  }

  @Test fun theSampleShowsNoPlanCardButStillOpensPlanAndUsage() {
    show(AppState(mode = Mode.sample, ready = true, usage = ended))
    assertEquals("no Plan card in the sample", 0, rule.onAllNodes(androidx.compose.ui.test.hasText("Trial ended", substring = true)).fetchSemanticsNodes().size)
    assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("PLAN")).fetchSemanticsNodes().size)
    rule.onNodeWithText(UsageWords.TITLE).assertIsDisplayed().performClick()
    rule.waitForIdle()
    assertEquals(1, opened)
  }

  @Test fun beforeKiffsUsageIsReadTheCardStillOpensPlanAndUsage() {
    show(AppState(mode = Mode.kiff, ready = true))
    rule.onNodeWithText(UsageWords.TITLE).assertIsDisplayed().performClick()
    rule.waitForIdle()
    assertEquals(1, opened)
  }
}
