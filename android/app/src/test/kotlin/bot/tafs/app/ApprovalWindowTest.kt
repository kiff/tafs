package bot.tafs.app

import bot.tafs.app.model.Words
import bot.tafs.app.kiff.ApprovalWindow
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffError
import bot.tafs.app.kiff.PlanqueClient
import bot.tafs.app.kiff.PlanqueError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The approval window (kiff/tafs#494): one fingerprint opens it at KIFF, then a routine approval is a tap, signed by the device key
 * with the window's id. KIFF and planque decide; the phone falls back to the approval key, or opens a new window, when told.
 */
class ApprovalWindowTest {
  private val transport = FakeTransport()
  private val window = "win_abcdefghijklmnopqrstuvwxyz"
  private fun client() = KiffClient(transport, token = "tok").also { it.tenant = "t_01" }

  @Test fun theWindowMessageIsKiffsRequestWithTheWindowLast() {
    val message = String(KiffClient.requestMessageWindow("t_01", "POST", "/v1/me/mandates", "{}".toByteArray(), "2026-11-05T11:00:00Z", window))
    assertEquals(
      listOf("kiff.request.v2", "t_01", "POST", "/v1/me/mandates", "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", "2026-11-05T11:00:00Z", window),
      message.split("\n"),
    )
  }

  @Test fun planquesWindowMessageIsItsRequestWithTheWindowLastAndNoneWithoutOne() {
    val body = "{}".toByteArray()
    val v2 = String(PlanqueClient.messageWindow("https://planque.example/", "t_01", "POST", "/v1/accounts/t_01/grants/g1/approve", body, "2026-11-05T11:00:00Z", window))
    assertEquals(listOf("planque.request.v2", "https://planque.example", "t_01", "POST", "/v1/accounts/t_01/grants/g1/approve"), v2.split("\n").take(5))
    assertEquals(window, v2.split("\n").last())
    val v1 = PlanqueClient.messageWindow("https://planque.example", "t_01", "POST", "/v1/accounts/t_01/grants/g1/approve", body, "2026-11-05T11:00:00Z", null)
    assertTrue(v1.contentEquals(PlanqueClient.message("https://planque.example", "t_01", "POST", "/v1/accounts/t_01/grants/g1/approve", body, "2026-11-05T11:00:00Z")))
  }

  @Test fun openingIsSignedByTheApprovalKeyWithKiffsMessageAndHoldsTheWindow() = runTest {
    val signed = mutableListOf<String>()
    val c = client().also {
      it.setupSigner = { "dev" }
      it.approvalSigner = { built -> signed += String(built()).lines()[3]; "app" }
    }
    transport.answer("POST /v1/me/window", 201, """{"window":{"id":"$window","expires":"2026-11-05T11:05:00Z","open":true,"words":"No fingerprint for the next 4 minutes"}}""")
    val opened = c.openWindow("2026-11-05T11:05:00Z", "0123456789abcdef0123456789abcdef")
    assertEquals(listOf("/v1/me/window"), signed)
    val request = transport.requestsTo("POST /v1/me/window").single()
    assertTrue(request.headers["KIFF-Device-Signature"]!!.contains(",v1=app"))
    assertEquals("""{"message":"kiff.window.v1","expires":"2026-11-05T11:05:00Z","nonce":"0123456789abcdef0123456789abcdef"}""", String(request.body!!))
    assertEquals(window, opened.id)
    assertEquals(window, c.window?.id)
  }

  @Test fun insideAWindowWhatTheApprovalKeyWouldSignIsATapByTheDeviceKey() = runTest {
    val signed = mutableListOf<String>()
    val c = client().also {
      it.setupSigner = { m -> signed += "device:" + String(m).lines().first(); "dev" }
      it.approvalSigner = { built -> signed += "approval:" + String(built()).lines().first(); "app" }
      it.window = ApprovalWindow(window, open = true)
    }
    transport.answer("POST /v1/me/agent-drafts/d1/confirm", 200)
    transport.answer("POST /v1/me/time-zone", 200)
    c.send("POST", "/v1/me/agent-drafts/d1/confirm")
    c.send("POST", "/v1/me/time-zone", mapOf("time_zone" to "UTC"))
    assertEquals(listOf("device:kiff.request.v2", "device:kiff.request.v1"), signed)
    val header = transport.requestsTo("POST /v1/me/agent-drafts/d1/confirm").single().headers["KIFF-Device-Signature"]!!
    assertTrue(header, Regex("^t=[^,]+,v2=dev,w=$window$").matches(header))
    // What grants nothing is signed as before, never with the window.
    assertTrue(transport.requestsTo("POST /v1/me/time-zone").single().headers["KIFF-Device-Signature"]!!.contains(",v1=dev"))
  }

  @Test fun anActThatTakesTheFingerprintEveryTimeIsSignedAgainByTheApprovalKeyAndTheWindowStays() = runTest {
    val signed = mutableListOf<String>()
    val c = client().also {
      it.setupSigner = { m -> signed += "device:" + String(m).lines().first(); "dev" }
      it.approvalSigner = { built -> signed += "approval:" + String(built()).lines().first(); "app" }
      it.window = ApprovalWindow(window, open = true)
    }
    transport.answer("POST /v1/me/did-this-happen/p1", 403, """{"error":"critical_key_required","detail":"No takes your fingerprint"}""")
    transport.answer("POST /v1/me/did-this-happen/p1", 200)
    c.answerDidThisHappen("p1", false)
    assertEquals(listOf("device:kiff.request.v2", "approval:kiff.request.v1"), signed)
    assertEquals(window, c.window?.id)
  }

  @Test fun aWindowThatEndedIsForgottenAndTheApprovalKeySignsAgainOnce() = runTest {
    val signed = mutableListOf<String>()
    val c = client().also {
      it.setupSigner = { m -> signed += "device:" + String(m).lines().first(); "dev" }
      it.approvalSigner = { built -> signed += "approval:" + String(built()).lines().first(); "app" }
      it.window = ApprovalWindow(window, open = true)
    }
    transport.answer("POST /v1/agents/a1/resume", 403, """{"error":"window_required"}""")
    transport.answer("POST /v1/agents/a1/resume", 403, """{"error":"window_required"}""")
    try {
      c.send("POST", "/v1/agents/a1/resume")
      fail("a second refusal is the service's")
    } catch (e: KiffError.Server) {
      assertEquals(403, e.status)
    }
    assertEquals(listOf("device:kiff.request.v2", "approval:kiff.request.v1"), signed)
    assertNull(c.window)
  }

  @Test fun withNoApprovalKeyARefusalInTheWindowKeepsKiffsCodeAndSaysItInTheAppsWords() = runTest {
    val c = client().also {
      it.setupSigner = { "dev" }
      it.window = ApprovalWindow(window, open = true)
    }
    transport.answer("POST /v1/agents/a1/resume", 403, """{"error":"critical_key_required","detail":"Resuming takes your fingerprint"}""")
    try {
      c.send("POST", "/v1/agents/a1/resume")
      fail("refused")
    } catch (e: KiffError.WindowRefused) {
      assertEquals("critical_key_required", e.code)
      // KIFF's `detail` is for the log: the person reads the app's own line.
      assertEquals(Words.Error.needsConfirmationFirst, e.message)
    }
  }

  @Test fun closingIsDeviceSignedAndForgetsTheWindowWhateverKiffAnswers() = runTest {
    val c = client().also { it.window = ApprovalWindow(window, open = true) }
    transport.answer("POST /v1/me/window/$window/close", 503)
    runCatching { c.closeWindow { "dev" } }
    assertNull(c.window)
    val header = transport.requestsTo("POST /v1/me/window/$window/close").single().headers["KIFF-Device-Signature"]!!
    assertTrue(header, header.contains(",v1=dev"))
  }

  @Test fun planqueSignsInTheWindowItIsGivenAndSaysWhenItEnded() = runTest {
    val planque = PlanqueClient(transport, "https://planque.example").also {
      it.tenant = "t_01"
      it.deviceId = "dev_phone"
      it.signer = { "dev" }
      it.window = window
    }
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 403, """{"error":"Your approval window has ended.","code":"window_required"}""")
    try {
      planque.approveAsAsked("g1", kotlinx.serialization.json.JsonObject(emptyMap()))
      fail("refused")
    } catch (e: PlanqueError.WindowRequired) {
      // planque's `error` is for the log: the person reads the app's own line.
      assertEquals(Words.Error.approvalWindowEnded, e.message)
    }
    val header = transport.requestsTo("POST /v1/accounts/t_01/grants/g1/approve").single().headers["Planque-Device-Signature"]!!
    assertTrue(header, Regex("^t=[^,]+,v2=dev,w=$window,d=dev_phone$").matches(header))
    assertNull(planque.window)
  }
}
