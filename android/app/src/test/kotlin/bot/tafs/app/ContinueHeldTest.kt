package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.Answer
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answering a call KIFF holds asks tafs cloud to carry on the run paused on it (kiff/tafs#206), and the chat then
 * shows the taf working. A fake KIFF and tafs cloud stand in: no real held call is answered, no real run is touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContinueHeldTest {
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val goOn = "POST /v1/tafs/taf_1/holds/ce_1/continue"
  private val conversation = "GET /v1/tafs/taf_1/conversation"

  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay","capabilities":["holds.answer","cards.manage"]}"""
  private val held =
    """{"id":"ce_1","subject":"refunds-1","action":"create_refund","title":"Refund a payment","entity_id":"tc-1","proposal_id":"prop_1","accounting_version":1,
       "breaches":[{"outcome":"per_action_exceeded","reason":"over","quantity":"sum(amount)","limit":10000,"unit":"EUR","scale":2}],
       "parameters":{"amount":15000},"parameter_units":{"amount":{"unit":"EUR","scale":2}},"arguments":{"payment_intent":"pi_1"},
       "created_at":"2026-10-28T09:00:00Z","expires_at":"2099-01-01T00:00:00Z"}"""

  private fun reads(home: String = """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"waiting","card":{"ids":["c1"],"active":true}}],"others":[{"name":"Billing bot","kiff_agent":"billing-x","color":2,"state":"waiting","card":{"ids":["c9"],"active":true}}],"removing":[]}""", exceptions: String = held) {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$exceptions]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer(conversation, 200, """{"messages":[]}""")
    transport.answer("POST /v1/me/card-exceptions/ce_1/approve-once", 200, "{}")
    transport.answer("POST /v1/me/card-exceptions/ce_1/reject", 200, "{}")
  }

  private suspend fun TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0.plusMillis(currentTime) }, start = false, scope = backgroundScope)
      .also { it.restore() }
  }

  @Test fun answeringATafsCallAsksItsRunToGoOnAtOnceByTheCall() = runTest {
    reads()
    transport.answer(goOn, 200, """{"run":"r1","status":"PAUSED"}""")
    val m = model()
    assertTrue(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    runCurrent()
    val request = transport.requestsTo(goOn).single()
    assertEquals("https://cloud.tafs.kiff.dev/v1/tafs/taf_1/holds/ce_1/continue", request.url)
    assertEquals("Bearer at_1", request.headers["Authorization"])
    assertTrue("only KIFF's answer is signed; this carries nothing", request.body == null)
  }

  @Test fun aRefusalGoesOnToo() = runTest {
    reads()
    transport.answer(goOn, 200, """{"run":"r1","status":"PAUSED"}""")
    val m = model()
    assertTrue(m.answer(m.state.value.held.single(), Answer.Refuse))
    runCurrent()
    assertEquals(1, transport.requestsTo(goOn).size)
  }

  @Test fun theChatShowsTheTafWorkingOnceItIsAskedAndStopsOnceItAnswered() = runTest {
    reads()
    transport.answer(goOn, 200, """{"run":"r1","status":"PAUSED"}""")
    val m = model()
    m.answer(m.state.value.held.single(), Answer.ApproveOnce)
    assertTrue("thinking before the run has said anything", m.state.value.thinking("refunds-1"))
    runCurrent()
    assertTrue(m.state.value.thinking("refunds-1"))
    transport.answer(
      conversation, 200,
      """{"messages":[{"at":"2026-10-28T09:00:03Z","from":"taf","text":"Refunded.","session":"s1"}]}""",
    )
    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(listOf("Refunded."), m.state.value.chats["refunds-1"].orEmpty().map { it.text })
    assertFalse(m.state.value.thinking("refunds-1"))
  }

  @Test fun theAnswerStandsWhenTafsCloudCannotBeAsked() = runTest {
    reads()
    transport.answer(goOn, 404, """{"error":"no run of this taf waits on that held call"}""")
    val m = model()
    val call = m.state.value.held.single()
    assertTrue(m.answer(call, Answer.ApproveOnce))
    runCurrent()
    assertEquals("KIFF has it", 1, transport.requestsTo("POST /v1/me/card-exceptions/ce_1/approve-once").size)
    assertEquals(null, m.state.value.lastError)
  }

  @Test fun theAnswerStandsWhenTheNetworkDropsOnTheAsk() = runTest {
    reads()
    transport.drop(goOn)
    val m = model()
    assertTrue(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    runCurrent()
    assertEquals(null, m.state.value.lastError)
  }

  @Test fun anotherAgentsCallHasNoRunHereToGoOn() = runTest {
    reads(exceptions = held.replace("refunds-1", "billing-x"))
    val m = model()
    assertTrue(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    runCurrent()
    assertTrue(transport.requestsTo(goOn).isEmpty())
    assertFalse(m.state.value.thinking("billing-x"))
  }

  @Test fun aCallNobodyConfirmedAsksNothing() = runTest {
    reads()
    auth.confirms = false
    val m = model()
    assertFalse(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    runCurrent()
    assertTrue(transport.requestsTo(goOn).isEmpty())
    assertFalse(m.state.value.thinking("refunds-1"))
  }
}
