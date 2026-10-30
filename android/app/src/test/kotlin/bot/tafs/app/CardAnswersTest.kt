package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.model.CoachWords
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coach's cards answered as data (docs/chat.md): the answer goes through tafs cloud's route, each card shows the answer the thread
 * holds in kiff/agents' words, and the run that goes on is followed until it ends, so Coach's next words show without leaving.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardAnswersTest {
  private val t0 = Instant.parse("2026-10-30T05:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val conversation = "GET /v1/tafs/setup_1/conversation"

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["members.manage","cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach","kiff_agent":"setup-acme"},"tools":{"state":"given"}}""")
  }

  private fun TestScope.settle() = repeat(20) {
    testScheduler.advanceTimeBy(100)
    testScheduler.runCurrent()
  }

  private fun sse(vararg events: Pair<String, String>) = events.joinToString("\n") { (name, data) -> "event: $name\ndata: $data\n" }

  private suspend fun TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0.plusMillis(currentTime) }, start = false, scope = backgroundScope)
      .also { it.restore() }
  }

  private fun shown(id: String, component: String, answer: String? = null) =
    """{"at":"2026-10-30T05:00:00Z","session":"s1","run":"r1","component":$component${answer?.let { ""","answer":$it""" } ?: ""}}"""

  private val cards = listOf(
    "c1" to """{"type":"confirm","id":"c1","title":"Use these rules?","options":[{"label":"Agree","value":"agree"}]}""",
    "q1" to """{"type":"questions","id":"q1","questions":[{"id":"cur","text":"Which currency?","options":[{"label":"Euro","value":"EUR"}]}]}""",
    "a1" to """{"type":"approval","id":"a1","title":"Connect Stripe","grant":"g1"}""",
    "s1" to """{"type":"sign_in","id":"s1","grant":"g1","service":"mcp.stripe.com"}""",
  )

  @Test fun everyCardTheThreadSaysIsAnsweredShowsItsAnswerAndNoneStaysOpen() = runTest {
    reads()
    val words = mapOf("c1" to "Agree", "q1" to "Which currency? Euro", "a1" to "Approved", "s1" to "Signed in")
    transport.answer(conversation, 200, """{"messages":[],"components":[${cards.joinToString(",") { (id, c) -> shown(id, c, """{"state":"answered","words":"${words[id]}"}""") }}]}""")
    val m = model()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    val read = m.state.value.coachComponents.associate { it.id to it.answer?.let(CoachWords::answered) }
    assertEquals(words, read)
    // The phone answered none of them: what shows is the thread's.
    assertTrue(transport.requests.none { it.url.contains("/components/") })
  }

  @Test fun anAnswerGoesAsDataThenTheCardShowsTheThreadsAnswer() = runTest {
    reads()
    val confirm = cards.first().second
    transport.answer(conversation, 200, """{"messages":[],"components":[${shown("c1", confirm)}]}""")
    transport.answer(conversation, 200, """{"messages":[],"components":[${shown("c1", confirm, """{"state":"answered","words":"Agree"}""")}]}""")
    transport.answer("POST /v1/tafs/setup_1/components/c1/answer", 200, """{"session":"s1","run":"r1","status":"running"}""")
    transport.answer("GET /v1/tafs/setup_1/runs/r1/stream", 200, sse("end" to """{"status":"COMPLETED","stop":"end_turn"}"""))
    val m = model()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    assertNull(m.state.value.coachComponents.single().answer)
    assertTrue(m.answerCard("confirm", "c1", "agree"))
    settle()
    assertEquals("""{"answer":"agree","session":"s1"}""", String(transport.requestsTo("POST /v1/tafs/setup_1/components/c1/answer").single().body!!))
    assertTrue("never the chat's text form", transport.requestsTo("POST /v1/tafs/setup_1/messages").isEmpty())
    assertEquals("Agree", m.state.value.coachComponents.single().answer?.let(CoachWords::answered))
    assertFalse(m.state.value.cardsAnswering.contains("c1"))
  }

  @Test fun anAnswerTafsCloudTookNeverLeavesTheCardSendingEvenIfTheThreadCantBeRead() = runTest {
    reads()
    val signIn = cards[3].second
    transport.answer(conversation, 200, """{"messages":[],"components":[${shown("s1", signIn)}]}""")
    transport.answer(conversation, 503, """{"error":"later"}""")
    transport.answer("POST /v1/tafs/setup_1/components/s1/answer", 200, """{"session":"s1","run":"r1","status":"running"}""")
    val m = model()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    assertTrue(m.answerCard("sign_in", "s1", "signed in"))
    // Taken: not "Sending your answer…", and not open again, though the thread hasn't the answer yet.
    assertFalse(m.state.value.cardsAnswering.contains("s1"))
    assertTrue(m.state.value.cardsTaken.contains("s1"))
    assertNull(m.state.value.coachComponents.single().answer)
  }

  @Test fun aQuestionsCardsAnswerGoesAsItsJson() = runTest {
    reads()
    transport.answer(conversation, 200, """{"messages":[],"components":[${shown("q1", cards[1].second)}]}""")
    transport.answer("POST /v1/tafs/setup_1/components/q1/answer", 200, """{"session":"s1","run":"r1","status":"running"}""")
    val m = model()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.answerCard("questions", "q1", """{"cur":{"value":"EUR"}}""")
    assertEquals("""{"answer":{"cur":{"value":"EUR"}},"session":"s1"}""", String(transport.requestsTo("POST /v1/tafs/setup_1/components/q1/answer").single().body!!))
  }

  @Test fun aCardThatNoLongerWaitsIsReadAgainAndOneNotTakenSaysWhyInTheAppsWords() = runTest {
    reads()
    val confirm = cards.first().second
    transport.answer(conversation, 200, """{"messages":[],"components":[${shown("c1", confirm)}]}""")
    transport.answer("POST /v1/tafs/setup_1/components/c1/answer", 409, """{"error":"the card doesn't wait for an answer any more: read the conversation"}""")
    transport.answer("POST /v1/tafs/setup_1/components/c1/answer", 503, """{"error":"kiff/agents is down"}""")
    val m = model()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    val reads = transport.requestsTo(conversation).size
    assertFalse(m.answerCard("confirm", "c1", "agree"))
    assertTrue(transport.requestsTo(conversation).size > reads)
    assertNull(m.state.value.cardErrors["c1"])
    assertFalse(m.answerCard("confirm", "c1", "agree"))
    assertEquals("Your answer wasn't sent. " + bot.tafs.app.model.ErrorWords.DOWN, m.state.value.cardErrors["c1"])
  }

  @Test fun afterTheCardsApprovalCoachsRunIsFollowedToItsEndAndItsNextWordsShow() = runTest {
    reads()
    val approval = cards[2].second
    transport.answer(conversation, 200, """{"messages":[],"components":[${shown("a1", approval)}]}""")
    transport.answer(
      conversation, 200,
      """{"messages":[{"at":"2026-10-30T05:01:00Z","from":"taf","kind":"message","text":"Refunds 5 is set up.","session":"s1"}],"components":[${shown("a1", approval, """{"state":"answered","words":"Approved"}""")}]}""",
    )
    transport.answer("POST /v1/tafs/setup_1/components/a1/answer", 200, """{"session":"s1","run":"r1","status":"running"}""")
    transport.answer("GET /v1/tafs/setup_1/runs/r1/stream", 200, sse(
      "activity" to """{"tool":"show","call_id":"k1"}""",
      "said" to "{}",
      "end" to """{"status":"COMPLETED","stop":"end_turn"}""",
    ))
    val m = model()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.answerCard("approval", "a1", "approved")
    settle()
    // The view leaves "Thinking…" without the owner leaving it: the run ended, and the thread has Coach's words.
    assertEquals(1, transport.requestsTo("GET /v1/tafs/setup_1/runs/r1/stream").size)
    assertNull(m.state.value.chatLive[COACH_CHAT])
    assertNull(m.state.value.chatAwaiting[COACH_CHAT])
    assertTrue(m.state.value.chats[COACH_CHAT].orEmpty().any { it.text == "Refunds 5 is set up." })
  }
}
