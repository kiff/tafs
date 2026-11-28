package bot.tafs.app

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Unanswered
import bot.tafs.app.kiff.UnansweredWords
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.views.UnansweredCard
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A writer the taf couldn't answer, as the owner sees it (docs/doors.md): tafs cloud's line, Connect an app only when it's
 * offered, Answer them yourself opening a field whose text goes as typed, and Ignore.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h900dp-port")
class UnansweredCardTest {
  @get:Rule val compose = createComposeRule()

  private val line = "Refunds can't do what Ben asked. Their message waits here, and they haven't been answered."

  private fun writer(connect: String?) = Unanswered(
    run = "run_9", door = "door_1", to = "Ben", subject = "Invoice 118", at = Instant.parse("2026-11-28T08:59:00Z"),
    cantDo = if (connect != null) "no_app" else "other",
    words = UnansweredWords(line, connect, "Answer them yourself", "Ignore", "Send this reply to Ben", "It changed."),
  )

  @Test fun itDrawsTafsCloudsWordsAndSendsTheOwnersTextAsTyped() {
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val pressed = mutableListOf<String>()
    compose.setContent {
      TafsTheme {
        UnansweredCard(writer("Connect an app"), busy = false, answer = { pressed += "send:$it"; true }, ignore = { pressed += "ignore"; true }, connect = { pressed += "connect" })
      }
    }
    compose.onNodeWithText(line).assertExists()
    compose.onNodeWithText("Invoice 118").assertExists()
    compose.onNodeWithText("Connect an app").performClick()
    compose.onNodeWithText("Answer them yourself").performClick()
    // Send waits for words.
    compose.onNodeWithText(Words.Common.send).assertIsNotEnabled()
    compose.onNode(hasSetTextAction()).performTextInput("I'll send it today.")
    compose.onNodeWithText(Words.Common.send).performClick()
    compose.waitForIdle()
    assertEquals(listOf("connect", "send:I'll send it today."), pressed)
  }

  @Test fun withoutAMissingAppThereIsNoConnectAndIgnoreGoes() {
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val pressed = mutableListOf<String>()
    compose.setContent {
      TafsTheme { UnansweredCard(writer(null), busy = false, answer = { pressed += "send"; true }, ignore = { pressed += "ignore"; true }, connect = { pressed += "connect" }) }
    }
    compose.onNodeWithText("Connect an app").assertDoesNotExist()
    compose.onNodeWithTag("unanswered-run_9").assertExists()
    compose.onNodeWithText("Ignore").performClick()
    compose.waitForIdle()
    assertEquals(listOf("ignore"), pressed)
  }
}
