package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.ApprovalWindow
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.PairQr
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing a computer (KIFF's docs/desktop-client.md §5): the phone reads the computer's QR, shows the check code both screens
 * show, and on the person's yes signs the QR's fields to KIFF with its approval key. KIFF decides whether it's paired.
 */
class PairingTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-11-14T18:00:00Z")

  private fun key(): ByteArray = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded
  private fun url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  private val device = key()
  private val approval = key()

  private fun qrText(
    v: String = "kiff.pair.v1",
    pub: String? = url(device),
    approvalPub: String? = url(approval),
    name: String = "tafs on this Windows PC",
    nonce: String = url(ByteArray(32) { it.toByte() }),
    exp: String = "2026-11-14T18:05:00Z",
  ): String = buildString {
    append("""{"v":"$v",""")
    pub?.let { append(""""pub":"$it",""") }
    approvalPub?.let { append(""""approval_pub":"$it",""") }
    append(""""name":"$name","platform":"windows","kind":"${if (approvalPub != null) "hello" else "passkey"}","nonce":"$nonce","exp":"$exp"}""")
  }

  private suspend fun model(approvalKey: Boolean = true): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":[]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    if (approvalKey) secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore(); it.refresh() }
  }

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDER))
    return Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(Base64.getDecoder().decode(signatureBase64)) }
  }

  // MARK: The QR and its check code

  @Test fun base32IsRfc4648WithoutPadding() {
    // RFC 4648 §10's vectors, padding dropped.
    listOf("" to "", "f" to "MY", "fo" to "MZXQ", "foo" to "MZXW6", "foob" to "MZXW6YQ", "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI").forEach { (text, want) ->
      assertEquals(text, want, PairQr.base32(text.toByteArray()))
    }
  }

  /** The formula's parts, on random keys; [theCheckCodeMatchesAFixedVector] is what ties it to every other implementation. */
  @Test fun theCheckCodeIsTheFirstSixOfBase32OfBothKeysHashed() {
    val qr = PairQr.read(qrText())!!
    val want = PairQr.base32(MessageDigest.getInstance("SHA-256").digest(device + approval)).take(6)
    assertEquals(want, qr.checkCode)
    assertEquals(6, qr.checkCode!!.length)
    assertTrue(qr.checkCode!!.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" })
    // A computer with no approval key (a passkey): its device key alone.
    val passkey = PairQr.read(qrText(approvalPub = null))!!
    assertEquals(PairQr.base32(MessageDigest.getInstance("SHA-256").digest(device)).take(6), passkey.checkCode)
    // Another approval key, another code: a page showing its own QR shows another code.
    assertNotEquals(qr.checkCode, PairQr.read(qrText(approvalPub = url(key())))!!.checkCode)
  }

  /**
   * A fixed vector, worked out outside the app: the P-256 public keys of private keys 1 and 2, as SPKI DER, and the code from
   * Python's hashlib and base64.b32encode. Every implementation of §5.2 must give these two codes literally.
   */
  @Test fun theCheckCodeMatchesAFixedVector() {
    fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val pub = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
    val approvalPub = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc4766997807775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1")
    assertEquals("H6SJNU", PairQr.read(qrText(pub = url(pub), approvalPub = url(approvalPub)))!!.checkCode)
    assertEquals("LTJFF6", PairQr.read(qrText(pub = url(pub), approvalPub = null))!!.checkCode)
  }

  @Test fun theAlertSaysWhatPairingGivesByWhatApprovesOnTheComputer() {
    val hello = PairQr.read(qrText())!!
    assertEquals("tafs on this Windows PC will be able to approve your tafs' actions with Windows Hello.", bot.tafs.app.views.pairGives(hello))
    assertTrue(bot.tafs.app.views.pairGives(PairQr.read(qrText().replace("\"kind\":\"hello\"", "\"kind\":\"enclave\""))!!).endsWith("with Touch ID."))
    assertTrue(bot.tafs.app.views.pairGives(PairQr.read(qrText(approvalPub = null))!!).endsWith("with a passkey."))
  }

  @Test fun aCodeKiffPairedThatIsntTheOneCheckedIsSaid() {
    val qr = PairQr.read(qrText())!!
    val code = qr.checkCode!!
    assertEquals("tafs on this Windows PC is paired. It showed $code.", bot.tafs.app.views.pairedLine(qr, bot.tafs.app.kiff.Pairing("pair_1", code, "")))
    assertEquals("tafs on this Windows PC is paired. It showed $code.", bot.tafs.app.views.pairedLine(qr, bot.tafs.app.kiff.Pairing("pair_1", "", "")))
    val other = if (code == "AAAAAA") "BBBBBB" else "AAAAAA"
    assertEquals(
      "KIFF paired tafs on this Windows PC with the code $other, not $code as checked. If that isn't your computer, sign it out here.",
      bot.tafs.app.views.pairedLine(qr, bot.tafs.app.kiff.Pairing("pair_1", other, "")),
    )
  }

  @Test fun theCheckCodeIsTheSameForAPaddedKey() {
    val padded = Base64.getUrlEncoder().encodeToString(device)
    assertEquals(PairQr.read(qrText())!!.checkCode, PairQr.read(qrText(pub = padded))!!.checkCode)
  }

  @Test fun onlyAComputerAskingToBePairedIsRead() {
    assertNotNull(PairQr.read(qrText()))
    assertNull("another version", PairQr.read(qrText(v = "kiff.pair.v2")))
    assertNull("no device key", PairQr.read(qrText(pub = null)))
    assertNull("a key that isn't base64url", PairQr.read(qrText(pub = "not a key!")))
    assertNull("an approval key that isn't base64url", PairQr.read(qrText(approvalPub = "%%%")))
    assertNull("no name", PairQr.read(qrText(name = "")))
    assertNull("no nonce", PairQr.read(qrText(nonce = "")))
    assertNull("no expiry", PairQr.read(qrText(exp = "")))
    assertNull("a kind the phone can't word", PairQr.read(qrText().replace("\"kind\":\"hello\"", "\"kind\":\"usb\"")))
    assertNull("a web address", PairQr.read("https://example.com/pair"))
    assertNull("not JSON at all", PairQr.read("K7Q2MX"))
  }

  @Test fun theBodyIsTheQrsFieldsAsTheyCame() {
    val body = PairQr.read(qrText())!!.body()
    assertEquals(setOf("v", "pub", "approval_pub", "name", "platform", "kind", "nonce", "exp"), body.keys)
    assertEquals(url(device), body.getValue("pub").jsonPrimitive.content)
    // A passkey computer sends no approval key, not an empty one.
    assertFalse(PairQr.read(qrText(approvalPub = null))!!.body().containsKey("approval_pub"))
    assertFalse(PairQr.read(qrText(approvalPub = null))!!.body().containsKey("key_store"))
    // A computer whose device key is in software says so (§1.1), and KIFF gets it as it came.
    val software = PairQr.read(qrText(approvalPub = null).replace("\"exp\":", "\"key_store\":\"software\",\"exp\":"))!!
    assertEquals("software", software.keyStore)
    assertEquals("software", software.body().getValue("key_store").jsonPrimitive.content)
  }

  // MARK: Pairing

  @Test fun pairingIsSignedByTheApprovalKeyBehindAFingerprintThatNamesTheComputer() = runTest {
    transport.answer("POST /v1/me/pairings", 201, """{"pairing":{"id":"pair_1","code":"K7Q2MX","expires":"2026-11-14T18:05:00Z"}}""")
    val m = model()
    val reads = transport.requestsTo("GET /v1/me/devices").size
    val qr = PairQr.read(qrText())!!
    val pairing = m.pairComputer(qr)
    assertEquals("pair_1", pairing?.id)
    assertEquals("K7Q2MX", pairing?.code)
    assertEquals(listOf("Pair tafs on this Windows PC: it can approve actions"), auth.asked)
    val sent = transport.requestsTo("POST /v1/me/pairings").single()
    val body = Json.parseToJsonElement(String(sent.body!!)).jsonObject
    assertEquals(qr.body(), JsonObject(body))
    val (at, sig) = sent.headers.getValue("KIFF-Device-Signature").split(",").let { it[0].removePrefix("t=") to it[1].removePrefix("v1=") }
    val message = KiffClient.requestMessage("t_01", "POST", "/v1/me/pairings", sent.body!!, at)
    assertTrue("only the approval key grants approval authority", verifies(signer.approvalPublicKeyDER, message, sig))
    assertFalse(verifies(signer.publicKeyDER, message, sig))
    assertEquals("the computer is listed once paired", reads + 1, transport.requestsTo("GET /v1/me/devices").size)
    assertNull(m.state.value.devicesError)
  }

  @Test fun anOpenApprovalWindowNeverCoversAPairing() = runTest {
    transport.answer("POST /v1/me/pairings", 201, """{"pairing":{"id":"pair_1","code":"K7Q2MX","expires":"2026-11-14T18:05:00Z"}}""")
    val signed = mutableListOf<String>()
    val c = KiffClient(transport, token = "tok").also {
      it.tenant = "t_01"
      it.setupSigner = { m -> signed += "device:" + String(m).lines().first(); "dev" }
      it.approvalSigner = { built -> signed += "approval:" + String(built()).lines().first(); "app" }
      it.window = ApprovalWindow("win_abcdefghijklmnopqrstuvwxyz", open = true)
    }
    c.pair(PairQr.read(qrText())!!)
    assertEquals(listOf("approval:kiff.request.v1"), signed)
    val header = transport.requestsTo("POST /v1/me/pairings").single().headers.getValue("KIFF-Device-Signature")
    assertTrue(header, header.contains(",v1=app") && !header.contains("w="))
    assertTrue(KiffClient.grantsAuthority("POST", KiffClient.PAIRINGS))
  }

  @Test fun putTheFingerprintAwayAndNothingIsSent() = runTest {
    val m = model()
    auth.confirms = false
    assertNull(m.pairComputer(PairQr.read(qrText())!!))
    assertTrue(transport.requestsTo("POST /v1/me/pairings").isEmpty())
    assertNull(m.state.value.devicesError)
  }

  @Test fun withoutTheApprovalKeyNothingIsSentAndItSaysWhy() = runTest {
    val m = model(approvalKey = false)
    assertNull(m.pairComputer(PairQr.read(qrText())!!))
    assertTrue(transport.requestsTo("POST /v1/me/pairings").isEmpty())
    assertEquals(KiffClient.APPROVAL_KEY_LOST, m.state.value.devicesError)
  }

  @Test fun eachRefusalSaysWhatToDo() = runTest {
    val m = model()
    val qr = PairQr.read(qrText())!!
    listOf(
      Triple(400, "pairing_expired", "The code on your computer expired."),
      Triple(409, "pairing_used", "That code was used already."),
      Triple(409, "key_registered", "That computer is already paired."),
      Triple(400, "invalid_pairing", "KIFF couldn't read that code."),
    ).forEach { (status, code, words) ->
      transport.answer("POST /v1/me/pairings", status, """{"error":"$code"}""")
      assertNull(m.pairComputer(qr))
      val said = m.state.value.devicesError!!
      assertTrue(said, said.startsWith("Add a computer didn't go through") && said.contains(words))
    }
  }

  @Test fun kiffsOwnWordsComeFirst() = runTest {
    val m = model()
    transport.answer("POST /v1/me/pairings", 409, """{"error":"pairing_used","words":"This computer's code was already approved from another phone."}""")
    assertNull(m.pairComputer(PairQr.read(qrText())!!))
    assertTrue(m.state.value.devicesError!!.contains("This computer's code was already approved from another phone."))
  }
}
