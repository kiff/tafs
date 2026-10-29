package bot.tafs.app

import bot.tafs.app.model.Answer
import bot.tafs.app.signing.Biometry
import bot.tafs.app.signing.DeviceSigner
import bot.tafs.app.signing.Messages
import bot.tafs.app.signing.SignerException
import bot.tafs.app.signing.SigningKeys
import bot.tafs.app.signing.SoftwareKeys
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SigningTest {
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val message = "tafs.answer.v1|held-1|approve-once".toByteArray()

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDER))
    return Signature.getInstance("SHA256withECDSA").run {
      initVerify(key)
      update(message)
      verify(Base64.getDecoder().decode(signatureBase64))
    }
  }

  private class Clock(var now: Instant) : () -> Instant {
    override fun invoke() = now
  }

  // MARK: The messages

  @Test fun anAnswerIsSignedAsKiffAndTheSampleReadIt() {
    assertEquals("tafs.answer.v1|h|approve-once", String(Messages.answer("h", Answer.ApproveOnce)))
    assertEquals("tafs.answer.v1|h|reject", String(Messages.answer("h", Answer.Refuse)))
    assertEquals("tafs.answer.v1|h|reject:Return first", String(Messages.answer("h", Answer.NextStep("Return first"))))
  }

  // MARK: Signing

  @Test fun aSignatureIsDerEcdsaOverTheMessageAndVerifiesAgainstTheKey() = runTest {
    val keys = SoftwareKeys()
    val signer = DeviceSigner(keys, FakeAuthenticator())
    val signature = signer.sign(message, "Answer it")
    assertTrue(verifies(signer.publicKeyDER, message, signature))
    // Another message doesn't verify under it.
    assertFalse(verifies(signer.publicKeyDER, "tafs.answer.v1|held-1|reject".toByteArray(), signature))
    // DER: a sequence, 0x30.
    assertEquals(0x30, Base64.getDecoder().decode(signature)[0].toInt())
  }

  @Test fun theSigningKeyAndTheApprovalKeyAreDifferentKeys() = runTest {
    val signer = DeviceSigner(SoftwareKeys(), FakeAuthenticator())
    assertFalse(signer.publicKeyDER.contentEquals(signer.approvalPublicKeyDER))
    val signed = signer.sign(message, "Refuse it")
    assertFalse(verifies(signer.approvalPublicKeyDER, message, signed))
  }

  @Test fun anApprovalIsSignedByTheApprovalKeyAfterOneConfirmation() = runTest {
    val auth = FakeAuthenticator()
    val signer = DeviceSigner(SoftwareKeys(), auth)
    val signature = signer.signApproval(message, "Approve it")
    assertEquals(listOf("Approve it"), auth.asked)
    assertTrue(verifies(signer.approvalPublicKeyDER, message, signature))
    assertFalse(verifies(signer.publicKeyDER, message, signature))
  }

  @Test fun theOwnerIsAskedForEverySignature() = runTest {
    val auth = FakeAuthenticator()
    val signer = DeviceSigner(SoftwareKeys(), auth)
    signer.sign(message, "one")
    signer.sign(message, "two")
    signer.signApproval(message, "three")
    assertEquals(listOf("one", "two", "three"), auth.asked)
  }

  // MARK: Not confirmed

  @Test fun anAnswerTheOwnerDeclinedIsNotSigned() = runTest {
    val signer = DeviceSigner(SoftwareKeys(), FakeAuthenticator(confirms = false))
    try {
      signer.sign(message, "Answer it")
      fail("signed without a confirmation")
    } catch (_: SignerException) {
    }
    try {
      signer.signApproval(message, "Approve it")
      fail("signed an approval without a confirmation")
    } catch (_: SignerException) {
    }
  }

  @Test fun aDeclinedConfirmationOpensNoStep() = runTest {
    val auth = FakeAuthenticator(confirms = false)
    val signer = DeviceSigner(SoftwareKeys(), auth)
    runCatching { signer.signApproval(message, "x") }
    try {
      signer.signApprovalConfirmed(message)
      fail("signed an approval under a confirmation that was declined")
    } catch (_: SignerException) {
    }
  }

  @Test fun nothingSignsAnApprovalBeforeAnyConfirmation() {
    val signer = DeviceSigner(SoftwareKeys(), FakeAuthenticator())
    try {
      signer.signApprovalConfirmed(message)
      fail("signed an approval with no confirmation")
    } catch (_: SignerException) {
    }
  }

  @Test fun aPhoneWithNoScreenLockSignsNothing() = runTest {
    val signer = DeviceSigner(SoftwareKeys(), FakeAuthenticator(available = false))
    try {
      signer.sign(message, "x")
      fail("signed on a phone with nothing to confirm with")
    } catch (e: SignerException) {
      assertTrue(e.message!!.contains("screen lock"))
    }
  }

  @Test fun aDebugBuildWithNoScreenLockLetsTheDemoThroughWithoutAsking() = runTest {
    val auth = FakeAuthenticator(available = false)
    val signer = DeviceSigner(SoftwareKeys(), auth, demoWithoutLock = true)
    val signature = signer.sign(message, "x")
    assertTrue(verifies(signer.publicKeyDER, message, signature))
    assertTrue(auth.asked.isEmpty())
  }

  // MARK: A step

  @Test fun oneConfirmationCoversTheSignaturesOfItsStepAndNoLonger() = runTest {
    val clock = Clock(t0)
    val signer = DeviceSigner(SoftwareKeys(), FakeAuthenticator(), clock = clock)
    signer.sign(message, "Issue it")
    clock.now = t0.plus(Duration.ofSeconds(119))
    assertTrue(verifies(signer.approvalPublicKeyDER, message, signer.signApprovalConfirmed(message)))
    clock.now = t0.plus(Duration.ofSeconds(120))
    try {
      signer.signApprovalConfirmed(message)
      fail("signed an approval after its step ended")
    } catch (_: SignerException) {
    }
  }

  @Test fun aNewConfirmationStartsANewStep() = runTest {
    val clock = Clock(t0)
    val auth = FakeAuthenticator()
    val signer = DeviceSigner(SoftwareKeys(), auth, clock = clock)
    signer.sign(message, "first")
    clock.now = t0.plusSeconds(200)
    signer.sign(message, "second")
    assertTrue(verifies(signer.approvalPublicKeyDER, message, signer.signApprovalConfirmed(message)))
  }

  @Test fun endingAStepEndsItsConfirmation() = runTest {
    val signer = DeviceSigner(SoftwareKeys(), FakeAuthenticator())
    signer.sign(message, "x")
    signer.endStep()
    try {
      signer.signApprovalConfirmed(message)
      fail("signed an approval after the step was ended")
    } catch (_: SignerException) {
    }
  }

  // MARK: A phone with no approval key

  private class OneKey(private val keys: SoftwareKeys = SoftwareKeys()) : SigningKeys {
    override val publicKeyDER get() = keys.publicKeyDER
    override val approvalPublicKeyDER get() = ByteArray(0)
    override fun sign(message: ByteArray, approval: Boolean) = keys.sign(message, approval = false)
  }

  @Test fun aSignerWithoutAnApprovalKeySignsEverythingWithItsOneKey() = runTest {
    val auth = FakeAuthenticator()
    val signer = DeviceSigner(OneKey(), auth)
    assertArrayEquals(ByteArray(0), signer.approvalPublicKeyDER)
    val signature = signer.signApproval(message, "Approve it")
    assertTrue(verifies(signer.publicKeyDER, message, signature))
    // It still asked.
    assertEquals(listOf("Approve it"), auth.asked)
  }

  // MARK: What the phone asks with

  @Test fun theBiometricIsNamedInWordsTheOwnerKnows() {
    assertEquals("your fingerprint", Biometry.name(strongEnrolled = true, face = false, fingerprint = true))
    assertEquals("face unlock", Biometry.name(strongEnrolled = true, face = true, fingerprint = false))
    assertEquals("your fingerprint or face", Biometry.name(strongEnrolled = true, face = true, fingerprint = true))
  }

  @Test fun withoutAnEnrolledBiometricItIsTheScreenLock() {
    assertEquals("your screen lock", Biometry.name(strongEnrolled = false, face = true, fingerprint = true))
    assertEquals("your screen lock", Biometry.name(strongEnrolled = true, face = false, fingerprint = false))
  }
}
