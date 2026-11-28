@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.watch.WatchRelay
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import java.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coach from the Wear OS watch (docs/watch-wear.md): text only, only after the AI consent given on the phone, only to a Coach
 * started on the phone (never a fingerprint from the wrist), and Coach's answer short, the rest on the phone.
 */
class WatchCoachTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val now = 1_763_244_000_000L

  private fun consent(given: Boolean) =
    """{"consent":{"given":$given,"version":"1","words":{"title":"Your data and the AI","lines":["tafs uses AI."],"allow":"Allow","not_now":"Not now","withdraw":"Withdraw","given":"AI use: allowed"}}}"""

  private fun reads(given: Boolean) {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/ai-consent", 200, consent(given))
  }

  private suspend fun TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, start = false, scope = backgroundScope).also { it.restore() }
  }

  private suspend fun WatchRelay.ask(r: WatchRequest): WatchReply = WatchLink.encode(r).let { (path, data) -> answer(path, data) }

  @Test fun withoutTheAIConsentNothingIsSent() = runTest {
    reads(given = false)
    val m = model()
    val before = transport.requests.size
    val reply = WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Coach(now, "Pause refunds until Monday"))
    assertEquals(WatchReply.Failed("Before you talk to Coach, allow AI use in tafs on your phone."), reply)
    // Restoring the session may register the phone's own keys; the watch's ask sends nothing.
    assertTrue(transport.requests.drop(before).none { it.method == "POST" })
  }

  /**
   * The phone held the AI consent as given, and tafs cloud refuses Coach's turn for it (`ai_consent_required`: the consent asked
   * again, e.g. for new words): the watch says so in its consent line, never a bare "Open on phone".
   */
  @Test fun coachRefusedForTheAIConsentSaysSoOnTheWatch() = runTest {
    reads(given = true)
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach"}}""")
    val m = model()
    m.loadCoach()
    assertEquals("setup_1", m.state.value.coachCloudId)
    val refused = """{"given":false,"version":"2","words":{"title":"Your data and the AI","lines":["tafs uses AI."],"allow":"Allow","not_now":"Not now","withdraw":"Withdraw","given":"AI use: allowed"}}"""
    transport.answer("POST /v1/tafs/setup_1/messages", 403, """{"error":"Allow AI use first","code":"ai_consent_required","consent":$refused}""")
    val reply = WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Coach(now, "Pause refunds until Monday"))
    assertEquals(WatchReply.Failed("Before you talk to Coach, allow AI use in tafs on your phone."), reply)
  }

  @Test fun aCoachNotStartedOnThePhoneIsStartedThereNeverFromTheWrist() = runTest {
    reads(given = true)
    val m = model()
    assertNull(m.state.value.coachCloudId)
    val before = transport.requests.size
    val asked = auth.asked.size
    val reply = WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Coach(now, "Pause refunds until Monday"))
    assertEquals(WatchReply.Failed("Open on phone"), reply)
    assertTrue("no fingerprint asked from the wrist", auth.asked.size == asked && auth.fingerprints.isEmpty())
    assertTrue(transport.requests.drop(before).none { it.method == "POST" })
  }

  @Test fun theSampleIsNotCoachFromTheWrist() = runTest {
    assertEquals(WatchReply.SignedOut, WatchRelay(model = { testModel() }, now = { now }).ask(WatchRequest.Coach(now, "hi")))
  }

  /** The 8 s run from the request's start: a slow send leaves less for the answer, so the watch's 10 s is never passed. */
  @Test fun theWaitForCoachCountsTheSendToo() {
    assertEquals(8_000, WatchRelay.waitLeft(startedMs = now, nowMs = now, budgetMs = 8_000))
    assertEquals("a 3 s send leaves 5 s", 5_000, WatchRelay.waitLeft(now, now + 3_000, 8_000))
    assertEquals("a send slower than the budget leaves nothing to wait", 0, WatchRelay.waitLeft(now, now + 9_000, 8_000))
    assertEquals("a clock set back never waits longer than the budget", 8_000, WatchRelay.waitLeft(now, now - 60_000, 8_000))
  }

  @Test fun coachsAnswerIsItsNewestSinceTheMessage() {
    fun msg(at: Long, from: ChatMessage.From, text: String, kind: ChatMessage.Kind = ChatMessage.Kind.message) =
      ChatMessage(at = Instant.ofEpochMilli(at), from = from, text = text, session = "s1", kind = kind)
    val chat = listOf(
      msg(now - 60_000, ChatMessage.From.taf, "An older answer."),
      msg(now, ChatMessage.From.owner, "Pause refunds until Monday"),
      msg(now + 1_000, ChatMessage.From.taf, "Working on it", ChatMessage.Kind.kickoff),
      msg(now + 2_000, ChatMessage.From.taf, "Done. Refunds pause until Monday."),
    )
    val asked = "Pause refunds until Monday"
    assertEquals("Done. Refunds pause until Monday.", WatchRelay.coachAnswer(AppState(chats = mapOf(COACH_CHAT to chat)), asked))
    assertNull(WatchRelay.coachAnswer(AppState(chats = mapOf(COACH_CHAT to chat.take(3))), asked))
    // By the chat's order, not the clocks: tafs cloud's times behind the phone's still find the answer.
    val behind = chat.map { it.copy(at = it.at.minusSeconds(3_600)) }
    assertEquals("Done. Refunds pause until Monday.", WatchRelay.coachAnswer(AppState(chats = mapOf(COACH_CHAT to behind)), asked))
    assertNull("no message of the owner's with those words: no answer", WatchRelay.coachAnswer(AppState(chats = mapOf(COACH_CHAT to chat)), "Something else"))
    // Still at work: not done, so the watch isn't given half an answer.
    assertTrue(!WatchRelay.coachDone(AppState(chatSending = setOf(COACH_CHAT))))
    assertTrue(WatchRelay.coachDone(AppState()))
  }
}
