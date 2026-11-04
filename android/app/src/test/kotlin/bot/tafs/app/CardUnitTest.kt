package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Card asked to take a currency it has no limit for (KIFF's `card_unit` exception): drawn as tafs cloud sends it, never as a
 * held call to approve once; Coach talks it over, and Dismiss signs KIFF's ordinary refusal against the exception as it is now.
 */
class CardUnitTest {
  private val t0 = Instant.parse("2026-11-04T13:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val exception = "GET /v1/me/card-exceptions/exc_1"
  private val reject = "POST /v1/me/card-exceptions/exc_1/reject"

  private val row =
    """{"id":"exc_1","kind":"card_unit","taf":"taf_1","name":"Chargebacks","title":"Answers in USD aren't on its Card",""" +
      """"words":"Its Card's limits are in EUR, and these disputes are in USD, so KIFF refuses each answer.",""" +
      """"history":"4 tries since 2 Nov","action_title":"Add a USD limit with Coach","coach_text":"Add a USD limit for answering disputes.",""" +
      """"dismiss_title":"Dismiss","can_approve_once":false}"""

  private fun kiffException(status: String = "held") =
    """{"id":"exc_1","kind":"card_unit","subject":"chargebacks-1","action":"answer_dispute","status":"$status","created_at":"2026-11-02T12:00:00Z","accounting_version":3}"""

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["members.manage","cards.manage","holds.answer"]}""")
    transport.answer("GET /v1/home", 200,
      """{"tafs":[{"id":"taf_1","name":"Chargebacks","kiff_agent":"chargebacks-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[],"card_unit_exceptions":[$row]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[${kiffException()}]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"chargebacks-1":{"use":true,"oversee":true,"manage":true}}}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
  }

  @Test fun itIsDrawnAsTafsCloudSendsItAndNeverAsAHeldCall() = runTest {
    reads()
    val m = model()
    m.refresh()
    assertEquals(listOf("exc_1"), m.state.value.cardUnitExceptions.map { it.id })
    assertEquals("Add a USD limit with Coach", m.state.value.cardUnitExceptions.single().actionTitle)
    assertFalse(m.state.value.cardUnitExceptions.single().canApproveOnce)
    assertTrue("not a held call with Approve once", m.state.value.held.isEmpty())
  }

  @Test fun dismissSignsKiffsRefusalAgainstTheExceptionAsItIsNow() = runTest {
    reads()
    transport.answer(exception, 200, kiffException())
    transport.answer(reject, 200, "{}")
    val m = model()
    m.refresh()
    m.dismissCardUnit(m.state.value.cardUnitExceptions.single())
    val sent = KiffClient.json.parseToJsonElement(String(transport.requestsTo(reject).single().body!!)).jsonObject
    assertNotNull(sent["signature"])
    assertEquals(1, transport.requestsTo(exception).size)
    assertTrue(m.state.value.dismissingCardUnits.isEmpty())
  }

  @Test fun aRequestThatChangedMeanwhileIsNotDismissed() = runTest {
    reads()
    transport.answer(exception, 200, kiffException(status = "changed"))
    val m = model()
    m.refresh()
    m.dismissCardUnit(m.state.value.cardUnitExceptions.single())
    assertTrue(transport.requestsTo(reject).isEmpty())
    assertEquals("This request changed. Read it again before dismissing it.", m.state.value.lastError)
  }

  @Test fun talkingItOverOpensCoachOnTheTafWithTheProposalWrittenNotSent() = runTest {
    reads()
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach","kiff_agent":"setup-acme"},"tools":{"state":"given"}}""")
    val m = model()
    m.refresh()
    m.loadCoach()
    m.discussCardUnit(m.state.value.cardUnitExceptions.single())
    assertEquals("chargebacks-1", m.state.value.coachImproving)
    assertEquals("Add a USD limit for answering disputes.", m.state.value.drafts[COACH_CHAT])
    assertTrue(m.state.value.coachRequested)
    assertTrue("nothing was sent to Coach", transport.requests.none { it.url.contains("/improve") || it.url.contains("/messages") })
  }
}
