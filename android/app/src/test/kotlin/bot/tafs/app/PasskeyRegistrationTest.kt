package bot.tafs.app

import bot.tafs.app.kiff.ApprovalWindow
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffError
import bot.tafs.app.kiff.PasskeyRegistration
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A kiff.dev passkey made on this phone and registered with KIFF (`POST /v1/me/passkeys`), with proof of possession. The
 * shared vector (vectors/passkey-registration.json) is read by the iPhone and iPad tests too, so the apps can't drift.
 */
class PasskeyRegistrationTest {
  private val vector: JsonObject = KiffClient.json.parseToJsonElement(File("../../vectors/passkey-registration.json").readText()).jsonObject
  private val inputs = vector.getValue("inputs").jsonObject
  private val expected = vector.getValue("expected").jsonObject
  private val create = expected.getValue("create").jsonObject
  private fun input(key: String) = inputs.getValue(key).jsonPrimitive.content
  private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
  private fun url(bytes: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  private val transport = FakeTransport()

  /** The vector's creation response as Credential Manager answers it (`registrationResponseJson`). */
  private val responseJson: String = vector.getValue("creation_response").jsonObject.let { r ->
    """{"id":"cred","rawId":"cred","type":"public-key","response":{"clientDataJSON":"${url(hex(r.getValue("client_data_json_hex").jsonPrimitive.content))}","attestationObject":"${url(hex(r.getValue("attestation_object_hex").jsonPrimitive.content))}"}}"""
  }

  @Test fun theChallengeIsTheVectorsOverItsMessage() {
    assertEquals(expected.getValue("message").jsonPrimitive.content, PasskeyRegistration.message(input("tenant"), input("subject"), input("issued_at")))
    assertEquals(expected.getValue("challenge").jsonPrimitive.content, PasskeyRegistration.challenge(input("tenant"), input("subject"), input("issued_at")))
  }

  @Test fun theCreateRequestIsTheVectors() {
    val o = PasskeyRegistration.createOptions(input("tenant"), input("subject"), input("email"), input("issued_at"))
    fun str(key: String) = create.getValue(key).jsonPrimitive.content
    assertEquals(expected.getValue("challenge").jsonPrimitive.content, o.getValue("challenge").jsonPrimitive.content)
    assertEquals(str("rp_id"), o.getValue("rp").jsonObject.getValue("id").jsonPrimitive.content)
    assertEquals(str("user_id"), o.getValue("user").jsonObject.getValue("id").jsonPrimitive.content)
    assertEquals(str("user_name"), o.getValue("user").jsonObject.getValue("name").jsonPrimitive.content)
    // ES256 alone: KIFF refuses any other algorithm.
    assertEquals(listOf(str("algorithm")), o.getValue("pubKeyCredParams").jsonArray.map { it.jsonObject.getValue("alg").jsonPrimitive.content })
    val selection = o.getValue("authenticatorSelection").jsonObject
    assertEquals(str("user_verification"), selection.getValue("userVerification").jsonPrimitive.content)
    assertEquals(str("resident_key"), selection.getValue("residentKey").jsonPrimitive.content)
    assertEquals(str("attestation"), o.getValue("attestation").jsonPrimitive.content)
  }

  @Test fun theBodyIsTheResponseAsItCameAndNeverTheEarlierFields() {
    val body = PasskeyRegistration.registerBody(input("name"), input("issued_at"), responseJson)
    assertEquals(vector.getValue("register_body"), body)
    // Both kinds of body at once is a 400 from KIFF: the earlier fields are never sent.
    for (never in vector.getValue("never_in_body").jsonArray.map { it.jsonPrimitive.content }) assertFalse(never, never in body)
  }

  @Test fun aResponseWithoutItsProofSendsNothing() {
    assertThrows(IllegalArgumentException::class.java) { PasskeyRegistration.registerBody("Pixel", "2026-11-15T18:30:00Z", """{"id":"x","response":{"clientDataJSON":"abc"}}""") }
    assertThrows(IllegalArgumentException::class.java) { PasskeyRegistration.registerBody("Pixel", "2026-11-15T18:30:00Z", """{"id":"x"}""") }
  }

  @Test fun aNameIsTheVectorsRule() {
    val rule = vector.getValue("name").jsonObject
    for (pair in rule.getValue("accepted").jsonArray) {
      val (given, kept) = pair.jsonArray.map { it.jsonPrimitive.content }
      assertEquals(given, kept, PasskeyRegistration.validName(given))
    }
    for (given in rule.getValue("refused").jsonArray.map { it.jsonPrimitive.content }) assertNull(given, PasskeyRegistration.validName(given))
    // The phone's model, or a name KIFF takes when the model is one it would refuse: never a cut one.
    assertEquals("Pixel 8", PasskeyRegistration.name("  Pixel 8 "))
    // Trimmed as KIFF trims (Go's strings.TrimSpace): a no-break space and U+0085 at an end too.
    assertEquals("Pixel 8", PasskeyRegistration.name("\u00a0Pixel 8\u0085"))
    assertEquals("Android phone", PasskeyRegistration.name(""))
    assertEquals("Android phone", PasskeyRegistration.name("x".repeat(65)))
  }

  @Test fun issuedAtIsTheVectorsForm() {
    val at = vector.getValue("issued_at").jsonObject
    val now = java.time.Instant.ofEpochSecond(at.getValue("date_unix").jsonPrimitive.content.toLong()).plusMillis(400)
    assertEquals(at.getValue("rfc3339").jsonPrimitive.content, PasskeyRegistration.issuedAt(now))
  }

  private fun client(signed: MutableList<String>) = KiffClient(transport, token = "tok").also {
    it.tenant = "t_01"
    it.setupSigner = { m -> signed += "device:" + String(m).lines().first(); "dev" }
    it.approvalSigner = { built -> signed += "approval:" + String(built()).lines().first(); "app" }
    it.window = ApprovalWindow("win_abcdefghijklmnopqrstuvwxyz", open = true)
  }

  @Test fun registeringIsSignedByTheApprovalKeyNeverInsideAWindow() = runTest {
    transport.answer("POST /v1/me/passkeys", 201, """{"passkey":{"id":"pk_1","name":"Pixel 8","synced":null,"created_at":"2026-11-15T18:30:02Z"}}""")
    val signed = mutableListOf<String>()
    val body = PasskeyRegistration.registerBody(input("name"), input("issued_at"), responseJson)
    val made = client(signed).registerPasskey(body)
    assertEquals("pk_1", made.id)
    assertNull(made.synced)
    assertEquals(listOf("approval:kiff.request.v1"), signed)
    val sent = transport.requestsTo("POST /v1/me/passkeys").single()
    // Never inside a window: no `w=` field (a base64 signature may itself end in "w=").
    assertFalse(sent.headers.getValue("KIFF-Device-Signature").split(",").any { it.startsWith("w=") })
    assertEquals(vector.getValue("register_body"), KiffClient.json.parseToJsonElement(String(sent.body!!)))
    assertTrue(KiffClient.grantsAuthority("POST", KiffClient.PASSKEYS))
  }

  /**
   * KIFF takes only Play App Signing's certificate as the origin (`android:apk-key-hash:…`), so a locally signed debug build
   * is refused: the refusal says so (`origin`) and nothing is kept.
   */
  @Test fun aDebugBuildsOriginIsKiffsRefusalNotOurs() = runTest {
    transport.answer("POST /v1/me/passkeys", 400, """{"error":"registration_invalid","reason":"origin"}""")
    val body = PasskeyRegistration.registerBody(input("name"), input("issued_at"), responseJson)
    val e = assertThrows(KiffError.Server::class.java) { kotlinx.coroutines.runBlocking { client(mutableListOf()).registerPasskey(body) } }
    assertEquals(400, e.status)
    assertEquals("origin", e.reason)
  }

  @Test fun removingOneIsSignedByTheApprovalKeyAndTheListIsNot() = runTest {
    transport.answer("GET /v1/me/passkeys", 200, """{"passkeys":[{"id":"pk_1","name":"Pixel 8","synced":true},{"name":"Office PC","device_id":"dev_9"}]}""")
    transport.answer("DELETE /v1/me/passkeys/pk_1", 204, "")
    val signed = mutableListOf<String>()
    val c = client(signed)
    val list = c.passkeys()
    assertEquals(listOf(true, false), list.map { it.removable })
    assertTrue("a read signs nothing with the approval key", signed.none { it.startsWith("approval:") })
    c.removePasskey("pk_1")
    assertEquals("approval:kiff.request.v1", signed.last())
    assertTrue(KiffClient.grantsAuthority("DELETE", "/v1/me/passkeys/pk_1"))
    assertFalse("the list isn't a passkey", KiffClient.grantsAuthority("DELETE", "/v1/me/passkeys/"))
    assertFalse(KiffClient.grantsAuthority("DELETE", "/v1/me/passkeys/pk_1/extra"))
    assertFalse(KiffClient.grantsAuthority("GET", KiffClient.PASSKEYS))
  }
}
