package bot.tafs.app

import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffError
import bot.tafs.app.kiff.SigningTime
import bot.tafs.app.signing.SoftwareKeys
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KiffClientTest {
  private val transport = FakeTransport()
  private fun client(token: String? = "tok", tenant: String? = "t_01") =
    KiffClient(transport, token = token).also { it.tenant = tenant }

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDER))
    return Signature.getInstance("SHA256withECDSA").run {
      initVerify(key)
      update(message)
      verify(Base64.getDecoder().decode(signatureBase64))
    }
  }

  private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  // MARK: Signing in

  @Test fun startingSignInSendsTheKeysTheDeviceAndThePlatformWithNoSession() = runTest {
    transport.answer(
      "POST /v1/auth/device/start", 200,
      """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://app.kiff.dev/device","verification_uri_complete":"https://app.kiff.dev/device?c=ABCD-1234","expires_in":600,"interval":5}""",
    )
    val keys = SoftwareKeys()
    val start = client(token = null).startDeviceSignIn(keys.publicKeyDER, keys.approvalPublicKeyDER, "tafs on Android phone", "android")
    assertEquals("ABCD-1234", start.userCode)
    assertEquals(600, start.expiresIn)
    val request = transport.requests.single()
    assertEquals("https://api.kiff.dev/v1/auth/device/start", request.url)
    assertNull(request.headers["Authorization"])
    assertEquals("application/json", request.headers["Content-Type"])
    assertEquals("tafs-android", request.headers["User-Agent"])
    assertEquals("en", request.headers["Accept-Language"])
    val body = String(request.body!!)
    val encoder = Base64.getEncoder()
    assertTrue(body, body.contains(""""public_key":"${encoder.encodeToString(keys.publicKeyDER)}""""))
    assertTrue(body, body.contains(""""approval_public_key":"${encoder.encodeToString(keys.approvalPublicKeyDER)}""""))
    assertTrue(body, body.contains(""""device_name":"tafs on Android phone""""))
    assertTrue(body, body.contains(""""platform":"android""""))
  }

  @Test fun aPollNamesWhyThereIsNoSessionYet() = runTest {
    val c = client(token = null)
    transport.answer("POST /v1/auth/device/token", 400, """{"error":"authorization_pending"}""")
    try { c.pollDeviceToken("dc"); fail() } catch (_: KiffError.AuthorizationPending) {}
    transport.answer("POST /v1/auth/device/token", 400, """{"error":"slow_down"}""")
    try { c.pollDeviceToken("dc"); fail() } catch (_: KiffError.SlowDown) {}
    transport.answer("POST /v1/auth/device/token", 400, """{"error":"expired_token"}""")
    try { c.pollDeviceToken("dc"); fail() } catch (_: KiffError.Expired) {}
    transport.answer("POST /v1/auth/device/token", 400, """{"error":"access_denied"}""")
    try { c.pollDeviceToken("dc"); fail() } catch (e: KiffError.Server) { assertEquals("access_denied", e.message) }
  }

  @Test fun anApprovedPollGivesTheSession() = runTest {
    transport.answer("POST /v1/auth/device/token", 200, """{"access_token":"at","expires_in":3600,"tenant":"t_01","subject":"user_1"}""")
    val token = client(token = null).pollDeviceToken("dc")
    assertEquals("at", token.accessToken)
    assertEquals("t_01", token.tenant)
    assertEquals("""{"device_code":"dc"}""", String(transport.requests.single().body!!))
  }

  // MARK: Authorising

  @Test fun noSessionMeansNothingIsSentToAnAuthorisedRoute() = runTest {
    try { client(token = null).me(); fail() } catch (_: KiffError.Unauthorized) {}
    assertTrue(transport.requests.isEmpty())
  }

  @Test fun aSessionKiffEndedIsSignedOut() = runTest {
    transport.answer("GET /v1/me", 401, """{"error":"unauthorized"}""")
    try { client().me(); fail() } catch (_: KiffError.Unauthorized) {}
  }

  @Test fun theSessionGoesAsABearer() = runTest {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme"}""")
    client(token = "secret-token").me()
    assertEquals("Bearer secret-token", transport.requests.single().headers["Authorization"])
  }

  @Test fun theAccountIsReadAndNamedByItsNameElseItsSlug() = runTest {
    transport.answer(
      "GET /v1/me", 200,
      """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay","email":"o@acme.test","time_zone":"Europe/Paris","capabilities":["cards.manage"],"something_new":1}""",
    )
    val account = client().me()
    assertEquals("Acme Pay", account.name)
    assertEquals("o@acme.test", account.email)
    assertEquals("Europe/Paris", account.timeZone)
    assertEquals(listOf("cards.manage"), account.capabilities)

    val other = FakeTransport().also { it.answer("GET /v1/me", 200, """{"tenant_id":"t_02","tenant_slug":"plain","tenant_display_name":""}""") }
    assertEquals("plain", KiffClient(other, token = "x").me().name)
  }

  @Test fun anErrorIsKiffsOwnWordsWithItsReason() = runTest {
    transport.answer("GET /v1/me", 500, """{"error":"boom","reason":"card_revised"}""")
    try { client().me(); fail() } catch (e: KiffError.Server) {
      assertEquals(500, e.status)
      assertEquals("boom", e.message)
      assertEquals("card_revised", e.reason)
    }
  }

  @Test fun anErrorWithNoJsonStillSaysWhatKiffAnswered() = runTest {
    transport.answer("GET /v1/me", 502, "<html>Bad gateway</html>")
    try { client().me(); fail() } catch (e: KiffError.Server) {
      assertEquals(502, e.status)
      assertEquals("KIFF answered 502", e.message)
    }
  }

  // MARK: Signed requests

  @Test fun aSignedRequestCarriesTheSignatureOfKiffsRequestMessage() = runTest {
    val keys = SoftwareKeys()
    transport.answer("POST /v1/me/device/approval-key", 200, """{"fingerprint":"ab12 cd34 ef56 7890"}""")
    val c = client()
    val held = c.registerApprovalKey(keys.approvalPublicKeyDER) { message ->
      Base64.getEncoder().encodeToString(keys.sign(message))
    }
    assertEquals("ab12 cd34 ef56 7890", held)
    val request = transport.requests.single()
    val header = request.headers["KIFF-Device-Signature"]!!
    val match = Regex("""^t=(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z),v1=(.+)$""").matchEntire(header)
    assertTrue(header, match != null)
    val signedAt = match!!.groupValues[1]
    val body = request.body!!
    val message = "kiff.request.v1\nt_01\nPOST\n/v1/me/device/approval-key\n${sha256Hex(body)}\n$signedAt".toByteArray()
    assertTrue("the signature is over KIFF's message", verifies(keys.publicKeyDER, message, match.groupValues[2]))
    assertEquals("Bearer tok", request.headers["Authorization"])
  }

  @Test fun aReadIsNeverSignedEvenOnASignedPath() = runTest {
    transport.answer("GET /v1/me/agent-drafts", 200, "{}")
    val c = client().also { it.setupSigner = { "sig" } }
    c.send("GET", "/v1/me/agent-drafts")
    assertNull(transport.requests.single().headers["KIFF-Device-Signature"])
  }

  @Test fun theSigningTimeIsEverLaterThanTheLast() {
    val time = SigningTime { Instant.parse("2026-10-14T10:45:00Z") }
    assertEquals("2026-10-14T10:45:00.000Z", time.next())
    assertEquals("2026-10-14T10:45:00.001Z", time.next())
    assertEquals("2026-10-14T10:45:00.002Z", time.next())
  }

  @Test fun aClockThatMovesOnIsFollowed() {
    var now = Instant.parse("2026-10-14T10:45:00Z")
    val time = SigningTime { now }
    time.next()
    now = Instant.parse("2026-10-14T10:45:01.500Z")
    assertEquals("2026-10-14T10:45:01.500Z", time.next())
  }

  @Test fun aClockBehindIsNeverFollowedBackwards() {
    var now = Instant.parse("2026-10-14T10:45:05Z")
    val time = SigningTime { now }
    time.next()
    now = Instant.parse("2026-10-14T10:45:00Z")
    assertEquals("2026-10-14T10:45:05.001Z", time.next())
  }

  // MARK: Which key signs what

  @Test fun whatGrantsAuthorityGoesToTheApprovalKeyAndTheRestToTheCurrentOne() = runTest {
    val signed = mutableListOf<String>()
    val c = client().also {
      it.setupSigner = { m -> signed += "current:" + String(m).lines()[3]; "cur" }
      it.approvalSigner = { m -> signed += "approval:" + String(m).lines()[3]; "app" }
    }
    for (key in listOf("POST /v1/me/agent-drafts", "POST /v1/me/time-zone", "POST /v1/agents/a1/resume", "DELETE /v1/agents/a1/grants")) {
      transport.answer(key, 200)
    }
    // Each is sent with no explicit signer: the client picks by route.
    c.send("POST", "/v1/me/agent-drafts", mapOf("a" to "b"))
    c.send("POST", "/v1/me/time-zone", mapOf("time_zone" to "UTC"))
    c.send("POST", "/v1/agents/a1/resume")
    c.send("DELETE", "/v1/agents/a1/grants?subject=user_bo")
    assertEquals(
      listOf(
        "approval:/v1/me/agent-drafts",
        "current:/v1/me/time-zone",
        "approval:/v1/agents/a1/resume",
        "current:/v1/agents/a1/grants?subject=user_bo",
      ),
      signed,
    )
  }

  @Test fun withoutAnApprovalKeyTheCurrentOneSignsEverything() = runTest {
    val signed = mutableListOf<String>()
    val c = client().also { it.setupSigner = { m -> signed += String(m).lines()[3]; "cur" } }
    transport.answer("POST /v1/agents/a1/resume", 200)
    c.send("POST", "/v1/agents/a1/resume")
    assertEquals(listOf("/v1/agents/a1/resume"), signed)
    assertTrue(transport.requests.single().headers["KIFF-Device-Signature"]!!.endsWith("v1=cur"))
  }

  @Test fun aRequestKiffTakesUnsignedIsNotSignedEvenWithASigner() = runTest {
    val c = client().also { it.setupSigner = { "cur" } }
    transport.answer("POST /v1/me/card-exceptions/ce_1/approve-once", 200)
    c.send("POST", "/v1/me/card-exceptions/ce_1/approve-once", mapOf())
    assertNull(transport.requests.single().headers["KIFF-Device-Signature"])
  }

  @Test fun nothingIsSignedWithNoAccountOrNoSigner() = runTest {
    transport.answer("POST /v1/me/time-zone", 200)
    // A signer, but no account.
    client(tenant = null).also { it.setupSigner = { "cur" } }.send("POST", "/v1/me/time-zone", mapOf())
    // An account, but no signer.
    client().send("POST", "/v1/me/time-zone", mapOf())
    assertTrue(transport.requests.none { it.headers.containsKey("KIFF-Device-Signature") })
  }

  @Test fun whatNeedsAPhonesSignatureIsExactlyWhatKiffTakesSigned() {
    val signed = listOf(
      "/v1/me/agent-drafts", "/v1/me/alert-targets", "/v1/keys", "/v1/me/time-zone", "/v1/me/delete",
      "/v1/groups", "/v1/groups/g1", "/v1/groups/g1/members/u1",
      "/v1/agents/a1/grants", "/v1/agents/a1/grants?subject=user_bo", "/v1/agents/a1/grants?group=g1",
      "/v1/me/pack-requests/p1/decline", "/v1/resume", "/v1/agents/a1/resume",
      "/v1/me/agent-drafts/d1/confirm", "/v1/me/agent-drafts/d1/trial",
    )
    for (path in signed) assertTrue("$path is signed", KiffClient.signsSetup(path))
    val notSigned = listOf(
      "/v1/me", "/v1/me/devices", "/v1/me/card-exceptions", "/v1/me/card-exceptions/ce_1/approve-once",
      "/v1/agents/a1", "/v1/groups/g1/members", "/v1/me/pack-requests/p1", "/v1/me/agent-drafts/d1",
      "/v1/me/agent-drafts/d1/other", "/v1/auth/device/start",
    )
    for (path in notSigned) assertFalse("$path is not signed", KiffClient.signsSetup(path))
  }

  @Test fun onlyAPostThatGivesAuthorityIsSignedByTheApprovalKey() {
    assertTrue(KiffClient.grantsAuthority("POST", "/v1/me/agent-drafts"))
    assertTrue(KiffClient.grantsAuthority("POST", "/v1/me/agent-drafts/d1/confirm"))
    assertTrue(KiffClient.grantsAuthority("POST", "/v1/agents/a1/grants"))
    assertTrue(KiffClient.grantsAuthority("POST", "/v1/agents/a1/resume"))
    assertTrue(KiffClient.grantsAuthority("POST", "/v1/resume"))
    // Taking authority away is the current key's: ending a trial, unsharing, a time zone.
    assertFalse(KiffClient.grantsAuthority("POST", "/v1/me/agent-drafts/d1/trial"))
    assertFalse(KiffClient.grantsAuthority("DELETE", "/v1/agents/a1/grants"))
    assertFalse(KiffClient.grantsAuthority("POST", "/v1/me/time-zone"))
    assertFalse(KiffClient.grantsAuthority("GET", "/v1/me/agent-drafts"))
  }

  // MARK: The approval key

  @Test fun aKeyKiffHoldsAlreadyIsKiffsAnswerWithItsFingerprint() = runTest {
    transport.answer("POST /v1/me/device/approval-key", 409, """{"error":"approval_key_exists","fingerprint":"ab12 cd34 ef56 7890"}""")
    try {
      client().registerApprovalKey(byteArrayOf(1, 2, 3)) { "sig" }
      fail()
    } catch (e: KiffError.ApprovalKeyExists) {
      assertEquals("ab12 cd34 ef56 7890", e.fingerprint)
    }
  }

  @Test fun anActKiffTakesOnlyFromTheApprovalKeyIsSaidAndTheAppIsTold() = runTest {
    var told = false
    val c = client().also { it.approvalKeyRequired = { told = true } }
    transport.answer("GET /v1/me", 403, """{"error":"approval_key_required"}""")
    try { c.me(); fail() } catch (_: KiffError.ApprovalKeyRequired) {}
    assertTrue(told)
    assertEquals("Sign in with KIFF again to approve from this phone.", KiffError.ApprovalKeyRequired.message)
  }

  @Test fun theReasonAloneAlsoSaysIt() = runTest {
    var told = false
    val c = client().also { it.approvalKeyRequired = { told = true } }
    transport.answer("GET /v1/me", 403, """{"error":"forbidden","reason":"approval_key_required"}""")
    try { c.me(); fail() } catch (_: KiffError.ApprovalKeyRequired) {}
    assertTrue(told)
  }

  @Test fun anotherForbiddenIsJustKiffsWords() = runTest {
    transport.answer("GET /v1/me", 403, """{"error":"role_forbidden"}""")
    try { client().me(); fail() } catch (e: KiffError.Server) { assertEquals("role_forbidden", e.message) }
  }

  // MARK: Devices

  @Test fun thisDeviceIsTheOneKiffMarksWithADevId() = runTest {
    transport.answer(
      "GET /v1/me/devices", 200,
      """{"devices":[{"id":"dev_a","name":"tafs on iPhone","this_device":false},{"id":"sess_b","this_device":true},{"id":"dev_c","name":"tafs on Android phone","this_device":true}]}""",
    )
    assertEquals("dev_c", client().thisDeviceId())
  }

  @Test fun aDeviceIsRemovedByItsEscapedId() = runTest {
    // The fake keys by the decoded path; the request itself carries it escaped.
    transport.answer("DELETE /v1/me/devices/dev_a/b", 200)
    client().removeDevice("dev_a/b")
    assertEquals("https://api.kiff.dev/v1/me/devices/dev_a%2Fb", transport.requests.single().url)
  }

  @Test fun signingOutTellsKiff() = runTest {
    transport.answer("POST /v1/auth/device/logout", 200)
    client().signOut()
    assertEquals("""{}""", String(transport.requests.single().body!!))
  }

  // MARK: What the phone signs

  @Test fun theRequestMessageIsKiffRequestV1OneFieldPerLine() {
    val message = String(KiffClient.requestMessage("t1", "POST", "/v1/me/time-zone", "{}".toByteArray(), "2026-10-14T10:45:00.000Z"))
    assertEquals("kiff.request.v1\nt1\nPOST\n/v1/me/time-zone\n${sha256Hex("{}".toByteArray())}\n2026-10-14T10:45:00.000Z", message)
  }

  @Test fun theAnswerMessageIsKiffAnswerV1() {
    assertEquals(
      "kiff.answer.v1\nt1\nce_1\nreject\nreturn first\n\n2\n2026-10-14T10:45:00Z",
      String(KiffClient.answerMessage("t1", "ce_1", "reject", "return first", accountingVersion = 2, signedAt = "2026-10-14T10:45:00Z")),
    )
    assertEquals(
      "kiff.answer.v1\nt1\nce_1\napprove-once\n\n\n2\n2026-10-14T10:45:00Z",
      String(KiffClient.answerMessage("t1", "ce_1", "approve-once", null, accountingVersion = 2, signedAt = "2026-10-14T10:45:00Z")),
    )
  }

  @Test fun theAnswerMessageHashesTheNote() {
    // The same vector as iOS's.
    assertEquals(
      "kiff.answer.v1\nt1\nce_1\nreject\nreturn_first\n2b951386ddf8c195c3f5603c65de2156acb7f9f2a13638581448711e019dc654\n2\n2026-10-14T12:00:00Z",
      String(KiffClient.answerMessage("t1", "ce_1", "reject", "return_first", "Ask for photos.", 2, "2026-10-14T12:00:00Z")),
    )
  }

  @Test fun anEmptyNoteIsNoNote() {
    assertEquals(
      String(KiffClient.answerMessage("t1", "ce_1", "reject", null, null, 1, "x")),
      String(KiffClient.answerMessage("t1", "ce_1", "reject", null, "", 1, "x")),
    )
  }

  // MARK: Fingerprints and languages

  @Test fun theFingerprintIsKiffsFirstEightBytesInGroupsOfFour() {
    val der = byteArrayOf(0x30, 0x59, 0x02)
    val fingerprint = KiffClient.fingerprint(der)
    assertEquals(19, fingerprint.length)
    assertEquals(4, fingerprint.split(" ").size)
    assertEquals(sha256Hex(der).take(16).chunked(4).joinToString(" "), fingerprint)
  }

  @Test fun aFingerprintIsTheKeysWhateverItsCase() {
    val der = byteArrayOf(0x30, 0x59, 0x02)
    val fingerprint = KiffClient.fingerprint(der)
    assertTrue(KiffClient.sameKey(fingerprint.uppercase(), der))
    assertFalse(KiffClient.sameKey(fingerprint, byteArrayOf(0x30, 0x59, 0x03)))
    assertFalse(KiffClient.sameKey(null, der))
  }

  @Test fun theLanguagesAskedForAreTheAppsWithEnglishLast() {
    assertEquals("en", KiffClient.acceptLanguage(listOf("en")))
    assertEquals("fr-CA, de;q=0.9, en;q=0.8", KiffClient.acceptLanguage(listOf("fr_CA", "de")))
    assertEquals("en-GB, fr;q=0.9", KiffClient.acceptLanguage(listOf("en-GB", "fr")))
  }
}
