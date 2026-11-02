package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import bot.tafs.app.app.Mode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.ApprovalReadiness
import bot.tafs.app.model.ApprovalWords
import bot.tafs.app.views.ApprovalGate
import bot.tafs.app.views.ApprovalsSetupScreen
import bot.tafs.app.views.LocalApprovalReadiness
import bot.tafs.app.views.LocalRecheckApprovals
import bot.tafs.app.views.ProvideApprovalReadiness
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Set up approvals on this device: the screen in each state, and an approval's button in its place only when the phone can approve. */
class ApprovalsSetupTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private var continued = 0
  private var rechecked = 0

  private fun setup(r: ApprovalReadiness, firstTaf: Boolean = false, canContinue: Boolean = true) = rule.setShown {
    TafsTheme {
      ApprovalsSetupScreen(r, firstTaf, onCheckAgain = { rechecked += 1 }, onContinue = if (canContinue) ({ continued += 1 }) else null)
    }
  }

  private fun count(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

  @Test fun noFingerprintOffersSettingsAndSigningInWithout() {
    setup(ApprovalReadiness.NoneEnrolled)
    rule.onNodeWithText(ApprovalWords.TITLE).assertIsDisplayed()
    rule.onNodeWithText(ApprovalWords.state(ApprovalReadiness.NoneEnrolled)).assertIsDisplayed()
    rule.onNodeWithText(ApprovalWords.OPEN_SETTINGS).assertIsDisplayed()
    rule.onNodeWithText(ApprovalWords.NEW_FINGERPRINT).assertIsDisplayed()
    rule.onNodeWithText(ApprovalWords.WITHOUT_ALLOWS).assertIsDisplayed()
    rule.onNodeWithText(ApprovalWords.CONTINUE_WITHOUT).performClick()
    rule.waitForIdle()
    assertEquals(1, continued)
  }

  @Test fun aBusySensorSaysTryAgainWithNoSettings() {
    setup(ApprovalReadiness.TemporarilyUnavailable)
    rule.onNodeWithText(ApprovalWords.state(ApprovalReadiness.TemporarilyUnavailable)).assertIsDisplayed()
    assertEquals(0, count(ApprovalWords.OPEN_SETTINGS))
    rule.onNodeWithText(ApprovalWords.CHECK_AGAIN).performClick()
    rule.waitForIdle()
    assertEquals(1, rechecked)
  }

  @Test fun aPhoneThatCantApproveSaysAnotherDevice() {
    setup(ApprovalReadiness.Unsupported)
    rule.onNodeWithText(ApprovalWords.state(ApprovalReadiness.Unsupported)).assertIsDisplayed()
    assertEquals(0, count(ApprovalWords.OPEN_SETTINGS))
    assertEquals(0, count(ApprovalWords.CHECK_AGAIN))
  }

  @Test fun beforeTheFirstTafItSaysCoachNeedsApprovals() {
    setup(ApprovalReadiness.NoneEnrolled, firstTaf = true, canContinue = false)
    rule.onNodeWithText(ApprovalWords.FIRST_TAF).assertIsDisplayed()
    assertEquals(0, count(ApprovalWords.CONTINUE_WITHOUT))
  }

  private fun gate(r: ApprovalReadiness) = rule.setShown {
    TafsTheme {
      CompositionLocalProvider(LocalApprovalReadiness provides r, LocalRecheckApprovals provides { rechecked += 1 }) {
        ApprovalGate { PrimaryButton("Approve once with your fingerprint", {}) }
      }
    }
  }

  private fun provided(mode: Mode, device: ApprovalReadiness) = rule.setShown {
    TafsTheme {
      ProvideApprovalReadiness(mode, device, recheck = {}) {
        ApprovalGate { PrimaryButton("Approve once with your fingerprint", {}) }
      }
    }
  }

  @Test fun inTheSampleTheButtonStaysOnAPhoneWithNoFingerprint() {
    provided(Mode.sample, ApprovalReadiness.NoneEnrolled)
    rule.onNodeWithText("Approve once with your fingerprint").assertIsDisplayed()
    assertEquals(0, count(ApprovalWords.cantApprove(ApprovalReadiness.NoneEnrolled)))
  }

  @Test fun inTheSampleTheButtonStaysOnAPhoneThatCantApprove() {
    provided(Mode.sample, ApprovalReadiness.Unsupported)
    rule.onNodeWithText("Approve once with your fingerprint").assertIsDisplayed()
  }

  @Test fun withKiffThePhonesOwnAnswerGates() {
    provided(Mode.kiff, ApprovalReadiness.NoneEnrolled)
    assertEquals(0, count("Approve once with your fingerprint"))
    rule.onNodeWithText(ApprovalWords.cantApprove(ApprovalReadiness.NoneEnrolled)).assertIsDisplayed()
  }

  @Test fun anApprovalsButtonShowsWhenThePhoneCanApprove() {
    gate(ApprovalReadiness.Ready)
    rule.onNodeWithText("Approve once with your fingerprint").assertIsDisplayed()
  }

  @Test fun inItsPlaceTheStateAndWhatToDo() {
    gate(ApprovalReadiness.NoneEnrolled)
    assertEquals(0, count("Approve once with your fingerprint"))
    rule.onNodeWithText(ApprovalWords.cantApprove(ApprovalReadiness.NoneEnrolled)).assertIsDisplayed()
    rule.onNodeWithText(ApprovalWords.OPEN_SETTINGS).assertIsDisplayed()
    rule.onNodeWithText(ApprovalWords.CHECK_AGAIN).performClick()
    rule.waitForIdle()
    assertEquals(1, rechecked)
  }
}
