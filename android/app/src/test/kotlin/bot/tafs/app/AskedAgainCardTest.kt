package bot.tafs.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.CardAnswer
import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.model.Res
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.CoachCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An approval card tafs cloud answered before any fingerprint, because its connection was asked in an earlier chat or for another
 * taf (`asked again`, docs/chat.md): it reads as tafs cloud's note alone. Coach's title and planque's grant text ("You're already
 * signed in: no second sign-in.") would sit beside a note that says Coach asks for the app again, and contradict it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h900dp-port")
class AskedAgainCardTest {
  @get:Rule val compose = createComposeRule()

  private val title = "Qonto morning balance may: List client invoices: reads only"
  private val grantText = "You're already signed in: no second sign-in."
  private val note = "This Qonto connection was started in an earlier chat, so it isn't resumed here. Coach asks for Qonto again, for this taf."
  private val card = SetupComponent(type = "approval", id = "ap-qonto", title = title, grant = "g1")
  private val state = AppState(mode = Mode.kiff, ready = true, grants = listOf(Grant(id = "g1", status = "approved", text = grantText)))

  private fun draw(answer: CardAnswer) {
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    compose.setContent {
      TafsTheme {
        CoachCard(ShownComponent(java.time.Instant.EPOCH, "s", "r", card, answer, "k"), state, CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}))
      }
    }
    compose.waitForIdle()
  }

  @Test fun askedAgainReadsAsTafsCloudsNoteAlone() {
    draw(CardAnswer(words = note, answer = "asked again"))
    compose.onNodeWithText(note).assertExists()
    compose.onNodeWithText(grantText, substring = true).assertDoesNotExist()
    compose.onNodeWithText(title, substring = true).assertDoesNotExist()
  }

  @Test fun anyOtherAnswerStillNamesWhatItWas() {
    draw(CardAnswer(words = "Approved", answer = "approved"))
    compose.onNodeWithText("Approved · $title").assertExists()
  }
}
