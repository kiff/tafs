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

  // MARK: Its work (docs/reliability.md, "The taf's view")

  private val work =
    """{"taf":"taf_1","time_zone":"Europe/Paris","summary":{"status":"needs_you","line":"1 needs you","needs_you":1},"next_run":"2026-10-28T12:00:00Z",
        "counts":{"done":1,"needs_you":1},
        "requests":[
          {"id":"run_2","at":"2026-10-28T08:40:00Z","state":"done","line":"Answered Mara: refund of €15 done.","deliveries":[{"place":"door_reply","state":"not_sent","line":"The reply to Mara didn't go after 3 tries.","send_again":true}]},
          {"id":"run_1","at":"2026-10-28T07:00:00Z","state":"needs_you","line":"Waits for your answer on a €900 refund.","deliveries":[]}],
        "skipped":[{"due":"2026-10-28T08:00:00Z","why":"busy","words":"Skipped the 9:00 run: the last one was still working"}]}"""

  @Test fun itsWorkReadsAsTafsCloudSaysWithSkippedFiringsInTheirPlaceByTime() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/work", 200, work)
    val m = model()
    m.refresh()
    m.loadWork("refunds-1")
    val read = m.state.value.works["refunds-1"]!!
    assertEquals("1 needs you", read.summary?.line)
    // Newest first, the skipped firing between the two requests by its time.
    val rows = bot.tafs.app.model.Chat.workRows(read)
    assertEquals(listOf("run_2", "skipped", "run_1"), rows.map { (it as? bot.tafs.app.model.Chat.WorkRow.Request)?.request?.id ?: "skipped" })
    assertEquals("Skipped the 9:00 run: the last one was still working", (rows[1] as bot.tafs.app.model.Chat.WorkRow.Skipped).firing.words)
    assertTrue(read.requests.first().deliveries.single().sendAgain)
  }

  @Test fun sendAgainSendsThatRunsReplyOnceAndReadsTheWorkAgain() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/work", 200, work)
    transport.answer("POST /v1/tafs/taf_1/requests/run_2/deliveries/door_reply/send", 200, """{"state":"sent"}""")
    val m = model()
    m.refresh()
    m.loadWork("refunds-1")
    m.sendAgain("refunds-1", "run_2")
    assertEquals(1, transport.requestsTo("POST /v1/tafs/taf_1/requests/run_2/deliveries/door_reply/send").size)
    assertEquals(2, transport.requestsTo("GET /v1/tafs/taf_1/work").size)
    assertFalse(m.state.value.sendingAgain.contains("run_2"))
  }

  @Test fun aSendAgainTafsCloudRefusesSaysSoInTheAppsWords() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/work", 200, work)
    transport.answer("POST /v1/tafs/taf_1/requests/run_2/deliveries/door_reply/send", 409, """{"error":"it's being sent"}""")
    val m = model()
    m.refresh()
    m.sendAgain("refunds-1", "run_2")
    assertEquals("It wasn't sent again. " + bot.tafs.app.model.ErrorWords.CHANGED, m.state.value.workErrors["refunds-1"])
  }

  @Test fun aTafPushReadsItsWorkAgainWhereItsBeenRead() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/work", 200, work)
    val m = model()
    m.refresh()
    m.loadWork("refunds-1")
    m.readChange("taf", "taf_1")
    assertEquals(2, transport.requestsTo("GET /v1/tafs/taf_1/work").size)
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

  @Test fun aRunNowKiffAgentsRefusedSaysWhyInTafsCloudsWords() = runTest {
    reads()
    transport.answer("POST /v1/tafs/taf_1/run", 409, """{"error":"refused","code":"ai_fair_use","words":"Your tafs have used this period's AI. It starts again on 1 Nov."}""")
    val m = model()
    assertNull(m.runNow("refunds-1"))
    assertEquals("The run didn't start. Your tafs have used this period's AI. It starts again on 1 Nov.", m.state.value.lastError)
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
    assertEquals("The run didn't start. It changed in the meantime. What's shown now is how it is.", m.state.value.lastError)
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
    assertEquals("KIFF didn't revoke the Card. Your role on this account doesn't allow this.", m.state.value.lastError)
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

  // MARK: The Record

  @Test fun aRefreshNamesWhatEachLineActedOnFromTafsCloudsCallsCheckedAgainstKiffsHash() = runTest {
    val hash = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest("pi_3Q".toByteArray()).joinToString("") { "%02x".format(it) }
    reads()
    transport.answer(
      "GET /v1/me/activity", 200,
      """{"calls":[{"proposal_id":"p1","action_name":"create_refund","title":"Refund a payment","actor_id":"refunds-1","outcome":"allowed","created_at":"2026-10-28T08:00:00Z","entity_id":"inv_1","on_hash":"$hash","on_arg":"payment_intent"}]}""",
    )
    transport.answer("GET /v1/tafs/taf_1/calls", 200, """{"calls":[{"invocation":"inv_1","value":"pi_3Q"}]}""")
    val m = model()
    // The first read took reads()' empty activity; this one has the call.
    m.refresh()
    val line = m.state.value.record.single { it.entityId == "inv_1" }
    assertEquals("pi_3Q", line.onValue)
    assertEquals(1, transport.requestsTo("GET /v1/tafs/taf_1/calls").size)
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
    assertEquals("It wasn't forgotten. It isn't working on our side right now. Nothing to do on your side; try again later.", m.state.value.memoryErrors["refunds-1"])
  }

  @Test fun notesThatCantBeReadSaySo() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/memory", 500, """{"error":"down"}""")
    val m = model()
    m.readMemory("refunds-1")
    assertNull(m.state.value.memories["refunds-1"])
    assertEquals("Its notes couldn't be loaded. It isn't working on our side right now. Nothing to do on your side; try again later.", m.state.value.memoryErrors["refunds-1"])
  }
}
