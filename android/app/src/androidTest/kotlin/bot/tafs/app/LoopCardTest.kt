package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.SetupOption
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.model.Card
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.Taf
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafChatView
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The loop card in a taf's chat: a run that read one list page after page waits on it, in tafs cloud's words, and Read on goes back
 * to that taf's run, never to Coach. Run with `-e hold 8` to keep it for a screenshot.
 */
class LoopCardTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val t0 = Instant.parse("2026-11-11T23:18:00Z")
  private val taf = Taf(id = "disputes-1", name = "Disputes", job = "Open disputes", tools = emptyList(), colorIndex = 2, card = Card(emptyList(), "You"), cloudId = "taf_1")

  private val loop = ShownComponent(
    t0.plusSeconds(60), "s1", "r1",
    SetupComponent(
      type = "confirm", id = "tafs-loop-12", waits = "owner",
      loop = buildJsonObject { put("tool", JsonPrimitive("list_disputes")); put("pages", JsonPrimitive(10)) },
      title = "Still reading", text = "Read 10 pages of List disputes, and there's more.",
      options = listOf(SetupOption("tafs_loop_continue", "Read on"), SetupOption("tafs_loop_summarise", "Stop here")),
    ),
  )

  private val state = AppState(
    mode = Mode.kiff, ready = true, tafs = listOf(taf), kiffAccount = Account("t_01", "acme"),
    access = mapOf("disputes-1" to Access(use = true, oversee = true, manage = true)),
    chats = mapOf("disputes-1" to listOf(ChatMessage(t0, ChatMessage.From.owner, "Which disputes are open?", "s1"))),
    tafCards = mapOf("disputes-1" to listOf(loop)),
  )

  private val answered = mutableListOf<List<String>>()
  private val coachAnswered = mutableListOf<String>()

  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, id, _ -> coachAnswered += id }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
    answerCard = { tafId, type, id, answer -> answered += listOf(tafId, type, id, answer) },
  )

  @Test fun readOnAnswersTheTafsRun() {
    rule.setShown {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { TafChatView("disputes-1", state, actions) } }
    }
    rule.onNodeWithText("Still reading").assertIsDisplayed()
    rule.onNodeWithText("Read 10 pages of List disputes, and there's more.").assertIsDisplayed()
    rule.onNodeWithText("Stop here").assertIsDisplayed()
    rule.onNodeWithText("Read on").performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(listOf(listOf("disputes-1", "confirm", "tafs-loop-12", "tafs_loop_continue")), answered)
    assertTrue("never Coach's", coachAnswered.isEmpty())
  }
}
