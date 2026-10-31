package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.views.MicButton
import bot.tafs.app.voice.Dictation
import bot.tafs.app.voice.SpeechEngine
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The mic's language switch, as on iOS: shown with more than one of the owner's languages, each tap the next. */
class MicButtonTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private class TwoLanguages(list: List<String>) : SpeechEngine {
    override val available = true
    override val mayListen = true
    override val languages = MutableStateFlow(list)
    override fun listen(listener: SpeechEngine.Listener, language: String?) {}
    override fun stop() {}
  }

  @Test fun theSwitchShowsTheLanguageAndATapGoesToTheNext() {
    val dictation = Dictation(TwoLanguages(listOf("en-GB", "fr-FR")))
    rule.setShown { TafsTheme { MicButton(dictation, "") {} } }
    rule.onNodeWithText("EN").assertIsDisplayed()
    rule.onNodeWithContentDescription("Dictation in English. Switch language").performClick()
    rule.onNodeWithText("FR").assertIsDisplayed()
    rule.onNodeWithContentDescription("Dictation in Français. Switch language").assertIsDisplayed()
  }

  @Test fun oneLanguageShowsNoSwitch() {
    rule.setShown { TafsTheme { MicButton(Dictation(TwoLanguages(listOf("en-GB"))), "") {} } }
    rule.onNodeWithContentDescription("Dictate a message").assertIsDisplayed()
    assertTrue(rule.onAllNodesWithTextCount("EN") == 0)
  }

  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
