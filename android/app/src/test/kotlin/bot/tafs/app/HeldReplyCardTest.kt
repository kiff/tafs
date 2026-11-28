package bot.tafs.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.HeldReply
import bot.tafs.app.kiff.HeldReplyWords
import bot.tafs.app.kiff.Home
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.model.Res
import bot.tafs.app.views.HeldReplyCard
import bot.tafs.app.views.NeedsYouView
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A held door reply as the owner sees it (docs/doors.md): tafs cloud's line, the exact text that would go, Send and Don't send in
 * tafs cloud's words; and in Needs you, the taf listed with its newest reply's line, opening it where the reply is answered.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h900dp-port")
class HeldReplyCardTest {
  @get:Rule val compose = createComposeRule()

  private val reply = HeldReply(
    run = "run_7", door = "door_1", to = "Ben", subject = "Re: refund",
    text = "Hello Ben,\n\nYour refund for order T-3001 is on its way.", at = Instant.parse("2026-10-28T08:59:00Z"), textSha256 = "a".repeat(64),
    words = HeldReplyWords("Refunds wrote a reply to Ben. It waits for you to send it.", "Send", "Don't send", "Send this reply to Ben", "It changed."),
  )

  @Test fun theCardShowsTheExactTextAndTafsCloudsButtons() {
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val pressed = mutableListOf<String>()
    compose.setContent {
      TafsTheme { HeldReplyCard(reply, busy = false, send = { pressed += "send"; true }, discard = { pressed += "discard"; true }) }
    }
    compose.onNodeWithText(reply.words!!.line).assertExists()
    compose.onNodeWithText("Re: refund").assertExists()
    compose.onNodeWithTag("reply-text").assertExists()
    compose.onNodeWithText(reply.text).assertExists()
    compose.onNodeWithText("Don't send").performClick()
    compose.onNodeWithText("Send").performClick()
    compose.waitForIdle()
    assertEquals(listOf("discard", "send"), pressed)
  }

  @Test fun needsYouListsATafWithAHeldReplyInItsOwnLine() {
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val taf = KiffMapping.taf(
      KiffClient.json.decodeFromString<Home>(
        """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true},
          "doors":[{"app":"email","replies":1,"reply_line":"Refunds wrote a reply to Ben. It waits for you to send it."}]}]}""",
      ).tafs.single(),
      "You",
    )
    val opened = mutableListOf<String>()
    compose.setContent { TafsTheme { NeedsYouView(AppState(mode = Mode.kiff, tafs = listOf(taf)), onAnswer = { _, _ -> }, onOpenKnocks = { opened += it }) } }
    compose.onNodeWithText("Refunds wrote a reply to Ben. It waits for you to send it.").assertExists()
    compose.onAllNodesWithTag("knocked-${taf.id}").assertCountEquals(0)
    compose.onNodeWithTag("replying-${taf.id}").performClick()
    assertEquals(listOf(taf.id), opened)
  }
}
