package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Credits in Plan & usage (docs/accounts.md#credits): tafs cloud's two reads, read-only, every line in its words. A fake KIFF and
 * tafs cloud stand in, answering as the contract's sample responses do.
 */
class CreditsTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-11-07T09:00:00Z")

  private val period =
    """{"period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z","current":true},"credits":1240,"runs":61,
       "words":"This period: 1,240 credits used · ends on 1 Dec",
       "tafs":[{"taf":"taf_1","credits":820,"runs":41,"words":"Refunds · 820 credits · 41 runs"},
               {"taf":null,"credits":20,"runs":2,"words":"Tafs removed since · 20 credits · 2 runs"}],
       "coach":{"credits":46,"runs":2,"words":"Coach, past its fair use · 46 credits · 2 runs"},
       "not_counted":{"runs":12,"words":"Not counted: 12 runs","lines":[
         {"why":"coach","runs":7,"words":"Coach, within its fair use · 7 runs"},
         {"why":"later","runs":5,"words":"Something new · 5 runs"}]},
       "note":"Credits will replace tasks. Nothing is charged in credits yet."}"""
  private val lastPeriod =
    """{"period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z","current":false},"credits":980,"runs":50,
       "words":"Last period: 980 credits used · 1 Oct to 31 Oct","tafs":[],"coach":null,
       "not_counted":{"runs":0,"words":"Not counted: no runs","lines":[]}}"""

  private fun runs(first: Int, count: Int, next: String?, current: Boolean = true) =
    """{"period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z","current":$current},"taf":"taf_1","credits":820,
       "runs":41,"words":"Refunds · 820 credits · 41 runs this period","items":[""" +
      (first until first + count).joinToString(",") {
        """{"run":"run_$it","at":"2026-11-0${7 - it % 6}T08:00:41Z","credits":6,"counted":${it % 3 != 0},"why":${if (it % 3 == 0) "\"trial\"" else "null"},
            "words":"Its schedule · 6 credits","opens":${it % 3 != 0}}"""
      } + "]" + (next?.let { ""","next":"$it"""" } ?: "") + "}"

  private suspend fun model(credits: Pair<Int, String>?): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/usage", 200, """{"plan":"solo","period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"},"tasks":{"counted":1,"included":20,"packs":0,"left":19}}""")
    transport.answer("GET /v1/me/usage/tasks", 200, """{"items":[]}""")
    transport.answer("GET /v1/usage/ai", 200, """{"used_pct":10,"asks_at_pct":100,"paused":false}""")
    credits?.let { (status, body) -> transport.answer("GET /v1/usage/credits", status, body) }
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also {
      it.restore()
      it.refresh()
      it.refreshUsage()
    }
  }

  private fun query(key: String, n: Int = -1) = transport.requestsTo(key).let { if (n < 0) it.last() else it[n] }.let { URI.create(it.url).rawQuery }

  @Test fun plansAndUsageReadsThePeriodsCreditsInTafsCloudsWords() = runTest {
    val m = model(200 to period)
    val c = m.state.value.credits!!
    assertNull("this period: no query", query("GET /v1/usage/credits"))
    assertEquals("This period: 1,240 credits used · ends on 1 Dec", c.words)
    assertEquals(listOf("taf_1", null), c.tafs.map { it.taf })
    assertEquals("Coach, past its fair use · 46 credits · 2 runs", c.coach!!.words)
    // A reason this app doesn't know reads by its words.
    assertEquals(listOf("Coach, within its fair use · 7 runs", "Something new · 5 runs"), c.notCounted!!.lines.map { it.words })
    assertEquals("Credits will replace tasks. Nothing is charged in credits yet.", c.note)
    assertFalse(m.state.value.creditsPrevious)
    assertNull(m.state.value.creditsError)
    // The tasks are read as before, beside it.
    assertEquals(19, m.state.value.usage!!.tasks.left)
  }

  @Test fun aTafsCloudFromBeforeCreditsShowsNoPanel() = runTest {
    val m = model(404 to """{"error":"not found"}""")
    assertNull(m.state.value.credits)
    assertNull("a 404 isn't a failure", m.state.value.creditsError)
    assertEquals(19, m.state.value.usage!!.tasks.left)
  }

  @Test fun whenKiffCantBeReadTafsCloudsWordsAreShown() = runTest {
    val m = model(503 to """{"error":"KIFF didn't answer","words":"Credits can't be read right now. Try again in a minute."}""")
    assertNull(m.state.value.credits)
    assertEquals("Credits can't be read right now. Try again in a minute.", m.state.value.creditsError)
  }

  @Test fun theCoachRowIsNullWhileWithinFairUse() = runTest {
    val m = model(200 to period.replace(""""coach":{"credits":46,"runs":2,"words":"Coach, past its fair use · 46 credits · 2 runs"}""", """"coach":null"""))
    assertNull(m.state.value.credits!!.coach)
  }

  @Test fun theLastPeriodIsReadOnAskingAndAFailedReadKeepsWhatWasShown() = runTest {
    val m = model(200 to period)
    transport.answer("GET /v1/usage/credits", 200, lastPeriod)
    m.showCreditsPeriod(true)
    assertEquals("period=previous", query("GET /v1/usage/credits"))
    assertTrue(m.state.value.creditsPrevious)
    assertEquals("Last period: 980 credits used · 1 Oct to 31 Oct", m.state.value.credits!!.words)

    // Back to this period, and KIFF can't be read: the last period stays, said as not read.
    transport.answer("GET /v1/usage/credits", 503, """{"error":"KIFF didn't answer","words":"Credits can't be read right now."}""")
    m.showCreditsPeriod(false)
    assertTrue("still the period shown", m.state.value.creditsPrevious)
    assertEquals("Last period: 980 credits used · 1 Oct to 31 Oct", m.state.value.credits!!.words)
    assertEquals("Credits can't be read right now.", m.state.value.creditsError)

    // Plan & usage read again keeps to the period shown.
    transport.answer("GET /v1/usage/credits", 200, lastPeriod)
    m.refreshUsage()
    assertEquals("period=previous", query("GET /v1/usage/credits"))
    assertNull(m.state.value.creditsError)
  }

  @Test fun aTafsRowOpensItsRunsAPageAtATime() = runTest {
    val m = model(200 to period)
    transport.answer("GET /v1/usage/credits/tafs/taf_1", 200, runs(1, 50, "cursor_1"))
    m.openTafCredits("taf_1")
    assertNull(query("GET /v1/usage/credits/tafs/taf_1"))
    val open = m.state.value.tafCredits!!
    assertEquals("Refunds · 820 credits · 41 runs this period", open.words)
    assertEquals(50, open.items.size)
    assertEquals("trial", open.items.first { !it.counted }.why)
    assertFalse("a trial's row never opens", open.items.first { !it.counted }.opens)

    transport.answer("GET /v1/usage/credits/tafs/taf_1", 200, runs(51, 3, null))
    m.moreTafCredits()
    assertEquals("after=cursor_1", query("GET /v1/usage/credits/tafs/taf_1"))
    assertEquals((1..53).map { "run_$it" }, m.state.value.tafCredits!!.items.map { it.run })
    assertNull(m.state.value.tafCredits!!.next)

    // No next page: More asks for nothing.
    val asked = transport.requestsTo("GET /v1/usage/credits/tafs/taf_1").size
    m.moreTafCredits()
    assertEquals(asked, transport.requestsTo("GET /v1/usage/credits/tafs/taf_1").size)

    m.closeTafCredits()
    assertNull(m.state.value.tafCredits)
  }

  @Test fun theLastPeriodsRowOpensTheLastPeriodsRuns() = runTest {
    val m = model(200 to period)
    transport.answer("GET /v1/usage/credits", 200, lastPeriod)
    m.showCreditsPeriod(true)
    transport.answer("GET /v1/usage/credits/tafs/taf_1", 200, runs(1, 2, "cursor_1", current = false))
    m.openTafCredits("taf_1")
    assertEquals("period=previous", query("GET /v1/usage/credits/tafs/taf_1"))
    transport.answer("GET /v1/usage/credits/tafs/taf_1", 200, runs(3, 1, null, current = false))
    m.moreTafCredits()
    assertEquals("period=previous&after=cursor_1", query("GET /v1/usage/credits/tafs/taf_1"))
  }

  @Test fun aFailedPageKeepsTheRunsReadAndSaysSo() = runTest {
    val m = model(200 to period)
    transport.answer("GET /v1/usage/credits/tafs/taf_1", 200, runs(1, 2, "cursor_1"))
    m.openTafCredits("taf_1")
    transport.drop("GET /v1/usage/credits/tafs/taf_1")
    m.moreTafCredits()
    assertEquals(2, m.state.value.tafCredits!!.items.size)
    assertEquals("cursor_1", m.state.value.tafCredits!!.next)
    assertTrue(m.state.value.tafCreditsError!!.startsWith("More runs couldn't be read."))
    assertFalse(m.state.value.readingCredits)
  }

  @Test fun aRunsRowOpensItsRequestOnTheTafTafsCloudNames() = runTest {
    val m = model(200 to period)
    transport.answer("GET /v1/tafs/taf_1/requests/run_2", 200, """{"id":"run_2","at":"2026-11-02T22:41:00Z","state":"done","line":"Refund $20 · order 4402","from":"Its schedule","deliveries":[],"steps":[]}""")
    m.openCreditsRun("taf_1", "run_2")
    val (taf, detail) = m.state.value.openRequest!!
    assertEquals("refunds-1", taf)
    assertEquals("run_2", detail.id)
  }

  @Test fun aRunOfATafNotOnThisPhoneSaysWhyItDoesntOpen() = runTest {
    val m = model(200 to period)
    m.openCreditsRun("taf_gone", "run_2")
    assertNull(m.state.value.openRequest)
    assertTrue(transport.requestsTo("GET /v1/tafs/taf_gone/requests/run_2").isEmpty())
    assertTrue(m.state.value.requestError!!.startsWith("That request couldn't be read"))
  }
}
