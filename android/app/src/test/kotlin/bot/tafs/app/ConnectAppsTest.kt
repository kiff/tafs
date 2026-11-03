package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Connect apps: tafs cloud's list (the knob API), read for a search, and said in words when it can't be read. */
class ConnectAppsTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val apps = "GET /v1/connect/apps"

  private suspend fun model(): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":true}}}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { Instant.parse("2026-11-02T21:00:00Z") }, start = false).also { it.restore(); it.refresh() }
  }

  @Test fun theListIsTafsCloudsInItsOrderAndASearchIsSentAsTyped() = runTest {
    val m = model()
    transport.answer(apps, 200, """{"apps":[{"entry":"notion@1","vendor":"Notion","title":"Notion","state":"available"},{"entry":"stripe@1","vendor":"Stripe","title":"Stripe","state":"connected","grant":"g1"}],"by_key":true}""")
    m.loadConnectApps()
    assertEquals("https://cloud.tafs.kiff.dev/v1/connect/apps", transport.requestsTo(apps).single().url)
    assertEquals(listOf("Notion", "Stripe"), m.state.value.connectApps!!.apps.map { it.title })
    assertTrue(m.state.value.connectApps!!.byKey)
    transport.answer(apps, 200, """{"apps":[{"entry":"google","vendor":"Google","title":"Google Sheets","state":"available"}],"by_key":true}""")
    m.loadConnectApps("google sheets")
    assertEquals("https://cloud.tafs.kiff.dev/v1/connect/apps?q=google%20sheets", transport.requestsTo(apps).last().url)
    assertEquals(listOf("Google Sheets"), m.state.value.connectApps!!.apps.map { it.title })
    assertNull(m.state.value.connectAppsError)
  }

  @Test fun connectStartsTheGrantAndOpensItsApprovalOverTheList() = runTest {
    val m = model()
    // planque's grant comes back as its view, an object.
    transport.answer("POST /v1/connect/apps/notion@1", 200, """{"grant":{"id":"g7","status":"pending"}}""")
    m.startConnectApp(bot.tafs.app.kiff.ConnectApps.App("notion@1", "Notion", "Notion"))
    val sent = transport.requestsTo("POST /v1/connect/apps/notion@1").single()
    assertEquals("Bearer at_1", sent.headers["Authorization"])
    assertEquals("for the only taf this person manages, by its tafs cloud id", """{"taf":"taf_1"}""", String(sent.body!!))
    assertEquals("g7", m.state.value.listApproval)
    assertNull(m.state.value.startingApp)
    m.closeListApproval()
    assertNull(m.state.value.listApproval)
  }

  @Test fun aVendorThatTakesAKeyIsCoachsForNowAndNothingOpens() = runTest {
    val m = model()
    transport.answer("POST /v1/connect/apps/holded", 200, """{"grant":"g8","needs_key":true}""")
    m.startConnectApp(bot.tafs.app.kiff.ConnectApps.App("holded", "Holded", "Holded", how = "key"))
    assertNull(m.state.value.listApproval)
    assertEquals(bot.tafs.app.model.ConnectWords.needsKey("Holded"), m.state.value.connectAppsError)
  }

  @Test fun signingInAgainOpensTheGrantItHasWithNoNewOne() = runTest {
    val m = model()
    m.startConnectApp(bot.tafs.app.kiff.ConnectApps.App("google", "Google", "Google Sheets", state = "needs_reconnect", grant = "g2"))
    assertTrue(transport.requests.none { it.method == "POST" && it.url.contains("/connect/apps") })
    assertEquals("g2", m.state.value.listApproval)
  }

  @Test fun aListThatCantBeReadIsSaidInWords() = runTest {
    val m = model()
    transport.answer(apps, 503, """{"error":"down"}""")
    m.loadConnectApps()
    assertTrue(m.state.value.connectAppsError!!.startsWith("The apps couldn't be read."))
  }
}
