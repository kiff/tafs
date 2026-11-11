package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.app.Mode
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.CoachConnect
import bot.tafs.app.kiff.CoachTools
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.Words
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.CoachChatActions
import bot.tafs.app.views.CoachScreen
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.TafActions
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Coach's tools as a step of their own (docs/coach.md): asked in a sheet at the end of a first Start, put off with Not now, then
 * one line above the conversation that opens the same sheet. The message box keeps its room and its buttons their size, the
 * keyboard open.
 */
class CoachToolsStepTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val t0 = Instant.parse("2026-11-10T08:00:00Z")
  private val lines = (1..10).map { "Tool line $it" }
  private val tools = CoachTools(state = "to_give", connect = CoachConnect(body = JsonObject(emptyMap()), lines = lines))

  private fun state(asked: Boolean) = AppState(
    mode = Mode.kiff, ready = true, kiffAccount = Account("t_01", "acme", capabilities = listOf("members.manage")),
    coachChecked = true, coachCloudId = "setup_1", coachAgent = "setup-acme", coachChat = "s1", planqueReady = true,
    coachTools = tools, coachToolsAsked = asked,
    chats = mapOf(COACH_CHAT to (0 until 12).map { i -> ChatMessage(t0.plusSeconds(i * 60L), if (i % 2 == 0) ChatMessage.From.owner else ChatMessage.From.taf, "Coach message ${i + 1}", "s1") }),
  )

  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
  )

  @Test fun aFirstStartEndsOnTheToolsStepAndNotNowLeavesOneLine() {
    var current by mutableStateOf(state(asked = true))
    var given = 0
    val chats = CoachChatActions(giveTools = { given += 1; false }, notNowTools = { current = current.copy(coachToolsAsked = false) })
    rule.setShown {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { CoachScreen(current, actions, loadCoach = {}, onBack = {}, chats = chats) } }
    }
    // The step: the tools in tafs cloud's words, its button, and Not now.
    rule.onNodeWithText("· Tool line 2").assertExists()
    rule.onNodeWithText(Words.Coach.Tools.give).assertExists()
    rule.onNodeWithText(Words.Common.notNow).assertExists().performClick()
    rule.waitForIdle()
    // Put off: one line opens it again, and the tools' lines no longer sit above the conversation.
    rule.onAllNodesWithText("· Tool line 2").assertCountEquals(0)
    rule.onNodeWithText(Words.Coach.Tools.title).assertIsDisplayed().performClick()
    rule.waitForIdle()
    rule.onNodeWithText(Words.Coach.Tools.give).assertExists().performClick()
    rule.waitUntil(5_000) { given == 1 }
    assertEquals(1, given)
  }

  @Test fun tafsCloudsWordsShowTheGroupsWithEachToolFoldedUnderThem() {
    val step = bot.tafs.app.kiff.CoachToolsStep(
      title = "Give Coach its tools", intro = "Coach needs a few tools.",
      groups = listOf(bot.tafs.app.kiff.CoachToolsStep.Group("Watch your tafs", "See your tafs and their runs.",
        listOf(bot.tafs.app.kiff.CoachToolsStep.Tool("See your tafs", "Read each taf. Up to 500 reads a day.", "It reads your account.")))),
      details = "Each tool and its limits", ask = "Give Coach these tools?", give = "Give them", notNow = "Later", row = "Coach has no tools yet · Give them",
    )
    var current by mutableStateOf(state(asked = true).copy(coachTools = tools.copy(step = step)))
    rule.setShown {
      TafsTheme {
        CompositionLocalProvider(LocalBiometry provides "your fingerprint") {
          CoachScreen(current, actions, loadCoach = {}, onBack = {}, chats = CoachChatActions(notNowTools = { current = current.copy(coachToolsAsked = false) }))
        }
      }
    }
    rule.onNodeWithText("Coach needs a few tools.").assertExists()
    rule.onNodeWithText("See your tafs and their runs.").assertExists()
    rule.onNodeWithText("Give Coach these tools?").assertExists()
    rule.onNodeWithText("Give them").assertExists()
    // Folded at first: no tool's own line, and none of the old lines.
    rule.onAllNodesWithText("It reads your account.").assertCountEquals(0)
    rule.onAllNodesWithText("· Tool line 2").assertCountEquals(0)
    rule.onNodeWithText("Each tool and its limits", substring = true).performClick()
    rule.waitForIdle()
    rule.onNodeWithText("It reads your account.").assertExists()
    rule.onNodeWithText("Later").performClick()
    rule.waitForIdle()
    // Put off: tafs cloud's row.
    rule.onNodeWithText("Coach has no tools yet · Give them").assertIsDisplayed()
  }

  /** The box and its buttons, in what's left of a phone once the keyboard is up. */
  private fun assertTheBoxKeepsItsRoom() {
    val send = rule.onNodeWithContentDescription(Words.Common.send).assertIsDisplayed().fetchSemanticsNode()
    val box = rule.onNode(hasSetTextAction()).assertIsDisplayed().fetchSemanticsNode()
    with(rule.density) {
      assertEquals(40.dp.roundToPx(), send.size.width)
      assertEquals(40.dp.roundToPx(), send.size.height)
    }
    assertTrue("the box shows", box.size.height > 0)
  }

  @Test fun withTheKeyboardOpenTheBoxKeepsItsRoomAndItsButtonsTheirSize() {
    // What's left above a keyboard on a small phone, the keyboard said open: no emulator needs to raise one.
    rule.setShown {
      TafsTheme {
        CompositionLocalProvider(LocalBiometry provides "your fingerprint", bot.tafs.app.views.LocalKeyboardOpen provides true) {
          androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.height(320.dp)) {
            CoachScreen(state(asked = false), actions, loadCoach = {}, onBack = {}, chats = CoachChatActions())
          }
        }
      }
    }
    rule.onAllNodesWithText(Words.Coach.Tools.title).assertCountEquals(0)
    assertTheBoxKeepsItsRoom()
  }

  @Test fun withTheRealKeyboardUpTheBoxShows() {
    rule.setShown {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { CoachScreen(state(asked = false), actions, loadCoach = {}, onBack = {}, chats = CoachChatActions()) } }
    }
    rule.onNode(hasSetTextAction()).performClick()
    // Only where this device raises a keyboard: an emulator with a hardware one shows none, and the test above stands in.
    val up = runCatching { rule.waitUntil(5_000) { rule.onAllNodesWithText(Words.Coach.Tools.title).fetchSemanticsNodes().isEmpty() } }.isSuccess
    org.junit.Assume.assumeTrue("no soft keyboard on this device", up)
    assertTheBoxKeepsItsRoom()
  }
}
