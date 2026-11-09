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
 * first, then by name; then the connections of tafs tafs cloud names as removed (`removed_tafs`), cleared at once with one
 * fingerprint; then the rest, which the app claims nothing about. A fake KIFF and planque stand in.
 */
class ConnectAppsByTafTest {
  private val stripeEntry = "connector:composio:stripe"
  private val stripe = ConnectApps.App(entry = stripeEntry, vendor = "Stripe", title = "Stripe")

  private fun g(id: String, agent: String, status: String = "connected", account: String? = null, signedIn: Boolean? = null, agentName: String? = null) =
    KiffClient.json.decodeFromString<Grant>(
      """{"id":"$id","status":"$status","entry":"$stripeEntry"${account?.let { ",\"account\":\"$it\"" } ?: ""}${signedIn?.let { ",\"signed_in\":$it" } ?: ""}${agentName?.let { ",\"agent_name\":\"$it\"" } ?: ""},
         "request":{"server":"https://mcp.stripe.com","agent":"$agent","tools":[]}}""",
    )

  /** tafs cloud's Connect apps page, naming [removed] as removed tafs' agents. */
  private fun page(vararg removed: String) =
    KiffClient.json.decodeFromString<ConnectApps>("""{"apps":[],"removed_tafs":[${removed.joinToString(",") { "{\"agent\":\"$it\",\"removed_at\":\"2026-11-08T08:00:00Z\"}" }}]}""")

  private fun taf(id: String, name: String) =
    Taf(id = id, name = name, job = "custom", tools = emptyList(), colorIndex = 0, card = bot.tafs.app.model.Card(rules = emptyList(), issuedBy = "you"))

  private val tafs = listOf(
    taf("refunds-1", "Refunds"),
    taf("chargebacks-1", "Chargebacks"),
    taf("save-1", "Save Cancels"),
  )

  @Test fun eachTafIsASectionTheOnesNeedingThePersonFirstThenByNameThenRemovedThenOthers() {
    val state = AppState(
      tafs = tafs,
      // tafs cloud names gone-1 and gone-2 as removed; setup-1 it doesn't, though no taf here is it.
      connectApps = page("gone-1", "gone-2"),
      grants = listOf(
        g("g1", "save-1"),
        g("g2", "gone-1"),
        g("g3", "refunds-1", account = "acct_1Rk…"),
        g("g4", "chargebacks-1"),
        // Approved, and its sign-in never came back: it needs the person.
        g("g5", "save-1", status = "approved"),
        g("g6", "gone-2", status = "approved"),
        g("g7", "refunds-1"),
        g("g8", "setup-1"),
      ),
    )
    val (sections, removed, others) = byTaf(state, connectionsOf(state, stripe))
    assertEquals(listOf("Save Cancels", "Chargebacks", "Refunds"), sections.map { it.taf })
    assertEquals("a taf's connections stay together, in planque's order", listOf("g3", "g7"), sections.last().grants.map { it.id })
    assertEquals("only what tafs cloud names as removed", listOf("g2", "g6"), removed.map { it.id })
    assertEquals("a taf missing from the list isn't proof it was removed", listOf("g8"), others.map { it.id })
  }

  @Test fun fromATafsCloudThatNamesNoneNothingIsCalledRemoved() {
    // A tafs cloud from before `removed_tafs`, or someone who doesn't manage Cards: the field is empty or absent.
    val state = AppState(tafs = tafs, connectApps = KiffClient.json.decodeFromString<ConnectApps>("""{"apps":[]}"""), grants = listOf(g("g1", "gone-1"), g("g2", "refunds-1")))
    val (sections, removed, others) = byTaf(state, connectionsOf(state, stripe))
    assertEquals(listOf("Refunds"), sections.map { it.taf })
    assertTrue(removed.isEmpty())
    assertEquals(listOf("g1"), others.map { it.id })
  }

  @Test fun aRemovedTafsConnectionIsNamedByThePlanqueKeeps() {
    assertEquals("Old refunds", g("g1", "gone-1", agentName = "Old refunds").agentName)
    assertNull(g("g1", "gone-1").agentName)
  }

  @Test fun aRowIsTheVendorsAccountElseTheAppsName() {
    assertEquals("acct_1Rk…", ConnectAppsWords.rowTitle("Stripe", g("g1", "refunds-1", account = "acct_1Rk…")))
    assertEquals("Stripe", ConnectAppsWords.rowTitle("Stripe", g("g1", "refunds-1")))
    assertEquals("Stripe", ConnectAppsWords.rowTitle("Stripe", g("g1", "refunds-1", account = " ")))
  }

  @Test fun clearingSaysWhatWasClearedKeptAndLeft() {
    assertEquals("Clear 3 connections from removed tafs?", ConnectionWords.clearTitle(3))
    assertEquals("Clear 1 connection from removed tafs?", ConnectionWords.clearTitle(1))
    assertEquals("Cleared 2.", ConnectionWords.cleared(2, 0, emptyList(), 0))
    assertEquals("Cleared 2. Kept 1 for Chargebacks, whose Card uses it.", ConnectionWords.cleared(2, 1, listOf("Chargebacks"), 0))
    assertEquals("Cleared 1. 1 couldn't be cleared. Try again.", ConnectionWords.cleared(1, 0, emptyList(), 1))
    assertEquals("Kept 2 for Chargebacks and Refunds, whose Cards use them.", ConnectionWords.cleared(0, 2, listOf("Chargebacks", "Refunds"), 0))
    assertEquals("Kept: Chargebacks's Card uses it", ConnectionWords.usedBy(listOf("Chargebacks")))
    assertNull(ConnectionWords.usedBy(emptyList()))
  }

  // MARK: Clearing, against a fake planque

  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val grants = "GET /v1/accounts/t_01/grants"

  private fun planqueGrant(id: String, agent: String) =
    """{"id":"$id","status":"connected","entry":"$stripeEntry","request":{"server":"https://mcp.stripe.com","agent":"$agent","tools":[]},"events":[]}"""

  private suspend fun model(
    removed: String = """[{"agent":"gone-1","grants":[{"grant":"g1","used_by":[]}]},{"agent":"gone-2","grants":[{"grant":"g2","used_by":[]}]}]""",
  ): AppModel {
    transport.answer("GET /v1/connect/apps", 200, """{"apps":[],"removed_tafs":$removed}""")
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
      .also { it.restore(); it.refreshConnections(); it.loadConnectApps() }
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

  @Test fun aConnectionTafsCloudDoesntListIsNeverCleared() = runTest {
    // Only gone-1's connection is listed: g2's taf may be one this person doesn't see, so Clear leaves it, whatever the screen sent.
    val m = model(removed = """[{"agent":"gone-1","grants":[{"grant":"g1","used_by":[]}]}]""")
    transport.answer("DELETE /v1/accounts/t_01/grants/g1", 200, planqueGrant("g1", "gone-1").replace("connected", "disconnected"))
    assertTrue(m.clearRemovedConnections(listOf("g1", "g2")))
    assertTrue(transport.requestsTo("DELETE /v1/accounts/t_01/grants/g2").isEmpty())
    assertEquals(listOf("Clear 1 connection from removed tafs?"), auth.asked)
  }

  @Test fun aConnectionALiveTafsCardUsesIsKeptAndTheLineNamesIt() = runTest {
    // tafs cloud says Chargebacks' Card in force still uses g2: it's kept, by tafs cloud's word, not the phone's.
    val m = model(removed = """[{"agent":"gone-1","grants":[{"grant":"g1","used_by":[]}]},{"agent":"gone-2","grants":[{"grant":"g2","used_by":[{"taf":"taf-c","name":"Chargebacks"}]}]}]""")
    transport.answer("DELETE /v1/accounts/t_01/grants/g1", 200, planqueGrant("g1", "gone-1").replace("connected", "disconnected"))
    assertTrue(m.clearRemovedConnections(listOf("g1", "g2")))
    assertTrue(transport.requestsTo("DELETE /v1/accounts/t_01/grants/g2").isEmpty())
    assertEquals(listOf("Clear 1 connection from removed tafs?"), auth.asked)
    assertEquals("Cleared 1. Kept 1 for Chargebacks, whose Card uses it.", m.state.value.clearNote)
  }

  @Test fun withoutTafsCloudsListNothingIsCleared() = runTest {
    // No signed read, or tafs cloud couldn't read planque or KIFF's Cards: no `grants`, so no Clear, and nothing sent.
    val m = model(removed = """[{"agent":"gone-1"},{"agent":"gone-2"}]""")
    assertFalse(m.clearRemovedConnections(listOf("g1", "g2")))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo("DELETE /v1/accounts/t_01/grants/g1").isEmpty())
  }

  @Test fun theReadCarriesThisPhonesSignedGrantsRead() = runTest {
    model()
    val sent = transport.requestsTo("GET /v1/connect/apps").first()
    assertTrue("the signed grants read tafs cloud forwards to planque", sent.headers.containsKey("Planque-Device-Signature"))
  }

  @Test fun nothingToClearSendsNothing() = runTest {
    val m = model()
    assertFalse(m.clearRemovedConnections(emptyList()))
    assertTrue(auth.asked.isEmpty())
    assertNull(m.state.value.clearNote)
  }
}
