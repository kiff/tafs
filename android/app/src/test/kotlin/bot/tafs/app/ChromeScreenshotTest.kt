package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.ChromePairing
import bot.tafs.app.kiff.ChromeScreenshot
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.views.ChromeShotPhase
import bot.tafs.app.views.chromeShotPhase
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture kept before a Chrome step acted (docs/chrome.md, "Screenshots of a run's acts"), as iOS's
 * ChromeScreenshotTests: shown only when it's WebP, at most 120 KB and hashes to planque's `sha256`; none kept is its own
 * answer, and anything else reads as failed, never as a picture.
 */
class ChromeScreenshotTest {
  private val webp = "RIFF".toByteArray() + byteArrayOf(0x10, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(32) { it.toByte() }

  private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  private fun answer(
    image: ByteArray = webp,
    sha: String = hex(image),
    takenAt: String? = "2026-11-22T17:40:00Z",
    encoded: String = ChromePairing.base64Url(image),
  ) = """{"step":"run_1.3","sha256":"$sha","image":"$encoded"${takenAt?.let { ",\"taken_at\":\"$it\"" }.orEmpty()}}""".toByteArray()

  @Test fun aCheckedPictureIsRead() {
    val shot = ChromeScreenshot.read(answer())!!
    assertEquals("run_1.3", shot.step)
    assertEquals(Instant.parse("2026-11-22T17:40:00Z"), shot.takenAt)
    assertArrayEquals(webp, shot.image)
    assertTrue("planque's hash in capitals is the same hash", ChromeScreenshot.read(answer(sha = hex(webp).uppercase())) != null)
  }

  @Test fun anythingThatDoesntCheckOutIsNoPicture() {
    assertNull("another hash", ChromeScreenshot.read(answer(sha = hex(webp + 1))))
    assertNull("not WebP", ChromeScreenshot.read(answer(image = "\u0089PNG\r\n\u001a\n".toByteArray() + ByteArray(16))))
    val big = webp + ByteArray(ChromeScreenshot.LARGEST)
    assertNull("over 120 KB", ChromeScreenshot.read(answer(image = big)))
    assertNull("padded or not base64url", ChromeScreenshot.read(answer(encoded = ChromePairing.base64Url(webp) + "==")))
    assertNull("no time", ChromeScreenshot.read(answer(takenAt = null)))
    assertNull("a time that isn't one", ChromeScreenshot.read(answer(takenAt = "yesterday")))
    assertNull("not the shape", ChromeScreenshot.read("""{"step":"run_1.3"}""".toByteArray()))
    assertNull("not JSON", ChromeScreenshot.read("<html>".toByteArray()))
  }

  @Test fun theSheetsPhaseIsAPictureNoneOrFailed() = runTest {
    val shot = ChromeScreenshot.read(answer())!!
    assertTrue(chromeShotPhase { shot } is ChromeShotPhase.Shown)
    assertEquals(ChromeShotPhase.None, chromeShotPhase { null })
    assertEquals(ChromeShotPhase.Failed, chromeShotPhase { throw IllegalStateException("bad_picture") })
  }

  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val route = "GET /v1/accounts/t_01/chromes/screenshots/run_1.3"

  private suspend fun model(): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { Instant.parse("2026-11-22T18:00:00Z") }, start = false).also {
      it.restore()
      it.refreshConnections()
    }
  }

  @Test fun planquesReadIsDeviceSignedAndItsAnswersAreKept() = runTest {
    val m = model()
    transport.answer(route, 200, String(answer()))
    assertArrayEquals(webp, m.chromeScreenshot("run_1.3")!!.image)
    val sent = transport.requests.last { it.method == "GET" && it.url.endsWith("/v1/accounts/t_01/chromes/screenshots/run_1.3") }
    assertTrue("signed by this device", sent.headers["Planque-Device-Signature"]?.contains("d=dev_1") == true)
    assertTrue("a read asks for no fingerprint", auth.fingerprints.isEmpty())

    transport.answer(route, 404, """{"error":"no picture is kept for this step"}""")
    assertNull("none kept is its own answer", m.chromeScreenshot("run_1.3"))

    transport.answer(route, 200, String(answer(sha = hex(webp + 1))))
    assertEquals("a picture that doesn't check out fails", ChromeShotPhase.Failed, chromeShotPhase { m.chromeScreenshot("run_1.3") })

    transport.answer(route, 503, """{"error":"down"}""")
    assertEquals(ChromeShotPhase.Failed, chromeShotPhase { m.chromeScreenshot("run_1.3") })
  }
}
