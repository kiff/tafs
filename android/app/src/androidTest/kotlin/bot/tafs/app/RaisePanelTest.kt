package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Balance
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.ApprovalReadiness
import bot.tafs.app.model.ApprovalWords
import bot.tafs.app.model.UsageWords
import bot.tafs.app.views.AccountActions
import bot.tafs.app.views.BalancePanel
import bot.tafs.app.views.ProvideApprovalReadiness
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Plan & usage's balance: Raise the limit for who manages billing at the limit; tafs cloud's words for everyone else. */
class RaisePanelTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private var raised = 0

  private var read = 0

  private val raiseWords = "Raise the spend limit by one step"

  private fun balance(raise: Boolean, warning: String) = KiffClient.json.decodeFromString<Balance>(
    """{"balance":{"state":"waiting","why":"limit_reached","meter_pct":100,"words":"Company · 4 seats · the spend limit is used",
       "warning":"$warning","raise":${if (raise) """{"words":"$raiseWords","from_cents":8000}""" else "null"}},
       "plan_line":"Company · waiting: the spend limit is reached","plan_tone":"waiting"}""",
  )

  private fun show(b: Balance, readiness: ApprovalReadiness = ApprovalReadiness.Ready, stale: Boolean = false) = rule.setShown {
    TafsTheme {
      ProvideApprovalReadiness(Mode.kiff, readiness, recheck = {}) {
        BalancePanel(
          AppState(mode = Mode.kiff, ready = true, balance = b, balanceStale = stale),
          AccountActions(halt = {}, refreshHalt = {}, resumeHalt = { false }, refreshUsage = {}, raiseLimit = { raised += 1; true }, readBalance = { read += 1 }),
        )
      }
    }
  }

  private fun count(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

  @Test fun whoManagesBillingRaisesWithOneTap() {
    show(balance(raise = true, warning = "Your tafs are waiting: the spend limit is reached. Raise it to let them go on."))
    rule.onNodeWithText("Company · 4 seats · the spend limit is used").assertIsDisplayed()
    rule.onNodeWithText("Your tafs are waiting: the spend limit is reached. Raise it to let them go on.").assertIsDisplayed()
    rule.onNodeWithText(raiseWords).assertIsDisplayed().performClick()
    rule.waitForIdle()
    assertEquals(1, raised)
  }

  @Test fun anyoneElseReadsWhoCanAndNoButton() {
    val words = "Your tafs are waiting: the spend limit is reached. An admin of this account can raise it."
    show(balance(raise = false, warning = words))
    rule.onNodeWithText(words).assertIsDisplayed()
    assertEquals(0, count(raiseWords))
  }

  @Test fun aPhoneThatCantApproveSaysWhyInTheRaisesPlace() {
    show(balance(raise = true, warning = "Your tafs are waiting."), ApprovalReadiness.NoneEnrolled)
    assertEquals(0, count(raiseWords))
    rule.onNodeWithText(ApprovalWords.cantApprove(ApprovalReadiness.NoneEnrolled)).assertIsDisplayed()
  }

  @Test fun afterARaiseWhoseReadFailedOnlyReadingAgainIsOffered() {
    show(balance(raise = true, warning = "Your tafs are waiting."), stale = true)
    rule.onNodeWithText(UsageWords.RAISED_UNREAD).assertIsDisplayed()
    assertEquals("never a second raise against the limit from before", 0, count(raiseWords))
    rule.onNodeWithText("Read it again").performClick()
    rule.waitForIdle()
    assertEquals(1, read)
    assertEquals(0, raised)
  }
}
