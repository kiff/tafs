package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.Passkey
import bot.tafs.app.kiff.PasskeyRegistration
import bot.tafs.app.model.Words
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
 * Settings, Devices: a kiff.dev passkey on this phone. Android makes it (Credential Manager, faked here), then the approval key
 * behind its fingerprint registers it with KIFF. KIFF decides whether it's registered; the phone shows what KIFF answers.
 */
class PasskeyAddTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-11-15T18:30:00.250Z")

  private val people = """{"members":[{"subject":"usr_other","role":"member"},{"subject":"usr_me","role":"owner","email":"owner@example.com","you":true}]}"""

  /** Credential Manager's answer, as WebAuthn's JSON: the proof KIFF reads the credential from. */
  private val made = """{"id":"cred","rawId":"cred","type":"public-key","response":{"clientDataJSON":"Y2xpZW50","attestationObject":"YXR0ZXN0"}}"""

  private suspend fun model(approvalKey: Boolean = true): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":[]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/tenant/members", 200, people)
    transport.answer("GET /v1/me/passkeys", 200, """{"passkeys":[]}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    if (approvalKey) secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore(); it.refresh() }
  }

  @Test fun androidMakesItFromKiffsChallengeAndTheApprovalKeyRegistersIt() = runTest {
    transport.answer("POST /v1/me/passkeys", 201, """{"passkey":{"id":"pk_1","name":"Pixel 8","synced":null}}""")
    val m = model()
    var asked: String? = null
    assertTrue(m.addPasskey("Pixel 8") { asked = it; made })
    // The creation's options carry the challenge over this person and this time, to the second.
    val options = KiffClient.json.parseToJsonElement(asked!!).jsonObject
    assertEquals(PasskeyRegistration.challenge("t_01", "usr_me", "2026-11-15T18:30:00Z"), options.getValue("challenge").jsonPrimitive.content)
    assertEquals("owner@example.com", options.getValue("user").jsonObject.getValue("name").jsonPrimitive.content)
    // One fingerprint, the approval key's, named for what it does; the body is the creation's proof as it came.
    assertEquals(listOf(Words.Settings.Devices.passkeyPrompt), auth.fingerprints)
    val sent = transport.requestsTo("POST /v1/me/passkeys").single()
    val body = KiffClient.json.parseToJsonElement(String(sent.body!!)).jsonObject
    assertEquals(PasskeyRegistration.registerBody("Pixel 8", "2026-11-15T18:30:00Z", made), body)
    assertFalse(sent.headers.getValue("KIFF-Device-Signature").contains("w="))
    assertEquals(Words.Settings.Devices.passkeyAdded, m.state.value.passkeyLine)
    assertFalse(m.state.value.passkeyBusy)
    assertTrue("the list is read again", transport.requestsTo("GET /v1/me/passkeys").isNotEmpty())
  }

  @Test fun theSamePasskeyRegisteredAlreadyIsDone() = runTest {
    transport.answer("POST /v1/me/passkeys", 409, """{"error":"passkey_registered"}""")
    val m = model()
    assertTrue(m.addPasskey("Pixel 8") { made })
    assertEquals(Words.Settings.Devices.passkeyAdded, m.state.value.passkeyLine)
  }

  @Test fun puttingAndroidsSheetAwaySendsNothing() = runTest {
    val m = model()
    assertFalse(m.addPasskey("Pixel 8") { null })
    assertTrue(transport.requestsTo("POST /v1/me/passkeys").isEmpty())
    assertTrue(auth.fingerprints.isEmpty())
    assertNull(m.state.value.passkeyLine)
    assertFalse(m.state.value.passkeyBusy)
  }

  @Test fun aPhoneThatCantMakeOneSaysSoAndSendsNothing() = runTest {
    val m = model()
    assertFalse(m.addPasskey("Pixel 8") { throw IllegalStateException("no provider") })
    assertTrue(transport.requestsTo("POST /v1/me/passkeys").isEmpty())
    assertTrue(m.state.value.passkeyLine!!.startsWith(Words.Settings.Devices.passkeyNotMade(why = "").trim().removeSuffix(".").take(20)))
  }

  /** Only the approval key registers a passkey: a session without one would let a stolen session add its own. */
  @Test fun aSessionWithNoApprovalKeyAddsNone() = runTest {
    val m = model(approvalKey = false)
    var made = false
    assertFalse(m.addPasskey("Pixel 8") { made = true; this@PasskeyAddTest.made })
    assertFalse("Android isn't even asked", made)
    assertTrue(transport.requestsTo("POST /v1/me/passkeys").isEmpty())
    assertEquals(Words.Settings.Devices.passkeyNeedsApprover, m.state.value.passkeyLine)
  }

  @Test fun kiffsRefusalsAreItsOwn() = runTest {
    transport.answer("POST /v1/me/passkeys", 403, """{"error":"admins_only"}""")
    val m = model()
    assertFalse(m.addPasskey("Pixel 8") { made })
    assertEquals(Words.Settings.Devices.passkeyAdminsOnly, m.state.value.passkeyLine)
    // A debug build's origin: KIFF's refusal, said with its reason, never an app's guess.
    transport.reset()
    val n = model()
    transport.answer("POST /v1/me/passkeys", 400, """{"error":"registration_invalid","reason":"origin"}""")
    assertFalse(n.addPasskey("Pixel 8") { made })
    assertTrue(n.state.value.passkeyLine!!.isNotEmpty())
    assertTrue(transport.requestsTo("POST /v1/me/passkeys").size == 1)
  }

  @Test fun aComputersOwnPasskeyIsNeverRemovedHere() = runTest {
    transport.answer("DELETE /v1/me/passkeys/pk_1", 204, "")
    val m = model()
    assertFalse(m.removePasskey(Passkey(name = "Office PC", deviceId = "dev_9")))
    assertTrue(transport.requestsTo("DELETE /v1/me/passkeys/pk_1").isEmpty())
    assertTrue(m.removePasskey(Passkey(id = "pk_1", name = "Pixel 8")))
    assertEquals(listOf(Words.Settings.Devices.passkeyRemovePrompt(name = "Pixel 8")), auth.fingerprints)
    assertEquals(1, transport.requestsTo("DELETE /v1/me/passkeys/pk_1").size)
  }
}
