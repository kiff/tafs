package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.AIUse
import bot.tafs.app.model.ApprovalReadiness
import bot.tafs.app.model.ApprovalWords
import bot.tafs.app.views.AIUsePanel
import bot.tafs.app.views.AccountActions
import bot.tafs.app.views.ProvideApprovalReadiness
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Settings › AI use: Go on for the billing admin while the tafs wait; tafs cloud's words for everyone else. */
class GoOnPanelTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private var wentOn = 0

  private var read = 0

  private fun show(ai: AIUse, readiness: ApprovalReadiness = ApprovalReadiness.Ready, stale: Boolean = false) = rule.setShown {
    TafsTheme {
      ProvideApprovalReadiness(Mode.kiff, readiness, recheck = {}) {
        AIUsePanel(
          ai, AppState(mode = Mode.kiff, ready = true, aiUse = ai),
          AccountActions(halt = {}, refreshHalt = {}, resumeHalt = { false }, refreshUsage = {}, refreshTafUsage = {}, goOnAI = { wentOn += 1; true }),
        )
      }
    }
  }

  private fun count(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

  @Test fun theBillingAdminGoesOnWithOneTap() {
    show(AIUse(100, 100, paused = true, canGoOn = true, words = "Your tafs wait for you. Go on lets them carry on."))
    rule.onNodeWithText("Your tafs wait for you. Go on lets them carry on.").assertIsDisplayed()
    rule.onNodeWithText("Go on with", substring = true).assertIsDisplayed().performClick()
    rule.waitForIdle()
    assertEquals(1, wentOn)
  }

  @Test fun anyoneElseReadsWhoCanAndNoButton() {
    val words = "An admin of this account can let them go on from Settings › AI use. Otherwise it starts again on 1 Nov."
    show(AIUse(100, 100, paused = true, canGoOn = false, words = words))
    rule.onNodeWithText(words).assertIsDisplayed()
    assertEquals(0, count("Go on with"))
  }

  @Test fun notWaitingNoButton() {
    show(AIUse(40, 100, paused = false, canGoOn = true, words = "Your tafs used 40% of this period's AI."))
    assertEquals(0, count("Go on with"))
  }

  @Test fun aPhoneThatCantApproveSaysWhyInGoOnsPlace() {
    show(AIUse(100, 100, paused = true, canGoOn = true, words = "Your tafs wait for you."), ApprovalReadiness.NoneEnrolled)
    assertEquals(0, count("Go on with"))
    rule.onNodeWithText(ApprovalWords.cantApprove(ApprovalReadiness.NoneEnrolled)).assertIsDisplayed()
  }

  @Test fun afterAGoOnWhoseReadFailedOnlyReadingAgainIsOffered() {
    show(AIUse(100, 100, paused = true, canGoOn = true, words = "Your tafs wait for you."), stale = true)
    rule.onNodeWithText(bot.tafs.app.model.UsageWords.WENT_ON_UNREAD).assertIsDisplayed()
    assertEquals("never a second Go on against the use from before", 0, count("Go on with"))
    rule.onNodeWithText("Read it again").performClick()
    rule.waitForIdle()
    assertEquals(1, read)
    assertEquals(0, wentOn)
  }
}
