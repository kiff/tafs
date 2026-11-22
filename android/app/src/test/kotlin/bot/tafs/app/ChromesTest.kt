package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.ChromePairing
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
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
 * Account › Chrome (docs/android-app.md, "Pairing a Chrome"; docs/chrome.md): the row only once planque offers Chromes, an
 * offer shown only on its proof, and the confirmation the approval key signs, behind one fingerprint, with the secret never sent.
 * A fake planque and KIFF stand in; the extension's side is checked as it checks it.
 */
class ChromesTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-11-21T12:00:00Z")
  private val chromes = "GET /v1/accounts/t_01/chromes"
  private val open = "POST /v1/accounts/t_01/chromes/pairings"
  private val read = "GET /v1/accounts/t_01/chromes/pairings/pr_1"
  private val confirm = "POST /v1/accounts/t_01/chromes/pairings/pr_1/confirm"

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
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also {
      it.restore()
      it.refreshConnections()
    }
  }

  /** An extension's key pair, and what it posts for [secret]: its SPKI and its proof under the secret. */
  private val extension = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  private fun offer(secret: String, proofKey: ByteArray = extension.public.encoded, kiffOffer: String? = null, proofOffer: String? = kiffOffer, oneTouch: Boolean = false) =
    """{"pairing":"pr_1","status":"waiting_for_phone",${if (oneTouch) "\"one_touch\":true," else ""}${kiffOffer?.let { "\"kiff_offer\":" + kotlinx.serialization.json.JsonPrimitive(it) + "," }.orEmpty()}"key":"${ChromePairing.base64Url(extension.public.encoded)}","proof":"${ChromePairing.base64Url(ChromePairing.hmac(secret, ChromePairing.EXTENSION_PROOF_LABEL, proofKey + proofOffer.orEmpty().toByteArray()))}","version":"1.0.0","browser":"Chrome 131","os":"macOS"}"""

  /**
   * Account opened before planque was ready (the first seconds after launch) read no Chromes, and its row stayed hidden: the
   * first grants read that makes planque ready reads them, so the row shows whatever else Account read meanwhile.
   */
  @Test fun theRowShowsOnceReadyEvenWhenAccountOpenedBeforePlanqueWas() = runTest {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    // planque isn't ready at first (its first grants read fails), then is.
    transport.answer("GET /v1/accounts/t_01/grants", 503, """{"error":"unavailable"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
    transport.answer("GET /v1/me/sign-in", 403, """{"error":"phone_session_forbidden"}""")
    transport.answer(chromes, 200, """{"chromes":[]}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    val m = testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore() }
    // Account opens first: the Sign-in read is refused, and the Chromes can't be read yet.
    m.loadCodeSignIn()
    m.refreshChromes()
    assertNull("not ready: no row yet", m.state.value.chromes)
    // planque becomes ready: the Chromes are read, and the row shows.
    m.refreshConnections()
    assertEquals(emptyList<bot.tafs.app.kiff.Chrome>(), m.state.value.chromes)
    assertEquals(1, transport.requestsTo(chromes).size)
  }

  @Test fun theRowShowsOnlyOncePlanqueOffersChromes() = runTest {
    val m = model()
    transport.answer(chromes, 404, """{"error":"a browser in your own Chrome isn't offered here"}""")
    m.refreshChromes()
    assertNull("planque's switch is off: no row", m.state.value.chromes)
    transport.answer(chromes, 200, """{"chromes":[{"chrome_id":"chr_1","name":"Work Mac","browser":"Chrome 131","os":"macOS"}]}""")
    m.refreshChromes()
    assertEquals(listOf("Work Mac"), m.state.value.chromes?.map { it.name })
    assertTrue("a read asks for nothing", auth.asked.isEmpty() && auth.fingerprints.isEmpty())
  }

  @Test fun anOfferShowsOnlyWhenItsProofHoldsUnderThePhonesSecret() = runTest {
    val m = model()
    transport.answer(open, 201, """{"pairing":"pr_1","lookup":"K7QF","expires_at":"2026-11-21T12:10:00Z"}""")
    val p = m.openChromePairing()!!
    assertEquals("K7QF", p.lookup)
    assertEquals(ChromePairing.SECRET_LENGTH, p.secret.length)
    assertEquals("K7QF-" + p.secret.chunked(4).joinToString("-"), p.code)
    assertFalse("the secret is never logged", p.toString().contains(p.secret))
    transport.answer(read, 200, """{"pairing":"pr_1","status":"waiting_for_chrome"}""")
    assertEquals(AppModel.ChromePairingStep.Waiting, m.chromePairingStep(p))
    // Another extension used the lookup: its proof isn't under our secret, or isn't over its own key.
    transport.answer(read, 200, offer("Z".repeat(ChromePairing.SECRET_LENGTH)))
    assertEquals(AppModel.ChromePairingStep.Refused, m.chromePairingStep(p))
    transport.answer(read, 200, offer(p.secret, proofKey = byteArrayOf(1, 2, 3)))
    assertEquals(AppModel.ChromePairingStep.Refused, m.chromePairingStep(p))
    transport.answer(read, 200, offer(p.secret))
    val step = m.chromePairingStep(p)
    assertTrue(step is AppModel.ChromePairingStep.Offered)
    assertEquals("Chrome 131", (step as AppModel.ChromePairingStep.Offered).offer.browser)
    // Another Chrome also tried the code: planque spent it, and the owner starts again.
    transport.answer(read, 200, """{"pairing":"pr_1","status":"contested"}""")
    assertEquals(AppModel.ChromePairingStep.Refused, m.chromePairingStep(p))
    transport.answer(read, 404, """{"error":"no such pairing, or it expired"}""")
    assertEquals(AppModel.ChromePairingStep.Expired, m.chromePairingStep(p))
    // The secret never left the phone.
    assertTrue(transport.requestsTo(open).plus(transport.requestsTo(read)).none { String(it.body ?: ByteArray(0)).contains(p.secret) || it.url.contains(p.secret) })
  }

  @Test fun oneFingerprintNamingTheChromeSignsTheConfirmationWithTheApprovalKeyAsRawRAndS() = runTest {
    val m = model()
    transport.answer(open, 201, """{"pairing":"pr_1","lookup":"K7QF"}""")
    val p = m.openChromePairing()!!
    transport.answer(read, 200, offer(p.secret))
    val offered = (m.chromePairingStep(p) as AppModel.ChromePairingStep.Offered).offer
    transport.answer(confirm, 200, """{"chrome_id":"chr_1","name":"Work Mac"}""")
    transport.answer(chromes, 200, """{"chromes":[{"chrome_id":"chr_1","name":"Work Mac"}]}""")
    assertTrue(m.confirmChrome(p, offered, "  Work Mac "))
    assertEquals(listOf("Pair Work Mac with your tafs"), auth.fingerprints)
    val body = KiffClient.json.parseToJsonElement(String(transport.requestsTo(confirm).single().body!!)).jsonObject
    fun field(k: String) = body[k]!!.jsonPrimitive.content
    // This phone's key is the approval key, and its proof is over it and the extension's key, under the secret.
    assertEquals(ChromePairing.base64Url(signer.approvalPublicKeyDER), field("phone_key"))
    assertEquals(ChromePairing.phoneProof(p.secret, signer.approvalPublicKeyDER, extension.public.encoded), field("phone_proof"))
    assertEquals(ChromePairing.chromeKeyHash(extension.public.encoded), field("chrome_key_sha256"))
    assertEquals("Work Mac", field("name"))
    // The extension checks the signature as WebCrypto does: raw r‖s over label ‖ 0 ‖ JCS, with the phone's key.
    val raw = ChromePairing.base64UrlDecoded(field("phone_sig"))!!
    assertEquals(64, raw.size)
    val signed = ChromePairing.signedBytes("pr_1", ChromePairing.Confirmation(field("chrome_key_sha256"), "Work Mac", field("phone_key"), field("phone_proof")))
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(signer.approvalPublicKeyDER))
    assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(key); update(signed); verify(raw) })
    val device = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(signer.publicKeyDER))
    assertFalse("never the key that signs with no prompt", Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(device); update(signed); verify(raw) })
    assertFalse(String(transport.requestsTo(confirm).single().body!!).contains(p.secret))
    assertEquals(listOf("Work Mac"), m.state.value.chromes?.map { it.name })
  }

  /** The extension's own KIFF device, as `chrome/src/kiff.js` offers it. */
  private val kiffKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  private fun kiffOffer(kind: String = "extension", keyStore: String = "software") =
    """{"exp":"2026-11-21T12:04:30Z","key_store":"$keyStore","kind":"$kind","name":"tafs for Chrome","nonce":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","platform":"chrome","pub":"${ChromePairing.base64Url(kiffKey.public.encoded)}","v":"kiff.pair.v1"}"""
  private val kiffPair = "POST /v1/me/pairings"

  @Test fun anOfferWhoseKiffDeviceCouldApproveOrWhoseProofLeavesItOutIsRefused() = runTest {
    val m = model()
    transport.answer(open, 201, """{"pairing":"pr_1","lookup":"K7QF"}""")
    val p = m.openChromePairing()!!
    transport.answer(read, 200, offer(p.secret, kiffOffer = kiffOffer(kind = "enclave", keyStore = "hardware")))
    assertEquals(AppModel.ChromePairingStep.Refused, m.chromePairingStep(p))
    // planque swapped the KIFF offer: the proof was over another one.
    transport.answer(read, 200, offer(p.secret, kiffOffer = kiffOffer(), proofOffer = ""))
    assertEquals(AppModel.ChromePairingStep.Refused, m.chromePairingStep(p))
    transport.answer(read, 200, offer(p.secret, kiffOffer = kiffOffer()))
    assertTrue(m.chromePairingStep(p) is AppModel.ChromePairingStep.Offered)
  }

  @Test fun theExtensionsKiffDeviceIsPairedByTheApprovalKeyBeforePlanqueHearsTheConfirmation() = runTest {
    val m = model()
    transport.answer(open, 201, """{"pairing":"pr_1","lookup":"K7QF"}""")
    val p = m.openChromePairing()!!
    transport.answer(read, 200, offer(p.secret, kiffOffer = kiffOffer()))
    val offered = (m.chromePairingStep(p) as AppModel.ChromePairingStep.Offered).offer
    transport.answer(kiffPair, 201, """{"pairing":{"id":"pair_1","code":"K7Q2MX","expires":"2026-11-21T12:05:00Z"}}""")
    transport.answer(confirm, 200, """{"chrome_id":"chr_1","name":"Work Mac"}""")
    transport.answer(chromes, 200, """{"chromes":[{"chrome_id":"chr_1","name":"Work Mac"}]}""")
    assertTrue(m.confirmChrome(p, offered, "Work Mac"))
    // One fingerprint for each signature the approval key makes, both naming the Chrome.
    assertEquals(listOf("Pair Work Mac with your tafs", "Pair Work Mac with your tafs"), auth.fingerprints)
    val sent = transport.requests.map { "${it.method} ${java.net.URI(it.url).path}" }
    assertTrue("KIFF's pairing first", sent.indexOf(kiffPair) in 0 until sent.indexOf(confirm))
    val pairing = transport.requestsTo(kiffPair).single()
    assertEquals(KiffClient.json.parseToJsonElement(offered.kiffOffer), KiffClient.json.parseToJsonElement(String(pairing.body!!)))
    val (at, sig) = pairing.headers.getValue("KIFF-Device-Signature").split(",").let { it[0].removePrefix("t=") to it[1].removePrefix("v1=") }
    val message = KiffClient.requestMessage("t_01", "POST", "/v1/me/pairings", pairing.body!!, at)
    val approval = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(signer.approvalPublicKeyDER))
    assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(approval); update(message); verify(java.util.Base64.getDecoder().decode(sig)) })
    assertNull(m.state.value.chromesError)
  }

  @Test fun anExtensionThatChecksOneTouchGetsOneFingerprintOverThePairingAndItsKiffDevice() = runTest {
    val m = model()
    transport.answer(open, 201, """{"pairing":"pr_1","lookup":"K7QF"}""")
    val p = m.openChromePairing()!!
    transport.answer(read, 200, offer(p.secret, kiffOffer = kiffOffer(), oneTouch = true))
    val offered = (m.chromePairingStep(p) as AppModel.ChromePairingStep.Offered).offer
    assertTrue(offered.oneTouch)
    transport.answer(kiffPair, 201, """{"pairing":{"id":"pair_1","code":"K7Q2MX","expires":"2026-11-21T12:05:00Z"}}""")
    transport.answer(confirm, 200, """{"chrome_id":"chr_1","name":"Work Mac"}""")
    transport.answer(chromes, 200, """{"chromes":[{"chrome_id":"chr_1","name":"Work Mac"}]}""")
    assertTrue(m.confirmChrome(p, offered, "Work Mac"))
    assertEquals("one prompt for both", listOf("Pair Work Mac with your tafs"), auth.fingerprints)
    val sent = transport.requests.map { "${it.method} ${java.net.URI(it.url).path}" }
    assertTrue("KIFF's device first", sent.indexOf(kiffPair) in 0 until sent.indexOf(confirm))

    // planque's confirm: the pairing the extension checks, and what it rebuilds the one signature from.
    val body = KiffClient.json.parseToJsonElement(String(transport.requestsTo(confirm).single().body!!)).jsonObject
    fun field(k: String) = body[k]!!.jsonPrimitive.content
    val pairHash = KiffClient.messageHash(ChromePairing.signedBytes("pr_1", ChromePairing.Confirmation(field("chrome_key_sha256"), "Work Mac", field("phone_key"), field("phone_proof"))))
    // KIFF's request: v3 with the pairing's hash, at the same time.
    val pairing = transport.requestsTo(kiffPair).single()
    val header = pairing.headers.getValue("KIFF-Device-Signature").split(",").associate { it.substringBefore("=") to it.substringAfter("=") }
    assertEquals("sha256:$pairHash", header["with"])
    val at = header.getValue("t")
    val kiffHash = KiffClient.messageHash(KiffClient.requestMessage("t_01", "POST", "/v1/me/pairings", pairing.body!!, at))
    assertEquals("t_01", field("tenant"))
    assertEquals(at, field("signed_at"))
    assertEquals(kiffHash, field("kiff_request_sha256"))
    // One signature over both, as KIFF checks it (DER) and as the extension checks it (raw r‖s).
    val both = KiffClient.requestsMessage("t_01", at, pairHash, kiffHash)
    val approval = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(signer.approvalPublicKeyDER))
    assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(approval); update(both); verify(java.util.Base64.getDecoder().decode(header.getValue("v3"))) })
    val raw = ChromePairing.base64UrlDecoded(field("phone_sig"))!!
    assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(approval); update(both); verify(raw) })
    assertFalse("never the pairing's bytes alone: an extension that checks one-touch rebuilds both",
      Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(approval); update(ChromePairing.signedBytes("pr_1", ChromePairing.Confirmation(field("chrome_key_sha256"), "Work Mac", field("phone_key"), field("phone_proof")))); verify(raw) })
    assertNull(m.state.value.chromesError)
  }

  @Test fun withoutOneTouchTheConfirmCarriesNoneOfItsFields() = runTest {
    val m = model()
    transport.answer(open, 201, """{"pairing":"pr_1","lookup":"K7QF"}""")
    val p = m.openChromePairing()!!
    transport.answer(read, 200, offer(p.secret, kiffOffer = kiffOffer()))
    val offered = (m.chromePairingStep(p) as AppModel.ChromePairingStep.Offered).offer
    transport.answer(kiffPair, 201, """{"pairing":{"id":"pair_1","code":"K7Q2MX","expires":"2026-11-21T12:05:00Z"}}""")
    transport.answer(confirm, 200, """{"chrome_id":"chr_1","name":"Work Mac"}""")
    transport.answer(chromes, 200, """{"chromes":[{"chrome_id":"chr_1","name":"Work Mac"}]}""")
    assertTrue(m.confirmChrome(p, offered, "Work Mac"))
    assertEquals(2, auth.fingerprints.size)
    val body = KiffClient.json.parseToJsonElement(String(transport.requestsTo(confirm).single().body!!)).jsonObject
    assertTrue(listOf("tenant", "signed_at", "kiff_request_sha256").none { it in body })
  }

  @Test fun aKiffRefusalOfTheExtensionsDeviceConfirmsNoChrome() = runTest {
    val m = model()
    transport.answer(open, 201, """{"pairing":"pr_1","lookup":"K7QF"}""")
    val p = m.openChromePairing()!!
    transport.answer(read, 200, offer(p.secret, kiffOffer = kiffOffer()))
    val offered = (m.chromePairingStep(p) as AppModel.ChromePairingStep.Offered).offer
    transport.answer(kiffPair, 410, """{"error":"pairing_expired","message":"This pairing expired. Start again on the computer."}""")
    assertFalse(m.confirmChrome(p, offered, "Work Mac"))
    assertTrue("planque hears nothing", transport.requestsTo(confirm).isEmpty())
    assertTrue(m.state.value.chromesError!!.isNotBlank())
  }

  @Test fun aClosedPromptConfirmsNothing() = runTest {
    val m = model()
    transport.answer(open, 201, """{"pairing":"pr_1","lookup":"K7QF"}""")
    val p = m.openChromePairing()!!
    transport.answer(read, 200, offer(p.secret))
    val offered = (m.chromePairingStep(p) as AppModel.ChromePairingStep.Offered).offer
    auth.confirms = false
    assertFalse(m.confirmChrome(p, offered, "Work Mac"))
    assertTrue("nothing sent", transport.requestsTo(confirm).isEmpty())
    assertTrue(m.state.value.chromesError != null)
  }

  @Test fun unpairingIsSignedByThisPhoneWithNoPromptAndTheListIsReadAgain() = runTest {
    val m = model()
    transport.answer(chromes, 200, """{"chromes":[{"chrome_id":"chr_1","name":"Work Mac"}]}""")
    m.refreshChromes()
    transport.answer("DELETE /v1/accounts/t_01/chromes/chr_1", 200, """{"chrome_id":"chr_1","status":"unpaired"}""")
    transport.answer(chromes, 200, """{"chromes":[]}""")
    m.removeChrome(m.state.value.chromes!!.single())
    assertTrue(auth.fingerprints.isEmpty())
    assertEquals(1, transport.requestsTo("DELETE /v1/accounts/t_01/chromes/chr_1").size)
    assertEquals(emptyList<String>(), m.state.value.chromes?.map { it.name })
  }
}
