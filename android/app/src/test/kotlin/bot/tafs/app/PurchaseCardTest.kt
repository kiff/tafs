package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.CardExceptions
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.PurchaseApproval
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.views.HeldCallCard
import bot.tafs.app.views.LocalApprovePurchase
import java.io.File
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A held purchase's card (docs/purchases.md), drawn from the contract's own approval (contracts/purchase.json): KIFF's words, the
 * items closed behind Show more each time, Approve once with the case it drew, and Refuse; no Extra room, next step or third
 * answer. A purchase whose words can't be read says so and offers Refuse only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h900dp-port")
class PurchaseCardTest {
  @get:Rule val compose = createComposeRule()

  private val approval = KiffClient.json.parseToJsonElement(File("../../contracts/purchase.json").readText()).jsonObject
    .getValue("approval").jsonObject.getValue("value").toString()

  private fun held(purchase: String = approval): HeldCall = KiffMapping.held(
    KiffClient.json.decodeFromString<CardExceptions>(
      """{"exceptions":[{"id":"exc_7f3a","subject":"a-1","action":"issue_card","title":"Buy","entity_id":"","accounting_version":1,
        "next_steps":["return_first"],"breaches":[{"outcome":"per_action_exceeded"}],"reason_text":"Over the limit",
        "created_at":"2026-11-28T09:15:09Z","expires_at":"2099-01-01T00:00:00Z","purchase":$purchase}]}""",
    ).exceptions.single(),
  )

  private val state = AppState(
    mode = Mode.kiff, sessionHasKey = true,
    kiffAccount = Account("t_acme", "acme", capabilities = listOf("holds.answer", "cards.manage")),
  )

  private fun draw(call: HeldCall, approved: MutableList<PurchaseApproval> = mutableListOf()) {
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    compose.setContent {
      TafsTheme {
        CompositionLocalProvider(
          LocalApprovePurchase provides { _, shown -> approved += shown; true },
        ) {
          HeldCallCard(call, state.copy(held = listOf(call))) { _, _ -> }
        }
      }
    }
  }

  @Test fun itDrawsKiffsWordsWithTheItemsClosedUntilShowMore() {
    val call = held()
    draw(call)
    val w = call.purchase!!.words
    for (line in listOf(w.title, w.total, w.delivery, w.priceRule!!, w.grant!!, w.buysNow!!, w.nothingUntil!!)) {
      compose.onNodeWithText(line).assertExists()
    }
    // The Card's own reason, as KIFF words it for a purchase, not the hold's generic one.
    compose.onNodeWithText(w.reason!!).assertExists()
    compose.onAllNodesWithText("Over the limit").assertCountEquals(0)
    compose.onAllNodesWithTag("purchase-lines").assertCountEquals(0)
    compose.onNodeWithTag("purchase-show-more").performClick()
    compose.onNodeWithText("A4 paper, 80 g, 5 reams").assertExists()
    compose.onNodeWithText("€9.50").assertExists()
  }

  @Test fun approveOnceSendsTheCaseDrawnAndNothingElseIsOffered() {
    val call = held()
    val approved = mutableListOf<PurchaseApproval>()
    draw(call, approved)
    compose.onAllNodesWithText(Words.NeedsYou.refuse).assertCountEquals(1)
    // No next step, no third answer.
    compose.onAllNodesWithText("return_first").assertCountEquals(0)
    compose.onAllNodesWithText(Words.NeedsYou.allowFromNowOn).assertCountEquals(0)
    compose.onNodeWithText(Words.NeedsYou.approveOnceWith(Words.Approval.Biometry.screenLock), substring = true, useUnmergedTree = true)
      .performClick()
    compose.waitForIdle()
    assertEquals(listOf(call.purchase), approved)
  }

  @Test fun unreadableWordsSayItAndOfferRefuseOnly() {
    val call = held(approval.replace("\"total\":\"€84.20\",", ""))
    draw(call)
    compose.onNodeWithText(Words.Purchase.unreadable).assertExists()
    compose.onAllNodesWithText(Words.NeedsYou.refuse).assertCountEquals(1)
    assertTrue(compose.onAllNodesWithText(Words.NeedsYou.approveOnceWith(Words.Approval.Biometry.screenLock), substring = true).fetchSemanticsNodes().isEmpty())
  }
}
