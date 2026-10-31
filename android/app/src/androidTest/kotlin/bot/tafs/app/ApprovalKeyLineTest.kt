package bot.tafs.app

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import bot.tafs.app.app.Relink
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.signing.ApprovalKeyRenewed
import bot.tafs.app.views.APPROVE_AGAIN
import bot.tafs.app.views.ApprovalKeyLine
import bot.tafs.app.views.ApproveAgain
import bot.tafs.app.views.LocalApproveAgain
import bot.tafs.app.views.LocalSignInAgain
import bot.tafs.app.views.WAITING_FOR_BROWSER
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The line where this phone can't approve: Approve again in your browser, its code while it waits, and Sign in again where KIFF
 * can't re-link it. Run with `-e hold 8` to keep each for a screenshot.
 */
class ApprovalKeyLineTest {
  // Awake and unlocked before the activity launches: a just-booted emulator may not be yet.
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val taps = mutableListOf<String>()

  private fun show(again: ApproveAgain?) = rule.setContent {
    TafsTheme {
      CompositionLocalProvider(LocalApproveAgain provides again, LocalSignInAgain provides { taps += "sign in again" }) {
        ApprovalKeyLine(Modifier.padding(16.dp))
      }
    }
  }

  private fun again(relink: Relink? = null, problem: String? = null) =
    ApproveAgain(relink, problem, start = { taps += "start" }, cancel = { taps += "cancel" }, nudge = {})

  private fun hold() {
    val seconds = InstrumentationRegistry.getArguments().getString("hold")?.toLongOrNull() ?: return
    rule.waitForIdle()
    Thread.sleep(seconds * 1000)
  }

  @Test fun aPhoneThatCantApproveOffersApproveAgainAndSignInAgainInstead() {
    show(again())
    rule.onNodeWithText(ApprovalKeyRenewed.APPROVE_AGAIN_WORDS).assertIsDisplayed()
    rule.onNodeWithText("Sign in again instead").assertIsDisplayed()
    hold()
    rule.onNodeWithText(APPROVE_AGAIN).performClick()
    assertEquals(listOf("start"), taps)
  }

  @Test fun whileTheBrowserIsAwaitedTheCodeShowsWithCancel() {
    show(again(Relink("WDJB-MJHT", "https://app.kiff.dev/auth/device?code=WDJB-MJHT")))
    rule.onNodeWithText("WDJB-MJHT").assertIsDisplayed()
    rule.onNodeWithText(WAITING_FOR_BROWSER).assertIsDisplayed()
    hold()
    rule.onNodeWithText("Cancel").performClick()
    assertEquals(listOf("cancel"), taps)
  }

  @Test fun whereKiffCantReLinkItsSignInAgainAsBefore() {
    show(null)
    rule.onNodeWithText(ApprovalKeyRenewed.WORDS).assertIsDisplayed()
    rule.onNodeWithText("Sign in again").performClick()
    assertEquals(listOf("sign in again"), taps)
  }
}
