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

  @Test fun aPurchasesOutcomeShowsUnderWhatWasTappedWithRestoreThere() {
    val note = bot.tafs.app.app.PurchaseNote("pack_rayo", "Bought. KIFF hasn't added it yet: tafs tries again when it opens.", offersRestore = true)
    rule.setShown {
      TafsTheme {
        UsageSheet(
          AppState(mode = Mode.kiff, ready = true, playBilling = true, usage = usage(true), storeProducts = products, purchaseNote = note),
          AccountActions(halt = {}, refreshHalt = {}, resumeHalt = { false }, refreshUsage = {}, refreshTafUsage = {}, restorePurchases = { bought += "restore" }),
          onClose = {},
        )
      }
    }
    // Right under the pack's row, above the panel's own Restore purchases.
    val line = rule.onNodeWithText(note.words).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val pack = rule.onNodeWithText("Rayo · 70 tasks").fetchSemanticsNode().boundsInRoot
    val restores = rule.onAllNodes(androidx.compose.ui.test.hasText("Restore purchases")).fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.top }
    assertEquals(2, restores.size)
    assertEquals(true, line.top > pack.top && line.top < restores.last().top)
    // The Restore offered with it is the one just under it.
    rule.onAllNodes(androidx.compose.ui.test.hasText("Restore purchases"))[0].performClick()
    rule.waitUntil(5_000) { bought.isNotEmpty() }
    assertEquals(listOf("restore"), bought)
  }

  @Test fun beforeOrAfterATrialOnlySoloIsOfferedAndHeldPacksWaitForIt() {
    val ended = KiffClient.json.decodeFromString<Usage>(
      """{"plan":"trial","period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"},"can_add_tasks":true,"can_add_packs":false,"billing":{"source":""},
      "tasks":{"counted":0,"included":0,"left":0,"packs":0},"trial":{"ends_at":"2026-11-01T21:56:00Z","days_left":0,"tasks_left":0},
      "pack_list":[{"id":"pk_1","name":"Pico","tasks":12,"tasks_left":12,"expires_at":"2027-11-01T00:00:00Z"}],
      "store":{"app_account_token":"acct_1","google_solo_product":"solo_monthly",
        "packs":[{"id":"rayo","name":"Rayo","line":"Rayo · 70 tasks","tasks":70,"months":12,"google_product":"pack_rayo"}]}}""",
    )
    rule.setShown {
      TafsTheme {
        UsageSheet(
          AppState(mode = Mode.kiff, ready = true, playBilling = true, usage = ended, storeProducts = products),
          AccountActions(halt = {}, refreshHalt = {}, resumeHalt = { false }, refreshUsage = {}, refreshTafUsage = {}),
          onClose = {},
        )
      }
    }
    rule.onNodeWithText("Start Solo").performScrollTo().assertIsDisplayed()
    assertEquals("no pack offered", 0, rule.onAllNodesWithTextCount("Rayo · 70 tasks"))
    rule.onNodeWithText(bot.tafs.app.model.UsageWords.PACKS_WAIT_FOR_SOLO).performScrollTo().assertIsDisplayed()
    // Ended: said so, with Solo to keep the tafs running; no period line (its date would be past).
    rule.onNodeWithText("Your trial has ended.").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(bot.tafs.app.model.UsageWords.START_SOLO).assertIsDisplayed()
    assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("This period ends", substring = true)).fetchSemanticsNodes().size)
    assertEquals(0, rule.onAllNodesWithTextCount("Your trial's tasks are used. Your tafs are paused until tasks are added."))
  }

  @Test fun nothingIsOfferedUntilKiffNamesPlaysProducts() {
    show(google = false)
    rule.waitForIdle()
    assertEquals(0, rule.onAllNodesWithTextCount("Start Solo") + rule.onAllNodesWithTextCount("Restore purchases"))
  }

  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
