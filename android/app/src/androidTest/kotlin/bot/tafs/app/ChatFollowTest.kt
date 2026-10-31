package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import bot.tafs.app.app.AppState
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.app.Mode
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Access
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.CardAnswer
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.model.Card
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.LiveAnswer
import bot.tafs.app.model.LiveState
import bot.tafs.app.model.Taf
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.CoachScreen
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafChatView
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A chat follows a reply as it streams in, at its end: the thread never jumps to its top and back while the taf writes (the
 * owner saw it flicker so while Coach was thinking).
 */
class ChatFollowTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val t0 = Instant.parse("2026-10-31T08:00:00Z")
  private val taf = Taf(id = "refunds-1", name = "Refunds", job = "Refund", tools = emptyList(), colorIndex = 1, card = Card(emptyList(), "You"), cloudId = "taf_1")

  private val thread = (0 until 30).map { i ->
    ChatMessage(t0.plusSeconds(i * 60L), if (i % 2 == 0) ChatMessage.From.owner else ChatMessage.From.taf, "Message ${i + 1} of the thread", "s1")
  }

  private fun state(live: LiveAnswer?) = AppState(
    mode = Mode.kiff, ready = true, tafs = listOf(taf), kiffAccount = Account("t_01", "acme"),
    access = mapOf("refunds-1" to Access(use = true, oversee = true, manage = true)),
    chats = mapOf("refunds-1" to thread),
    chatLive = live?.let { mapOf("refunds-1" to it) } ?: emptyMap(),
  )

  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
  )

  @Test fun aReplyStreamingInKeepsTheThreadAtItsEndNeverAtItsTop() {
    var current by mutableStateOf(state(null))
    rule.setContent {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { TafChatView("refunds-1", current, actions) } }
    }
    rule.onNodeWithText("Message 30 of the thread").assertIsDisplayed()

    // The reply grows a few words at a time, frame by frame, as a stream does.
    rule.mainClock.autoAdvance = false
    val words = "Two refunds went through this morning, both inside your Card, and nothing else is waiting on you. ".repeat(6).split(" ")
    var text = ""
    var sawTop = false
    val tops = mutableListOf<Float?>()
    for (word in words) {
      text += "$word "
      current = state(LiveAnswer(started = t0.plusSeconds(1900), session = "s1", text = text))
      repeat(2) {
        rule.mainClock.advanceTimeByFrame()
        // The thread's first message never comes into view while the reply streams.
        if (rule.onAllNodesWithText("Message 1 of the thread").fetchSemanticsNodes().isNotEmpty()) sawTop = true
        tops += rule.onAllNodesWithText("Message 29 of the thread").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
      }
    }
    rule.mainClock.autoAdvance = true
    rule.waitForIdle()
    assertTrue("the thread jumped to its top while the reply streamed", !sawTop)
    assertFollowedForward(tops)
    rule.onNodeWithText(text.trim(), substring = true).assertIsDisplayed()
  }

  // MARK: Coach, while it works: the dots, its live line, steps, then the reply

  private val coachThread = (0 until 30).map { i ->
    ChatMessage(t0.plusSeconds(i * 60L), if (i % 2 == 0) ChatMessage.From.owner else ChatMessage.From.taf, "Coach message ${i + 1}", "s1")
  }
  private val rules = KiffClient.json.decodeFromString<SetupComponent>(
    """{"type":"confirm","id":"rules","title":"Use these rules?","text":"Refund up to €100 each.","options":[{"label":"Agree","value":"agree"}]}""",
  )

  private fun coach(working: Boolean, live: LiveAnswer?) = AppState(
    mode = Mode.kiff, ready = true, kiffAccount = Account("t_01", "acme", capabilities = listOf("members.manage")),
    coachChecked = true, coachCloudId = "setup_1", coachAgent = "setup-acme", coachChat = "s1",
    coachComponents = listOf(ShownComponent(t0.plusSeconds(25 * 60L), "s1", "r1", rules, CardAnswer(words = "Agree"))),
    chats = mapOf(COACH_CHAT to coachThread),
    chatAwaiting = if (working) mapOf(COACH_CHAT to t0.plusSeconds(1900)) else emptyMap(),
    chatLive = live?.let { mapOf(COACH_CHAT to it) } ?: emptyMap(),
  )

  @Test fun coachWorkingKeepsItsThreadAtItsEndNeverAtItsTop() {
    var current by mutableStateOf(coach(false, null))
    rule.setContent {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { CoachScreen(current, actions, loadCoach = {}, onBack = {}) } }
    }
    rule.onNodeWithText("Coach message 30").assertIsDisplayed()

    rule.mainClock.autoAdvance = false
    var sawTop = false
    val tops = mutableListOf<Float?>()
    fun frames(n: Int = 2) = repeat(n) {
      rule.mainClock.advanceTimeByFrame()
      if (rule.onAllNodesWithText("Coach message 1").fetchSemanticsNodes().isNotEmpty()) sawTop = true
      tops += rule.onAllNodesWithText("Coach message 29").fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
    }
    // The owner sent: the dots. Then the live line, steps one by one, and the reply, a few words at a time.
    current = coach(true, null); frames(6)
    var live = LiveAnswer(started = t0.plusSeconds(1900), session = "s1", state = LiveState("thinking", "Reading your payments"))
    current = coach(true, live); frames(6)
    for (i in 1..4) {
      live = live.began("tool_$i", "Reading step $i", "c$i").copy(state = LiveState("working", "Step $i of 4"))
      current = coach(true, live); frames(4)
    }
    for (word in "Stripe is connected and I read your last payments, so here is the Card I'd give Refunds. ".repeat(5).split(" ")) {
      live = live.copy(text = live.text + "$word ")
      current = coach(true, live); frames()
    }
    rule.mainClock.autoAdvance = true
    rule.waitForIdle()
    assertTrue("Coach's thread jumped to its top while it worked", !sawTop)
    assertFollowedForward(tops)
  }

  /**
   * Following the end, an earlier message only ever moves up the screen, or off its top for good: never down again (the
   * list scrolling back), nor back on screen once it left.
   */
  private fun assertFollowedForward(tops: List<Float?>) {
    var left = false
    for ((i, top) in tops.withIndex()) {
      if (top == null) { left = true; continue }
      assertTrue("frame $i: it came back after leaving the screen: $tops", !left)
      if (i > 0) tops[i - 1]?.let { before -> assertTrue("frame $i: it moved down from $before to $top: $tops", top <= before + 1f) }
    }
  }
}
