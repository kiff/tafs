package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.YourStep
import bot.tafs.app.kiff.YourStepTaf
import bot.tafs.app.kiff.YourStepWords
import bot.tafs.app.views.LocalYourStepActions
import bot.tafs.app.views.YourStepActions
import bot.tafs.app.views.YourStepPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Your step in Needs you (docs/android-app.md › Your step): every word is tafs cloud's; Open in shows only with its link and
 * words; Done and Skip it hand the answer to the model, which signs it and sends it to KIFF.
 */
class YourStepScreenTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val words = YourStepWords(title = "Your step", done = "Done", skip = "Skip it", open = "Open in Stripe", line = "Your step: Approve the payout")
  private val step = YourStep(
    handoff = "ho_1", taf = YourStepTaf("taf_1", "Payouts"), what = "Approve the payout to Acme", why = "A payout needs your own approval.",
    link = "https://dashboard.stripe.com/payouts/po_1", linkHost = "dashboard.stripe.com", words = words,
  )

  private fun show(s: YourStep, answered: MutableList<Pair<String, Boolean>>, opened: MutableList<String>, state: AppState = AppState(mode = Mode.kiff, ready = true)) =
    rule.setShown {
      TafsTheme {
        CompositionLocalProvider(LocalYourStepActions provides YourStepActions(answer = { st, done -> answered += st.handoff to done }, open = { opened += it })) {
          YourStepPanel(s, state)
        }
      }
    }

  @Test fun itShowsTafsCloudsWordsAndEachButtonDoesItsPart() {
    val answered = mutableListOf<Pair<String, Boolean>>()
    val opened = mutableListOf<String>()
    show(step, answered, opened)
    rule.onNodeWithText("Your step · Payouts", substring = true, ignoreCase = true).assertExists()
    rule.onNodeWithText("Approve the payout to Acme").assertExists()
    rule.onNodeWithText("A payout needs your own approval.").assertExists()
    rule.onNodeWithText("dashboard.stripe.com").assertExists()
    rule.onNodeWithText("Open in Stripe").performClick()
    rule.waitForIdle()
    assertEquals(listOf("https://dashboard.stripe.com/payouts/po_1"), opened)
    rule.onNodeWithText("Done").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Skip it").performClick()
    rule.waitForIdle()
    assertEquals(listOf("ho_1" to true, "ho_1" to false), answered)
  }

  @Test fun withoutALinkThereIsNoButtonButTheWordsStay() {
    show(step.copy(link = null, linkHost = null), mutableListOf(), mutableListOf())
    rule.onAllNodesWithText("Open in Stripe").assertCountEquals(0)
    rule.onNodeWithText("Approve the payout to Acme").assertExists()
  }

  @Test fun aLinkWhoseHostIsntTafsCloudsHasNoButton() {
    show(step.copy(link = "https://dashboard.stripe.com.evil.example/login"), mutableListOf(), mutableListOf())
    rule.onAllNodesWithText("Open in Stripe").assertCountEquals(0)
    rule.onNodeWithText("Approve the payout to Acme").assertExists()
  }

  @Test fun someoneWhoCantAnswerHoldsSeesTheStepWithoutDoneOrSkip() {
    show(step, mutableListOf(), mutableListOf(), AppState(mode = Mode.kiff, ready = true, canAnswerHolds = false))
    rule.onNodeWithText("Approve the payout to Acme").assertExists()
    rule.onAllNodesWithText("Done").assertCountEquals(0)
    rule.onAllNodesWithText("Skip it").assertCountEquals(0)
  }
}
