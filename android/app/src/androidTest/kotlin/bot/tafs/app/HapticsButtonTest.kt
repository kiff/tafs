package bot.tafs.app

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.design.Haptics
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.TafsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A plain button press gives one tap, from the shared buttons themselves, and none with Haptics off (docs/android-app.md). */
class HapticsButtonTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val felt = mutableListOf<Haptics.Kind>()
  private var pressed = 0

  @After fun reset() {
    Haptics.performer = null
    Haptics.setEnabledForTest(true)
  }

  private fun show() {
    Haptics.performer = { felt += it }
    rule.setShown { TafsTheme { androidx.compose.foundation.layout.Column { PrimaryButton("Send", { pressed++ }); OutlineButton("Not now", { pressed++ }) } } }
  }

  @Test fun aButtonPressGivesOneTap() {
    Haptics.setEnabledForTest(true)
    show()
    rule.onNodeWithText("Send").performClick()
    rule.onNodeWithText("Not now").performClick()
    rule.waitForIdle()
    assertEquals(2, pressed)
    assertEquals(listOf(Haptics.Kind.Tap, Haptics.Kind.Tap), felt)
  }

  @Test fun withHapticsOffAPressStillWorksAndTapsNothing() {
    Haptics.setEnabledForTest(false)
    show()
    rule.onNodeWithText("Send").performClick()
    rule.waitForIdle()
    assertEquals(1, pressed)
    assertEquals(emptyList<Haptics.Kind>(), felt)
  }
}
