package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  @Test fun aVendorThatTakesAKeyOpensItsApprovalToTakeTheKeyThere() = runTest {
    val m = model()
    transport.answer("POST /v1/connect/apps/holded", 200, """{"grant":"g8","needs_key":true}""")
    m.startConnectApp(bot.tafs.app.kiff.ConnectApps.App("holded", "Holded", "Holded", how = "key"))
    assertEquals("g8", m.state.value.listApproval)
    assertTrue("the key is typed on the sheet", m.state.value.listApprovalNeedsKey)
    assertNull(m.state.value.connectAppsError)
    m.closeListApproval()
    assertFalse(m.state.value.listApprovalNeedsKey)
  }

  @Test fun signingInAgainOpensTheGrantItHasWithNoNewOne() = runTest {
    val m = model()
    m.startConnectApp(bot.tafs.app.kiff.ConnectApps.App("google", "Google", "Google Sheets", state = "needs_reconnect", grant = "g2"))
    assertTrue(transport.requests.none { it.method == "POST" && it.url.contains("/connect/apps") })
    assertEquals("g2", m.state.value.listApproval)
  }

  @Test fun aTafsDialIsReadFromTafsCloudByItsCloudId() = runTest {
    // Kept with Connect apps: the same signed-in account with one taf it manages.
    val m = model()
    transport.answer("GET /v1/tafs/taf_1/knob", 200, """{"does":{"label":"Refund","line":""},"wakes":[{"id":"trg_1","kind":"run_now","label":"Run now"}],"rim":{"done":2}}""")
    m.loadKnob("refunds-1")
    assertEquals("Refund", m.state.value.knobs.getValue("refunds-1").does.label)
    // A dial that can't be read leaves the doors' own dial: nothing is kept.
    transport.answer("GET /v1/tafs/taf_1/knob", 503, """{"error":"down"}""")
    m.loadKnob("refunds-1")
    assertEquals("the last one read stays", "Refund", m.state.value.knobs.getValue("refunds-1").does.label)
  }

  @Test fun lowerOrRaiseSendsTheChoicesSettingThenReadsThatVersionToBuildOn() = runTest {
    val m = model()
    transport.answer("GET /v1/tafs/taf_1/knob", 200, """{"version":7,"why":"Refund double charges","wakes":[{"id":"t","kind":"schedule","label":"Weekdays",
      "size":{"label":"Time","index":0,"choices":[{"label":"09:00","setting":{"at":"09:00"}},{"label":"12:00","setting":{"at":"12:00"}}]}}],"rim":{}}""")
    m.loadKnob("refunds-1")
    val raise = m.state.value.knobs.getValue("refunds-1").item("t")!!.size!!.step(1)!!
    transport.answer("PUT /v1/tafs/taf_1/knob", 200, """{"version":8,"words":["Weekdays at 12:00."]}""")
    transport.answer("GET /v1/tafs/taf_1/knob", 200, """{"version":7,"shown":8,"why":"Refund double charges","wakes":[{"id":"t","kind":"schedule","label":"Weekdays",
      "size":{"label":"Time","index":1,"choices":[{"label":"09:00","setting":{"at":"09:00"}},{"label":"12:00","setting":{"at":"12:00"}}]}}],"rim":{}}""")
    assertTrue(m.changeKnobSize("refunds-1", raise.setting))
    val body = kotlinx.serialization.json.Json.parseToJsonElement(String(transport.requestsTo("PUT /v1/tafs/taf_1/knob").single().body!!))
    assertEquals("the choice's setting, as it is", kotlinx.serialization.json.Json.parseToJsonElement("""{"at":"12:00"}"""), body)
    assertEquals("that version is read, so the next change builds on it", "https://cloud.tafs.kiff.dev/v1/tafs/taf_1/knob?version=8", transport.requestsTo("GET /v1/tafs/taf_1/knob").last().url)
    assertEquals(8, m.state.value.knobs.getValue("refunds-1").shown)
  }

  @Test fun aChangeThatCantBeReadBackKeepsItsVersionUntilTheDialShowsIt() = runTest {
    val m = model()
    transport.answer("GET /v1/tafs/taf_1/knob", 200, """{"version":7,"why":"Refund double charges","rim":{}}""")
    m.loadKnob("refunds-1")
    transport.answer("PUT /v1/tafs/taf_1/knob", 200, """{"version":8,"words":["Weekdays at 12:00."]}""")
    transport.answer("GET /v1/tafs/taf_1/knob", 503, """{"error":"down"}""")
    assertTrue("the change was made", m.changeKnobSize("refunds-1", kotlinx.serialization.json.JsonObject(emptyMap())))
    assertEquals("− and + wait for version 8", 8, m.state.value.knobBehind["refunds-1"])
    assertFalse("a choice from before isn't sent", m.changeKnobSize("refunds-1", kotlinx.serialization.json.JsonObject(emptyMap())))
    assertEquals("one PUT only", 1, transport.requestsTo("PUT /v1/tafs/taf_1/knob").size)
    // Read again: the live dial says 8 waits, and that one can't be read either.
    transport.answer("GET /v1/tafs/taf_1/knob", 200, """{"version":7,"why":"Refund double charges","pending":{"version":8,"changes":1},"rim":{}}""")
    transport.answer("GET /v1/tafs/taf_1/knob", 503, """{"error":"down"}""")
    m.loadKnob("refunds-1")
    assertEquals("still behind the waiting change", 8, m.state.value.knobBehind["refunds-1"])
    // Then it reads.
    transport.answer("GET /v1/tafs/taf_1/knob", 200, """{"version":7,"why":"Refund double charges","pending":{"version":8,"changes":1},"rim":{}}""")
    transport.answer("GET /v1/tafs/taf_1/knob", 200, """{"version":7,"shown":8,"why":"Refund double charges","pending":{"version":8,"changes":1},"rim":{}}""")
    m.loadKnob("refunds-1")
    assertNull("the dial shows 8: − and + build on it", m.state.value.knobBehind["refunds-1"])
  }

  @Test fun aReloadWhileAChangeWaitsReadsThatVersionNotTheLiveOne() = runTest {
    val m = model()
    // A door opened or the taf paused: the dial is read again, and tafs cloud says a change waits.
    transport.answer("GET /v1/tafs/taf_1/knob", 200, """{"version":7,"why":"Refund double charges","pending":{"version":8,"changes":2},"rim":{}}""")
    transport.answer("GET /v1/tafs/taf_1/knob", 200, """{"version":7,"shown":8,"why":"Refund double charges","pending":{"version":8,"changes":2},"rim":{}}""")
    m.loadKnob("refunds-1")
    assertEquals("the waiting change stays in view", "https://cloud.tafs.kiff.dev/v1/tafs/taf_1/knob?version=8", transport.requestsTo("GET /v1/tafs/taf_1/knob").last().url)
    val knob = m.state.value.knobs.getValue("refunds-1")
    assertEquals(8, knob.shown)
    assertEquals("as tafs cloud counts it", bot.tafs.app.kiff.KnobWire.Pending(8, 2), knob.pending)
  }

  @Test fun nothingWaitingMeansNothingToMakeLive() = runTest {
    val m = model()
    transport.answer("GET /v1/tafs/taf_1/knob", 200, """{"version":7,"why":"Refund double charges","pending":null,"rim":{}}""")
    m.loadKnob("refunds-1")
    assertEquals("the live dial, read once", 1, transport.requestsTo("GET /v1/tafs/taf_1/knob").size)
    assertFalse(m.confirmKnobChange("refunds-1"))
    assertTrue(transport.requestsTo("GET /v1/tafs/taf_1/versions").isEmpty())
  }

  @Test fun aFormTheSchedulerCantKeepIsSaidInTafsCloudsWords() = runTest {
    val m = model()
    transport.answer("PUT /v1/tafs/taf_1/knob", 400, """{"error":"bad","words":"Every 25 minutes can't be kept exactly: the nearest is every 20 minutes."}""")
    assertFalse(m.changeKnobSize("refunds-1", kotlinx.serialization.json.JsonObject(emptyMap())))
    assertTrue(m.state.value.knobErrors.getValue("refunds-1").contains("the nearest is every 20 minutes"))
    assertTrue("nothing read: no version was made", transport.requestsTo("GET /v1/tafs/taf_1/knob").isEmpty())
  }

  @Test fun showMoreAddsTheNextPageForTheSameSearch() = runTest {
    val m = model()
    transport.answer("GET /v1/connect/apps", 200, """{"apps":[{"entry":"notion@1","title":"Notion"},{"entry":"connector:composio:gmail","title":"Gmail"}],"by_key":true,"provider":"composio","next_cursor":"p2+x"}""")
    m.loadConnectApps("mail")
    transport.answer("GET /v1/connect/apps", 200, """{"apps":[{"entry":"connector:composio:gmail","title":"Gmail"},{"entry":"connector:composio:outlook","title":"Outlook"}],"by_key":true,"provider":"composio"}""")
    m.loadMoreConnectApps()
    val asked = transport.requestsTo("GET /v1/connect/apps").last().url
    assertEquals("the same search, its provider, and the cursor with its + kept", "https://cloud.tafs.kiff.dev/v1/connect/apps?q=mail&provider=composio&cursor=p2%2Bx", asked)
    assertEquals("an app already shown isn't added twice", listOf("Notion", "Gmail", "Outlook"), m.state.value.connectApps!!.apps.map { it.title })
    assertNull("the last page", m.state.value.connectApps!!.nextCursor)
  }

  @Test fun aListThatCantBeReadIsSaidInWords() = runTest {
    val m = model()
    transport.answer(apps, 503, """{"error":"down"}""")
    m.loadConnectApps()
    assertTrue(m.state.value.connectAppsError!!.startsWith("The apps couldn't be read."))
  }
}
