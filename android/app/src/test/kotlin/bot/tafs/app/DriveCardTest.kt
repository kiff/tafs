package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.drive.DriveInput
import bot.tafs.app.drive.DriveState
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coach's drive card (docs/drive.md): Approve signs the Start with the approval key, behind the fingerprint; Not now declines. */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveCardTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-11-06T00:30:00Z")
  private val card = """{"type":"drive","id":"d1","text":"Run Chargebacks"}"""
  private var scope: CoroutineScope? = null

  @After
  fun done() {
    scope?.cancel()
    DriveInput.turn(false)
  }

  private fun reads(drive: String = """{"drive":null,"steps":[]}""") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["members.manage","cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach","kiff_agent":"setup-acme"}}""")
    transport.answer(
      "GET /v1/tafs/setup_1/conversation", 200,
      """{"messages":[],"components":[{"at":"2026-11-06T00:29:00Z","session":"s1","run":"r1","component":$card}]}""",
    )
    transport.answer("GET /v1/coach/drive", 200, drive)
  }

  private suspend fun TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    val s = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scope = it }
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false, scope = s).also {
      it.restore()
      it.refresh()
      it.loadCoach()
      it.loadChat(COACH_CHAT)
    }
  }

  private val shown get() = KiffClient.json.decodeFromString(SetupComponent.serializer(), card)

  @Test
  fun aParkedRunThatGoesOnAfterContinueIsFollowedToItsEndWithoutReopeningTheChat() = runTest {
    reads()
    transport.answer("POST /v1/coach/drive", 200, """{"drive":{"id":"dv_1","session":"s1","task":"Run Chargebacks","started":"2026-11-06T00:30:01Z"}}""")
    transport.hold("GET /v1/coach/drive/dv_1/stream")
    transport.answer("POST /v1/coach/drive/pause", 200, "{}")
    // Continue: tafs cloud names the parked run that goes on.
    transport.answer("POST /v1/coach/drive/resume", 200, """{"resumed":true,"coach":{"run":"r1","session":"s1"}}""")
    transport.answer("GET /v1/tafs/setup_1/runs/r1/stream", 200, "event: end\ndata: {\"status\":\"COMPLETED\",\"stop\":\"answered\"}\n")
    val m = model()
    m.startDrive(shown)
    m.drive.pause()
    runCurrent()
    // The run's end is in the thread once it has ended.
    transport.answer(
      "GET /v1/tafs/setup_1/conversation", 200,
      """{"messages":[{"at":"2026-11-06T00:32:00Z","from":"taf","text":"Chargebacks has run.","session":"s1"}],"components":[]}""",
    )
    m.resumeDrive()
    runCurrent()
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals("the parked run is followed again", 1, transport.requestsTo("GET /v1/tafs/setup_1/runs/r1/stream").size)
    assertTrue(m.state.value.chats[COACH_CHAT].orEmpty().any { it.text == "Chargebacks has run." })
    assertFalse("its end shows, and the chat isn't left thinking", m.state.value.thinking(COACH_CHAT))
  }

  @Test
  fun approveSignsTheStartWithTheApprovalKeyAndTheDriveIsOn() = runTest {
    reads()
    transport.answer("POST /v1/coach/drive", 200, """{"drive":{"id":"dv_1","session":"s1","task":"Run Chargebacks","started":"2026-11-06T00:30:01Z"}}""")
    // The drive's stream stays open, with nothing to do yet.
    transport.hold("GET /v1/coach/drive/dv_1/stream")
    val m = model()
    m.startDrive(shown)
    val sent = transport.requestsTo("POST /v1/coach/drive").single()
    val body = KiffClient.json.parseToJsonElement(String(sent.body!!)).jsonObject
    assertEquals("s1", body.getValue("session").jsonPrimitive.content)
    assertEquals("Run Chargebacks", body.getValue("task").jsonPrimitive.content)
    assertEquals("d1", body.getValue("component").jsonPrimitive.content)
    assertTrue("signed as a setup call", sent.headers.getValue("KIFF-Device-Signature").matches(Regex("t=[^,]+,v1=.+")))
    // Behind the fingerprint, with the approval key: never a tap, never the device key alone.
    assertEquals(listOf("Let Coach drive the app: Run Chargebacks"), auth.fingerprints)
    assertTrue(m.drive.state.value is DriveState.On)
    runCurrent()
    assertTrue("the person's input is guarded while it's on", DriveInput.driving)
    // The person's stop ends it at once, and tafs cloud is told.
    transport.answer("POST /v1/coach/drive/stop", 200, """{"ended":{"why":"stopped","at":"2026-11-06T00:31:00Z"}}""")
    m.stopDrive("stopped")
    runCurrent()
    assertTrue(m.drive.state.value is DriveState.Over)
    assertFalse(DriveInput.driving)
    assertEquals("stopped", KiffClient.json.parseToJsonElement(String(transport.requestsTo("POST /v1/coach/drive/stop").single().body!!)).jsonObject.getValue("why").jsonPrimitive.content)
  }

  @Test
  fun aStartTafsCloudRefusesStartsNothingAndSaysWhy() = runTest {
    reads()
    transport.answer("POST /v1/coach/drive", 403, """{"error":"a drive starts only with your fingerprint","code":"critical_key_required"}""")
    val m = model()
    m.startDrive(shown)
    assertTrue(m.drive.state.value is DriveState.Off)
    assertTrue(m.state.value.coachNotes.getValue("d1").startsWith("Coach can't drive"))
    assertTrue(transport.requestsTo("GET /v1/coach/drive/dv_1/stream").isEmpty())
  }

  @Test
  fun anOwnerWhoDoesNotConfirmStartsNothing() = runTest {
    reads()
    auth.confirms = false
    val m = model()
    m.startDrive(shown)
    assertTrue(transport.requestsTo("POST /v1/coach/drive").isEmpty())
    assertTrue(m.drive.state.value is DriveState.Off)
  }

  @Test
  fun notNowDeclinesTheCard() = runTest {
    reads()
    transport.answer("POST /v1/tafs/setup_1/components/d1/answer", 200, """{"session":"s1","run":"r1","status":"running"}""")
    val m = model()
    m.answerCard("drive", "d1", "declined")
    val sent = transport.requestsTo("POST /v1/tafs/setup_1/components/d1/answer").single()
    assertEquals("declined", KiffClient.json.parseToJsonElement(String(sent.body!!)).jsonObject.getValue("answer").jsonPrimitive.content)
    assertTrue(transport.requestsTo("POST /v1/coach/drive").isEmpty())
  }

  @Test
  fun aDriveTheAppOutlivedIsEndedAtTheNextStart() = runTest {
    reads(drive = """{"drive":{"id":"dv_0","session":"s1","task":"Run Chargebacks"},"steps":[]}""")
    transport.answer("POST /v1/coach/drive/stop", 200, """{"ended":null}""")
    model()
    runCurrent()
    val stop = KiffClient.json.parseToJsonElement(String(transport.requestsTo("POST /v1/coach/drive/stop").single().body!!)).jsonObject
    assertEquals("dv_0" to "left_app", stop.getValue("drive").jsonPrimitive.content to stop.getValue("why").jsonPrimitive.content)
  }
}
