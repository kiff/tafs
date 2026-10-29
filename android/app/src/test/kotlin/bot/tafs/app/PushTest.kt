package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.AlertWords
import bot.tafs.app.push.Alert
import bot.tafs.app.push.AlertPayload
import bot.tafs.app.push.PushPlatform
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a phone does with alerts: turning them on, keeping them registered, and showing what arrives. A fake KIFF, tafs cloud and Firebase stand in. */
class PushTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private class FakePush(
    override var available: Boolean = true,
    var allow: Boolean = true,
    var token: String? = "fcm_token_1",
  ) : PushPlatform {
    var asked = 0
    var tokens = 0
    override suspend fun allowed(): Boolean { asked++; return allow }
    override suspend fun token(): String? { tokens++; return token }
  }

  private val push = FakePush()
  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}"""
  private val target = """{"id":"tgt_1","signing_secret":"whsec_abcdefghijklmnopqrstuvwxyz0123"}"""
  private val alertTarget = "POST /v1/me/alert-targets"
  private val register = "POST /v1/push"

  private fun reads() {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
  }

  private suspend fun model(keyed: Boolean = true, platform: PushPlatform = push): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    if (keyed) secrets.save("1", AppModel.KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false, push = platform).also { it.restore() }
  }

  private fun body(key: String, index: Int = 0) = KiffClient.json.parseToJsonElement(String(transport.requestsTo(key)[index].body!!)).jsonObject

  // MARK: What arrives

  private val now = Instant.parse("2026-10-28T09:00:00Z")

  @Test fun aHeldCallsAlertBecomesANotificationThatOpensNeedsYou() {
    val alert = AlertPayload.parse(
      mapOf("kind" to "alert", "title" to "A taf needs you", "body" to "Refund waits for your answer.", "open" to "needs-you", "hold_id" to "h1", "level" to "interrupt", "expires_at" to "2026-10-28T09:10:00Z"),
      now,
    ) as Alert.Show
    assertEquals("A taf needs you", alert.title)
    assertEquals("Refund waits for your answer.", alert.body)
    assertEquals(AlertPayload.NEEDS_YOU, alert.open)
    assertEquals("h1", alert.tag)
    assertTrue(alert.loud)
    assertEquals(Instant.parse("2026-10-28T09:10:00Z"), alert.expiresAt)
  }

  @Test fun aRequestForTasksOpensUsageAndIsQuiet() {
    val alert = AlertPayload.parse(mapOf("kind" to "alert", "title" to "More tasks asked for", "body" to "Open Usage.", "open" to "usage", "request_id" to "r1", "level" to "quiet"), now) as Alert.Show
    assertEquals(AlertPayload.USAGE, alert.open)
    assertEquals("r1", alert.tag)
    assertFalse(alert.loud)
  }

  @Test fun anAlertWithoutWordsOrADestinationSaysWhatItIs() {
    val alert = AlertPayload.parse(mapOf("kind" to "alert", "hold_id" to "h1"), now) as Alert.Show
    assertEquals("A taf needs you", alert.title)
    assertEquals("Open Needs you to answer.", alert.body)
    assertEquals(AlertPayload.NEEDS_YOU, alert.open)
    assertEquals("a destination the app doesn't know is Needs you", AlertPayload.NEEDS_YOU, (AlertPayload.parse(mapOf("kind" to "alert", "open" to "elsewhere", "hold_id" to "h"), now) as Alert.Show).open)
    assertEquals("alert", (AlertPayload.parse(mapOf("kind" to "alert"), now) as Alert.Show).tag)
    val usage = AlertPayload.parse(mapOf("kind" to "alert", "open" to "usage", "request_id" to "r"), now) as Alert.Show
    assertEquals("tafs", usage.title)
  }

  @Test fun aHoldIsTheTagWhenBothAreNamed() {
    assertEquals("h1", (AlertPayload.parse(mapOf("kind" to "alert", "hold_id" to "h1", "request_id" to "r1"), now) as Alert.Show).tag)
  }

  @Test fun aHeldCallThatHasExpiredIsNotShown() {
    assertNull(AlertPayload.parse(mapOf("kind" to "alert", "hold_id" to "h1", "expires_at" to "2026-10-28T08:59:59Z"), now))
    assertNull("expiring now is expired", AlertPayload.parse(mapOf("kind" to "alert", "hold_id" to "h1", "expires_at" to "2026-10-28T09:00:00Z"), now))
    assertNotNull("an expiry it can't read doesn't hide the alert", AlertPayload.parse(mapOf("kind" to "alert", "hold_id" to "h1", "expires_at" to "soon"), now))
  }

  @Test fun theAnswerToAHeldCallTakesItsNotificationAway() {
    assertEquals(Alert.Clear("h1"), AlertPayload.parse(mapOf("kind" to "answered", "hold_id" to "h1"), now))
    assertNull("an answer that names nothing clears nothing", AlertPayload.parse(mapOf("kind" to "answered"), now))
  }

  @Test fun aMessageOfAKindThePhoneDoesNotKnowIsIgnored() {
    assertNull(AlertPayload.parse(mapOf("kind" to "something-new", "hold_id" to "h1"), now))
    assertNull(AlertPayload.parse(emptyMap(), now))
  }

  // MARK: Registering the phone

  @Test fun aPhoneIsRegisteredWithItsFirebaseTokenAndWhatSoundItWants() = runTest {
    reads()
    transport.answer(register, 200, """{"answers":true,"alerts":true}""")
    val cloud = bot.tafs.app.kiff.TafsCloud(transport, token = "at_1")
    val status = cloud.registerPush("fcm_token_1", sound = false, target = bot.tafs.app.kiff.AlertTarget("tgt_1", "whsec_x"))
    assertTrue(status.alerts)
    val sent = body(register)
    assertEquals("fcm_token_1", sent.getValue("device_token").jsonPrimitive.content)
    assertEquals("android", sent.getValue("platform").jsonPrimitive.content)
    assertEquals("production", sent.getValue("environment").jsonPrimitive.content)
    assertEquals("false", sent.getValue("sound").jsonPrimitive.content)
    assertEquals("tgt_1", sent.getValue("alert_target").jsonObject.getValue("id").jsonPrimitive.content)
    assertEquals("whsec_x", sent.getValue("alert_target").jsonObject.getValue("signing_secret").jsonPrimitive.content)
    assertEquals("https://cloud.tafs.kiff.dev/v1/kiff/alerts", cloud.alertsUrl)
  }

  @Test fun aPhoneWithNoTargetToHandOverSendsNone() = runTest {
    transport.answer(register, 200, """{"answers":true,"alerts":true}""")
    bot.tafs.app.kiff.TafsCloud(transport, token = "at_1").registerPush("fcm_token_1")
    assertNull(body(register)["alert_target"])
    assertEquals("true", body(register).getValue("sound").jsonPrimitive.content)
  }

  // MARK: Turning alerts on

  @Test fun turningOnAlertsAsksPermissionThenConfirmsThenPointsKiffAtTafsCloudThenRegistersThePhone() = runTest {
    reads()
    transport.answer(alertTarget, 200, target)
    transport.answer(register, 200, """{"answers":true,"alerts":true}""")
    val m = model()
    assertTrue(m.turnOnAlerts())
    assertEquals(1, push.asked)
    assertEquals(listOf("Get an alert when a taf needs you"), auth.asked)
    val kiffRequest = transport.requestsTo(alertTarget).single()
    assertEquals("https://cloud.tafs.kiff.dev/v1/kiff/alerts", body(alertTarget).getValue("url").jsonPrimitive.content)
    assertTrue("a signed setup call", kiffRequest.headers.containsKey("KIFF-Device-Signature"))
    val sent = body(register)
    assertEquals("fcm_token_1", sent.getValue("device_token").jsonPrimitive.content)
    assertEquals("tgt_1", sent.getValue("alert_target").jsonObject.getValue("id").jsonPrimitive.content)
    assertTrue(m.state.value.alertsReady)
    assertEquals(true, m.state.value.canAnswerHolds)
    assertFalse(m.state.value.turningOnAlerts)
    assertEquals("1", secrets.load(AppModel.ALERTS_ON))
    assertNull("the secret is handed over, no longer kept", secrets.load(AppModel.HANDOFF))
    val order = transport.requests.map { it.method + " " + java.net.URI(it.url).path }
    assertTrue(order.indexOf("POST /v1/me/alert-targets") < order.indexOf("POST /v1/push"))
  }

  @Test fun aPhoneThatMayNotAnswerHeldCallsIsToldAndAlertsStayOff() = runTest {
    reads()
    transport.answer(alertTarget, 200, target)
    transport.answer(register, 200, """{"answers":false,"alerts":false}""")
    val m = model()
    assertFalse(m.turnOnAlerts())
    assertEquals(false, m.state.value.canAnswerHolds)
    assertFalse(m.state.value.alertsReady)
    assertEquals("Alerts go to the people who may answer held calls. Your role on this account can't.", AlertWords.foot(true, false))
  }

  @Test fun withoutTheNotificationPermissionNothingIsSent() = runTest {
    reads()
    push.allow = false
    val m = model()
    assertFalse(m.turnOnAlerts())
    assertEquals("Allow notifications for tafs in Settings, then try again.", m.state.value.lastError)
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(alertTarget).isEmpty() && transport.requestsTo(register).isEmpty())
  }

  @Test fun aPhoneThatGivesNoTokenSaysSo() = runTest {
    reads()
    push.token = null
    val m = model()
    assertFalse(m.turnOnAlerts())
    assertEquals("This phone didn't give tafs a push token. Try again in a moment.", m.state.value.lastError)
    assertTrue(transport.requestsTo(alertTarget).isEmpty())
  }

  @Test fun aBuildWithoutFirebaseSaysAlertsAreNotSetUp() = runTest {
    reads()
    val m = model(platform = FakePush(available = false))
    assertFalse(m.state.value.alertsAvailable)
    assertFalse(m.turnOnAlerts())
    assertEquals("Alerts aren't set up in this build of tafs.", m.state.value.lastError)
    assertEquals("Not available", AlertWords.status(false, false))
    assertEquals(AlertWords.NOT_IN_BUILD, AlertWords.foot(false, null))
  }

  @Test fun aSessionThatCannotSignForThePhoneCannotTurnOnAlerts() = runTest {
    reads()
    val m = model(keyed = false)
    assertFalse(m.turnOnAlerts())
    assertEquals("Sign in with KIFF again to turn on alerts: this sign-in can't sign for the phone.", m.state.value.lastError)
    assertEquals(0, push.asked)
  }

  @Test fun anOwnerWhoDoesNotConfirmTurnsNothingOn() = runTest {
    reads()
    auth.confirms = false
    val m = model()
    assertFalse(m.turnOnAlerts())
    assertTrue(transport.requestsTo(alertTarget).isEmpty() && transport.requestsTo(register).isEmpty())
    assertFalse(m.state.value.turningOnAlerts)
  }

  @Test fun aRoleKiffRefusesIsToldInWords() = runTest {
    reads()
    transport.answer(alertTarget, 403, """{"error":"forbidden"}""")
    val m = model()
    assertFalse(m.turnOnAlerts())
    assertEquals("Your role on this KIFF account can't turn on alerts.", m.state.value.lastError)
    transport.answer(alertTarget, 403, """{"error":"phone_alert_url_forbidden"}""")
  }

  @Test fun aKiffThatDoesNotSendAlertsToTafsYetIsToldInWords() = runTest {
    reads()
    transport.answer(alertTarget, 403, """{"error":"phone_alert_url_forbidden"}""")
    val m = model()
    assertFalse(m.turnOnAlerts())
    assertEquals("KIFF doesn't send alerts to tafs yet on this account.", m.state.value.lastError)
  }

  @Test fun aSecretTafsCloudDidNotTakeIsHandedOverAgainWithoutANewTarget() = runTest {
    reads()
    transport.answer(alertTarget, 200, target)
    transport.answer(register, 502, """{"error":"later"}""")
    val m = model()
    assertFalse(m.turnOnAlerts())
    assertEquals("Alerts are almost on: tafs cloud didn't answer (later). Tap Turn on alerts again.", m.state.value.lastError)
    assertNotNull("kept: KIFF already signs with it", secrets.load(AppModel.HANDOFF))
    transport.answer(register, 200, """{"answers":true,"alerts":true}""")
    m.dismissError()
    assertTrue(m.turnOnAlerts())
    assertEquals("one target only: no second registration", 1, transport.requestsTo(alertTarget).size)
    assertEquals("tgt_1", body(register, 1).getValue("alert_target").jsonObject.getValue("id").jsonPrimitive.content)
    assertNull(secrets.load(AppModel.HANDOFF))
  }

  // MARK: Staying registered

  @Test fun aPhoneThatNeverTurnedAlertsOnIsNeverRegistered() = runTest {
    reads()
    val m = model()
    m.tokenArrived("fcm_token_2")
    m.syncPush()
    assertTrue(transport.requestsTo(register).isEmpty())
    assertFalse(m.state.value.alertsReady)
  }

  @Test fun aPhoneThatTurnedAlertsOnIsRegisteredAgainAtLaunchAndWhenItsTokenChanges() = runTest {
    reads()
    transport.answer(register, 200, """{"answers":true,"alerts":true}""")
    secrets.save("1", AppModel.ALERTS_ON)
    val m = model()
    assertEquals("registered at launch", 1, transport.requestsTo(register).size)
    assertEquals("fcm_token_1", body(register, 0).getValue("device_token").jsonPrimitive.content)
    assertNull("nothing to hand over", body(register, 0)["alert_target"])
    assertTrue(m.state.value.alertsReady)
    m.tokenArrived("fcm_token_2")
    // The token's own registration runs on the view model's scope: asked directly here.
    m.syncPush()
    assertTrue(transport.requestsTo(register).size >= 2)
  }

  @Test fun theSoundSettingIsKeptAndSentAsTheAlertsSound() = runTest {
    reads()
    transport.answer(register, 200, """{"answers":true,"alerts":true}""")
    secrets.save("1", AppModel.ALERTS_ON)
    secrets.save("0", AppModel.ALERT_SOUND)
    val m = model()
    assertFalse(m.state.value.alertSound)
    assertEquals("false", body(register, 0).getValue("sound").jsonPrimitive.content)
  }

  @Test fun signingOutStopsThePhonesAlertsAndForgetsWhatWasKept() = runTest {
    reads()
    transport.answer(register, 200, """{"answers":true,"alerts":true}""")
    transport.answer("DELETE /v1/push/fcm_token_1", 200, """{"removed":true}""")
    transport.answer("POST /v1/auth/device/logout", 200, "{}")
    secrets.save("1", AppModel.ALERTS_ON)
    secrets.save("pending", AppModel.HANDOFF)
    val m = model()
    m.signOut()
    assertEquals(1, transport.requestsTo("DELETE /v1/push/fcm_token_1").size)
    assertNull(secrets.load(AppModel.ALERTS_ON))
    assertNull(secrets.load(AppModel.HANDOFF))
    assertFalse(m.state.value.alertsReady)
  }

  // MARK: Where a tapped alert goes

  @Test fun aTappedAlertAsksForItsScreenOnceAndTheScreenTakesIt() = runTest {
    reads()
    val m = model()
    m.requestOpen(null)
    assertNull(m.state.value.requestedOpen)
    m.requestOpen(AlertPayload.NEEDS_YOU)
    assertEquals("needs-you", m.state.value.requestedOpen)
    m.openRequestTaken()
    assertNull(m.state.value.requestedOpen)
  }

  @Test fun alertsReadOnOffOrNotAvailable() {
    assertEquals("On", AlertWords.status(true, true))
    assertEquals("Off", AlertWords.status(true, false))
    assertTrue(AlertWords.foot(true, null)!!.startsWith("When a taf needs you"))
  }
}
