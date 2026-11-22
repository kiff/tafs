@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.RecordHit
import bot.tafs.app.kiff.TafActivityEntry
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Report this payment" (docs/record.md): who sees it is tafs cloud's to say, on the entry; the phone sends the tap and shows the
 * taf halted as tafs cloud answers. A fake tafs cloud stands in: no real payment is reported and no taf is halted.
 */
class ReportPaymentTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-11-05T10:00:00Z")

  private val home =
    """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}"""

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}""")
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore() }
  }

  private val entry = """{"id":"e1","at":"2026-11-05T09:00:00Z","kind":"done","title":"Pay at the checkout","detail":"Done","time":"09:00",
    "amount_text":"€42.00","call":{"proposal_id":"p-pay","actor_id":"refunds-1","action_name":"browse_pay","outcome":"allowed","created_at":"2026-11-05T09:00:00Z"}"""

  @Test fun theOfferIsTafsCloudsAndAnEntryWithoutItHasNone() {
    val offered = KiffClient.json.decodeFromString<TafActivityEntry>("$entry,\"report\":{\"trace_id\":\"tr-pay\",\"at\":\"2026-11-05T08:59:00Z\"}}")
    assertEquals("tr-pay", offered.report?.traceId)
    assertEquals("2026-11-05T08:59:00Z", offered.report?.at)
    assertNull(KiffClient.json.decodeFromString<TafActivityEntry>("$entry}").report)
    // Reported already: when, and no offer.
    val reported = KiffClient.json.decodeFromString<TafActivityEntry>("$entry,\"reported_at\":\"2026-11-05T09:30:00Z\"}")
    assertNull(reported.report)
    assertEquals("2026-11-05T09:30:00Z", reported.reportedAt)
    // The Record's search draws its hit as the taf's Activity does, offer included.
    val hit = KiffClient.json.decodeFromString<RecordHit>(
      "$entry,\"taf\":{\"id\":\"taf_1\",\"name\":\"Refunds\"},\"report\":{\"trace_id\":\"tr-pay\",\"at\":\"2026-11-05T08:59:00Z\"}}")
    assertEquals("tr-pay", hit.asActivity.report?.traceId)
  }

  @Test fun reportingSendsTheTapWithNoSignatureAndShowsTheTafHaltedAsTafsCloudSays() = runTest {
    reads()
    transport.answer("POST /v1/record/tr-pay/report", 200,
      """{"reported_at":"2026-11-05T10:00:00Z","taf":"taf_1","halt":{"state":"halting","runtime":true,"kiff":true,"planque":false,"account":false}}""")
    val m = model()
    assertTrue(m.reportPayment("refunds-1", "tr-pay"))
    val request = transport.requestsTo("POST /v1/record/tr-pay/report").single()
    assertEquals("https://cloud.tafs.kiff.dev/v1/record/tr-pay/report", request.url)
    assertEquals("Bearer at_1", request.headers["Authorization"])
    assertTrue("reporting only stops things: nothing to confirm on the phone", auth.asked.isEmpty())
    val h = m.state.value.haltOf("refunds-1")
    assertTrue(h.halting)
    assertTrue(h.runtime)
  }

  @Test fun aReportThatDidntGoThroughChangesNothing() = runTest {
    reads()
    transport.answer("POST /v1/record/tr-pay/report", 502, """{"error":"KIFF answered 503"}""")
    val m = model()
    assertFalse(m.reportPayment("refunds-1", "tr-pay"))
    assertTrue(m.state.value.haltOf("refunds-1").running)
  }
}
