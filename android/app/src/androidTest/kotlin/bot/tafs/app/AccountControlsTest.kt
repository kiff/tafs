package bot.tafs.app

import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.design.Theme
import bot.tafs.app.kiff.*
import bot.tafs.app.model.ThemePreference
import bot.tafs.app.views.SettingsSheet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountControlsTest {
  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createStableComposeRule()
  private val changes = mutableListOf<String>()
  @After fun resetAppearance() { rule.runOnUiThread { Theme.choose(InstrumentationRegistry.getInstrumentation().targetContext, ThemePreference.System) } }

  private fun show() {
    // Install without setContent's implicit root wait: the first window may still be attaching.
    rule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        TafsTheme {
          Box(Modifier.fillMaxSize()) {
            SettingsSheet(
              AppState(mode = Mode.kiff, ready = true, alertsAvailable = true,
                phoneAlerts = PhoneAlerts(enabled = true, kinds = listOf(AlertChoice("answered", "A taf answered someone"))),
                timeZones = listOf(TimeZoneChoice("Asia/Tokyo", "Asia / Tokyo"))),
              onSignIn = {}, onSignOut = { true }, onUsage = {}, onTurnOnAlerts = {}, onAlertSound = {},
              onDevices = {}, onPeople = {}, onUseDeviceZone = {}, onDeleteAccount = {}, onClose = {},
              onToggleAlerts = { changes += "alerts $it" }, onAlertKind = { id, on -> changes += "$id $on" },
              onPickTimeZone = { changes += it },
            )
          }
        }
      }
    }
    // Observe readiness without requiring a root before Android has drawn its first frame.
    rule.waitUntil(30_000) {
      rule.onAllNodes(hasText("Account")).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
  }
  @Test fun alertSwitchesCallTheirOwnActions() {
    show()
    rule.onNodeWithContentDescription("Alerts on this phone").performScrollTo().assertIsOn().performClick()
    rule.onNodeWithContentDescription("A taf answered someone").performScrollTo().assertIsOn().performClick()
    rule.runOnIdle { assertEquals(listOf("alerts false", "answered false"), changes) }
  }
  @Test fun appearanceIsKeptAndTheZonePickerReturnsTheSourcesIdentifier() {
    show()
    rule.onNodeWithText("Dark").performScrollTo().performClick()
    rule.runOnIdle {
      assertEquals(ThemePreference.Dark, Theme.preference)
      assertEquals("dark", InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("tafs.display", 0).getString("theme", null))
    }
    rule.onNodeWithText("Choose time zone…").performScrollTo().performClick()
    rule.onNodeWithText("Asia / Tokyo").performClick()
    rule.runOnIdle { assertEquals(listOf("Asia/Tokyo"), changes) }
  }
}

