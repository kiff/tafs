package bot.tafs.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Account
import bot.tafs.app.views.SettingsSheet
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

/** Account › Time zone, as on the iPad: "Use this phone's" only when the account's zone is another, and the footer under it. */
class AccountTimeZoneScreenTest {
  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val phoneZone = ZoneId.systemDefault().id
  private val otherZone = if (phoneZone == "Pacific/Auckland") "Europe/Paris" else "Pacific/Auckland"

  private fun account(zone: String) {
    rule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        TafsTheme {
          Box(Modifier.fillMaxSize()) {
            SettingsSheet(
              AppState(mode = Mode.kiff, ready = true, kiffAccount = Account(tenantId = "t_1", slug = "acme", timeZone = zone)),
              onSignIn = {}, onSignOut = { true }, onUsage = {}, onTurnOnAlerts = {}, onAlertSound = {},
              onDevices = {}, onPeople = {}, onUseDeviceZone = {}, onDeleteAccount = {}, onClose = {},
            )
          }
        }
      }
    }
    rule.waitUntil(30_000) {
      rule.onAllNodes(hasText("Account")).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
  }

  @Test fun anAccountInAnotherZoneIsOfferedThePhonesWithTheFooter() {
    account(otherZone)
    rule.onNodeWithText("Use this phone's: $phoneZone").performScrollTo()
    rule.onNodeWithText("Holds' deadlines, Activity, the Record and what your tafs write use this time zone.").performScrollTo()
  }

  @Test fun anAccountAlreadyInThePhonesZoneIsOfferedNothingAndStillSaysWhy() {
    account(phoneZone)
    rule.onAllNodesWithText("Use this phone's: $phoneZone").assertCountEquals(0)
    rule.onNodeWithText("Holds' deadlines, Activity, the Record and what your tafs write use this time zone.").performScrollTo()
  }
}
