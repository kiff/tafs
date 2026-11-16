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

  @Test fun coachsAnswerIsItsNewestSinceTheMessage() {
    val since = Instant.ofEpochMilli(now)
    fun msg(at: Long, from: ChatMessage.From, text: String, kind: ChatMessage.Kind = ChatMessage.Kind.message) =
      ChatMessage(at = Instant.ofEpochMilli(at), from = from, text = text, session = "s1", kind = kind)
    val chat = listOf(
      msg(now - 60_000, ChatMessage.From.taf, "An older answer."),
      msg(now, ChatMessage.From.owner, "Pause refunds until Monday"),
      msg(now + 1_000, ChatMessage.From.taf, "Working on it", ChatMessage.Kind.kickoff),
      msg(now + 2_000, ChatMessage.From.taf, "Done. Refunds pause until Monday."),
    )
    assertEquals("Done. Refunds pause until Monday.", WatchRelay.coachAnswer(AppState(chats = mapOf(COACH_CHAT to chat)), since))
    assertNull(WatchRelay.coachAnswer(AppState(chats = mapOf(COACH_CHAT to chat.take(3))), since))
    // Still at work: not done, so the watch isn't given half an answer.
    assertTrue(!WatchRelay.coachDone(AppState(chatSending = setOf(COACH_CHAT))))
    assertTrue(WatchRelay.coachDone(AppState()))
  }
}
