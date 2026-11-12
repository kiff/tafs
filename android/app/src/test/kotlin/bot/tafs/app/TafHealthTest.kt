package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.TafWork
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Skipped work is never healthy (docs/reliability.md › Its health): a taf's work carries its `health` as tafs cloud decides it, each
 * reason a line, and a fix for the lines that have one. The skipped firings that started no run come with their own words. The app
 * reads both as they are and works out nothing.
 */
class TafHealthTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-11-11T09:00:00Z")

  private val work =
    """{"taf":"taf_1","summary":{"status":"idle","line":"Nothing ran today"},"requests":[],""" +
      """"skipped":[""" +
      """{"due":"2026-11-11T07:00:00Z","why":"too_large","words":"Skipped the 8:00 run: its check found work too big for one run"},""" +
      """{"due":"2026-11-11T08:00:00Z","why":"credits","words":"Skipped the 9:00 run: waiting for credits"}],""" +
      """"health":{"level":"failing","lines":["Its last 2 checks found work too big for one run, so nothing ran.","Waiting for credits since 09:00."],""" +
      """"fixes":[{"line":0,"kind":"coach","words":"Ask Coach to narrow what it reads","say":"Refunds' check finds work too big for one run. Narrow what it reads."},""" +
      """{"line":1,"kind":"credits","words":"Add credits"}]}}"""

  private fun read(json: String) = KiffClient.json.decodeFromString<TafWork>(json)

  @Test fun healthReadsAsTafsCloudSendsItWithAFixPerLine() {
    val h = read(work).health!!
    assertEquals("failing", h.level)
    assertEquals(2, h.lines.size)
    assertEquals("coach", h.fixFor(0)?.kind)
    assertEquals("Refunds' check finds work too big for one run. Narrow what it reads.", h.fixFor(0)?.say)
    assertEquals("Add credits", h.fixFor(1)?.words)
    assertNull(h.fixFor(1)?.say)
    assertNull(h.fixFor(2))
  }

  @Test fun theNewSkippedReasonsReadWithTheirOwnWords() {
    val skipped = read(work).skipped
    assertEquals(listOf("too_large", "credits"), skipped.map { it.why })
    assertEquals("Skipped the 9:00 run: waiting for credits", skipped[1].words)
  }

  /** A run's credits are tafs cloud's words from KIFF's usage record, drawn as sent; a run not counted yet has none. */
  @Test fun aRunsCreditsAreTafsCloudsLineAndNoneIsNone() {
    val work = read(
      """{"summary":{"status":"idle","line":"Idle"},"requests":[
        {"id":"r1","at":"2026-11-12T19:43:00Z","state":"done","line":"Done at 20:43","credits":{"credits":34,"counted":true,"words":"34 credits"}},
        {"id":"r2","at":"2026-11-12T18:48:00Z","state":"failed","line":"It couldn't start","credits":{"credits":0,"counted":false,"words":"No credits: it failed"}},
        {"id":"r3","at":"2026-11-12T18:00:00Z","state":"done","line":"Done at 19:00"}],"skipped":[]}""",
    )
    assertEquals("34 credits", work.requests[0].credits!!.words)
    assertEquals("why it used none, in tafs cloud's words", "No credits: it failed", work.requests[1].credits!!.words)
    assertNull("not counted yet: no line, and nothing counted here", work.requests[2].credits)
  }

  @Test fun anOlderTafsCloudWithoutHealthOrFixesStillReads() {
    assertNull(read("""{"summary":{"status":"idle","line":""},"requests":[],"skipped":[]}""").health)
    val h = read("""{"health":{"level":"at_risk","lines":["One scheduled run couldn't start."]}}""").health!!
    assertEquals(emptyList<Any>(), h.fixes)
    assertNull(h.fixFor(0))
  }

  @Test fun aTafsWorkReadCarriesItsHealth() = runTest {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage","holds.answer"]}""")
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""",
    )
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/tafs/taf_1/work", 200, work)
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    val m = testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
    m.refresh()
    m.loadWork("refunds-1")
    assertEquals("failing", m.state.value.works["refunds-1"]?.health?.level)
  }

  private suspend fun home(vararg tafs: String): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage","holds.answer"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[${tafs.joinToString(",")}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore(); it.refresh() }
  }

  private fun homeTaf(agent: String, state: String, health: String?, work: String?) =
    """{"id":"taf_$agent","name":"$agent","kiff_agent":"$agent","color":1,"state":"$state","card":{"ids":["c1"],"active":true}""" +
      (health?.let { ""","health":$it""" } ?: "") + (work?.let { ""","work":$it""" } ?: "") + "}"

  private val failing = """{"level":"failing","lines":["Its last 2 checks found work too big for one run, so nothing ran."]}"""

  @Test fun homeShowsTafsCloudsWorkLineTintedByHealthOnlyWhileItsWorkIsIdle() = runTest {
    val m = home(
      // Idle: tafs cloud's work line is the health reason, tinted by its level.
      homeTaf("refunds", "idle", failing, """{"status":"idle","line":"Its last 2 checks found work too big for one run, so nothing ran."}"""),
      homeTaf("invoices", "idle", """{"level":"at_risk","lines":["Waiting for credits since 09:00."]}""", """{"status":"idle","line":"Waiting for credits since 09:00."}"""),
      // Working, or retrying: what it's doing comes first, as tafs cloud orders it, with no tint.
      homeTaf("leads", "working", failing, """{"status":"working","line":"Working on 2 requests"}"""),
      homeTaf("disputes", "idle", failing, """{"status":"retrying","line":"Retrying at 10:15"}"""),
      // Healthy, or no health read: nothing changes.
      homeTaf("orders", "idle", """{"level":"healthy","lines":[]}""", """{"status":"idle","line":"Nothing ran today"}"""),
      homeTaf("returns", "idle", null, null),
    )
    val byId = m.state.value.tafs.associateBy { it.id }
    assertEquals("Its last 2 checks found work too big for one run, so nothing ran.", byId["refunds"]?.now)
    assertEquals("failing", byId["refunds"]?.nowHealth)
    assertEquals("Waiting for credits since 09:00.", byId["invoices"]?.now)
    assertEquals("at_risk", byId["invoices"]?.nowHealth)
    assertNull(byId["leads"]?.nowHealth)
    assertNull(byId["disputes"]?.nowHealth)
    assertNull(byId["orders"]?.nowHealth)
    assertEquals(bot.tafs.app.model.Words.Taf.Now.actingUnderKiff, byId["orders"]?.now)
    assertNull(byId["returns"]?.nowHealth)
  }

  @Test fun theTafsPageSaysTheFirstReasonOnce() {
    val h = read("""{"health":{"level":"at_risk","lines":["Its last check found work too big for one run, so nothing ran. Narrow what it reads with Coach.","Two of its last ten runs failed."]}}""").health!!
    // The work's line is the first reason, alone or with what to do.
    assertTrue(h.repeats("Its last check found work too big for one run, so nothing ran"))
    assertTrue(h.repeats("Its last check found work too big for one run, so nothing ran. Narrow what it reads with Coach."))
    // Any other line is said.
    assertFalse(h.repeats("Next run today at 14:00"))
    assertFalse(h.repeats("Two of its last ten runs failed"))
    assertFalse(h.repeats("  "))
    // A healthy taf's line is said.
    assertFalse(read("""{"health":{"level":"healthy","lines":["Its last check found work too big for one run"]}}""").health!!.repeats("Its last check found work too big for one run"))
  }
}
