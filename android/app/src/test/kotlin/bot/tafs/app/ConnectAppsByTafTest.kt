package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.kiff.ConnectApps
import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.ConnectionWords
import bot.tafs.app.model.Taf
import bot.tafs.app.views.ConnectAppsWords
import bot.tafs.app.views.byTaf
import bot.tafs.app.views.connectionsOf
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Connect apps by taf (the owner's ask, the same on iPhone): an app's connections in one section per taf, those needing the person
 * first, then by name, and removed tafs' connections last, cleared at once with one fingerprint. A fake KIFF and planque stand in.
 */
class ConnectAppsByTafTest {
  private val stripeEntry = "connector:composio:stripe"
  private val stripe = ConnectApps.App(entry = stripeEntry, vendor = "Stripe", title = "Stripe")

  private fun g(id: String, agent: String, status: String = "connected", account: String? = null, signedIn: Boolean? = null) =
    KiffClient.json.decodeFromString<Grant>(
      """{"id":"$id","status":"$status","entry":"$stripeEntry"${account?.let { ",\"account\":\"$it\"" } ?: ""}${signedIn?.let { ",\"signed_in\":$it" } ?: ""},
         "request":{"server":"https://mcp.stripe.com","agent":"$agent","tools":[]}}""",
    )

  private fun taf(id: String, name: String) =
    Taf(id = id, name = name, job = "custom", tools = emptyList(), colorIndex = 0, card = bot.tafs.app.model.Card(rules = emptyList(), issuedBy = "you"))

  private val tafs = listOf(
    taf("refunds-1", "Refunds"),
    taf("chargebacks-1", "Chargebacks"),
    taf("save-1", "Save Cancels"),
  )

  @Test fun eachTafIsASectionTheOnesNeedingThePersonFirstThenByNameAndRemovedTafsApart() {
    val state = AppState(
      tafs = tafs,
      grants = listOf(
        g("g1", "save-1"),
        g("g2", "gone-1"),
        g("g3", "refunds-1", account = "acct_1Rk…"),
        g("g4", "chargebacks-1"),
        // Approved, and its sign-in never came back: it needs the person.
        g("g5", "save-1", status = "approved"),
        g("g6", "gone-2", status = "approved"),
        g("g7", "refunds-1"),
      ),
    )
    val (sections, removed) = byTaf(state, connectionsOf(state, stripe))
    assertEquals(listOf("Save Cancels", "Chargebacks", "Refunds"), sections.map { it.taf })
    assertEquals("a taf's connections stay together, in planque's order", listOf("g3", "g7"), sections.last().grants.map { it.id })
    assertEquals(listOf("g2", "g6"), removed.map { it.id })
  }

  @Test fun aRowIsTheVendorsAccountElseTheAppsName() {
    assertEquals("acct_1Rk…", ConnectAppsWords.rowTitle("Stripe", g("g1", "refunds-1", account = "acct_1Rk…")))
    assertEquals("Stripe", ConnectAppsWords.rowTitle("Stripe", g("g1", "refunds-1")))
    assertEquals("Stripe", ConnectAppsWords.rowTitle("Stripe", g("g1", "refunds-1", account = " ")))
  }

  @Test fun clearingSaysWhatWasClearedKeptAndLeft() {
    assertEquals("Clear 3 connections from removed tafs?", ConnectionWords.clearTitle(3))
    assertEquals("Clear 1 connection from removed tafs?", ConnectionWords.clearTitle(1))
    assertEquals("Cleared 2.", ConnectionWords.cleared(2, 0, 0))
    assertEquals("Cleared 2. Kept 1: another taf uses it.", ConnectionWords.cleared(2, 1, 0))
    assertEquals("Cleared 1. 1 couldn't be cleared. Try again.", ConnectionWords.cleared(1, 0, 1))
    assertEquals("Kept 2: another taf uses them.", ConnectionWords.cleared(0, 2, 0))
  }

  // MARK: Clearing, against a fake planque

  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val grants = "GET /v1/accounts/t_01/grants"

  private fun planqueGrant(id: String, agent: String) =
    """{"id":"$id","status":"connected","entry":"$stripeEntry","request":{"server":"https://mcp.stripe.com","agent":"$agent","tools":[]},"events":[]}"""

  private suspend fun model(): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
    transport.answer(grants, 200, """{"grants":[${planqueGrant("g1", "gone-1")},${planqueGrant("g2", "gone-2")}]}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { Instant.parse("2026-11-07T09:00:00Z") }, start = false)
      .also { it.restore(); it.refreshConnections() }
  }

  @Test fun clearingAsksOneFingerprintThenDisconnectsEachSigned() = runTest {
    val m = model()
    assertTrue(m.state.value.planqueReady)
    transport.answer("DELETE /v1/accounts/t_01/grants/g1", 200, planqueGrant("g1", "gone-1").replace("connected", "disconnected"))
    transport.answer("DELETE /v1/accounts/t_01/grants/g2", 200, planqueGrant("g2", "gone-2").replace("connected", "disconnected"))
    assertTrue(m.clearRemovedConnections(listOf("g1", "g2")))
    assertEquals("one fingerprint for all of them", listOf("Clear 2 connections from removed tafs?"), auth.asked)
    for (id in listOf("g1", "g2")) {
      val sent = transport.requestsTo("DELETE /v1/accounts/t_01/grants/$id").single()
      assertTrue("signed by this phone, as planque takes it", sent.headers.containsKey("Planque-Device-Signature"))
    }
    assertEquals("Cleared 2.", m.state.value.clearNote)
    assertFalse(m.state.value.clearingRemoved)
  }

  @Test fun oneThatFailsIsSaidAndTheRestAreCleared() = runTest {
    val m = model()
    transport.answer("DELETE /v1/accounts/t_01/grants/g1", 200, planqueGrant("g1", "gone-1").replace("connected", "disconnected"))
    transport.answer("DELETE /v1/accounts/t_01/grants/g2", 502, """{"error":"the vendor didn't answer"}""")
    assertFalse(m.clearRemovedConnections(listOf("g1", "g2")))
    assertEquals("Cleared 1. 1 couldn't be cleared. Try again.", m.state.value.clearNote)
  }

  @Test fun clearingNobodyConfirmedSendsNothing() = runTest {
    val m = model()
    auth.confirms = false
    assertFalse(m.clearRemovedConnections(listOf("g1", "g2")))
    assertTrue(transport.requestsTo("DELETE /v1/accounts/t_01/grants/g1").isEmpty())
    assertEquals("Not done: it wasn't confirmed.", m.state.value.clearNote)
  }

  @Test fun nothingToClearSendsNothing() = runTest {
    val m = model()
    assertFalse(m.clearRemovedConnections(emptyList()))
    assertTrue(auth.asked.isEmpty())
    assertNull(m.state.value.clearNote)
  }
}
