package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffError
import bot.tafs.app.kiff.PairOffer
import bot.tafs.app.kiff.PairQr
import bot.tafs.app.signing.ApprovalSigning
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Signing in with your other phone (KIFF's pairing contract for phones): this phone shows a QR with its two keys and the code both
 * screens show, then claims the session the other phone approved, proving with its device key that it holds the key. KIFF decides.
 */
class PairSignInTest {
  private val t0 = Instant.parse("2026-11-14T22:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer: ApprovalSigning = softwareSigner(auth)

  private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  /** The shared keys: the SPKI DER P-256 public keys of private keys 1 and 2, as every implementation's vector uses them. */
  private val pub1 = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
  private val pub2 = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc4766997807775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1")

  private fun TestScope.model(): AppModel =
    testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0.plusMillis(currentTime) }, start = false)

  // MARK: The offer

  @Test fun theOfferShowsTheSharedCodeAndTheClaimMessage() {
    val nonce = ByteArray(32) { it.toByte() }
    val o = PairOffer.make(pub1, pub2, "tafs on Android phone", "android_phone", nonce, t0)
    assertEquals("the shared vector", "H6SJNU", o.checkCode)
    val nonceText = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
    assertEquals(nonceText, o.nonce)
    assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(pub1), o.pub)
    // Under the contract's 5 minutes, as iOS.
    assertEquals(t0.plusSeconds(270), o.expires)
    val keyHash = MessageDigest.getInstance("SHA-256").digest(pub1).joinToString("") { "%02x".format(it) }
    assertEquals("kiff.pair.claim.v1\n$nonceText\n$keyHash", String(o.claimMessage))
  }

  @Test fun theQrIsWhatTheOtherPhonesReaderTakesAsAPhone() {
    val o = PairOffer.make(pub1, pub2, "tafs on Android phone", "android_tablet", ByteArray(32) { 7 }, t0)
    val read = PairQr.read(o.qr)!!
    assertTrue(read.isPhone)
    assertEquals("android", read.platform)
    assertEquals("keystore", read.kind)
    assertEquals("android_tablet", read.deviceKind)
    assertEquals("tafs on Android phone", read.name)
    assertEquals("2026-11-14T22:04:30Z", read.exp)
    // Both screens show the same code.
    assertEquals(o.checkCode, read.checkCode)
  }

  @Test fun theDrawnQrReadsBackAsTheOffer() {
    val o = PairOffer.make(pub1, pub2, "tafs on Android phone", "android_phone", ByteArray(32) { 3 }, t0)
    val modules = bot.tafs.app.views.qrModules(o.qr)
    val scale = 4
    val size = modules.size * scale
    val pixels = IntArray(size * size) { i -> if (modules[(i / size) / scale][(i % size) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))).text
    assertEquals(o.qr, decoded)
  }

  // MARK: Claiming

  private val token = """{"access_token":"at_p","token_type":"Bearer","expires_in":3600,"tenant":"t_01","subject":"user_1","device_id":"dev_9"}"""

  // MARK: A token for a way the person stopped (16 Nov; iOS's tafs#1246)

  private val late = KiffClient.json.decodeFromString(bot.tafs.app.kiff.DeviceToken.serializer(), token)

  // On a tablet the person switched ways (or left) just as this one's token landed: it's ended at KIFF with its own bearer,
  // nothing of it is kept, and the stop goes on.
  @Test fun aTokenForAWayThePersonStoppedIsEndedAtKiffNotKept() = runTest {
    transport.answer("POST /v1/auth/device/logout", 204, "")
    val m = model()
    try {
      m.takeSession(late, stopped = true)
      fail("a stopped way's token was kept")
    } catch (_: kotlinx.coroutines.CancellationException) {
    }
    val logout = transport.requestsTo("POST /v1/auth/device/logout")
    assertEquals(1, logout.size)
    assertEquals("with the late token's own bearer", "Bearer at_p", logout.single().headers["Authorization"])
    assertEquals(null, secrets.load(AppModel.TOKEN))
    assertTrue("nothing of the account is read", transport.requestsTo("GET /v1/me").isEmpty())
    assertFalse(m.state.value.mode == Mode.kiff)
  }

  // The way still running: its token is the session, as before, and nothing is ended.
  @Test fun aTokenForTheWayStillRunningIsTheSession() = runTest {
    signedIn()
    val m = model()
    m.takeSession(late, stopped = false)
    assertEquals("at_p", secrets.load(AppModel.TOKEN))
    assertEquals(Mode.kiff, m.state.value.mode)
    assertTrue(transport.requestsTo("POST /v1/auth/device/logout").isEmpty())
  }

  // Every way hands its token over the same way (the device code, pairing, and the in-app sign-in of docs/onboarding.md),
  // so none can keep a token for a way that was stopped.
  @Test fun bothWaysTakeTheirTokenThroughTakeSession() {
    val model = java.io.File("src/main/kotlin/bot/tafs/app/app/AppModel.kt").readText()
    assertEquals(3, Regex("""takeSession\(token, stopped = !currentCoroutineContext\(\)\.isActive\)""").findAll(model).count())
  }

  private fun signedIn() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme"}""")
    transport.answer("POST /v1/me/device/approval-key", 409, """{"error":"approval_key_set","fingerprint":"${KiffClient.fingerprint(signer.approvalPublicKeyDER)}"}""")
  }

  // MARK: The welcome's consent (docs/onboarding.md)

  /** Every claim carries the terms version the welcome's tick agreed to, so KIFF records it for this device. */
  @Test fun everyClaimCarriesTheTickedTermsVersion() = runTest {
    transport.answer("POST /v1/pair/claim", 400, """{"error":"authorization_pending"}""")
    signedIn()
    val m = model()
    val done = async { m.finishPairSignIn(m.startPairSignIn(), termsVersion = "2026-11-20") }
    advanceTimeBy(3_100); runCurrent()
    transport.answer("POST /v1/pair/claim", 200, token)
    advanceTimeBy(3_100); runCurrent()
    done.await()
    val versions = transport.requestsTo("POST /v1/pair/claim").map { Json.parseToJsonElement(String(it.body!!)).jsonObject["terms_version"]?.jsonPrimitive?.content }
    assertEquals(listOf("2026-11-20", "2026-11-20"), versions)
  }

  /** With the new welcome off, the claim is today's: no terms version in it. */
  @Test fun offTheClaimIsTodays() = runTest {
    signedIn()
    transport.answer("POST /v1/pair/claim", 200, token)
    val m = model()
    val done = async { m.finishPairSignIn(m.startPairSignIn()) }
    advanceTimeBy(3_100); runCurrent()
    done.await()
    val body = Json.parseToJsonElement(String(transport.requestsTo("POST /v1/pair/claim").single().body!!)).jsonObject
    assertEquals(setOf("nonce", "pub", "proof"), body.keys)
  }

  /** Terms that changed since the tick: KIFF claims nothing, the claim isn't tried again, and the welcome asks the tick again. */
  @Test fun changedTermsStopTheClaim() = runTest {
    transport.answer("POST /v1/pair/claim", 409, """{"error":"terms_outdated","terms_version":"2026-11-20"}""")
    val m = model()
    val done = async { runCatching { m.finishPairSignIn(m.startPairSignIn(), termsVersion = "2026-11-14") } }
    advanceTimeBy(3_100); runCurrent()
    val failed = done.await().exceptionOrNull()
    assertTrue("$failed", failed is bot.tafs.app.kiff.AppSignInRefused && failed.error == AppModel.TERMS_OUTDATED)
    assertEquals(1, transport.requestsTo("POST /v1/pair/claim").size)
    assertEquals(null, secrets.load(AppModel.TOKEN))
  }

  @Test fun theClaimProvesTheDeviceKeyAndUsesTheSessionOnceApproved() = runTest {
    transport.answer("POST /v1/pair/claim", 400, """{"error":"authorization_pending"}""")
    signedIn()
    val m = model()
    val o = m.startPairSignIn()
    assertTrue("nothing is sent before the claim", transport.requests.isEmpty())
    val done = async { m.finishPairSignIn(o) }
    advanceTimeBy(3_100); runCurrent()
    assertEquals(1, transport.requestsTo("POST /v1/pair/claim").size)
    // The other phone approves.
    transport.answer("POST /v1/pair/claim", 200, token)
    advanceTimeBy(3_100); runCurrent()
    done.await()
    val claims = transport.requestsTo("POST /v1/pair/claim")
    assertEquals(2, claims.size)
    val body = Json.parseToJsonElement(String(claims.last().body!!)).jsonObject
    assertEquals(o.nonce, body.getValue("nonce").jsonPrimitive.content)
    assertEquals(o.pub, body.getValue("pub").jsonPrimitive.content)
    val proof = body.getValue("proof").jsonPrimitive.content
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(signer.publicKeyDER))
    assertTrue(
      "the device key signs the claim message",
      Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(o.claimMessage); verify(Base64.getDecoder().decode(proof)) },
    )
    assertTrue("a claim asks for no fingerprint", auth.asked.isEmpty())
    assertTrue("a claim carries no session", claims.none { it.headers.containsKey("Authorization") })
    assertEquals(Mode.kiff, m.state.value.mode)
    assertEquals("at_p", secrets.load(AppModel.TOKEN))
    assertEquals("t_01", secrets.load(AppModel.TENANT))
  }

  @Test fun claimsAreNeverFasterThanEveryThreeSeconds() = runTest {
    transport.answer("POST /v1/pair/claim", 400, """{"error":"authorization_pending"}""")
    val m = model()
    val done = async { runCatching { m.finishPairSignIn(m.startPairSignIn()) } }
    advanceTimeBy(2_900); runCurrent()
    assertEquals(0, transport.requestsTo("POST /v1/pair/claim").size)
    advanceTimeBy(9_000); runCurrent()
    assertEquals(3, transport.requestsTo("POST /v1/pair/claim").size)
    done.cancel()
  }

  @Test fun anOfferThatRunsOutEndsInExpired() = runTest {
    transport.answer("POST /v1/pair/claim", 400, """{"error":"authorization_pending"}""")
    val m = model()
    val o = m.startPairSignIn()
    val done = async { runCatching { m.finishPairSignIn(o) } }
    advanceTimeBy(PairOffer.LIFETIME_SECONDS * 1000 + 5_000); runCurrent()
    assertTrue(done.await().exceptionOrNull() is KiffError.Expired)
    assertFalse(m.state.value.signedIn)
  }

  @Test fun aProofKiffRefusesStops() = runTest {
    transport.answer("POST /v1/pair/claim", 403, """{"error":"invalid_proof"}""")
    val m = model()
    val done = async { runCatching { m.finishPairSignIn(m.startPairSignIn()) } }
    advanceTimeBy(3_100); runCurrent()
    val e = done.await().exceptionOrNull()
    assertTrue("$e", e is KiffError.Server && e.refused("invalid_proof"))
    assertEquals(1, transport.requestsTo("POST /v1/pair/claim").size)
  }

  @Test fun aPhoneWithNoApprovalKeyCantBePairedAndSaysSo() = runTest {
    val noKey = object : ApprovalSigning by signer {
      override val approvalPublicKeyDER: ByteArray get() = ByteArray(0)
    }
    val m = testModel(auth = auth, transport = transport, secrets = secrets, signer = noKey, clock = { t0.plusMillis(currentTime) }, start = false)
    try {
      m.startPairSignIn()
      fail("a phone with no approval key was offered")
    } catch (e: bot.tafs.app.model.OwnWords) {
      assertEquals("This phone can't hold a key, so it can't be paired. Sign in with KIFF instead.", e.message)
    }
    assertTrue(transport.requests.isEmpty())
  }
}
