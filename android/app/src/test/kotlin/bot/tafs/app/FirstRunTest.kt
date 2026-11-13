package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.Recipes
import bot.tafs.app.kiff.ResultOutcome
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A simpler first run (docs/android-app.md): no time-zone question for a new account, "Say it in your own words" first on Home,
 * the taf made with no plan said so, and alerts offered once on a new taf's card. tafs cloud's words; the app draws them.
 */
class FirstRunTest {
  private val t0 = Instant.parse("2026-11-13T09:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()

  private val pushOn = object : bot.tafs.app.push.PushPlatform {
    override val available = true
    override suspend fun allowed() = true
    override suspend fun token(): String? = "fcm_token_1"
  }

  private fun reads(zone: String = "UTC") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","time_zone":"$zone","capabilities":["members.manage","cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
  }

  private suspend fun model(push: bot.tafs.app.push.PushPlatform = bot.tafs.app.push.NoPush): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false, push = push).also { it.restore() }
  }

  @Test fun theSetupReadSendsThisPhonesZoneAndANewAccountsZoneSetByTafsCloudAsksNothing() = runTest {
    reads()
    val m = model()
    val phone = java.time.ZoneId.systemDefault().id
    transport.answer("GET /v1/setup", 200, """{"setup":null,"time_zone":"Europe/Paris"}""")
    m.loadCoach()
    assertEquals(phone, transport.requestsTo("GET /v1/setup").last().headers["X-Time-Zone"])
    assertEquals("Europe/Paris", m.state.value.kiffAccount?.timeZone)
    // Asked only for another zone than the one tafs cloud set.
    assertTrue(m.state.value.timeZoneOffer == null || m.state.value.timeZoneOffer != "Europe/Paris")
  }

  @Test fun homeLeadsWithTheOwnWordsTafsCloudSends() {
    val r = KiffClient.json.decodeFromString(
      Recipes.serializer(),
      """{"categories":[],"recipes":[],"home":{"shown":true,"words":"Pick a job","own_words":{"title":"Say it in your own words","line":"Coach builds the taf with you."}}}""",
    )
    assertEquals("Say it in your own words", r.home?.ownWords?.title)
    assertEquals("Coach builds the taf with you.", r.home?.ownWords?.line)
    // A tafs cloud from before it: none, and Home is as before.
    assertNull(KiffClient.json.decodeFromString(Recipes.serializer(), """{"home":{"shown":true,"words":"Pick a job"}}""").home?.ownWords)
  }

  @Test fun aTafMadeWithNoPlanSaysSoOnItsCard() {
    val o = KiffClient.json.decodeFromString(ResultOutcome.serializer(), """{"taf_id":"taf-1","plan_needed":{"words":"Choose a plan to start it."}}""")
    assertEquals("taf-1", o.tafId)
    assertEquals("Choose a plan to start it.", o.planNeeded?.words)
    assertNull(KiffClient.json.decodeFromString(ResultOutcome.serializer(), """{"taf_id":"taf-1"}""").planNeeded)
  }

  @Test fun alertsAreOfferedOncePerAccountOnThisPhone() = runTest {
    reads()
    val m = model(pushOn)
    assertTrue("alerts can be on and aren't, never offered here", m.state.value.offersAlerts)
    m.notNowAlertsOffer()
    assertFalse(m.state.value.offersAlerts)
    // Kept on this phone for this account: a new launch doesn't offer again.
    reads()
    val again = model(pushOn)
    assertFalse(again.state.value.offersAlerts)
  }

  @Test fun noOfferWhereAlertsCantBeOn() = runTest {
    reads()
    val m = model()
    assertFalse(m.state.value.offersAlerts)
  }
}
