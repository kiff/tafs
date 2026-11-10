package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.BalanceNow
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The balance (docs/accounts.md#the-balance-read): tafs cloud's read for Plan & usage, Settings' Plan card and Home's line, every
 * line in its words; KIFF's usage read only for the store, who bills the plan and requests; and a run refused for credits. A fake
 * KIFF and tafs cloud stand in.
 */
class BalanceTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-11-07T09:00:00Z")

  private val solo =
    """{"balance":{"state":"ok","why":null,"meter_pct":48,"words":"Solo · 260 of 500 credits left this period",
       "detail":"Then 900 in packs, which never expire","warning":null,"raise":null,"buy":{"solo":false,"packs":true}},
       "plan_line":"Solo · 260 credits left","plan_tone":"ok","home_line":null}"""
  private val warning =
    """{"balance":{"state":"warning","why":null,"meter_pct":80,"words":"Company · 4 seats · 80% of the spend limit used",
       "detail":"All the included credits are used. Beyond them, credits go on this period's invoice.",
       "warning":"80% of the spend limit is used. At the limit, your tafs wait until an admin raises it.",
       "raise":{"words":"Raise the spend limit by one step","from_cents":8000},"buy":null},
       "plan_line":"Company · 80% of the limit used","plan_tone":"warning","home_line":"80% of the spend limit is used"}"""
  private val noPlan =
    """{"balance":{"state":"waiting","why":"no_plan","meter_pct":null,"words":"No plan","detail":null,
       "warning":"Choose a plan to start your tafs.","raise":null,"buy":{"solo":true,"packs":false}},
       "plan_line":"No plan · choose one to start","plan_tone":"waiting","home_line":"Choose a plan to start your tafs."}"""

  private suspend fun model(balance: Pair<Int, String>?, tafs: String = "[]"): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":$tafs,"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    // KIFF's usage read keeps its old fields through phase 2, inert: the app reads only what it needs and ignores the rest.
    transport.answer("GET /v1/me/usage", 200, """{"plan":"solo","period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"},
      "tasks":{"counted":0,"included":0},"trial":null,"can_add_tasks":true,"billing":{"source":"google"},"store":{"packs":[]}}""")
    balance?.let { (status, body) -> transport.answer("GET /v1/usage/balance", status, body) }
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
  }

  @Test fun eachRefreshReadsTheBalanceForThePlanCardAndHomesLine() = runTest {
    val m = model(200 to warning)
    m.refresh()
    val b = m.state.value.balance!!
    assertEquals("Company · 80% of the limit used", b.planLine)
    assertEquals(BalanceNow.WARNING, b.planTone)
    assertEquals("80% of the spend limit is used", b.homeLine)
    // The plan line is tafs cloud's alone: KIFF's usage read isn't asked for it.
    assertTrue(transport.requestsTo("GET /v1/me/usage").isEmpty())
  }

  @Test fun plansAndUsageOpensOnTheBalanceInTafsCloudsWords() = runTest {
    val m = model(200 to solo)
    m.refreshUsage()
    val now = m.state.value.balance!!.balance
    assertEquals("Solo · 260 of 500 credits left this period", now.words)
    assertEquals("Then 900 in packs, which never expire", now.detail)
    assertEquals(48, now.meterPct)
    assertNull(now.warning)
    assertNull("no spend limit on Solo", now.raise)
    assertNull("Home stays about the tafs", m.state.value.balance!!.homeLine)
    // KIFF's usage read is still read, for who bills the plan and the store.
    assertEquals("google", m.state.value.usage!!.billing!!.source)
    assertEquals("solo", m.state.value.usage!!.plan)
  }

  @Test fun buyOffersSoloAndPacksAsTheBalanceSays() {
    fun offers(buy: String) = KiffClient.json.decodeFromString(BalanceNow.serializer(), """{"words":"x","buy":$buy}""")
    assertTrue(offers("""{"solo":true,"packs":false}""").offers("solo"))
    assertFalse(offers("""{"solo":true,"packs":false}""").offers("packs"))
    assertFalse(offers("null").offers("solo"))
    assertFalse(offers("""{"solo":null}""").offers("solo"))
  }

  @Test fun theCreditsPanelShowsUnlessTafsCloudSaysNot() {
    fun read(extra: String) = KiffClient.json.decodeFromString(BalanceNow.serializer(), """{"words":"x"$extra}""")
    assertFalse(read(""","credits_panel":false""").creditsPanel)
    assertTrue(read(""","credits_panel":true""").creditsPanel)
    // A tafs cloud from before the flag: shown, as before.
    assertTrue(read("").creditsPanel)
  }

  @Test fun theFirstMonthPackIsReadyWhenOfferedAndWaitsWithTafsCloudsWords() {
    fun offers(buy: String) = KiffClient.json.decodeFromString(BalanceNow.serializer(), """{"words":"x","buy":$buy}""")
    assertEquals(BalanceNow.FirstMonth(ready = true, words = null), offers("""{"solo":false,"packs":true,"first_month":{"ready":true,"words":null}}""").firstMonth)
    assertEquals(
      BalanceNow.FirstMonth(ready = false, words = "Add it once Solo starts."),
      offers("""{"solo":true,"packs":false,"first_month":{"ready":false,"words":"Add it once Solo starts."}}""").firstMonth,
    )
    // Not ready unless tafs cloud says so.
    assertEquals(BalanceNow.FirstMonth(ready = false, words = null), offers("""{"solo":true,"first_month":{}}""").firstMonth)
    assertNull(offers("""{"solo":true,"packs":false}""").firstMonth)
    assertNull(offers("""{"solo":true,"first_month":false}""").firstMonth)
    assertNull(offers("""{"solo":true,"first_month":null}""").firstMonth)
    assertNull(offers("null").firstMonth)
  }

  @Test fun theFirstMonthPackIsNeverOneOfThePacks() {
    val store = KiffClient.json.decodeFromString(
      bot.tafs.app.kiff.UsageStore.serializer(),
      """{"packs":[{"id":"rayo","name":"Rayo","google_product":"pack_rayo"},{"id":"credits-firstmonth","name":"First month pack","once":true,"google_product":"bot.tafs.credits.firstmonth"}]}""",
    )
    assertEquals(listOf("rayo"), store.repeatPacks.map { it.id })
    assertEquals("credits-firstmonth", store.firstMonthPack?.id)
    // Play's product is read for it too, so its price shows.
    assertTrue("bot.tafs.credits.firstmonth" in store.googleProducts)
    // KIFF drops it once bought: then there's none.
    assertNull(KiffClient.json.decodeFromString(bot.tafs.app.kiff.UsageStore.serializer(), """{"packs":[{"id":"rayo","name":"Rayo"}]}""").firstMonthPack)
  }

  @Test fun withNoPlanTheBalanceSaysToChooseOneAndHasNoBar() = runTest {
    val m = model(200 to noPlan)
    m.refreshUsage()
    val b = m.state.value.balance!!
    assertTrue(b.balance.waiting)
    assertEquals("no_plan", b.balance.why)
    assertNull(b.balance.meterPct)
    assertEquals("No plan · choose one to start", b.planLine)
    assertEquals("Choose a plan to start your tafs.", b.homeLine)
  }

  @Test fun aTafsCloudFromBeforeTheBalanceShowsNone() = runTest {
    val m = model(404 to """{"error":"not found"}""")
    m.refreshUsage()
    assertNull(m.state.value.balance)
    assertNull("a 404 isn't a failure", m.state.value.balanceError)
  }

  @Test fun aRefreshThatFailsShowsTafsCloudsWordsAndKeepsWhatWasRead() = runTest {
    val m = model(200 to solo)
    m.refreshUsage()
    transport.answer("GET /v1/usage/balance", 503, """{"error":"KIFF didn't answer","words":"The balance can't be read right now. Try again in a minute."}""")
    m.refreshUsage()
    assertEquals("Solo · 260 of 500 credits left this period", m.state.value.balance!!.balance.words)
    assertEquals("The balance can't be read right now. Try again in a minute.", m.state.value.balanceError)
    transport.answer("GET /v1/usage/balance", 200, solo)
    m.refreshUsage()
    assertNull(m.state.value.balanceError)
  }

  @Test fun theSampleShowsABalanceAndCredits() = runTest {
    val m = testModel(start = false)
    m.startSample()
    m.refreshUsage()
    assertEquals(Mode.sample, m.state.value.mode)
    assertEquals("Solo · 260 credits left", m.state.value.balance!!.planLine)
    assertEquals("This period: 1,240 credits used · ends on 30 Nov", m.state.value.credits!!.words)
    m.showCreditsPeriod(true)
    assertTrue(m.state.value.creditsPrevious)
    assertEquals("Last period: 2,310 credits used · 1 Oct to 31 Oct", m.state.value.credits!!.words)
    assertTrue("the sample reads nothing", transport.requests.isEmpty())
  }

  @Test fun leavingTheAccountForgetsTheBalance() = runTest {
    val m = model(200 to solo)
    m.refreshUsage()
    assertNotNull(m.state.value.balance)
    m.signOut()
    m.refreshUsage()
    assertNull(m.state.value.balance)
    assertNull(m.state.value.usage)
    assertNull(m.state.value.credits)
  }

  @Test fun runNowRefusedForCreditsShowsTafsCloudsWordsAsTheyAre() = runTest {
    val m = model(200 to noPlan, tafs = """[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}]""")
    m.refresh()
    transport.answer("POST /v1/tafs/taf_1/run", 409, """{"error":"no plan","code":"no_plan","words":"Choose a plan to start your tafs."}""")
    assertNull(m.runNow("refunds-1"))
    assertEquals("Choose a plan to start your tafs.", m.state.value.lastError)
  }

  @Test fun anotherRefusalOfRunNowKeepsTheAppsLead() = runTest {
    val m = model(200 to solo, tafs = """[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}]""")
    m.refresh()
    transport.answer("POST /v1/tafs/taf_1/run", 409, """{"error":"halted","code":"halted","words":"Refunds is halted. Resume it to run it."}""")
    assertNull(m.runNow("refunds-1"))
    assertEquals("The run didn't start. Refunds is halted. Resume it to run it.", m.state.value.lastError)
  }
}
