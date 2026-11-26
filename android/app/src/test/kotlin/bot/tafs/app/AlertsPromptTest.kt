package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.model.Words
import bot.tafs.app.push.PushPlatform
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alerts asked for where Android no longer asks for notifications: a guided dialog with the way to tafs's own switch, never a
 * bare "Not done"; back from Settings, alerts turn on with no other tap. And "Your watch is using tafs", once per phone.
 */
class AlertsPromptTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val t0 = Instant.parse("2026-11-25T17:00:00Z")

  private class FakePush(var enabled: Boolean = false) : PushPlatform {
    override val available: Boolean = true
    override suspend fun allowed(): Boolean = enabled
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
    transport.answer("POST /v1/me/alert-targets", 200, """{"id":"tgt_1","signing_secret":"whsec_abcdefghijklmnopqrstuvwxyz0123"}""")
    transport.answer("POST /v1/push", 200, """{"answers":true,"alerts":true}""")
    transport.answer("GET /v1/push", 200, """{"enabled":true,"sound":true,"kinds":[]}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = softwareSigner(auth), clock = { t0 }, start = false, push = push)
      .also { it.restore() }
  }

  @Test fun blockedNotificationsShowTheGuidedDialogNotABareNotDone() = runTest {
    reads()
    val m = model()
    assertFalse(m.turnOnAlerts())
    assertTrue(m.state.value.alertsBlocked)
    assertNull("no bare error line", m.state.value.lastError)
    assertEquals("Turn on alerts for tafs", Words.Alert.Blocked.title)
    assertEquals("Open notification settings", Words.Alert.openNotificationSettings)
  }

  @Test fun backFromSettingsWithNotificationsAllowedAlertsTurnOnWithNoOtherTap() = runTest {
    reads()
    val m = model()
    m.turnOnAlerts()
    m.leftForNotificationSettings()
    assertFalse(m.state.value.alertsBlocked)
    push.enabled = true
    m.recheckAlerts()
    assertEquals("1", secrets.load(AppModel.ALERTS_ON))
    assertEquals(Words.Alert.nowOn, m.state.value.alertsRecheckLine)
  }

  @Test fun backFromSettingsStillBlockedSaysSoOnce() = runTest {
    reads()
    val m = model()
    m.turnOnAlerts()
    m.leftForNotificationSettings()
    m.recheckAlerts()
    assertEquals(Words.Alert.stillOff, m.state.value.alertsRecheckLine)
    m.dismissAlertsRecheckLine()
    // A resume that didn't come from the dialog looks at nothing.
    m.recheckAlerts()
    assertNull(m.state.value.alertsRecheckLine)
  }

  @Test fun theWatchPromptShowsOncePerPhoneAndOnlyWithNotificationsOff() = runTest {
    reads()
    val m = model()
    m.watchUsed()
    assertTrue(m.state.value.watchAlertsOffer)
    m.dismissWatchAlertsOffer()
    m.signOut()
    val again = model()
    again.watchUsed()
    assertFalse("never again on this phone", again.state.value.watchAlertsOffer)
  }

  @Test fun theWatchPromptIsntShownWhenNotificationsAreOn() = runTest {
    reads()
    push.enabled = true
    val m = model()
    m.watchUsed()
    assertFalse(m.state.value.watchAlertsOffer)
  }
}
