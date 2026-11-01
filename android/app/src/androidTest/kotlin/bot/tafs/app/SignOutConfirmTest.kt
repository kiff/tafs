package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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

/** Every way out of KIFF in Settings asks first: one tap never signs the phone out, Cancel leaves it, and confirming does. */
class SignOutConfirmTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

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

  @Test fun signOutOfKiffAsksFirstAndCancelLeavesItSignedIn() {
    show()
    rule.onNodeWithText("Sign out of KIFF").performScrollTo().performClick()
    rule.onNodeWithText(SignOut.SignOut.title).assertIsDisplayed()
    assertEquals("one tap doesn't sign out", emptyList<String>(), done)
    rule.onNodeWithText("Cancel").performClick()
    rule.waitForIdle()
    assertEquals(emptyList<String>(), done)
    rule.onNodeWithText("Sign out of KIFF").performScrollTo().performClick()
    rule.onNodeWithText(SignOut.SignOut.confirm).performClick()
    rule.waitUntil(5_000) { done.isNotEmpty() }
    assertEquals(listOf("sign out", "close"), done)
  }

  @Test fun switchAccountAsksFirst() {
    show()
    rule.onNodeWithText("Switch account…").performScrollTo().performClick()
    rule.onNodeWithText(SignOut.SwitchAccount.title).assertIsDisplayed()
    assertEquals(emptyList<String>(), done)
    rule.onNodeWithText(SignOut.SwitchAccount.confirm).performClick()
    rule.waitUntil(5_000) { done.size == 2 }
    assertEquals(listOf("sign out", "sign in"), done)
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
