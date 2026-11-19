package bot.tafs.app

import bot.tafs.app.app.AppState
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.AgentDraft
import bot.tafs.app.kiff.CardAnswer
import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.SetupShape
import bot.tafs.app.model.SetupShape.CardState
import bot.tafs.app.model.SetupShape.Step
import bot.tafs.app.model.SetupShape.Tool
import bot.tafs.app.model.Words
import bot.tafs.app.model.setupShape
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Taking shape" panel beside Coach's conversation on a wide screen, as iPad's: the plan, the tools and the Card, read from
 * what the app already holds.
 */
class SetupShapeTest {
  private fun at(s: Long): Instant = Instant.ofEpochSecond(1_800_000_000L + s)

  private fun shown(json: String, t: Long, answer: CardAnswer? = null) =
    ShownComponent(at(t), "s1", "r1", KiffClient.json.decodeFromString<SetupComponent>(json), answer)

  private val request = """{"server":"mcp.stripe.com","agent":"refunds-x","tools":[{"tool":"refund_payment","name":"refund_payment_k1"},{"tool":"list_payments","read_only":true}]}"""

  private fun grant(status: String, signedIn: Boolean = false, reason: String? = null, statusWords: String? = null): Grant =
    KiffClient.json.decodeFromString(
      """{"id":"g_01","status":"$status","vendor":"Stripe","text":"Connect Stripe.","digest":"d","request":$request,"signed_in":$signedIn""" +
        (reason?.let { ""","reason":"$it"""" } ?: "") + (statusWords?.let { ""","status_words":"$it"""" } ?: "") + "}",
    )

  private fun draft(status: String, id: String = "drf_1", noTools: Boolean = false, label: String? = null): AgentDraft =
    KiffClient.json.decodeFromString(
      """{"id":"$id","agent_id":"refunds-x","status":"$status","version":"v1","no_tools":$noTools""" + (label?.let { ""","label":"$it"""" } ?: "") +
        ""","cards":[{"grants":[{"action":"refund_payment","per_action_max":[{"quantity":"sum(amount)","max":15000}]}],"aggregates":[{"quantity":"sum(amount)","limit":200000,"window":"calendar_day"}]}]}""",
    )

  private fun thread(components: List<ShownComponent>, messages: List<ChatMessage> = emptyList(), grants: List<Grant> = emptyList()) =
    SetupShape.Thread(components, messages, grants)

  private fun shape(
    components: List<ShownComponent>,
    messages: List<ChatMessage> = emptyList(),
    grants: List<Grant> = emptyList(),
    drafts: Map<String, AgentDraft> = emptyMap(),
    waiting: String? = null,
    states: Map<String, CardState> = emptyMap(),
  ) = SetupShape.of(thread(components, messages, grants), drafts, waiting, states)

  /** An empty conversation has nothing to show. */
  @Test fun nothingYet() {
    val empty = shape(emptyList())
    assertTrue(empty.isEmpty)
    assertNull(empty.name)
    assertNull(empty.title)
    assertTrue(AppState().setupShape.isEmpty)
  }

  /** Coach's plan, as it keeps it: its step at work needs the owner while a card waits. */
  @Test fun thePlanAsCoachKeepsIt() {
    val plan = listOf(shown("""{"type":"steps","id":"plan","title":"Connect Stripe","steps":[{"label":"Read what you want","status":"done"},{"label":"Sign in at Stripe","status":"active"},{"label":"Choose the tools","status":"pending"},{"label":"Test mode","status":"skipped"},{"label":"Check it","status":"failed"}]}""", 1))
    val waiting = shape(plan, waiting = "connect")
    assertEquals("Connect Stripe", waiting.title)
    assertNull("no taf handed over yet", waiting.name)
    assertFalse(waiting.isEmpty)
    assertEquals(listOf(Step.State.done, Step.State.needsYou, Step.State.toDo, Step.State.skipped, Step.State.failed), waiting.steps.map { it.state })
    assertEquals(listOf("Read what you want", "Sign in at Stripe", "Choose the tools", "Test mode", "Check it"), waiting.steps.map { it.label })

    val working = shape(plan)
    assertEquals("nothing waits for the owner: Coach is at it", Step.State.now, working.steps[1].state)
  }

  /** Only the first step at work waits for the owner; another at work is Coach's. */
  @Test fun oneStepWaits() {
    val plan = listOf(shown("""{"type":"steps","id":"plan","steps":[{"label":"A","status":"active"},{"label":"B","status":"active"}]}""", 1))
    assertEquals(listOf(Step.State.needsYou, Step.State.now), shape(plan, waiting = "x").steps.map { it.state })
  }

  /** No plan shown: what Coach asked of the owner, and where each stands. */
  @Test fun withoutAPlanTheAsks() {
    val messages = listOf(
      ChatMessage(at(0), ChatMessage.From.owner, "Refunds, please", "s1"),
      ChatMessage(at(5), ChatMessage.From.owner, "[confirm which] live", "s1"),
    )
    val components = listOf(
      shown("""{"type":"confirm","id":"which","text":"Which account?","options":[{"value":"live","label":"Live"}]}""", 2),
      shown("""{"type":"notice","id":"note","tone":"info","text":"Reading Stripe's tools"}""", 6),
      shown("""{"type":"approval","id":"connect","grant":"g_01"}""", 7),
    )
    val grants = listOf(grant("pending"))
    val s = shape(components, messages, grants, waiting = "connect", states = mapOf("connect" to CardState.NeedsYou))
    assertEquals("a notice asks nothing", listOf(
      Step("Which account?", Step.State.done),
      Step(Words.Coach.Shape.approveNamed("Stripe"), Step.State.needsYou),
    ), s.steps)
    assertNull(s.title)

    assertEquals(Step.State.skipped, shape(components, messages, grants, states = mapOf("connect" to CardState.Declined)).steps.last().state)
    assertEquals(Step.State.failed, shape(components, messages, grants, states = mapOf("connect" to CardState.Failed("planque is down"))).steps.last().state)
    assertEquals(Step.State.done, shape(components, messages, grants, states = mapOf("connect" to CardState.Done)).steps.last().state)
    assertEquals(Step.State.now, shape(components, messages, grants, states = mapOf("connect" to CardState.Working)).steps.last().state)
    assertEquals(Step.State.toDo, shape(components, messages, grants).steps.last().state)

    // A question the conversation went on without, and a sign-in, by its vendor.
    val unanswered = listOf(
      shown("""{"type":"confirm","id":"which","text":"Which account?"}""", 2),
      shown("""{"type":"sign_in","id":"in","service":"mcp.stripe.com"}""", 3),
      shown("""{"type":"approval","id":"other"}""", 4),
    )
    assertEquals(listOf(
      Step("Which account?", Step.State.skipped),
      Step(Words.Coach.SignIn.at("mcp.stripe.com"), Step.State.toDo),
      Step(Words.Coach.Shape.approveNamed(Words.Coach.Shape.theConnection), Step.State.toDo),
    ), shape(unanswered).steps)
  }

  /** The tools: each grant the conversation names, once, with where it stands; a connected one lists what the taf calls through it. */
  @Test fun theToolsWhereTheyStand() {
    val components = listOf(
      shown("""{"type":"sign_in","id":"in","grant":"g_01","service":"mcp.stripe.com"}""", 1),
      shown("""{"type":"approval","id":"connect","grant":"g_01"}""", 2),
      shown("""{"type":"approval","id":"other","grant":"g_unknown"}""", 3),
    )
    fun tools(g: Grant) = shape(components, grants = listOf(g)).tools
    assertEquals("once, and a grant planque hasn't shown is left out", listOf(Tool("g_01", "Stripe", Tool.State.Waiting, emptyList())), tools(grant("pending")))
    assertEquals(Tool.State.SignedIn, tools(grant("listed", signedIn = true)).first().state)
    assertEquals(Tool.State.SignedIn, tools(grant("pending", signedIn = true)).first().state)
    assertEquals(Tool.State.Failed(null), tools(grant("failed")).first().state)
    // planque's words for its state, in the person's language; never its English `reason`, which is for the log.
    assertEquals(Tool.State.Failed("Getrennt"), tools(grant("disconnected", reason = "KIFF answered 403: approval_refused (profile_not_approved)", statusWords = "Getrennt")).first().state)
    assertEquals(Tool.State.Failed(null), tools(grant("disconnected", reason = "KIFF answered 403: approval_refused")).first().state)
    val connected = tools(grant("connected")).first()
    assertEquals(Tool.State.Connected, connected.state)
    assertEquals(listOf("refund_payment", "list_payments"), connected.actions)
  }

  /**
   * The Card: the latest draft an approval names, a draft until confirmed; Coach's own no-tools draft isn't the taf's Card. The
   * name comes with the hand-over.
   */
  @Test fun theCardAndTheName() {
    val components = mutableListOf(
      shown("""{"type":"approval","id":"connect","grant":"g_01","draft":"drf_1"}""", 1),
      shown("""{"type":"approval","id":"setup","grant":"g_02","draft":"drf_s"}""", 2),
    )
    val drafts = mapOf("drf_1" to draft("draft"), "drf_s" to draft("confirmed", id = "drf_s", noTools = true))
    val card = assertNotNullAnd(shape(components, drafts = drafts).card)
    assertFalse(card.confirmed)
    assertEquals("refunds-x", card.agent)
    assertEquals(Words.Card.theTafsCard, card.title)
    assertEquals(drafts.getValue("drf_1").cardLines.flatten(), card.lines)
    assertEquals(2, card.lines.size)
    // The action in words, never its id.
    assertTrue(card.lines.first(), card.lines.first().startsWith("refund payment"))

    val confirmed = shape(components, drafts = mapOf("drf_1" to draft("confirmed", label = "Refunds assistant")))
    assertEquals(true, confirmed.card?.confirmed)
    assertEquals(Words.Coach.cardOf("Refunds assistant"), confirmed.card?.title)
    assertNull("a draft KIFF hasn't shown", shape(components).card)

    components += shown("""{"type":"result","id":"done","taf":{"agent":"refunds-x","name":"Refunds","job":"refunds","draft":"drf_1"},"text":"Done."}""", 3)
    assertEquals("Refunds", shape(components).name)
  }

  /** A card's state, from planque's grant and the owner's answer in the thread, as iOS's setupCardState. */
  @Test fun aCardsState() {
    val approval = shown("""{"type":"approval","id":"connect","grant":"g_01"}""", 1)
    fun state(answer: String?, g: Grant? = grant("pending"), connecting: String? = null): CardState {
      val shown = answer?.let { approval.copy(answer = CardAnswer(words = it)) } ?: approval
      return SetupShape.Thread(listOf(shown), emptyList(), listOfNotNull(g), connecting).cardState(shown.component)
    }
    assertEquals(CardState.NeedsYou, state(null))
    assertEquals(CardState.Working, state(null, connecting = "g_01"))
    assertEquals(CardState.Done, state("Approved"))
    assertEquals(CardState.Declined, state("Declined"))
    assertEquals(CardState.Failed("planque is down"), state("Approve · it didn't go through: planque is down"))
    // A failed grant's card says planque's words for its state, never planque's English `reason`.
    assertEquals(CardState.Failed("Échec"), state(null, grant("failed", reason = "vendor said no", statusWords = "Échec")))
    assertEquals(CardState.Failed(null), state(null, grant("failed", reason = "vendor said no")))

    // A sign-in is done once answered, or once planque holds it.
    val signIn = shown("""{"type":"sign_in","id":"in","grant":"g_01"}""", 1)
    assertEquals(CardState.Done, SetupShape.Thread(listOf(signIn), emptyList(), listOf(grant("listed"))).cardState(signIn.component))
    assertEquals(CardState.NeedsYou, SetupShape.Thread(listOf(signIn), emptyList(), listOf(grant("approved"))).cardState(signIn.component))
    val notNow = signIn.copy(answer = CardAnswer(words = "Not now"))
    assertEquals(CardState.Declined, SetupShape.Thread(listOf(notNow), emptyList(), listOf(grant("approved"))).cardState(notNow.component))
  }

  /** The card waiting for the owner: the latest one not answered, and a question the owner wrote past waits no more. */
  @Test fun theCardThatWaits() {
    val components = listOf(
      shown("""{"type":"confirm","id":"which","text":"Which account?"}""", 1),
      shown("""{"type":"approval","id":"connect","grant":"g_01"}""", 2),
    )
    val grants = listOf(grant("pending"))
    assertEquals("connect", SetupShape.Thread(components, emptyList(), grants).waiting?.id)
    assertEquals("answered on this phone, the question before it waits", "which", SetupShape.Thread(components, emptyList(), grants, answered = setOf(components[1].ref)).waiting?.id)
    val wrote = listOf(ChatMessage(at(3), ChatMessage.From.owner, "Actually, wait", "s1"))
    assertEquals("an approval still waits after a message", "connect", SetupShape.Thread(components, wrote, grants).waiting?.id)
    val declined = listOf(components[0], components[1].copy(answer = CardAnswer(words = "Declined")))
    assertNull("written past, and declined", SetupShape.Thread(declined, wrote, grants).waiting)
  }

  /** The app's state reads as the panel shows it: Coach's thread, planque's grants and KIFF's drafts. */
  @Test fun fromTheAppsState() {
    val components = listOf(
      shown("""{"type":"steps","id":"plan","title":"Connect Stripe","steps":[{"label":"Sign in at Stripe","status":"active"}]}""", 1),
      shown("""{"type":"approval","id":"connect","grant":"g_01","draft":"drf_1"}""", 2),
    )
    val state = AppState(
      coachComponents = components,
      chats = mapOf(COACH_CHAT to listOf(ChatMessage(at(0), ChatMessage.From.owner, "Refunds, please", "s1"))),
      grants = listOf(grant("pending")),
      setupDrafts = mapOf("drf_1" to draft("draft")),
    )
    val s = state.setupShape
    assertEquals("Connect Stripe", s.title)
    assertEquals(listOf(Step("Sign in at Stripe", Step.State.needsYou)), s.steps)
    assertEquals(listOf(Tool("g_01", "Stripe", Tool.State.Waiting)), s.tools)
    assertEquals(false, s.card?.confirmed)
    // On its way from this phone, the card waits no more: Coach is at it.
    assertEquals(Step.State.now, state.copy(cardsAnswering = setOf(components[1].ref)).setupShape.steps.single().state)
  }

  private fun <T : Any> assertNotNullAnd(value: T?): T {
    assertNotNull(value)
    return value!!
  }
}
