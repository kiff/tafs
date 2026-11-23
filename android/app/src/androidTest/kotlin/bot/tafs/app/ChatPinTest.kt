package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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
import bot.tafs.app.model.Words
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.CoachScreen
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafChatView
import java.time.Instant
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The sent message stays on top (docs/chat.md), as on the iPad (its ChatPinUITests): sending puts the owner's message at the top
 * of the chat, with room under it, and it stays there while the taf thinks, works and answers, and when tafs cloud's copy takes
 * its place. Scrolling hands the chat to the owner; "Jump to the latest" goes to the end.
 */
class ChatPinTest {
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val t0 = Instant.parse("2026-10-31T08:00:00Z")
  private val taf = Taf(id = "refunds-1", name = "Refunds", job = "Refund", tools = emptyList(), colorIndex = 1, card = Card(emptyList(), "You"), cloudId = "taf_1")
  private val words = "Refund order 1182 in full"
  private val sentAt = t0.plusSeconds(3600)

  private fun thread(n: Int, chat: String = "Message") = (0 until n).map { i ->
    ChatMessage(t0.plusSeconds(i * 60L), if (i % 2 == 0) ChatMessage.From.owner else ChatMessage.From.taf, "$chat ${i + 1} of the thread", "s1")
  }

  private fun state(chat: String, thread: List<ChatMessage>, draft: String = "", working: Boolean = false, live: LiveAnswer? = null) = AppState(
    mode = Mode.kiff, ready = true, tafs = listOf(taf), kiffAccount = Account("t_01", "acme", capabilities = listOf("members.manage")),
    access = mapOf("refunds-1" to Access(use = true, oversee = true, manage = true)),
    coachChecked = true, coachCloudId = "setup_1", coachAgent = "setup-acme", coachChat = "s1",
    chats = mapOf(chat to thread),
    drafts = if (draft.isEmpty()) emptyMap() else mapOf(chat to draft),
    chatAwaiting = if (working) mapOf(chat to sentAt) else emptyMap(),
    chatLive = live?.let { mapOf(chat to it) } ?: emptyMap(),
  )

  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
  )

  /** The sent message's top, from the list's top: where a pinned message sits, at the list's first line. */
  private fun sentTop(): Float {
    val list = rule.onNodeWithTag("chat-thread").fetchSemanticsNode().boundsInRoot.top
    return rule.onNodeWithText(words).fetchSemanticsNode().boundsInRoot.top - list
  }

  private fun assertAtTheTop(where: String) {
    val top = sentTop()
    // The list's top padding and a little: the message's own row starts there.
    val most = 24f * rule.density.density
    assertTrue("$where: the sent message is $top px from the list's top", top >= 0f && top <= most)
  }

  /** The whole turn, as tafs cloud and the app change it: sent, thinking, the work streaming, the copy settling, the answer landing. */
  private fun sendAndAnswer(chat: String, before: List<ChatMessage>, show: (AppState) -> Unit): Float {
    rule.onNodeWithContentDescription(Words.Common.send).performClick()
    val sent = ChatMessage(sentAt, ChatMessage.From.owner, words, "pending")
    show(state(chat, before + sent, working = true))
    rule.waitForIdle()
    assertAtTheTop("sent")
    val pinnedAt = sentTop()

    rule.mainClock.autoAdvance = false
    val tops = mutableListOf<Float>()
    fun frames(n: Int = 2) = repeat(n) { rule.mainClock.advanceTimeByFrame(); tops += sentTop() }
    var live = LiveAnswer(started = sentAt, session = "s1", run = "run_9", state = LiveState("thinking", "Reading your payments"))
    show(state(chat, before + sent, working = true, live = live)); frames(4)
    for (i in 1..3) {
      live = live.began("tool_$i", "Reading step $i", "c$i").copy(state = LiveState("working", "Step $i of 3"))
      show(state(chat, before + sent, working = true, live = live)); frames(4)
    }
    // tafs cloud's copy of the message takes the place of the one shown on sending.
    val settled = sent.copy(session = "s1", at = sentAt.plusSeconds(1), serverId = "run_9.in")
    for (word in "Order 1182 is refunded in full, inside your Card, and the customer has the receipt.".split(" ")) {
      live = live.copy(text = live.text + "$word ")
      show(state(chat, before + settled, working = true, live = live)); frames()
    }
    // The run ends: the answer lands in the thread as the live row goes.
    val answer = ChatMessage(sentAt.plusSeconds(90), ChatMessage.From.taf, live.text.trim(), "s1", serverId = "run_9.12")
    show(state(chat, before + settled + answer)); frames(6)
    rule.mainClock.autoAdvance = true
    rule.waitForIdle()
    for ((i, top) in tops.withIndex()) assertTrue("frame $i: the sent message moved from $pinnedAt to $top: $tops", abs(top - pinnedAt) <= 1f)
    assertAtTheTop("answered")
    rule.onNodeWithText("the customer has the receipt", substring = true).assertIsDisplayed()
    return pinnedAt
  }

  @Test fun aSentMessageGoesToTheTopAndTheWorkAndAnswerGrowUnderIt() {
    val before = thread(30)
    var current by mutableStateOf(state("refunds-1", before, draft = words))
    rule.setShown {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { TafChatView("refunds-1", current, actions) } }
    }
    rule.onNodeWithText("Message 30 of the thread").assertIsDisplayed()
    sendAndAnswer("refunds-1", before) { current = it }
  }

  /** A short thread: the room under the message lets it reach the top at once, not once the answer has filled the screen. */
  @Test fun aShortThreadsSentMessageReachesTheTopToo() {
    val before = thread(2)
    var current by mutableStateOf(state("refunds-1", before, draft = words))
    rule.setShown {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { TafChatView("refunds-1", current, actions) } }
    }
    rule.onNodeWithText("Message 2 of the thread").assertIsDisplayed()
    sendAndAnswer("refunds-1", before) { current = it }
  }

  @Test fun coachKeepsTheSentMessageOnTopWhileItWorks() {
    val before = thread(30, chat = "Coach")
    var current by mutableStateOf(state(COACH_CHAT, before, draft = words))
    rule.setShown {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { CoachScreen(current, actions, loadCoach = {}, onBack = {}) } }
    }
    rule.onNodeWithText("Coach 30 of the thread").assertIsDisplayed()
    sendAndAnswer(COACH_CHAT, before) { current = it }
  }

  /**
   * Coach's card just answered takes the pin, as on iOS (docs/chat.md): its answer starts Coach's next answer, so the card goes to
   * the top and stays there while Coach's work and words grow under it.
   */
  @Test fun coachsAnsweredCardGoesToTheTopAndCoachsAnswerGrowsUnderIt() {
    val before = thread(30, chat = "Coach")
    val rules = KiffClient.json.decodeFromString<SetupComponent>(
      """{"type":"confirm","id":"rules","title":"Use these rules?","text":"Refund up to 100 each.","options":[{"label":"Agree","value":"agree"}]}""",
    )
    val card = ShownComponent(t0.plusSeconds(25 * 60L + 30), "s1", "r1", rules, CardAnswer(words = "Agree"))
    fun coach(answered: Boolean, working: Boolean, live: LiveAnswer? = null) =
      state(COACH_CHAT, before, working = working, live = live).copy(coachComponents = listOf(card), coachAnsweredCard = if (answered) card.ref else null)
    var current by mutableStateOf(coach(answered = false, working = false))
    rule.setShown {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { CoachScreen(current, actions, loadCoach = {}, onBack = {}) } }
    }
    rule.onNodeWithText("Coach 30 of the thread").assertIsDisplayed()
    fun cardTop(): Float {
      val list = rule.onNodeWithTag("chat-thread").fetchSemanticsNode().boundsInRoot.top
      return rule.onNodeWithText("Use these rules?").fetchSemanticsNode().boundsInRoot.top - list
    }
    // Answered on this phone: the card goes to the top.
    current = coach(answered = true, working = true)
    rule.waitForIdle()
    val most = 24f * rule.density.density
    val pinnedAt = cardTop()
    assertTrue("the answered card is $pinnedAt px from the list's top", pinnedAt >= 0f && pinnedAt <= most)
    // Coach's work and answer grow under it without moving it.
    rule.mainClock.autoAdvance = false
    val tops = mutableListOf<Float>()
    var live = LiveAnswer(started = sentAt, session = "s1", run = "run_9", state = LiveState("thinking", "Reading your answer"))
    current = coach(answered = true, working = true, live = live); repeat(4) { rule.mainClock.advanceTimeByFrame(); tops += cardTop() }
    for (word in "Agreed, so Refunds gets these rules on its Card, and I'll ask you to approve them next.".split(" ")) {
      live = live.copy(text = live.text + "$word ")
      current = coach(answered = true, working = true, live = live); repeat(2) { rule.mainClock.advanceTimeByFrame(); tops += cardTop() }
    }
    rule.mainClock.autoAdvance = true
    rule.waitForIdle()
    for ((i, top) in tops.withIndex()) assertTrue("frame $i: the card moved from $pinnedAt to $top: $tops", abs(top - pinnedAt) <= 1f)
  }

  /** The owner scrolls back: the chat is theirs, and the answer still growing doesn't pull the message back up. */
  @Test fun scrollingHandsTheChatToTheOwnerAndJumpToTheLatestGoesToTheEnd() {
    val before = thread(30)
    var current by mutableStateOf(state("refunds-1", before, draft = words))
    rule.setShown {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { TafChatView("refunds-1", current, actions) } }
    }
    rule.onNodeWithText("Message 30 of the thread").assertIsDisplayed()
    rule.onNodeWithContentDescription(Words.Common.send).performClick()
    val sent = ChatMessage(sentAt, ChatMessage.From.owner, words, "pending")
    current = state("refunds-1", before + sent, working = true)
    rule.waitForIdle()
    assertAtTheTop("sent")
    val pinnedAt = sentTop()

    // An answer taller than the screen, then the owner reads back up.
    val long = (1..40).joinToString("\n") { "Line $it of the answer" }
    var live = LiveAnswer(started = sentAt, session = "s1", run = "run_9", text = long)
    current = state("refunds-1", before + sent, working = true, live = live)
    rule.waitForIdle()
    rule.onNodeWithTag("chat-thread").performTouchInput { swipeDown(startY = centerY - 200f, endY = centerY) }
    rule.waitForIdle()
    val readAt = sentTop()
    assertTrue("the owner's scroll didn't move the chat: $readAt", readAt > pinnedAt + 50f)
    live = live.copy(text = live.text + "\nOne more line, written after the owner scrolled")
    current = state("refunds-1", before + sent, working = true, live = live)
    rule.waitForIdle()
    assertTrue("the message was pulled back to the top after the owner scrolled: $readAt then ${sentTop()}", abs(sentTop() - readAt) <= 1f)

    rule.onNodeWithContentDescription(Words.Chat.jumpToLatest).performClick()
    rule.waitForIdle()
    rule.onNodeWithText("One more line, written after the owner scrolled", substring = true).assertIsDisplayed()
  }
}
