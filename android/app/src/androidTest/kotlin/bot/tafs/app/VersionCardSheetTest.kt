package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.AgentDraft
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.Version
import bot.tafs.app.views.VersionCardSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The Card a version brings, read before Install. The sheet can't be swiped away (the switch it began must be finished or
 * cancelled), but it must still open: one that refused every change of state never showed, and lay invisible over the
 * Versions sheet, taking every tap.
 */
class VersionCardSheetTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val taps = mutableListOf<String>()

  private val version = KiffClient.json.decodeFromString<Version>(
    """{"n":2,"note":"Also tells customers where their refund is","created":"2026-11-01T08:40:00Z","draft":"dr_2"}""",
  )
  private val draft = KiffClient.json.decodeFromString<AgentDraft>(
    """{"id":"dr_2","agent_id":"refunds-1","status":"draft","version":"v2","cards":[{"grants":[{"action":"get_refund","title":"Read a refund"}]}]}""",
  )

  private fun show() = rule.setShown {
    TafsTheme {
      VersionCardSheet(
        version, "refunds-1", AppState(), card = { _, _ -> draft },
        onCancel = { taps += "cancel" },
        onInstall = { taps += "install $it" },
      )
    }
  }

  @Test fun itOpensWithItsCardAndInstallSendsTheVersionShown() {
    show()
    rule.onNodeWithText("Its Card").assertIsDisplayed()
    rule.onNodeWithText("Version 2: Also tells customers where their refund is").assertIsDisplayed()
    rule.onNodeWithText("Install this version").assertIsDisplayed().performClick()
    assertEquals(listOf("install v2"), taps)
  }

  @Test fun cancelEndsIt() {
    show()
    rule.onNodeWithText("Cancel").assertIsDisplayed().performClick()
    assertEquals(listOf("cancel"), taps)
  }
}
