package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.ErrorWords
import bot.tafs.app.model.ReportResult
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reporting an answer Coach or a taf wrote (docs/chat.md): by tafs cloud's id for it, in the chat it's in. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportAnswerTest {
  private val t0 = Instant.parse("2026-11-01T09:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val route = "POST /v1/tafs/taf_1/messages/m_7/report"

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
  }

  private suspend fun TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0.plusMillis(currentTime) }, start = false, scope = backgroundScope)
      .also { it.restore() }
  }

  private val answer = ChatMessage(t0, ChatMessage.From.taf, "Refunded Ana €40.", session = "s1", serverId = "m_7")

  private fun sent(): Map<String, String> =
    bot.tafs.app.kiff.KiffClient.json.parseToJsonElement(String(transport.requestsTo(route).single().body!!)).jsonObject
      .mapValues { (_, v) -> (v as kotlinx.serialization.json.JsonPrimitive).content }

  @Test fun anAnswerIsReportedByItsIdWithItsReasonNoteAndChat() = runTest {
    reads()
    transport.answer(route, 200, """{"report":{"id":"rep_1","at":"2026-11-01T09:01:00Z","new":true}}""")
    val m = model()
    assertEquals(ReportResult.Sent(first = true), m.reportAnswer("refunds-1", answer, "wrong", "  It refunded the wrong order.  "))
    assertEquals(mapOf("reason" to "wrong", "note" to "It refunded the wrong order.", "session" to "s1"), sent())
  }

  @Test fun aBlankNoteIsLeftOutAndAnAnswerReportedBeforeSaysSo() = runTest {
    reads()
    transport.answer(route, 200, """{"report":{"id":"rep_1","at":"2026-11-01T09:01:00Z","new":false}}""")
    val m = model()
    assertEquals(ReportResult.Sent(first = false), m.reportAnswer("refunds-1", answer, "offensive", "   "))
    assertEquals(mapOf("reason" to "offensive", "session" to "s1"), sent())
  }

  @Test fun aReportThatDidntGoSaysWhyInTheAppsWords() = runTest {
    reads()
    transport.answer(route, 404, """{"error":"no such answer"}""")
    val m = model()
    val result = m.reportAnswer("refunds-1", answer, "harmful", null)
    assertTrue(result is ReportResult.NotSent && !result.why.contains("no such answer"))
  }

  @Test fun onlyAnAnswerOfTheTafsWithAnIdIsSent() = runTest {
    reads()
    val m = model()
    assertEquals(ReportResult.NotSent(ErrorWords.CHANGED), m.reportAnswer("refunds-1", answer.copy(from = ChatMessage.From.owner), "other", null))
    assertEquals(ReportResult.NotSent(ErrorWords.CHANGED), m.reportAnswer("refunds-1", answer.copy(serverId = null), "other", null))
    assertTrue(transport.requests.none { it.url.contains("/report") })
  }
}
