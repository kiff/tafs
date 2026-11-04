package bot.tafs.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.views.SettingsSheet
import bot.tafs.app.views.SignOut
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** One account per phone, and every way out of KIFF in Account asks first: one tap never signs the phone out, Cancel leaves it, and confirming does. */
class SignOutConfirmTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val done = mutableListOf<String>()

  private fun show(keyLost: Boolean = false) = rule.setShown {
    TafsTheme {
      SettingsSheet(
        AppState(mode = Mode.kiff, ready = true, approvalKeyLost = keyLost),
        onSignIn = { done += "sign in" },
        onSignOut = { done += "sign out"; true },
        onSignInAgain = { done += "sign in again"; true },
        onUsage = {}, onTurnOnAlerts = {}, onAlertSound = {}, onDevices = {}, onPeople = {}, onUseDeviceZone = {}, onDeleteAccount = {},
        onClose = { done += "close" },
      )
    }
  }

  // The row and the dialog's button share the words: the dialog's window is the newest, so its button is the last.
  private fun inDialog(text: String) = rule.onAllNodesWithText(text).onLast()

  @Test fun signOutAsksFirstAndCancelLeavesItSignedIn() {
    show()
    rule.onNodeWithText("Sign out").performScrollTo().performClick()
    rule.onNodeWithText(SignOut.SignOut.title).assertIsDisplayed()
    assertEquals("one tap doesn't sign out", emptyList<String>(), done)
    rule.onNodeWithText("Cancel").performClick()
    rule.waitForIdle()
    assertEquals(emptyList<String>(), done)
    rule.onNodeWithText("Sign out").performScrollTo().performClick()
    inDialog(SignOut.SignOut.confirm).performClick()
    rule.waitUntil(5_000) { done.isNotEmpty() }
    assertEquals(listOf("sign out", "close"), done)
  }

  @Test fun onePhoneHasOneAccountSoThereIsNoSwitchAccount() {
    show()
    rule.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed()
    rule.onAllNodesWithText("Switch account…").assertCountEquals(0)
  }

  @Test fun signInAgainAsksFirst() {
    show(keyLost = true)
    rule.onNodeWithText("Sign in again").performScrollTo().performClick()
    rule.onNodeWithText(SignOut.SignInAgain.title).assertIsDisplayed()
    assertEquals(emptyList<String>(), done)
    rule.onNodeWithText(SignOut.SignInAgain.confirm).performClick()
    rule.waitUntil(5_000) { done.isNotEmpty() }
    assertEquals(listOf("sign in again", "close"), done)
  }
}
