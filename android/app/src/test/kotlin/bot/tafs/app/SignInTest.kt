package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.Mode
import bot.tafs.app.app.SampleStore
import bot.tafs.app.kiff.DeviceStart
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffError
import bot.tafs.app.signing.ApprovalSigning
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SignInTest {
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val signer: ApprovalSigning = softwareSigner()

  private val start = DeviceStart("dc_1", "ABCD-1234", "https://app.kiff.dev/device", "https://app.kiff.dev/device?c=ABCD-1234", expiresIn = 600, interval = 5)

  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}"""
  private val token = """{"access_token":"at_1","expires_in":3600,"tenant":"t_01","subject":"user_1"}"""

  /** A model on virtual time, so a poll that waits seconds takes none. */
  private fun TestScope.model(store: SampleStore? = null, start: Boolean = false): AppModel =
    testModel(store = store, transport = transport, secrets = secrets, signer = signer, clock = { t0.plusMillis(currentTime) }, start = start)

  private fun fingerprintOfOurApprovalKey() = KiffClient.fingerprint(signer.approvalPublicKeyDER)

  private fun approved() {
    transport.answer("POST /v1/auth/device/token", 200, token)
    transport.answer("GET /v1/me", 200, me)
    transport.answer("POST /v1/me/device/approval-key", 200, """{"fingerprint":"${fingerprintOfOurApprovalKey()}"}""")
  }

  // MARK: Starting

  @Test fun theModelStartsWithNothingUntilItIsAskedToRestore() = runTest {
    val m = model()
    assertEquals(Mode.signedOut, m.state.value.mode)
    assertFalse(m.state.value.signedIn)
    assertFalse(m.state.value.ready)
  }

  @Test fun startingSignInSendsThisPhonesKeysAndNamesItAndroid() = runTest {
    transport.answer("POST /v1/auth/device/start", 200, """{"device_code":"dc_1","user_code":"ABCD-1234","verification_uri":"u","verification_uri_complete":"uc","expires_in":600,"interval":5}""")
    val started = model().startSignIn()
    assertEquals("ABCD-1234", started.userCode)
    val body = String(transport.requests.single().body!!)
    val encoder = Base64.getEncoder()
    assertTrue(body, body.contains(encoder.encodeToString(signer.publicKeyDER)))
    assertTrue(body, body.contains(encoder.encodeToString(signer.approvalPublicKeyDER)))
    assertTrue(body, body.contains(""""platform":"android""""))
    assertTrue(body, body.contains(""""device_name":"tafs on Android""""))
  }

  @Test fun aDroppedConnectionIsTriedAgainBeforeItCountsAsAFailure() = runTest {
    val good = """{"device_code":"dc_1","user_code":"ABCD-1234","verification_uri":"u","verification_uri_complete":"uc","expires_in":600,"interval":5}"""
    transport.drop("POST /v1/auth/device/start")
    transport.drop("POST /v1/auth/device/start")
    transport.answer("POST /v1/auth/device/start", 200, good)
    assertEquals("ABCD-1234", model().startSignIn().userCode)
    assertEquals(3, transport.requests.size)
  }

  @Test fun aConnectionThatStaysDownFails() = runTest {
    transport.drop("POST /v1/auth/device/start")
    try { model().startSignIn(); fail() } catch (_: IOException) {}
    assertEquals(3, transport.requests.size)
  }

  @Test fun anAnswerThatIsNotTransientFailsAtOnce() = runTest {
    transport.answer("POST /v1/auth/device/start", 400, """{"error":"bad_key"}""")
    try { model().startSignIn(); fail() } catch (e: KiffError.Server) { assertEquals("bad_key", e.message) }
    assertEquals(1, transport.requests.size)
  }

  @Test fun kiffBrieflyNotAnsweringIsTriedAgain() = runTest {
    transport.answer("POST /v1/auth/device/start", 503, """{"error":"unavailable"}""")
    transport.answer("POST /v1/auth/device/start", 200, """{"device_code":"dc_1","user_code":"X","verification_uri":"u","verification_uri_complete":"uc","expires_in":600,"interval":5}""")
    assertEquals("X", model().startSignIn().userCode)
  }

  @Test fun startingToSignInTouchesNothingTheOwnerHas() = runTest {
    val m = model(start = true)
    val before = m.state.value
    transport.answer("POST /v1/auth/device/start", 200, """{"device_code":"dc_1","user_code":"X","verification_uri":"u","verification_uri_complete":"uc","expires_in":600,"interval":5}""")
    m.startSignIn()
    assertEquals(before, m.state.value)
  }

  // MARK: Finishing

  @Test fun approvingInTheBrowserSignsThePhoneInAndKeepsTheSessionOnThePhone() = runTest {
    transport.answer("POST /v1/auth/device/token", 400, """{"error":"authorization_pending"}""")
    transport.answer("POST /v1/auth/device/token", 400, """{"error":"authorization_pending"}""")
    transport.answer("POST /v1/auth/device/token", 200, token)
    transport.answer("GET /v1/me", 200, me)
    transport.answer("POST /v1/me/device/approval-key", 200, """{"fingerprint":"${fingerprintOfOurApprovalKey()}"}""")
    val m = model()
    m.finishSignIn(start)
    val s = m.state.value
    assertEquals(Mode.kiff, s.mode)
    assertEquals("Acme Pay", s.accountLabel)
    assertEquals("at_1", secrets.load(AppModel.TOKEN))
    assertEquals("t_01", secrets.load(AppModel.TENANT))
    assertEquals("1", secrets.load(AppModel.KEYED))
    assertEquals(3, transport.requestsTo("POST /v1/auth/device/token").size)
  }

  @Test fun theSessionIsUsedAsABearerAfterwards() = runTest {
    approved()
    model().finishSignIn(start)
    assertEquals("Bearer at_1", transport.requestsTo("GET /v1/me").single().headers["Authorization"])
  }

  @Test fun theApprovalKeyIsRegisteredSignedByTheCurrentKeyAndRemembered() = runTest {
    approved()
    val m = model()
    m.finishSignIn(start)
    val register = transport.requestsTo("POST /v1/me/device/approval-key").single()
    assertNotNull(register.headers["KIFF-Device-Signature"])
    assertEquals(fingerprintOfOurApprovalKey(), secrets.load(AppModel.APPROVAL_KEYED))
  }

  @Test fun aKeyKiffHoldsAlreadyThatIsOursCounts() = runTest {
    transport.answer("POST /v1/auth/device/token", 200, token)
    transport.answer("GET /v1/me", 200, me)
    transport.answer("POST /v1/me/device/approval-key", 409, """{"error":"approval_key_exists","fingerprint":"${fingerprintOfOurApprovalKey().uppercase()}"}""")
    model().finishSignIn(start)
    assertEquals(fingerprintOfOurApprovalKey(), secrets.load(AppModel.APPROVAL_KEYED))
  }

  @Test fun aKeyKiffHoldsThatIsAnotherPhonesDoesNotCount() = runTest {
    transport.answer("POST /v1/auth/device/token", 200, token)
    transport.answer("GET /v1/me", 200, me)
    transport.answer("POST /v1/me/device/approval-key", 409, """{"error":"approval_key_exists","fingerprint":"0000 1111 2222 3333"}""")
    model().finishSignIn(start)
    assertNull(secrets.load(AppModel.APPROVAL_KEYED))
  }

  @Test fun aKiffWithoutTheRouteYetLeavesTheCurrentKeySigning() = runTest {
    transport.answer("POST /v1/auth/device/token", 200, token)
    transport.answer("GET /v1/me", 200, me)
    // No answer queued for the approval key: a 404.
    val m = model()
    m.finishSignIn(start)
    assertEquals(Mode.kiff, m.state.value.mode)
    assertNull(secrets.load(AppModel.APPROVAL_KEYED))
  }

  @Test fun signingInClearsTheSampleOffTheScreenButNotOffThePhone() = runTest {
    val file = File.createTempFile("sample", ".json").also { it.delete() }
    try {
      val m = model(SampleStore(file), start = true)
      assertTrue(m.state.value.tafs.isNotEmpty())
      val saved = file.readText()
      approved()
      m.finishSignIn(start)
      assertTrue(m.state.value.tafs.isEmpty())
      assertTrue(m.state.value.held.isEmpty())
      // The sample's file is as it was: it only goes when the owner clears it.
      assertEquals(saved, file.readText())
      // And nothing the sample does touches it while signed in.
      m.playNextDemoActions()
      assertEquals(saved, file.readText())
    } finally {
      file.delete()
    }
  }

  @Test fun anApprovalDeclinedInTheBrowserIsKiffsWords() = runTest {
    transport.answer("POST /v1/auth/device/token", 400, """{"error":"access_denied"}""")
    try { model().finishSignIn(start); fail() } catch (e: KiffError.Server) { assertEquals("access_denied", e.message) }
    assertNull(secrets.load(AppModel.TOKEN))
  }

  @Test fun theCodeExpiringEndsIt() = runTest {
    transport.answer("POST /v1/auth/device/token", 400, """{"error":"authorization_pending"}""")
    val m = model()
    try { m.finishSignIn(start.copy(expiresIn = 20)); fail() } catch (_: KiffError.Expired) {}
    assertNull(secrets.load(AppModel.TOKEN))
    assertEquals(Mode.signedOut, m.state.value.mode)
  }

  @Test fun anExpiredTokenAnswerEndsItToo() = runTest {
    transport.answer("POST /v1/auth/device/token", 400, """{"error":"expired_token"}""")
    try { model().finishSignIn(start); fail() } catch (_: KiffError.Expired) {}
  }

  @Test fun pollingKeepsToTheIntervalAndNeverSpeedsPastTheMinimum() = runTest {
    transport.answer("POST /v1/auth/device/token", 400, """{"error":"authorization_pending"}""")
    transport.answer("POST /v1/auth/device/token", 400, """{"error":"authorization_pending"}""")
    transport.answer("POST /v1/auth/device/token", 200, token)
    transport.answer("GET /v1/me", 200, me)
    // KIFF says 1 s; the least between two polls is 2.
    model().finishSignIn(start.copy(interval = 1))
    assertEquals(3, transport.requestsTo("POST /v1/auth/device/token").size)
    assertTrue("three polls take at least three waits of 2 s, took $currentTime ms", currentTime >= 6_000)
  }

  @Test fun slowDownWaitsLonger() = runTest {
    transport.answer("POST /v1/auth/device/token", 400, """{"error":"slow_down"}""")
    transport.answer("POST /v1/auth/device/token", 200, token)
    transport.answer("GET /v1/me", 200, me)
    model().finishSignIn(start.copy(interval = 5))
    // 5 s, then 5 + 5 s.
    assertTrue("took $currentTime ms", currentTime >= 15_000)
  }

  @Test fun aDroppedConnectionWhilePollingIsPolledThrough() = runTest {
    transport.drop("POST /v1/auth/device/token")
    transport.answer("POST /v1/auth/device/token", 200, token)
    transport.answer("GET /v1/me", 200, me)
    val m = model()
    m.finishSignIn(start)
    assertEquals(Mode.kiff, m.state.value.mode)
  }

  @Test fun comingBackFromTheBrowserPollsAtOnce() = runTest {
    transport.answer("POST /v1/auth/device/token", 200, token)
    transport.answer("GET /v1/me", 200, me)
    val m = model()
    m.nudgeSignIn()
    m.finishSignIn(start.copy(interval = 30))
    assertEquals("nothing waited", 0L, currentTime)
    assertEquals(Mode.kiff, m.state.value.mode)
  }

  // MARK: Restoring

  @Test fun aKeptSessionIsShownAtOnceNotAfterTheNetwork() = runTest {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    val seen = mutableListOf<Pair<Boolean, Boolean>>()
    val m = model()
    // Every time the state changes while restoring, what the screens would draw.
    val job = launch(UnconfinedTestDispatcher(testScheduler)) { m.state.collect { seen += it.ready to it.listLoaded } }
    m.restore()
    job.cancel()
    // It was ready before its list was loaded: the list said it was loading in between.
    val firstReady = seen.indexOfFirst { it.first }
    assertTrue(seen.toString(), firstReady >= 0 && !seen[firstReady].second)
  }

  @Test fun withNothingKeptTheOwnerSeesTheFirstScreen() = runTest {
    val m = model()
    m.restore()
    assertTrue(m.state.value.ready)
    assertEquals(Mode.signedOut, m.state.value.mode)
  }

  @Test fun aSavedSampleComesBack() = runTest {
    val file = File.createTempFile("sample", ".json").also { it.delete() }
    try {
      model(SampleStore(file), start = true)
      val again = model(SampleStore(file))
      again.restore()
      assertEquals(Mode.sample, again.state.value.mode)
      assertEquals(5, again.state.value.tafs.size)
    } finally {
      file.delete()
    }
  }

  @Test fun aKeptSessionGoesOnWithoutSigningInAgain() = runTest {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(fingerprintOfOurApprovalKey(), AppModel.APPROVAL_KEYED)
    transport.answer("GET /v1/me", 200, me)
    val m = model()
    m.restore()
    val s = m.state.value
    assertTrue(s.ready)
    assertEquals(Mode.kiff, s.mode)
    assertEquals("Acme Pay", s.accountLabel)
    assertEquals("Bearer at_1", transport.requestsTo("GET /v1/me").single().headers["Authorization"])
    // The approval key KIFF holds is ours: nothing to register.
    assertTrue(transport.requestsTo("POST /v1/me/device/approval-key").isEmpty())
  }

  @Test fun aSessionFromBeforeTheApprovalKeyRegistersItOnce() = runTest {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    transport.answer("GET /v1/me", 200, me)
    transport.answer("POST /v1/me/device/approval-key", 200, """{"fingerprint":"${fingerprintOfOurApprovalKey()}"}""")
    val m = model()
    m.restore()
    assertEquals(1, transport.requestsTo("POST /v1/me/device/approval-key").size)
    assertEquals(fingerprintOfOurApprovalKey(), secrets.load(AppModel.APPROVAL_KEYED))
  }

  @Test fun aKeptSessionBeatsASavedSample() = runTest {
    val file = File.createTempFile("sample", ".json").also { it.delete() }
    try {
      model(SampleStore(file), start = true)
      secrets.save("at_1", AppModel.TOKEN)
      secrets.save("t_01", AppModel.TENANT)
      transport.answer("GET /v1/me", 200, me)
      val m = model(SampleStore(file))
      m.restore()
      assertEquals(Mode.kiff, m.state.value.mode)
    } finally {
      file.delete()
    }
  }

  @Test fun kiffEndingTheSessionSignsTheOwnerOutWithTheWordsToSignInAgain() = runTest {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    // KIFF refuses the session, and tafs cloud, which asks KIFF who it is, does too: KIFF's answer is the one read.
    transport.answer("GET /v1/me", 401, """{"error":"unauthorized"}""")
    transport.answer("GET /v1/me/card-exceptions", 401, """{"error":"unauthorized"}""")
    transport.answer("GET /v1/home", 401, """{"error":"unauthorized"}""")
    val m = model()
    m.restore()
    val s = m.state.value
    assertEquals(Mode.signedOut, s.mode)
    assertEquals("Signed out of KIFF. Sign in again.", s.lastError)
    assertNull(secrets.load(AppModel.TOKEN))
    // KIFF ended it: no logout is sent for a session it has already closed.
    assertTrue(transport.requestsTo("POST /v1/auth/device/logout").isEmpty())
  }

  @Test fun aNetworkThatIsDownLeavesTheSessionAloneForNextTimeAndSaysSo() = runTest {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    for (route in listOf("GET /v1/me", "GET /v1/me/card-exceptions", "GET /v1/home", "GET /v1/me/activity")) transport.drop(route)
    val m = model()
    m.restore()
    assertEquals(Mode.kiff, m.state.value.mode)
    assertEquals("at_1", secrets.load(AppModel.TOKEN))
    assertEquals("Can't reach KIFF", m.state.value.unreachable)
    assertEquals(null, m.state.value.lastError)
    // The list wasn't read: it says so, and is never shown as loaded and empty.
    assertTrue(m.state.value.listUnread)
  }

  // MARK: Signing out

  @Test fun signingOutTellsKiffAndLeavesNothingOnThePhone() = runTest {
    approved()
    transport.answer("POST /v1/auth/device/logout", 200)
    val m = model()
    m.finishSignIn(start)
    m.signOut()
    val s = m.state.value
    assertEquals(Mode.signedOut, s.mode)
    assertNull(s.kiffAccount)
    assertEquals(1, transport.requestsTo("POST /v1/auth/device/logout").size)
    for (account in listOf(AppModel.TOKEN, AppModel.TENANT, AppModel.KEYED, AppModel.APPROVAL_KEYED)) assertNull(secrets.load(account))
  }

  @Test fun signingOutStillSignsOutWhenKiffCannotBeReached() = runTest {
    approved()
    transport.drop("POST /v1/auth/device/logout")
    val m = model()
    m.finishSignIn(start)
    m.signOut()
    assertEquals(Mode.signedOut, m.state.value.mode)
    assertNull(secrets.load(AppModel.TOKEN))
  }

  @Test fun clearingTheSampleLeavesNothingAndTellsKiffNothing() = runTest {
    val file = File.createTempFile("sample", ".json").also { it.delete() }
    try {
      val m = model(SampleStore(file), start = true)
      assertTrue(file.exists())
      m.signOut()
      assertFalse(file.exists())
      assertEquals(Mode.signedOut, m.state.value.mode)
      assertTrue(transport.requests.isEmpty())
    } finally {
      file.delete()
    }
  }

  @Test fun theNextSessionNeverSeesTheLastOnesAccount() = runTest {
    approved()
    transport.answer("POST /v1/auth/device/logout", 200)
    val m = model()
    m.finishSignIn(start)
    assertEquals("Acme Pay", m.state.value.accountLabel)
    m.signOut()
    assertNull(m.state.value.accountLabel)
  }
}

