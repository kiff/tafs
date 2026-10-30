package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.UsageWords
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Asking an admin for tasks, and an admin's answer (as iOS's Usage). A fake KIFF stands in: no real request is made or answered. */
class TaskRequestsTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private fun request(id: String, status: String, note: String? = null, reason: String = "") =
    """{"id":"$id","asked_by":"user_bo","pack":"rayo","reason":"$reason","status":"$status","created_at":"2026-10-27T09:00:00Z","expires_at":"2026-11-01T00:00:00Z"${note?.let { ""","note":"$it"""" } ?: ""}}"""

  private fun usage(canAdd: Boolean, vararg requests: String) =
    """{"plan":"team","period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},"tasks":{"counted":25,"included":25,"left":0},
       "can_add_tasks":$canAdd,"store":{"packs":[{"id":"rayo","name":"Rayo","line":"Rayo · 70 tasks","tasks":70,"months":12}]},
       "requests":[${requests.joinToString(",")}]}"""

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
  }

  private fun decoded(json: String) = KiffClient.json.decodeFromString(bot.tafs.app.kiff.Usage.serializer(), json)

  // MARK: The words

  @Test fun aMemberSeesTheirRequestWaitingThenTheAnswerInWords() {
    val waiting = decoded(usage(false, request("r1", "open")))
    assertEquals("r1", UsageWords.waiting(waiting)?.id)
    assertNull(UsageWords.lastAnswer(waiting))
    assertEquals("An admin added Rayo · 70 tasks.", UsageWords.lastAnswer(decoded(usage(false, request("r1", "approved")))))
    assertEquals("An admin declined Rayo · 70 tasks: Next month", UsageWords.lastAnswer(decoded(usage(false, request("r1", "declined", note = "Next month")))))
    assertEquals("Your request for Rayo · 70 tasks ended with the period.", UsageWords.lastAnswer(decoded(usage(false, request("r1", "expired")))))
  }

  @Test fun onlySomeoneWhoMayAddTasksSeesTheOpenRequests() {
    assertEquals(listOf("r1"), UsageWords.openRequests(decoded(usage(true, request("r1", "open"), request("r0", "declined")))).map { it.id })
    assertTrue(UsageWords.openRequests(decoded(usage(false, request("r1", "open")))).isEmpty())
    assertNull("an admin has no waiting request of their own", UsageWords.waiting(decoded(usage(true, request("r1", "open")))))
  }

  // MARK: Asking and taking it back

  @Test fun askingSendsThePackAndTheReasonThenReadsUsageAgain() = runTest {
    reads()
    transport.answer("POST /v1/me/pack-requests", 200, request("r1", "open"))
    transport.answer("GET /v1/me/usage", 200, usage(false, request("r1", "open")))
    val m = model()
    assertTrue(m.askForPack("rayo", "  For the refunds backlog  "))
    val body = KiffClient.json.parseToJsonElement(String(transport.requestsTo("POST /v1/me/pack-requests").single().body!!)).jsonObject
    assertEquals("rayo", body.getValue("pack").jsonPrimitive.content)
    assertEquals("For the refunds backlog", body.getValue("reason").jsonPrimitive.content)
    assertEquals("r1", UsageWords.waiting(m.state.value.usage!!)?.id)
  }

  @Test fun aRequestKiffRefusedSaysWhy() = runTest {
    reads()
    transport.answer("POST /v1/me/pack-requests", 409, """{"error":"you already asked"}""")
    val m = model()
    assertFalse(m.askForPack("rayo", ""))
    assertEquals("The request didn't go: you already asked", m.state.value.tasksMessage)
  }

  @Test fun aMemberTakesTheirRequestBack() = runTest {
    reads()
    transport.answer("POST /v1/me/pack-requests/r1/withdraw", 200, request("r1", "withdrawn"))
    transport.answer("GET /v1/me/usage", 200, usage(false, request("r1", "withdrawn")))
    val m = model()
    m.withdrawRequest("r1")
    assertEquals(1, transport.requestsTo("POST /v1/me/pack-requests/r1/withdraw").size)
    assertNull(UsageWords.waiting(m.state.value.usage!!))
  }

  // MARK: An admin declines

  @Test fun decliningIsConfirmedAndSignedWithTheNote() = runTest {
    reads()
    transport.answer("POST /v1/me/pack-requests/r1/decline", 200, request("r1", "declined", note = "Next month"))
    transport.answer("GET /v1/me/usage", 200, usage(true, request("r1", "declined", note = "Next month")))
    val m = model()
    assertTrue(m.declineRequest("r1", " Next month "))
    assertEquals(listOf("Decline the request"), auth.asked)
    val decline = transport.requestsTo("POST /v1/me/pack-requests/r1/decline").single()
    assertNotNull("KIFF takes a decline from a phone only signed", decline.headers["KIFF-Device-Signature"])
    assertEquals("Next month", KiffClient.json.parseToJsonElement(String(decline.body!!)).jsonObject.getValue("note").jsonPrimitive.content)
  }

  @Test fun aDeclineNobodyConfirmedSendsNothing() = runTest {
    reads()
    auth.confirms = false
    val m = model()
    assertFalse(m.declineRequest("r1", ""))
    assertTrue(transport.requestsTo("POST /v1/me/pack-requests/r1/decline").isEmpty())
  }
}
