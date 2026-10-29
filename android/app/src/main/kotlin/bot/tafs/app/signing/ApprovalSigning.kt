package bot.tafs.app.signing

import bot.tafs.app.model.Answer
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** The owner didn't confirm, or the phone can't sign: nothing is signed. */
class SignerException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Signs an owner's answer. The app uses [DeviceSigner]; tests use a signer with software keys,
 * since a real signature waits for a person at the biometric prompt.
 */
interface ApprovalSigning {
  /** The public half of the signing key (P-256, SPKI DER). KIFF binds it to this phone's session. */
  val publicKeyDER: ByteArray

  /**
   * The public half of the approval key (P-256, SPKI DER), which signs only what grants
   * authority (kiff/tafs#233); empty when this device holds none.
   */
  val approvalPublicKeyDER: ByteArray

  /** Asks the owner to confirm, then signs [message]. A base64 DER ECDSA signature over its SHA-256. */
  suspend fun sign(message: ByteArray, reason: String): String

  /**
   * Signs [message] without asking again: only for the requests of a step the owner has just
   * confirmed (starting a taf signs each of its setup calls).
   */
  fun signConfirmed(message: ByteArray): String

  /**
   * Asks the owner to confirm, then signs [message] with the approval key. The key checks that
   * confirmation itself: code that skipped the prompt can't sign with it.
   */
  suspend fun signApproval(message: ByteArray, reason: String): String

  /**
   * Signs [message] with the approval key under the confirmation that just covered this step
   * ([sign] or [signApproval]), without asking again. Past the step, it throws.
   */
  fun signApprovalConfirmed(message: ByteArray): String
}

/** The messages the owner signs, as KIFF and tafs cloud read them: stable across versions. */
object Messages {
  /** An answer in the sample, where the phone checks the Card itself. */
  fun answer(heldId: String, answer: Answer): ByteArray = "tafs.answer.v1|$heldId|${answer.wireName}".toByteArray()

  /** Issuing a Card is signed as an approval of it. */
  fun issue(cardId: String): ByteArray = "tafs.answer.v1|$cardId|${Answer.ApproveOnce.wireName}".toByteArray()
}

/** The keys a signer holds, hardware-backed on a phone. */
interface SigningKeys {
  val publicKeyDER: ByteArray

  /** Empty when this device holds no approval key (no secure lock screen). */
  val approvalPublicKeyDER: ByteArray

  /** A DER ECDSA signature over the SHA-256 of [message]; with [approval], by the approval key. */
  fun sign(message: ByteArray, approval: Boolean): ByteArray
}

/** Asks the person at the phone to confirm: fingerprint, face or the screen lock. */
interface Authenticator {
  /** Whether this phone has something to ask with. */
  val canAuthenticate: Boolean

  /** True when they confirmed; false when they didn't or it was cancelled. */
  suspend fun authenticate(reason: String): Boolean
}

/**
 * Signs the owner's answer with keys that never leave the phone.
 *
 * It holds two keys (kiff/tafs#233). The **signing key** has no access control: it signs what
 * reads or takes authority away (Refuse, Halt all), which may run with no one at the screen. The
 * **approval key** signs only what grants authority (Approve, issuing a Card, starting a trial,
 * Resume): its own access control requires the screen lock or a biometric, so the key checks the
 * prompt, not the app's code. One confirmation covers one confirmed step, for [stepWindow].
 *
 * [demoWithoutLock] lets a debug build on a phone with no screen lock through, as iOS's Simulator
 * has no Face ID: never in a release.
 */
class DeviceSigner(
  private val keys: SigningKeys,
  private val authenticator: Authenticator,
  private val demoWithoutLock: Boolean = false,
  private val clock: () -> Instant = Instant::now,
  private val stepWindow: Duration = STEP_WINDOW,
) : ApprovalSigning {
  /** When the confirmation that covers the current step was given. */
  private var stepAt: Instant? = null

  override val publicKeyDER: ByteArray get() = keys.publicKeyDER
  override val approvalPublicKeyDER: ByteArray get() = keys.approvalPublicKeyDER

  override suspend fun sign(message: ByteArray, reason: String): String {
    authenticate(reason)
    return signConfirmed(message)
  }

  override fun signConfirmed(message: ByteArray): String = encode(keys.sign(message, approval = false))

  override suspend fun signApproval(message: ByteArray, reason: String): String {
    // A signer without an approval key signs everything with the one key it has.
    if (keys.approvalPublicKeyDER.isEmpty()) return sign(message, reason)
    authenticate(reason)
    return signApprovalConfirmed(message)
  }

  override fun signApprovalConfirmed(message: ByteArray): String {
    if (keys.approvalPublicKeyDER.isEmpty()) return signConfirmed(message)
    val at = stepAt
    if (at == null || Duration.between(at, clock()) >= stepWindow) {
      stepAt = null
      throw SignerException("The step was not confirmed")
    }
    return encode(keys.sign(message, approval = true))
  }

  /** The confirmation that covers a step ends: its signatures need a new one. */
  fun endStep() {
    stepAt = null
  }

  private suspend fun authenticate(reason: String) {
    if (!authenticator.canAuthenticate) {
      if (demoWithoutLock) {
        // No screen lock set on a debug phone: let the demo through.
        stepAt = clock()
        return
      }
      throw SignerException("Set a screen lock on this phone to approve")
    }
    if (!authenticator.authenticate(reason)) throw SignerException("Not confirmed")
    // This confirmation covers a new step: the last one's is over.
    stepAt = clock()
  }

  private fun encode(der: ByteArray): String = Base64.getEncoder().encodeToString(der)

  companion object {
    /** How long one confirmation covers a step's signatures. */
    val STEP_WINDOW: Duration = Duration.ofSeconds(120)
  }
}
