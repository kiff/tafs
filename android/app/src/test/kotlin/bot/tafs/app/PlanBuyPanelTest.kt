package bot.tafs.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.app.PurchaseNote
import bot.tafs.app.billing.StoreProduct
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.views.AccountActions
import bot.tafs.app.views.UsageSheet
import bot.tafs.app.views.packRowTag
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan & usage's buying, as the owner found it on the iPad (gaps 1–3): Solo's own confirmation once it runs, the whole pack row as
 * its button, and what credits buy and packs are for above the packs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w411dp-h2000dp")
class PlanBuyPanelTest {
  @get:Rule val compose = createComposeRule()

  @Before fun words() = Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))

  private val usage = KiffClient.json.decodeFromString(
    bot.tafs.app.kiff.Usage.serializer(),
    """{"plan":"solo","period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"},"can_add_tasks":true,"billing":{"source":"google"},
      "store":{"app_account_token":"acct_1","google_solo_product":"solo_monthly",
        "packs":[{"id":"credits-500","name":"500 credits","line":"500 credits","google_product":"pack_500"}]}}""",
  )

  /** On Solo: KIFF offers packs and no longer Solo. */
  private val onSolo = KiffClient.json.decodeFromString(
    bot.tafs.app.kiff.Balance.serializer(),
    """{"balance":{"state":"ok","words":"Solo · 500 credits left","buy":{"solo":false,"packs":true}},"plan_line":"Solo"}""",
  )

  private fun show(note: PurchaseNote? = null, bought: MutableList<String> = mutableListOf()) {
    val state = AppState(
      mode = Mode.kiff, playBilling = true, usage = usage, balance = onSolo, purchaseNote = note,
      storeProducts = mapOf("pack_500" to StoreProduct("pack_500", "500 credits", "€4.99")), storeLoaded = true,
    )
    val actions = AccountActions(halt = {}, refreshHalt = {}, resumeHalt = { false }, refreshUsage = {}, buy = { bought += it })
    compose.setContent { TafsTheme { UsageSheet(state, actions) {} } }
    compose.waitForIdle()
  }

  @Test fun theWholePackRowBuysIt() {
    val bought = mutableListOf<String>()
    show(bought = bought)
    compose.onNodeWithTag(packRowTag("pack_500")).performClick()
    compose.waitForIdle()
    assertEquals(listOf("pack_500"), bought)
  }

  @Test fun addCreditsSaysWhatCreditsBuyAndWhatPacksAreFor() {
    show()
    compose.onNodeWithText(Words.Billing.creditsBuy).assertExists()
    compose.onNodeWithText(Words.Billing.packAddsCreditsOnceNever).assertExists()
  }

  @Test fun soloSaysItsOnOnceKiffReadsIt() {
    show(note = PurchaseNote(PurchaseNote.SOLO_ON, Words.Billing.soloOn))
    compose.onNodeWithText(Words.Billing.soloOn).assertExists()
  }
}
