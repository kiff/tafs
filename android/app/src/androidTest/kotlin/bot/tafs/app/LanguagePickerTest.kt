package bot.tafs.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.AppLocale
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.views.SettingsSheet
import org.junit.Rule
import org.junit.Test

/** Account › Language's Change language opens the app's own list: the phone's language, then the shipped ones named in themselves. */
class LanguagePickerTest {
  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createStableComposeRule()

  @Test fun changeLanguageListsThePhonesLanguageAndTheShippedOnes() {
    rule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        TafsTheme {
          Box(Modifier.fillMaxSize()) {
            SettingsSheet(
              AppState(mode = Mode.kiff, ready = true),
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
    rule.onNodeWithText("Change language").performScrollTo().performClick()
    // The picker's own rows: the theme's three choices behind it, in the Account sheet's own dialog, are radio buttons too.
    rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and hasAnyAncestor(hasTestTag("language-picker"))).assertCountEquals(AppLocale.TAGS.size + 1)
    listOf("This phone's language", "Deutsch", "Français", "Español", "Italiano", "Português", "Nederlands", "Polski", "한국어", "日本語", "Eesti", "Lietuvių", "Latviešu", "Čeština", "Slovenčina", "Slovenščina", "Magyar", "Română", "Български", "Hrvatski").forEach {
      rule.onAllNodesWithText(it).assertCountEquals(1)
    }
    // English is the row, and the language shown beside Language.
    rule.onAllNodesWithText("English").assertCountEquals(2)
  }
}
