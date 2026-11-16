package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.views.SettingsSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * A fast fling to the end of Account leaves the sheet where it is (Android 111: the sheet bounced on its own after a fast swipe,
 * never after a slow one). The sheet's top doesn't move while nothing touches the screen, and Sign out ends fully on screen.
 */
class SheetFlingTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private fun show() = rule.setShown {
    TafsTheme {
      SettingsSheet(
        AppState(mode = Mode.kiff, ready = true),
        onSignIn = {}, onSignOut = { true }, onUsage = {}, onTurnOnAlerts = {}, onAlertSound = {},
        onDevices = {}, onPeople = {}, onUseDeviceZone = {}, onDeleteAccount = {}, onClose = {},
      )
    }
  }

  private fun titleTop() = rule.onNodeWithText("Account").getBoundsInRoot().top

  @Test fun aFastFlingToTheEndLeavesTheSheetStill() {
    show()
    rule.waitForIdle()
    val open = titleTop()
    // Fast, as the owner's thumb: each swipe ends with speed to spare once the list runs out.
    repeat(4) { rule.onNode(hasScrollAction()).performTouchInput { swipeUp(durationMillis = 60) } }
    rule.waitForIdle()
    // Nothing touches the screen now: the sheet's top stays where it opened, each time it's looked at.
    repeat(6) {
      rule.mainClock.advanceTimeBy(250)
      rule.waitForIdle()
      assertEquals("the sheet moved with no finger on the screen", open, titleTop())
    }
    rule.onNodeWithText("Sign out").assertIsDisplayed()
  }
}
