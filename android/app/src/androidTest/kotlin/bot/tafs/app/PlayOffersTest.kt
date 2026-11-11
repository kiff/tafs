package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.billing.StoreProduct
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Balance
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.Usage
import bot.tafs.app.model.UsageWords
import bot.tafs.app.views.AccountActions
import bot.tafs.app.views.UsageSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Plan & usage's Add credits, buying through Google Play (docs/android-app.md): where the balance offers them (`buy`) and KIFF's
 * usage names the Play products, Solo with its terms and each pack of credits show at Play's prices; with none named, nothing is
 * offered. Run with `-e hold 8` for a screenshot.
 */
class PlayOffersTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val bought = mutableListOf<String>()

  private fun usage(google: Boolean) = KiffClient.json.decodeFromString<Usage>(
    """{"plan":"solo","period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"},"billing":{"source":""},
    "store":{"app_account_token":"acct_1","solo_product":"bot.tafs.solo"${if (google) ""","google_solo_product":"solo_monthly"""" else ""},
      "packs":[{"id":"rayo","name":"Rayo","line":"Pack · 500 credits","credits":500,"apple_product":"bot.tafs.rayo"${if (google) ""","google_product":"pack_rayo"""" else ""}},
      {"id":"credits-firstmonth","name":"First month pack","line":"First month pack · 7,000 credits","credits":7000,"once":true${if (google) ""","google_product":"bot.tafs.credits.firstmonth"""" else ""}}]}}""",
  )

  private val coachIncluded = "Coach is included: enough to set up about 4 new jobs a month."

  private fun balance(solo: Boolean, packs: Boolean, firstMonth: String = "null", creditsPanel: Boolean = true) = KiffClient.json.decodeFromString<Balance>(
    """{"balance":{"state":"ok","words":"Solo · 260 of 500 credits left this period","buy":{"solo":$solo,"packs":$packs,"first_month":$firstMonth,"coach":"$coachIncluded"},"credits_panel":$creditsPanel},"plan_line":"Solo · 260 credits left"}""",
  )

  /** This period's credits, as tafs cloud words them for someone who ran nothing yet. */
  private val noCredits = bot.tafs.app.kiff.Credits(
    bot.tafs.app.kiff.CreditsPeriod(java.time.Instant.parse("2026-11-01T00:00:00Z"), java.time.Instant.parse("2026-12-01T00:00:00Z")),
    words = "This period: no credits used yet · ends on 30 Nov",
  )

  private val products = mapOf(
    "solo_monthly" to StoreProduct("solo_monthly", "Solo", "€4.99", period = "month", freeTrial = "1 week"),
    "pack_rayo" to StoreProduct("pack_rayo", "Rayo", "€9.99"),
    "bot.tafs.credits.firstmonth" to StoreProduct("bot.tafs.credits.firstmonth", "First month pack", "€99.99"),
  )

  private fun show(google: Boolean, solo: Boolean = true, packs: Boolean = true, firstMonth: String = "null", note: bot.tafs.app.app.PurchaseNote? = null, restore: Boolean = false, creditsPanel: Boolean = true) = rule.setShown {
    TafsTheme {
      UsageSheet(
        AppState(mode = Mode.kiff, ready = true, playBilling = true, usage = usage(google), balance = balance(solo, packs, firstMonth, creditsPanel), storeProducts = products, purchaseNote = note, restoreShown = restore, credits = noCredits),
        AccountActions(halt = {}, refreshHalt = {}, resumeHalt = { false }, refreshUsage = {}, buy = { bought += it }, restorePurchases = { bought += "restore" }),
        onClose = {},
      )
    }
  }

  @Test fun soloAndThePacksShowAtPlaysPricesOnceKiffNamesThem() {
    show(google = true)
    rule.onNodeWithText("Start Solo").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(UsageWords.soloTerms("€4.99", "month", "1 week")).assertIsDisplayed()
    rule.onNodeWithText("Pack · 500 credits").performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(UsageWords.PACKS_KEEP).assertIsDisplayed()
    rule.onNodeWithText("€9.99").assertIsDisplayed().performClick()
    rule.waitUntil(5_000) { bought.isNotEmpty() }
    assertEquals(listOf("pack_rayo"), bought)
    // Solo offered: the panel is about choosing a plan. Nothing bought yet: no Restore.
    rule.onNodeWithText("CHOOSE A PLAN").assertExists()
    assertEquals(0, rule.onAllNodesWithTextCount("ADD CREDITS"))
    assertEquals(0, rule.onAllNodesWithTextCount("Restore purchases"))
  }

  @Test fun whatCoachIncludesShowsUnderTheSoloOffer() {
    show(google = true, solo = true, packs = false)
    val terms = rule.onNodeWithText(UsageWords.soloTerms("€4.99", "month", "1 week")).performScrollTo().fetchSemanticsNode().boundsInRoot
    val coach = rule.onNodeWithText(coachIncluded).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    assertEquals(true, coach.top > terms.top)
  }

  @Test fun withNoSoloOfferThereIsNoLineAboutCoach() {
    show(google = true, solo = false, packs = true)
    assertEquals(0, rule.onAllNodesWithTextCount(coachIncluded))
  }

  @Test fun onceSoloRunsThePanelAddsCredits() {
    show(google = true, solo = false, packs = true)
    rule.onNodeWithText("ADD CREDITS").assertExists()
    assertEquals(0, rule.onAllNodesWithTextCount("CHOOSE A PLAN"))
  }

  @Test fun theCreditsPanelShowsUnlessTafsCloudSaysNot() {
    show(google = true, creditsPanel = true)
    rule.onNodeWithText(noCredits.words).performScrollTo().assertIsDisplayed()
  }

  @Test fun aNewAccountWithNoPlanAndNoRunGetsNoCreditsPanel() {
    show(google = true, creditsPanel = false)
    rule.onNodeWithText("Start Solo").performScrollTo().assertIsDisplayed()
    assertEquals(0, rule.onAllNodesWithTextCount(noCredits.words))
  }

  @Test fun restoreShowsWhileSomethingIsLeftToSendAgain() {
    show(google = true, restore = true)
    rule.onNodeWithText("Restore purchases").performScrollTo().assertIsDisplayed().performClick()
    rule.waitUntil(5_000) { bought.isNotEmpty() }
    assertEquals(listOf("restore"), bought)
  }

  @Test fun aPurchasesOutcomeShowsUnderWhatWasTappedWithRestoreThere() {
    val note = bot.tafs.app.app.PurchaseNote("pack_rayo", "Bought. KIFF hasn't added it yet: tafs tries again when it opens.", offersRestore = true)
    show(google = true, note = note, restore = true)
    // Right under the pack's row, above the panel's own Restore purchases.
    val line = rule.onNodeWithText(note.words).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val pack = rule.onNodeWithText("Pack · 500 credits").fetchSemanticsNode().boundsInRoot
    val restores = rule.onAllNodes(androidx.compose.ui.test.hasText("Restore purchases")).fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.top }
    assertEquals(2, restores.size)
    assertEquals(true, line.top > pack.top && line.top < restores.last().top)
    // The Restore offered with it is the one just under it.
    rule.onAllNodes(androidx.compose.ui.test.hasText("Restore purchases"))[0].performClick()
    rule.waitUntil(5_000) { bought.isNotEmpty() }
    assertEquals(listOf("restore"), bought)
  }

  @Test fun onlyWhatTheBalanceOffersIsShown() {
    show(google = true, solo = true, packs = false)
    rule.onNodeWithText("Start Solo").performScrollTo().assertIsDisplayed()
    assertEquals("no pack offered", 0, rule.onAllNodesWithTextCount("Pack · 500 credits"))
  }

  @Test fun aFirstMonthPackThatCantBeBoughtYetIsNeverShownGreyed() {
    show(google = true, solo = true, packs = false, firstMonth = """{"ready":false,"words":"Add it once Solo starts."}""")
    rule.onNodeWithText("Start Solo").performScrollTo().assertIsDisplayed()
    assertEquals(0, rule.onAllNodesWithTextCount("First month pack · 7,000 credits"))
    assertEquals(0, rule.onAllNodesWithTextCount("Add it once Solo starts."))
  }

  @Test fun onceSoloRunsTheFirstMonthPackIsBoughtAtPlaysPrice() {
    show(google = true, solo = false, packs = true, firstMonth = """{"ready":true,"words":null}""")
    val first = rule.onNodeWithText("First month pack · 7,000 credits").performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    // First among the packs.
    assertEquals(true, first.top < rule.onNodeWithText("Pack · 500 credits").fetchSemanticsNode().boundsInRoot.top)
    rule.onNodeWithText("€99.99").assertIsDisplayed().performClick()
    rule.waitUntil(5_000) { bought.isNotEmpty() }
    assertEquals(listOf("bot.tafs.credits.firstmonth"), bought)
    assertEquals("shown once", 1, rule.onAllNodesWithTextCount("First month pack · 7,000 credits"))
  }

  @Test fun theFirstMonthPackIsHiddenWhenTafsCloudDoesntOfferIt() {
    show(google = true, solo = false, packs = true)
    rule.onNodeWithText("Pack · 500 credits").performScrollTo().assertIsDisplayed()
    assertEquals(0, rule.onAllNodesWithTextCount("First month pack · 7,000 credits"))
  }

  @Test fun nothingIsOfferedUntilKiffNamesPlaysProducts() {
    show(google = false)
    rule.waitForIdle()
    assertEquals(0, rule.onAllNodesWithTextCount("Start Solo") + rule.onAllNodesWithTextCount("Restore purchases"))
  }

  @Test fun nothingIsOfferedWhereTheBalanceOffersNothing() {
    show(google = true, solo = false, packs = false)
    rule.waitForIdle()
    assertEquals(0, rule.onAllNodesWithTextCount("Start Solo") + rule.onAllNodesWithTextCount("Restore purchases"))
  }

  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
