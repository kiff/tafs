package bot.tafs.app


import bot.tafs.app.model.Words

import androidx.compose.ui.test.performScrollTo

import androidx.compose.ui.test.assertIsNotEnabled

import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
import bot.tafs.app.model.ApprovalReadiness
import bot.tafs.app.model.ApprovalWords
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.LiveAnswer
import bot.tafs.app.model.LiveState
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.CoachScreen
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.LocalApprovalReadiness
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

  @get:Rule(order = 1) val rule = createStableComposeRule()

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
  private val connect = component("""{"type":"approval","id":"connect","title":"Connect Stripe (test mode)","grant":"g1","draft":"dr_1"}""")
  private val grant = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>(
    """{"id":"g1","status":"connected","text":"Read your payments and refund one.","request":{"server":"https://mcp.stripe.example"}}""",
  )
  private val draft = KiffClient.json.decodeFromString<bot.tafs.app.kiff.AgentDraft>(
    """{"id":"dr_1","agent_id":"refunds-1","status":"confirmed","version":"v1","label":"Refunds",
        "cards":[{"grants":[{"action":"create_refund"}],"lines":["Refund a payment, up to €5 each","Up to €20 a day"]}]}""",
  )

  private fun state(live: LiveAnswer?) = AppState(
    mode = Mode.kiff, ready = true, kiffAccount = Account("t_01", "acme", capabilities = listOf("members.manage")),
    coachChecked = true, coachCloudId = "setup_1", coachAgent = "setup-acme", coachChat = "s1",
    grants = listOf(grant), setupDrafts = mapOf("dr_1" to draft),
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

  /** Coach not started, on a phone with no fingerprint yet: Start says Coach needs approvals and what to do, never a button that would fail. */
  private fun notStarted(readiness: ApprovalReadiness) = rule.setShown {
    TafsTheme {
      CompositionLocalProvider(LocalBiometry provides "your fingerprint", LocalApprovalReadiness provides readiness) {
        CoachScreen(
          AppState(mode = Mode.kiff, ready = true, kiffAccount = Account("t_01", "acme", capabilities = listOf("members.manage")), coachChecked = true),
          actions, loadCoach = {}, onBack = {},
        )
      }
    }
  }

  /** The tools and their Card under one fingerprint (`tools_and_cards`): the grant waits on its added tools, the card carries a draft. */
  private fun showToolsAndCards(drafts: Map<String, bot.tafs.app.kiff.AgentDraft>) = rule.setShown {
    // As the planque client marks a grant whose digest it checked against its request.
    val pending = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>(
      """{"id":"g1","status":"pending","text":"Read your payments and refund one.","digest":"d1","request":{"server":"https://mcp.stripe.example"}}""",
    ).copy(digestMatches = true)
    val open = KiffClient.json.decodeFromString<bot.tafs.app.kiff.AgentDraft>(
      """{"id":"dr_1","agent_id":"refunds-1","status":"draft","version":"v1","label":"Refunds",
          "cards":[{"grants":[{"action":"create_refund"}],"lines":["Refund a payment, up to €5 each","Up to €20 a day"]}]}""",
    )
    val s = state(null).copy(
      grants = listOf(pending), setupDrafts = drafts.mapValues { open }, planqueReady = true,
      coachComponents = listOf(ShownComponent(t0.plusSeconds(10), "s1", "r1", connect)),
    )
    TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { CoachScreen(s, actions, loadCoach = {}, onBack = {}) } }
  }

  @Test fun theToolsAndTheirCardUnderOneFingerprintShowTheCardsLimitsBeforeApprove() {
    showToolsAndCards(mapOf("dr_1" to draft))
    // The Card's own lines, KIFF's, on the card that signs them, while the grant still waits on its tools.
    rule.onNodeWithText(Words.Card.itsCard.uppercase()).assertExists()
    rule.onNodeWithText("Up to €20 a day").assertExists()
    rule.onNodeWithText(Words.Approval.approveWith("your fingerprint")).assertIsEnabled()
  }

  @Test fun untilTheCardIsReadApproveWaits() {
    showToolsAndCards(emptyMap())
    rule.onNodeWithText(Words.Card.itsCard.uppercase()).assertExists()
    rule.onNodeWithText(Words.Approval.approveWith("your fingerprint")).assertIsNotEnabled()
  }

  @Test fun beforeTheFirstTafAPhoneWithNoFingerprintSaysCoachNeedsApprovals() {
    notStarted(ApprovalReadiness.NoneEnrolled)
    rule.onNodeWithText(ApprovalWords.FIRST_TAF).assertIsDisplayed()
    rule.onNodeWithText(ApprovalWords.cantApprove(ApprovalReadiness.NoneEnrolled)).assertIsDisplayed()
    rule.onNodeWithText(ApprovalWords.OPEN_SETTINGS).assertIsDisplayed()
    rule.onNodeWithText(ApprovalWords.CHECK_AGAIN).assertIsDisplayed()
  }

  @Test fun beforeTheFirstTafAPhoneThatCantApproveSaysAnotherDevice() {
    notStarted(ApprovalReadiness.Unsupported)
    rule.onNodeWithText(ApprovalWords.FIRST_TAF).assertIsDisplayed()
    rule.onNodeWithText(ApprovalWords.cantApprove(ApprovalReadiness.Unsupported)).assertIsDisplayed()
    assertEquals(0, rule.onAllNodesWithText(ApprovalWords.OPEN_SETTINGS).fetchSemanticsNodes().size)
    assertEquals(0, rule.onAllNodesWithText(ApprovalWords.CHECK_AGAIN).fetchSemanticsNodes().size)
  }

  @Test fun aTafMadeWithNoPlanSaysSoWithChooseAPlan() {
    val plans = mutableListOf<Unit>()
    val made = component("""{"type":"result","id":"r9","text":"Refunds is ready.","taf":{"agent":"refunds-1","name":"Refunds"}}""")
    val answer = KiffClient.json.decodeFromString<CardAnswer>("""{"state":"answered","outcome":{"taf_id":"taf_1","plan_needed":{"words":"Choose a plan to start it."}}}""")
    val listed = bot.tafs.app.model.Taf(id = "refunds-1", name = "Refunds", job = "", tools = emptyList(), colorIndex = 1, card = bot.tafs.app.model.Card(emptyList(), "You"), cloudId = "taf_1")
    val withResult = state(null).copy(tafs = listOf(listed), coachComponents = listOf(ShownComponent(t0, "s1", "r9", made, answer)), chats = emptyMap())
    val withPlan = TafActions(
      answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
      sendChat = { _, _, _, _ -> }, issuer = "KIFF",
      doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
      coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}, openPlan = { plans += Unit }),
      connections = ConnectionActions({}, { false }),
    )
    rule.setShown {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { CoachScreen(withResult, withPlan, loadCoach = {}, onBack = {}) } }
    }
    rule.onNodeWithText("Choose a plan to start it.").assertIsDisplayed()
    rule.onNodeWithText(bot.tafs.app.model.Words.Usage.choosePlan).performClick()
    rule.waitForIdle()
    assertEquals(1, plans.size)
  }

  @Test fun theHeaderIsOneRowAndThePlanIsTafsCloudsLine() {
    show(null)
    rule.onNodeWithText("Coach").assertIsDisplayed()
    rule.onNodeWithText("Step 3 of 6 · Your rules").assertIsDisplayed()
    // Answered: kiff/agents' word and the Card that was signed (its draft confirmed), and no buttons left on it.
    rule.onNodeWithText("Approved · Refunds' Card", substring = true).assertIsDisplayed()
    hold()
  }

  @Test fun anAnsweredApprovalOpensOnATapToWhatWasApprovedAndFoldsBack() {
    show(null)
    // Folded: the line names the Card that was signed, and KIFF's own lines for it stay under it (the owner, 12 Nov: the line
    // said the key's words, not what was signed). Never planque's grant as it is now, which a later approval can widen.
    rule.onNodeWithText("Approved · Refunds' Card", substring = true).assertIsDisplayed()
    rule.onNodeWithText("Up to €20 a day").assertIsDisplayed()
    rule.onNodeWithText("Read your payments and refund one.").assertDoesNotExist()
    rule.onNodeWithText("Approved · Refunds' Card", substring = true).performClick()
    // Open: the Card in full, at the version approved, with nothing to answer.
    rule.onNodeWithText("Refund a payment, up to €5 each").assertIsDisplayed()
    rule.onNodeWithText("Up to €20 a day").assertIsDisplayed()
    rule.onNodeWithText("Read your payments and refund one.").assertDoesNotExist()
    rule.onNodeWithText("Approve with your fingerprint").assertDoesNotExist()
    rule.onNodeWithText("Decline").assertDoesNotExist()
    hold()
    rule.onNodeWithText("Approved · Refunds' Card", substring = true).performClick()
    // Folded again: the line and its Card's lines.
    rule.onNodeWithText("Up to €20 a day").assertIsDisplayed()
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
    rule.onNodeWithContentDescription("New conversation").assertIsDisplayed()
    rule.onNodeWithText("Previous conversations").assertIsDisplayed()
    hold()
  }

  @Test fun newConversationIsAlwaysEnabledAndOpensANewOneFromAConversationWithMessages() {
    var opened = 0
    rule.setShown {
      TafsTheme {
        CompositionLocalProvider(LocalBiometry provides "your fingerprint") {
          CoachScreen(state(null), actions, loadCoach = {}, onBack = {}, chats = bot.tafs.app.views.CoachChatActions(newChat = { opened += 1 }))
        }
      }
    }
    rule.onNodeWithContentDescription("New conversation").assertIsEnabled().performClick()
    rule.waitForIdle()
    assertEquals(1, opened)
  }

  @Test fun onAnEmptyConversationNewConversationIsEnabledAndKeepsIt() {
    var opened = 0
    val empty = state(null).copy(coachFresh = true, coachChat = null, coachComponents = emptyList(), chats = emptyMap())
    rule.setShown {
      TafsTheme {
        CompositionLocalProvider(LocalBiometry provides "your fingerprint") {
          CoachScreen(empty, actions, loadCoach = {}, onBack = {}, chats = bot.tafs.app.views.CoachChatActions(newChat = { opened += 1 }))
        }
      }
    }
    // The direct button stays enabled even when the conversation is already empty.
    rule.onNodeWithContentDescription("New conversation").assertIsEnabled().performClick()
    rule.waitForIdle()
    assertEquals(0, opened)
    rule.onNodeWithText("Coach").assertIsDisplayed()
  }

  @Test fun theLiveLineIsTafsCloudsAndNoThinkingTextShows() {
    show(LiveAnswer(started = Instant.now(), thinking = "The owner wants refunds capped, I should", state = LiveState("working", "Drafting the Card")))
    rule.onNodeWithText("Drafting the Card…").assertIsDisplayed()
    rule.onNodeWithText("The owner wants refunds capped, I should").assertDoesNotExist()
    hold()
  }

  /** The store's screenshots open Coach from the sample: what Coach is, as a new account first sees it, never a spinner. */
  @Test fun theSampleForTheStoresScreenshotsShowsCoachsIntro() {
    val sample = bot.tafs.app.app.AppState(mode = bot.tafs.app.app.Mode.sample, ready = true, screenshots = true)
    rule.setShown {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { CoachScreen(sample, actions, loadCoach = {}, onBack = {}) } }
    }
    rule.onNodeWithText("Name an app for your taf", substring = true).assertIsDisplayed()
  }
}
