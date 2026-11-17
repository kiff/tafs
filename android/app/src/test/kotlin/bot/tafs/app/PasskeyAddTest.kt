package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.Passkey
import bot.tafs.app.kiff.PasskeyRegistration
import bot.tafs.app.model.Words
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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

  private fun people(role: String = "owner") = """{"members":[{"subject":"usr_other","role":"member"},{"subject":"usr_me","role":"$role","email":"owner@example.com","you":true}]}"""

  /** Credential Manager's answer, as WebAuthn's JSON: the proof KIFF reads the credential from. */
  private val made = """{"id":"cred","rawId":"cred","type":"public-key","response":{"clientDataJSON":"Y2xpZW50","attestationObject":"YXR0ZXN0"}}"""

  private suspend fun model(approvalKey: Boolean = true, role: String = "owner"): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":[]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/tenant/members", 200, people(role))
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
    // Never inside a window: no `w=` field (a base64 signature may itself end in "w=").
    assertFalse(sent.headers.getValue("KIFF-Device-Signature").split(",").any { it.startsWith("w=") })
    assertEquals(Words.Settings.Devices.passkeyAdded, m.state.value.passkeyLine)
    assertFalse(m.state.value.passkeyBusy)
    assertTrue("the list is read again", transport.requestsTo("GET /v1/me/passkeys").isNotEmpty())
  }

  @Test fun theSamePasskeyRegisteredAlreadyIsDone() = runTest {
    // KIFF's own answer: its code in `error`, with no `reason`.
    transport.answer("POST /v1/me/passkeys", 409, """{"error":"passkey_registered","message":"this passkey is already registered"}""")
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

  /** KIFF takes a passkey from owners and admins only: a member's tap never asks Android, so nothing is left on the phone. */
  @Test fun aMembersTapNeverAsksAndroid() = runTest {
    val m = model(role = "member")
    var asked = false
    assertFalse(m.addPasskey("Pixel 8") { asked = true; made })
    assertFalse("Android isn't asked", asked)
    assertTrue(auth.fingerprints.isEmpty())
    assertTrue(transport.requestsTo("POST /v1/me/passkeys").isEmpty())
    assertEquals(Words.Settings.Devices.passkeyAdminsOnly, m.state.value.passkeyLine)
    assertEquals(false, m.state.value.passkeyMayAdd)
  }

  @Test fun addIsOfferedToOwnersAndAdminsAsKiffSays() = runTest {
    val m = model()
    m.refreshPasskeys()
    assertEquals(true, m.state.value.passkeyMayAdd)
    transport.reset()
    val member = model(role = "member")
    member.refreshPasskeys()
    assertEquals(false, member.state.value.passkeyMayAdd)
    assertTrue(PasskeyRegistration.mayAdd("admin"))
    assertFalse(PasskeyRegistration.mayAdd(null))
  }

  @Test fun kiffsRefusalsAreItsOwn() = runTest {
    transport.answer("POST /v1/me/passkeys", 403, """{"error":"admins_only"}""")
    val m = model()
    assertFalse(m.addPasskey("Pixel 8") { made })
    assertEquals(Words.Settings.Devices.passkeyAdminsOnly, m.state.value.passkeyLine)
    // A debug build's origin: made on the phone, refused by KIFF, so it says the passkey isn't on the account.
    transport.reset()
    val n = model()
    transport.answer("POST /v1/me/passkeys", 400, """{"error":"registration_invalid","reason":"origin"}""")
    assertFalse(n.addPasskey("Pixel 8") { made })
    assertEquals(Words.Settings.Devices.passkeyNotRegistered, n.state.value.passkeyLine)
    // Past KIFF's 10 minutes from the challenge: try again, with a new one.
    transport.reset()
    val o = model()
    transport.answer("POST /v1/me/passkeys", 400, """{"error":"registration_invalid","reason":"issued_at"}""")
    assertFalse(o.addPasskey("Pixel 8") { made })
    assertEquals(Words.Settings.Devices.passkeyTooSlow, o.state.value.passkeyLine)
  }

  /** The fingerprint put away after Android made the passkey: nothing is sent, and the person hears it isn't added. */
  @Test fun theFingerprintPutAwayAfterItsMadeSaysItIsntAdded() = runTest {
    val m = model()
    auth.confirms = false
    assertFalse(m.addPasskey("Pixel 8") { made })
    assertTrue(transport.requestsTo("POST /v1/me/passkeys").isEmpty())
    assertEquals(Words.Settings.Devices.passkeyNotRegistered, m.state.value.passkeyLine)
  }

  @Test fun aClosedPromptRemovesNoPasskeyAndSaysCancelled() = runTest {
    transport.answer("DELETE /v1/me/passkeys/pk_1", 204, "")
    val m = model()
    auth.fails = bot.tafs.app.signing.PromptClosed()
    assertFalse(m.removePasskey(Passkey(id = "pk_1", name = "Pixel 8")))
    assertTrue(transport.requestsTo("DELETE /v1/me/passkeys/pk_1").isEmpty())
    assertEquals("Cancelled. Nothing changed.", m.state.value.passkeyLine)
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

  @Test fun removingTheLastPasskeyThatCanApproveWarnsFirst() {
    val phone = Passkey(id = "pk_1", name = "Pixel 8")
    val computer = Passkey(name = "Office PC", deviceId = "dev_9")
    val ipad = Passkey(id = "pk_2", name = "iPad")
    assertFalse("phone + computer: the computer's own still approves", Passkey.removesLast(phone, listOf(phone, computer)))
    assertTrue("phone alone: warned", Passkey.removesLast(phone, listOf(phone)))
    assertFalse("phone + iPad: no warning", Passkey.removesLast(phone, listOf(phone, ipad)))
    assertEquals(
      "This is your only passkey that can approve. Without it, you need another device that can approve.",
      Words.Settings.Devices.passkeyRemoveLast,
    )
  }

  @Test fun aReadFromBeforeARemovalNeverBringsTheRemovedPasskeyBack() = runTest {
    val both = """{"passkeys":[{"id":"pk_1","name":"Pixel 8"},{"id":"pk_2","name":"iPad"}]}"""
    val m = model()
    // Whatever the model read on its own first, the list now has both.
    transport.answer("GET /v1/me/passkeys", 200, both)
    repeat(3) { if (m.state.value.passkeys.size != 2) m.refreshPasskeys() }
    assertEquals(listOf("pk_1", "pk_2"), m.state.value.passkeys.map { it.id })
    // A read starts and is slow; it will answer with the list from before the removal.
    val slow = transport.holdNext("GET /v1/me/passkeys")
    val reading = launch { m.refreshPasskeys() }
    runCurrent()
    transport.answer("DELETE /v1/me/passkeys/pk_1", 204, "")
    transport.answer("GET /v1/me/passkeys", 200, """{"passkeys":[{"id":"pk_2","name":"iPad"}]}""")
    assertTrue(m.removePasskey(Passkey(id = "pk_1", name = "Pixel 8")))
    assertEquals(listOf("pk_2"), m.state.value.passkeys.map { it.id })
    // The slow read ends now, with both: it started before the removal, so it's dropped.
    slow.complete(Unit)
    reading.join()
    assertEquals(listOf("pk_2"), m.state.value.passkeys.map { it.id })
  }
}
