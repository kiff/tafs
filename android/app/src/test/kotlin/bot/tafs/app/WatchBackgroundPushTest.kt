package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.push.PushPlatform
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A phone that serves a Wear OS watch takes the watch's background pushes whatever its alerts (docs/watch-wear.md, Delivery):
 * with alerts off it registers with `alerts: false`, never handing over an alert target, and takes it back at sign-out. The
 * owner's Galaxy S23 had alerts off, so tafs cloud had no Android phone to tell, and a taf's answer never reached the watch.
 */
class WatchBackgroundPushTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val t0 = Instant.parse("2026-11-25T17:00:00Z")
  private val register = "POST /v1/push"

  private class FakePush(var enabled: Boolean = false, var allow: Boolean = false) : PushPlatform {
    override val available: Boolean = true
    override suspend fun allowed(): Boolean = allow
    override fun enabledNow(): Boolean = enabled
    override suspend fun token(): String = "fcm_token_1"
  }

  private val push = FakePush()

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer(register, 200, """{"answers":false,"alerts":false}""")
    transport.answer("DELETE /v1/push", 200, """{"removed":true}""")
    transport.answer("DELETE /v1/push/fcm_token_1", 200, """{"removed":true}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = softwareSigner(auth), clock = { t0 }, start = false, push = push)
      .also { it.restore() }
  }

  private fun body(index: Int) = KiffClient.json.parseToJsonElement(String(transport.requestsTo(register)[index].body!!)).jsonObject

  @Test fun alertsOffAndAWatchRegistersForBackgroundPushesOnly() = runTest {
    reads()
    val m = model()
    assertTrue("alerts off, no watch yet: nothing registered", transport.requestsTo(register).isEmpty())
    m.watchUsed()
    m.syncPush()
    val sent = body(transport.requestsTo(register).lastIndex)
    assertEquals("false", sent.getValue("alerts").jsonPrimitive.content)
    assertEquals("fcm_token_1", sent.getValue("device_token").jsonPrimitive.content)
    assertNull("no alert target for a background-only phone", sent["alert_target"])
    assertEquals("fcm_token_1", secrets.load(AppModel.BACKGROUND_PUSH))
    // A background-only phone shows no alerts as on.
    assertFalse(m.state.value.alertsReady)
  }

  @Test fun alertsOffAndNoWatchIsNeverRegistered() = runTest {
    reads()
    val m = model()
    m.syncPush()
    assertTrue(transport.requestsTo(register).isEmpty())
    assertNull(secrets.load(AppModel.BACKGROUND_PUSH))
  }

  @Test fun alertsOnRegistersAsBefore() = runTest {
    reads()
    secrets.save("1", AppModel.ALERTS_ON)
    val m = model()
    m.watchUsed()
    m.syncPush()
    for (i in transport.requestsTo(register).indices) assertNull("with alerts on, never background-only", body(i)["alerts"])
  }

  @Test fun signOutTakesTheBackgroundRegistrationBack() = runTest {
    reads()
    val m = model()
    m.watchUsed()
    m.syncPush()
    m.signOut()
    assertEquals(1, transport.requestsTo("DELETE /v1/push/fcm_token_1").size)
    assertNull(secrets.load(AppModel.BACKGROUND_PUSH))
    assertNull("the next sign-in starts unserved", secrets.load(AppModel.WATCH_SERVED))
  }

  @Test fun turningAlertsOffKeepsTheWatchsBackgroundPushes() = runTest {
    reads()
    secrets.save("1", AppModel.ALERTS_ON)
    val m = model()
    m.watchUsed()
    m.turnOffAlerts()
    val last = body(transport.requestsTo(register).lastIndex)
    assertEquals("false", last.getValue("alerts").jsonPrimitive.content)
  }

  @Test fun theWatchIsServedOncePerSignIn() = runTest {
    reads()
    val m = model()
    m.watchUsed()
    assertEquals("t_01", secrets.load(AppModel.WATCH_SERVED))
    m.signOut()
    assertNull(secrets.load(AppModel.WATCH_SERVED))
  }
}
