package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.TafWork
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A taf's oldest waiting case and its owner's promise (docs/reliability.md › Its oldest waiting case): the line is tafs cloud's,
 * late past the promise, and whoever manages the taf sets the promise in whole hours or clears it. tafs cloud checks the range
 * and sends the alert; the app keeps no copy of either.
 */
class TafPromiseTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-11-11T10:00:00Z")

  private val waitingWork =
    """{"summary":{"status":"needs_you","line":"1 needs you"},"requests":[],"skipped":[],""" +
      """"oldest_waiting":{"since":"2026-11-11T05:00:00Z","words":"Oldest case waiting 5 h, past the 4 h you set","late":true},""" +
      """"promise":{"hours":4}}"""

  private fun read(json: String) = KiffClient.json.decodeFromString<TafWork>(json)

  @Test fun theOldestWaitingCaseAndThePromiseReadAsTafsCloudSendsThem() {
    val w = read(waitingWork)
    assertEquals("Oldest case waiting 5 h, past the 4 h you set", w.oldestWaiting?.words)
    assertTrue(w.oldestWaiting!!.late)
    assertEquals(Instant.parse("2026-11-11T05:00:00Z"), w.oldestWaiting.since)
    assertEquals(4, w.promise?.hours)
    // Nothing waits and no promise is set: neither is sent.
    val none = read("""{"summary":{"status":"idle","line":""},"requests":[],"skipped":[]}""")
    assertNull(none.oldestWaiting)
    assertNull(none.promise)
    // Waiting, not late.
    assertFalse(read("""{"oldest_waiting":{"words":"Oldest case waiting 1 h"}}""").oldestWaiting!!.late)
  }

  private suspend fun model(capabilities: String = """"cards.manage","holds.answer"""", manage: Boolean = true): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":[$capabilities]}""")
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""",
    )
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":$manage}}}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore(); it.refresh() }
  }

  private fun sentBody(i: Int) = KiffClient.json.parseToJsonElement(String(transport.requestsTo("PUT /v1/tafs/taf_1/promise")[i].body!!))

  @Test fun settingThePromiseSendsWholeHoursOrNullThenReadsTheWorkAgain() = runTest {
    val m = model()
    assertTrue(m.state.value.manages("refunds-1"))
    transport.answer("PUT /v1/tafs/taf_1/promise", 200, """{"taf":"taf_1","promise":{"hours":4}}""")
    transport.answer("GET /v1/tafs/taf_1/work", 200, waitingWork)
    m.setPromise("refunds-1", 4)
    assertEquals(KiffClient.json.parseToJsonElement("""{"hours":4}"""), sentBody(0))
    assertEquals(4, m.state.value.works["refunds-1"]?.promise?.hours)
    // Cleared: null, as tafs cloud takes it.
    transport.answer("PUT /v1/tafs/taf_1/promise", 200, """{"taf":"taf_1","promise":null}""")
    transport.answer("GET /v1/tafs/taf_1/work", 200, """{"summary":{"status":"idle","line":""},"requests":[],"skipped":[]}""")
    m.setPromise("refunds-1", null)
    assertEquals(KiffClient.json.parseToJsonElement("""{"hours":null}"""), sentBody(1))
    assertNull(m.state.value.works["refunds-1"]?.promise)
  }

  @Test fun aRefusalSaysWhyOnTheWorkAndKeepsWhatWasRead() = runTest {
    val m = model()
    assertTrue(m.state.value.manages("refunds-1"))
    transport.answer("PUT /v1/tafs/taf_1/promise", 400, """{"error":"hours must be 1 to 168"}""")
    m.setPromise("refunds-1", 500)
    assertNotNull(m.state.value.workErrors["refunds-1"])
    assertTrue(transport.requestsTo("GET /v1/tafs/taf_1/work").isEmpty())
  }

  @Test fun someoneWhoDoesNotManageTheTafSetsNothing() = runTest {
    val m = model(manage = false)
    m.setPromise("refunds-1", 4)
    assertTrue(transport.requestsTo("PUT /v1/tafs/taf_1/promise").isEmpty())
  }
}
