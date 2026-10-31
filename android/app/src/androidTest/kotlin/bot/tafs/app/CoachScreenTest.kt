package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import bot.tafs.app.app.AppState
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.app.Mode
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.CardAnswer
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.LiveAnswer
import bot.tafs.app.model.LiveState
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.CoachScreen
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.TafActions
import java.time.Instant
import org.junit.Rule
import org.junit.Test

/**
 * Coach's screen, drawn from what tafs cloud sends: the compact header, the plan in its one line, answered cards in kiff/agents'
 * words, and the live line. Run with `-e hold 8` to keep each state on screen for a screenshot.
 */
class CoachScreenTest {
  // Awake and unlocked before the activity launches: a just-booted emulator may not be yet.
  @get:Rule(order = 0) val awake = AwakeDevice()

  @get:Rule(order = 1) val rule = createComposeRule()

  private val t0 = Instant.parse("2026-10-30T05:00:00Z")
  private fun component(json: String) = KiffClient.json.decodeFromString<SetupComponent>(json)

  private val plan = component(
    """{"type":"steps","id":"plan","line":"Step 3 of 6 · Your rules","steps":[
      {"label":"Connect Stripe","status":"done"},{"label":"Read your payments","status":"done"},{"label":"Your rules","status":"active"},
      {"label":"Your Card","status":"pending"},{"label":"Try it","status":"pending"},{"label":"Start Refunds","status":"pending"}]}""",
  )
  private val rules = component(
    """{"type":"confirm","id":"rules","title":"Use these rules?","text":"Refund up to €5 each, €20 a day. Anything over waits for you.","options":[{"label":"Agree","value":"agree"},{"label":"Change them","value":"change"}]}""",
  )
  private val connect = component("""{"type":"approval","id":"connect","title":"Connect Stripe (test mode)","grant":"g1"}""")

  private fun state(live: LiveAnswer?) = AppState(
    mode = Mode.kiff, ready = true, kiffAccount = Account("t_01", "acme", capabilities = listOf("members.manage")),
    coachChecked = true, coachCloudId = "setup_1", coachAgent = "setup-acme", coachChat = "s1",
    coachComponents = listOf(
      ShownComponent(t0, "s1", "r1", plan),
      ShownComponent(t0.plusSeconds(10), "s1", "r1", connect, CardAnswer(words = "Approved")),
      ShownComponent(t0.plusSeconds(60), "s1", "r1", rules),
    ),
    chats = mapOf(
      COACH_CHAT to listOf(
        ChatMessage(t0.minusSeconds(30), ChatMessage.From.owner, "Set up refunds for Stripe test mode", "s1"),
        ChatMessage(t0.plusSeconds(5), ChatMessage.From.taf, "I'll connect Stripe first. Approve the connection below.", "s1"),
        ChatMessage(t0.plusSeconds(50), ChatMessage.From.taf, "Stripe is connected. Here are the rules I'd use for refunds.", "s1"),
      ),
    ),
    chatLive = live?.let { mapOf(COACH_CHAT to it) } ?: emptyMap(),
  )

  private val actions = TafActions(
    answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
    sendChat = { _, _, _, _ -> }, issuer = "KIFF",
    doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
    coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
    connections = ConnectionActions({}, { false }),
  )

  private fun show(live: LiveAnswer?) = rule.setShown {
    TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { CoachScreen(state(live), actions, loadCoach = {}, onBack = {}) } }
  }

  /** Keeps the screen as it is for a screenshot, when the run asks (`-e hold <seconds>`). */
  private fun hold() {
    val seconds = InstrumentationRegistry.getArguments().getString("hold")?.toLongOrNull() ?: return
    rule.waitForIdle()
    Thread.sleep(seconds * 1000)
  }

  @Test fun theHeaderIsOneRowAndThePlanIsTafsCloudsLine() {
    show(null)
    rule.onNodeWithText("Coach").assertIsDisplayed()
    rule.onNodeWithText("Step 3 of 6 · Your rules").assertIsDisplayed()
    // Answered: kiff/agents' word, and no buttons left on it.
    rule.onNodeWithText("Approved · Connect Stripe (test mode)").assertIsDisplayed()
    hold()
  }

  @Test fun thePlanOpensToItsStepsAndFoldsBack() {
    show(null)
    rule.onNodeWithText("Step 3 of 6 · Your rules").performClick()
    rule.onNodeWithText("Connect Stripe").assertIsDisplayed()
    rule.onNodeWithText("Start Refunds").assertIsDisplayed()
    hold()
    rule.onNodeWithText("Step 3 of 6 · Your rules").performClick()
    rule.onNodeWithText("Start Refunds").assertDoesNotExist()
  }

  @Test fun theLiveLineThinking() {
    show(LiveAnswer(started = Instant.now(), state = LiveState("thinking")))
    rule.onNodeWithText("Thinking…").assertIsDisplayed()
    hold()
  }

  @Test fun theLiveLineWaitingForYou() {
    show(LiveAnswer(started = Instant.now(), state = LiveState("waiting_for_you")))
    rule.onNodeWithText("Waiting for you").assertIsDisplayed()
    hold()
  }

  @Test fun atDoneTheLiveLineIsGone() {
    show(LiveAnswer(started = Instant.now(), state = LiveState("done")))
    rule.onNodeWithText("Thinking…").assertDoesNotExist()
    rule.onNodeWithText("Waiting for you").assertDoesNotExist()
  }

  @Test fun theMenuHoldsCoachsConversations() {
    show(null)
    rule.onNodeWithText("⋯").performClick()
    rule.onNodeWithText("New conversation").assertIsDisplayed()
    rule.onNodeWithText("Previous conversations").assertIsDisplayed()
    hold()
  }

  @Test fun theLiveLineIsTafsCloudsAndNoThinkingTextShows() {
    show(LiveAnswer(started = Instant.now(), thinking = "The owner wants refunds capped, I should", state = LiveState("working", "Drafting the Card")))
    rule.onNodeWithText("Drafting the Card…").assertIsDisplayed()
    rule.onNodeWithText("The owner wants refunds capped, I should").assertDoesNotExist()
    hold()
  }
}
