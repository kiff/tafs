package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.model.MemoryNote
import bot.tafs.app.model.TafState
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A taf's page signed in: its runs (Run now, Pause and Resume), its last run's report, what it remembers, Revoke and
 * Remove for good. A fake KIFF and tafs cloud stand in: no real taf is run, paused, revoked or removed.
 */
class TafPageTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private fun taf(status: String = "active", nextRun: String? = "2026-10-28T12:00:00Z", unscheduled: Boolean = false) =
    """{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"working","status":"$status"""" +
      (nextRun?.let { ""","next_run":"$it"""" } ?: "") + (if (unscheduled) ""","unscheduled":true""" else "") +
      ""","card":{"ids":["c1","c2"],"active":true}}"""

  private fun reads(home: String = """{"tafs":[${taf()}],"others":[],"removing":[]}""") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage","holds.answer"]}""")
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
  }

  // MARK: Its schedule, from the list

  @Test fun theListSaysWhetherItsPausedWhenItRunsNextAndWhetherItWorksFromItsChat() = runTest {
    reads(home = """{"tafs":[${taf(status = "paused", nextRun = null)}],"others":[],"removing":[]}""")
    val paused = model().state.value.taf("refunds-1")!!
    assertTrue(paused.paused)
    assertNull(paused.nextRun)
    assertFalse(paused.worksFromChat)
  }

  @Test fun aTafThatWorksFromItsChatHasNoScheduleToRunOrPause() = runTest {
    reads(home = """{"tafs":[${taf(nextRun = null, unscheduled = true)}],"others":[],"removing":[]}""")
    val taf = model().state.value.taf("refunds-1")!!
    assertTrue(taf.worksFromChat)
    assertNull(taf.nextRun)
  }

  // MARK: Runs

  @Test fun runNowStartsARunThenReadsItsReport() = runTest {
    reads()
    transport.answer("POST /v1/tafs/taf_1/run", 200, """{"session":"s9"}""")
    transport.answer("GET /v1/tafs/taf_1/runs", 200, """{"runs":[{"session":"s9","at":"2026-10-28T09:00:00Z"}]}""")
    transport.answer("GET /v1/tafs/taf_1/runs/s9", 200, """{"report":{"status":"running","text":""}}""")
    val m = model()
    assertEquals("s9", m.runNow("refunds-1"))
    val report = m.state.value.reports["refunds-1"]!!
    assertEquals("s9", report.session)
    assertFalse(report.finished)
    assertTrue(m.state.value.runsInFlight.isEmpty())
  }

  @Test fun aSecondRunNowWhileTheFirstIsOnItsWayStartsNothing() = runTest {
    reads()
    transport.answer("POST /v1/tafs/taf_1/run", 200, """{"session":"s9"}""")
    transport.hold("POST /v1/tafs/taf_1/run")
    val m = model()
    val first = launch { m.runNow("refunds-1") }
    testScheduler.runCurrent()
    assertTrue("refunds-1" in m.state.value.runsInFlight)
    assertNull(m.runNow("refunds-1"))
    transport.release("POST /v1/tafs/taf_1/run")
    first.join()
    assertEquals(1, transport.requestsTo("POST /v1/tafs/taf_1/run").size)
  }

  @Test fun aRunThatDidntStartSaysWhy() = runTest {
    reads()
    transport.answer("POST /v1/tafs/taf_1/run", 409, """{"error":"it's halted"}""")
    val m = model()
    assertNull(m.runNow("refunds-1"))
    assertEquals("The run didn't start: it's halted", m.state.value.lastError)
  }

  @Test fun theLastRunsReportIsWhatItWroteOnceItStopped() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/runs", 200, """{"runs":[{"at":"2026-10-28T08:59:00Z","error":"budget"},{"session":"s8","at":"2026-10-28T08:00:00Z"}]}""")
    transport.answer("GET /v1/tafs/taf_1/runs/s8", 200, """{"report":{"status":"idle","text":"Refunded **2** payments."}}""")
    val m = model()
    m.loadReport("refunds-1")
    val report = m.state.value.reports["refunds-1"]!!
    assertTrue(report.finished)
    assertEquals("Refunded **2** payments.", report.text)
    assertEquals(Instant.parse("2026-10-28T08:00:00Z"), report.at)
  }

  @Test fun aTafWithNoRunYetSaysSo() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/runs", 200, """{"runs":[]}""")
    val m = model()
    m.loadReport("refunds-1")
    val report = m.state.value.reports["refunds-1"]!!
    assertNull(report.session)
    assertNull(report.error)
  }

  @Test fun pausingStopsItsScheduleAndResumingShowsTheNextRun() = runTest {
    reads()
    transport.answer("POST /v1/tafs/taf_1/pause", 200, """{"taf":{"id":"taf_1","status":"paused","next_run":null}}""")
    transport.answer("POST /v1/tafs/taf_1/resume", 200, """{"taf":{"id":"taf_1","status":"active","next_run":"2026-10-28T12:00:00Z"}}""")
    val m = model()
    m.setPaused("refunds-1", true)
    var taf = m.state.value.taf("refunds-1")!!
    assertTrue(taf.paused)
    assertEquals(TafState.paused, taf.state)
    assertEquals("Paused by you", taf.now)
    m.setPaused("refunds-1", false)
    taf = m.state.value.taf("refunds-1")!!
    assertFalse(taf.paused)
    assertEquals(TafState.working, taf.state)
  }

  // MARK: Revoke

  @Test fun revokingSignedInRevokesEachOfItsCardsInKiffBeforeItShowsStopped() = runTest {
    reads()
    transport.answer("POST /v1/me/mandates/c1/revoke", 200)
    transport.answer("POST /v1/me/mandates/c2/revoke", 200)
    val m = model()
    assertTrue(m.revokeCards("refunds-1"))
    assertEquals(1, transport.requestsTo("POST /v1/me/mandates/c1/revoke").size)
    assertEquals(1, transport.requestsTo("POST /v1/me/mandates/c2/revoke").size)
    assertEquals(TafState.revoked, m.state.value.taf("refunds-1")!!.state)
    assertTrue("taking authority away needs no prompt", auth.asked.isEmpty())
  }

  @Test fun aRevokeKiffRefusedShowsNothingStopped() = runTest {
    reads()
    transport.answer("POST /v1/me/mandates/c1/revoke", 403, """{"error":"not yours"}""")
    val m = model()
    assertFalse(m.revokeCards("refunds-1"))
    assertEquals(TafState.working, m.state.value.taf("refunds-1")!!.state)
    assertEquals("KIFF didn't revoke the Card: not yours", m.state.value.lastError)
  }

  // MARK: Remove for good

  @Test fun removingIsConfirmedRevokesItsCardsThenTafsCloudRemovesTheRest() = runTest {
    reads()
    transport.answer("POST /v1/me/mandates/c1/revoke", 200)
    transport.answer("POST /v1/me/mandates/c2/revoke", 200)
    transport.answer("DELETE /v1/tafs/taf_1", 200, """{"done":true,"removed":["schedule","history","memory","key","name"],"failed":[]}""")
    val m = model()
    val words = m.removeTaf("refunds-1")
    assertEquals(listOf("Remove Refunds for good"), auth.asked)
    val order = transport.requests.map { "${it.method} ${java.net.URI.create(it.url).path}" }
    assertTrue(order.indexOf("POST /v1/me/mandates/c2/revoke") < order.indexOf("DELETE /v1/tafs/taf_1"))
    assertEquals(
      "Its Cards are revoked in KIFF. Its schedule, chats, run reports, memory and key are deleted, and the name Refunds is free. KIFF's receipts stay.",
      words,
    )
  }

  @Test fun aRemovalNobodyConfirmedDoesNothing() = runTest {
    reads()
    auth.confirms = false
    val m = model()
    assertNull(m.removeTaf("refunds-1"))
    assertTrue(transport.requests.none { it.method == "DELETE" || it.url.contains("/mandates/") })
  }

  @Test fun aRemovalLeftHalfWaySaysWhatsLeftAndIsFinishedOnTheNextRefresh() = runTest {
    reads()
    transport.answer("POST /v1/me/mandates/c1/revoke", 200)
    transport.answer("POST /v1/me/mandates/c2/revoke", 200)
    transport.answer("DELETE /v1/tafs/taf_1", 200, """{"done":false,"removed":["schedule"],"failed":["history","memory"]}""")
    val m = model()
    assertEquals(
      "Its Cards are revoked in KIFF. Not finished: its runs and chats, memory couldn't be removed. tafs tries again each time it refreshes.",
      m.removeTaf("refunds-1"),
    )
    // tafs cloud lists it as a removal begun: the next refresh finishes it.
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[{"id":"taf_1","name":"Refunds"}]}""")
    m.refresh()
    assertEquals(2, transport.requestsTo("DELETE /v1/tafs/taf_1").size)
  }

  // MARK: What it remembers

  @Test fun whatItRemembersIsReadFromTafsCloudAndForgettingOneTellsIt() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/memory", 200, """{"notes":[{"id":"n1","path":"/customers/acme.md","content":"Acme pays late.","updated_at":"2026-10-27T09:00:00Z"}]}""")
    transport.answer("DELETE /v1/tafs/taf_1/memory/n1", 200, """{"forgotten":"n1"}""")
    val m = model()
    m.readMemory("refunds-1")
    val note = m.state.value.memories["refunds-1"]!!.single()
    assertEquals("customers · acme", note.title)
    assertTrue(m.forgetAtCloud(note, "refunds-1"))
    assertEquals(1, transport.requestsTo("DELETE /v1/tafs/taf_1/memory/n1").size)
  }

  @Test fun aNoteTafsCloudWouldntForgetComesBackSayingWhy() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/memory", 200, """{"notes":[{"id":"n1","path":"/a.md","content":"One","updated_at":"2026-10-27T09:00:00Z"}]}""")
    transport.answer("DELETE /v1/tafs/taf_1/memory/n1", 503, """{"error":"unavailable"}""")
    val m = model()
    m.readMemory("refunds-1")
    val note: MemoryNote = m.state.value.memories["refunds-1"]!!.single()
    assertFalse(m.forgetAtCloud(note, "refunds-1"))
    assertEquals(listOf("n1"), m.state.value.memories["refunds-1"]!!.map { it.id })
    assertEquals("It wasn't forgotten: unavailable", m.state.value.memoryErrors["refunds-1"])
  }

  @Test fun notesThatCantBeReadSaySo() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/memory", 500, """{"error":"down"}""")
    val m = model()
    m.readMemory("refunds-1")
    assertNull(m.state.value.memories["refunds-1"])
    assertEquals("Its notes couldn't be loaded: down", m.state.value.memoryErrors["refunds-1"])
  }
}
