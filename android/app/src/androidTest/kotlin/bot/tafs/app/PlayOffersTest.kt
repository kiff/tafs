package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.billing.StoreProduct
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.Usage
import bot.tafs.app.model.UsageWords
import bot.tafs.app.views.AccountActions
import bot.tafs.app.views.UsageSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Usage's Add tasks, buying through Google Play (docs/android-app.md): once KIFF's usage names the Play products, Solo with its
 * terms and each pack show at Play's prices; with none named, nothing is offered. Run with `-e hold 8` for a screenshot.
 */
class PlayOffersTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val bought = mutableListOf<String>()

  private fun usage(google: Boolean) = KiffClient.json.decodeFromString<Usage>(
    """{"plan":"trial","period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"},"can_add_tasks":true,"billing":{"source":""},
    "store":{"app_account_token":"acct_1","solo_product":"bot.tafs.solo"${if (google) ""","google_solo_product":"solo_monthly"""" else ""},
      "packs":[{"id":"rayo","name":"Rayo","line":"Rayo · 70 tasks","tasks":70,"months":12,"apple_product":"bot.tafs.rayo"${if (google) ""","google_product":"pack_rayo"""" else ""}}]}}""",
  )

  private val products = mapOf(
    "solo_monthly" to StoreProduct("solo_monthly", "Solo", "€4.99", period = "month", freeTrial = "1 week"),
    "pack_rayo" to StoreProduct("pack_rayo", "Rayo", "€9.99"),
  )

  private fun show(google: Boolean) = rule.setShown {
    TafsTheme {
      UsageSheet(
        AppState(mode = Mode.kiff, ready = true, playBilling = true, usage = usage(google), storeProducts = products),
        AccountActions(halt = {}, refreshHalt = {}, resumeHalt = { false }, refreshUsage = {}, refreshTafUsage = {}, buy = { bought += it }),
        onClose = {},
      )
    }
  }

  @Test fun soloAndThePacksShowAtPlaysPricesOnceKiffNamesThem() {
    show(google = true)
    rule.onNodeWithText("Start Solo").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(UsageWords.soloTerms("€4.99", "month", "1 week")).assertIsDisplayed()
    rule.onNodeWithText("Rayo · 70 tasks").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText("€9.99").assertIsDisplayed().performClick()
    rule.onNodeWithText("Restore purchases").performScrollTo().assertIsDisplayed()
    rule.waitUntil(5_000) { bought.isNotEmpty() }
    assertEquals(listOf("pack_rayo"), bought)
  }

  @Test fun nothingIsOfferedUntilKiffNamesPlaysProducts() {
    show(google = false)
    rule.waitForIdle()
    assertEquals(0, rule.onAllNodesWithTextCount("Start Solo") + rule.onAllNodesWithTextCount("Restore purchases"))
  }

  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
