package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.Relink
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.Answer
import bot.tafs.app.signing.ApprovalKeyRenewed
import bot.tafs.app.signing.DeviceSigner
import bot.tafs.app.signing.SignerException
import bot.tafs.app.signing.SigningKeys
import bot.tafs.app.signing.SoftwareKeys
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
 * Approve again in your browser: a phone whose approval key a new fingerprint turned off gets its new key approved in KIFF,
 * with no sign-out. A fake KIFF stands in, answering as KIFF's re-link contract says.
 */
class RelinkTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()

  private val relink = "POST /v1/me/device/relink"
  private val token = "POST /v1/auth/device/token"
  private val link = "https://app.kiff.dev/auth/device?code=WDJB-MJHT"
  private val started =
    """{"device_code":"dc_1","user_code":"WDJB-MJHT","verification_uri":"https://app.kiff.dev/auth/device","verification_uri_complete":"$link","expires_in":600,"interval":5}"""

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["holds.answer","cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
  }

  /** Signed in, with the approval key KIFF holds turned off by a fingerprint added while tafs was closed: known at launch. */
  private suspend fun TestScope.lostKey(keys: SigningKeys = SoftwareKeys().apply { invalidateApproval = true }): Pair<AppModel, DeviceSigner> {
    reads()
    val signer = DeviceSigner(keys, auth)
    secrets.save("at_1", AppModel.TOKEN); secrets.save("t_01", AppModel.TENANT); secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    val m = testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, start = false, scope = backgroundScope)
    m.restore()
    assertTrue(m.state.value.approvalKeyLost)
    return m to signer
  }

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDER))
    return Signature.getInstance("SHA256withECDSA").run {
      initVerify(key)
      update(message)
      verify(Base64.getDecoder().decode(signatureBase64))
    }
  }

  private fun relinked(key: ByteArray) = """{"status":"relinked","fingerprint":"${KiffClient.fingerprint(key)}"}"""

  @Test fun theProofIsKiffsMessageLineByLine() {
    val device = byteArrayOf(1, 2, 3)
    val approval = byteArrayOf(4, 5, 6)
    fun hex(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    assertEquals(
      "kiff.approval-key-relink.v1\nt_01\n${hex(device)}\n${hex(approval)}\n2026-10-30T22:00:00.000Z",
      String(KiffClient.relinkMessage("t_01", device, approval, "2026-10-30T22:00:00.000Z")),
    )
  }

  @Test fun aLostKeyIsApprovedAgainInTheBrowserAndTheSessionStays() = runTest {
    val (m, signer) = lostKey()
    transport.answer(relink, 200, started)
    transport.answer(token, 400, """{"error":"authorization_pending"}""")
    transport.answer(token, 200, relinked(signer.approvalPublicKeyDER))
    transport.hold(token)
    m.startRelink()
    runCurrent()
    // The phone shows KIFF's code, to compare with the browser's, while it waits.
    assertEquals(Relink("WDJB-MJHT", link), m.state.value.relink)
    transport.release(token)
    // The model polls in the background, every 5 seconds as KIFF says, which advanceUntilIdle doesn't wait for.
    advanceTimeBy(30_000)
    runCurrent()

    // One fingerprint, to prove the new key, and the request: the new key, signed by it, and the request by the device key.
    assertEquals(listOf(AppModel.RELINK_REASON), auth.fingerprints)
    val sent = transport.requestsTo(relink).single()
    val body = KiffClient.json.parseToJsonElement(String(sent.body!!)).jsonObject
    val key = signer.approvalPublicKeyDER
    assertEquals(Base64.getEncoder().encodeToString(key), body.getValue("approval_public_key").jsonPrimitive.content)
    val at = body.getValue("signed_at").jsonPrimitive.content
    assertTrue(verifies(key, KiffClient.relinkMessage("t_01", signer.publicKeyDER, key, at), body.getValue("signature").jsonPrimitive.content))
    assertTrue(sent.headers.getValue("KIFF-Device-Signature").startsWith("t="))

    // Approving works again, with no sign-out: the same session, and nothing signed in anew.
    assertFalse(m.state.value.approvalKeyLost)
    assertNull(m.state.value.relink)
    assertNull(m.state.value.relinkProblem)
    assertEquals(KiffClient.fingerprint(key), secrets.load(AppModel.APPROVAL_KEYED))
    assertEquals("at_1", secrets.load(AppModel.TOKEN))
    assertTrue(transport.requests.none { it.url.endsWith("/v1/auth/device/logout") || it.url.endsWith("/v1/auth/device/start") })
  }

  @Test fun theApprovalKeySignsOnceApprovedAgain() = runTest {
    val (m, signer) = lostKey()
    transport.answer(relink, 200, started)
    transport.answer(token, 200, relinked(signer.approvalPublicKeyDER))
    m.relink()
    // Its own held call, now: approving is asked of the approval key, behind a fingerprint.
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[{"id":"ce_1","subject":"a-1","action":"create_refund","accounting_version":1,"created_at":"2026-10-28T09:00:00Z","expires_at":"2099-01-01T00:00:00Z"}]}""")
    transport.answer("POST /v1/me/card-exceptions/ce_1/approve-once", 200, "{}")
    m.refresh()
    auth.fingerprints.clear()
    assertTrue(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    assertEquals(1, auth.fingerprints.size)
  }

  @Test fun aKiffWithoutReLinkingLeavesSignInAgain() = runTest {
    val (m, _) = lostKey()
    m.relink()
    assertTrue(m.state.value.relinkUnavailable)
    assertTrue(m.state.value.approvalKeyLost)
    assertTrue(transport.requestsTo(token).isEmpty())
  }

  @Test fun aCodeThatExpiresLeavesThePhoneAsItWasAndSaysSo() = runTest {
    val (m, _) = lostKey()
    transport.answer(relink, 200, started)
    transport.answer(token, 400, """{"error":"expired_token"}""")
    m.relink()
    assertEquals("The code expired before it was approved in your browser. Try again.", m.state.value.relinkProblem)
    assertTrue(m.state.value.approvalKeyLost)
    assertNull(m.state.value.relink)
    assertNull(secrets.load(AppModel.APPROVAL_KEYED))
  }

  @Test fun aRefusalInTheBrowserLeavesThePhoneAsItWasAndSaysSo() = runTest {
    val (m, _) = lostKey()
    transport.answer(relink, 200, started)
    transport.answer(token, 400, """{"error":"access_denied"}""")
    m.relink()
    assertEquals("It wasn't approved in the browser.", m.state.value.relinkProblem)
    assertTrue(m.state.value.approvalKeyLost)
  }

  @Test fun kiffHoldingAnotherKeyAfterwardsDoesntCountAsApproved() = runTest {
    val (m, _) = lostKey()
    transport.answer(relink, 200, started)
    transport.answer(token, 200, relinked(SoftwareKeys().approvalPublicKeyDER))
    m.relink()
    assertEquals(AppModel.RELINK_DIDNT_TAKE, m.state.value.relinkProblem)
    assertTrue(m.state.value.approvalKeyLost)
    assertNull(secrets.load(AppModel.APPROVAL_KEYED))
  }

  @Test fun aSignInsTokenInPlaceOfRelinkedIsNotTaken() = runTest {
    val (m, _) = lostKey()
    transport.answer(relink, 200, started)
    transport.answer(token, 200, """{"access_token":"at_2","expires_in":3600,"tenant":"t_01","subject":"u_1"}""")
    m.relink()
    assertEquals(AppModel.RELINK_FAILED, m.state.value.relinkProblem)
    assertTrue(m.state.value.approvalKeyLost)
    assertEquals("at_1", secrets.load(AppModel.TOKEN))
  }

  @Test fun aSecondTapWhileOneWaitsStartsNoOther() = runTest {
    val (m, _) = lostKey()
    transport.answer(relink, 200, started)
    transport.answer(token, 400, """{"error":"authorization_pending"}""")
    transport.hold(token)
    m.startRelink()
    runCurrent()
    m.startRelink()
    runCurrent()
    assertEquals(1, transport.requestsTo(relink).size)
    m.cancelRelink()
  }

  @Test fun cancellingStopsWaitingAndLeavesThePhoneAsItWas() = runTest {
    val (m, _) = lostKey()
    transport.answer(relink, 200, started)
    transport.answer(token, 400, """{"error":"authorization_pending"}""")
    m.startRelink()
    runCurrent()
    assertEquals("WDJB-MJHT", m.state.value.relink?.userCode)
    m.cancelRelink()
    val polls = transport.requestsTo(token).size
    advanceTimeBy(30_000)
    runCurrent()
    assertNull(m.state.value.relink)
    assertNull(m.state.value.relinkProblem)
    assertTrue(m.state.value.approvalKeyLost)
    assertEquals(polls, transport.requestsTo(token).size)
  }

  @Test fun aPhoneWithNoFingerprintIsToldSoAndNoBrowserOpens() = runTest {
    val inner = SoftwareKeys().apply { invalidateApproval = true }
    var enrolled = true
    val keys = object : SigningKeys {
      override val publicKeyDER get() = inner.publicKeyDER
      override val approvalPublicKeyDER get() = if (enrolled) inner.approvalPublicKeyDER else ByteArray(0)
      override fun sign(message: ByteArray) = inner.sign(message)
      override fun approvalSignature(): Signature = if (enrolled) inner.approvalSignature() else throw SignerException(DeviceSigner.NO_FINGERPRINT)
    }
    val (m, _) = lostKey(keys)
    // Every fingerprint removed since.
    enrolled = false
    m.relink()
    assertEquals(DeviceSigner.NO_FINGERPRINT, m.state.value.relinkProblem)
    assertNull(m.state.value.relink)
    assertTrue(transport.requestsTo(relink).isEmpty())
    assertTrue(auth.asked.isEmpty())
  }

  @Test fun aFingerprintNotGivenSendsNothing() = runTest {
    val (m, _) = lostKey()
    auth.confirms = false
    m.relink()
    assertEquals(DeviceSigner.NOT_RECOGNIZED, m.state.value.relinkProblem)
    assertTrue(transport.requestsTo(relink).isEmpty())
  }

  @Test fun aPhoneThatCanStillApproveIsNotReLinked() = runTest {
    reads()
    val signer = softwareSigner(auth)
    secrets.save("at_1", AppModel.TOKEN); secrets.save("t_01", AppModel.TENANT); secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    val m = testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, start = false, scope = backgroundScope)
    m.restore()
    m.relink()
    assertTrue(transport.requestsTo(relink).isEmpty())
    assertFalse(ApprovalKeyRenewed.APPROVE_AGAIN_WORDS.isEmpty())
  }
}
