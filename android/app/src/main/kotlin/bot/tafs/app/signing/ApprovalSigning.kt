package bot.tafs.app.signing

import bot.tafs.app.model.Answer
import java.security.Signature
import java.util.Base64

/** The owner didn't confirm, or the phone can't sign: nothing is signed. [message] is said to the owner where it's theirs to act on. */
open class SignerException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The approval key was turned off by the phone (a fingerprint was added) and a new one made in its place. KIFF still holds
 * the old one, so approving waits for a new sign-in, which registers the new key; nothing was signed.
 */
class ApprovalKeyRenewed(cause: Throwable? = null) : SignerException(WORDS, cause) {
  companion object {
    /**
     * The approval key is gone, or a finger wasn't recognized: approvals need a fingerprint, and the screen lock never approves,
     * so the way on is a new sign-in, which registers a new key. Said where the owner would approve, before any tap.
     */
    const val WORDS = "Your fingerprints changed, or none was recognized, so this phone can't approve until you sign in again."
  }
}

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
  suspend fun sign(message: ByteArray, reason: String): String = sign(reason) { message }

  /** As [sign], with the message [built] only once the owner confirmed, so a signed time in it is when they did. */
  suspend fun sign(reason: String, built: () -> ByteArray): String

  /**
   * Signs [message] without asking again: only for the requests of a step the owner has just
   * confirmed (starting a taf signs each of its setup calls).
   */
  fun signConfirmed(message: ByteArray): String

  /**
   * Asks for a fingerprint (or another strong biometric), then signs [message] with the approval key. Every signature asks:
   * the key unlocks for one signature and only through the prompt, so code that skipped it can't sign, and an unlock of
   * the phone counts for nothing. Throws [ApprovalKeyRenewed] when the phone turned the key off.
   */
  suspend fun signApproval(message: ByteArray, reason: String): String = signApproval(reason) { message }

  /**
   * As [signApproval], with the message [built] only once the prompt succeeded: a signed time in it is when the person
   * approved, not when the prompt came up.
   */
  suspend fun signApproval(reason: String, built: () -> ByteArray): String

  /**
   * Whether the approval key can still sign, checked without a prompt: false when a fingerprint added since turned it off (a new
   * one is made in its place, which KIFF learns at the next sign-in). True where there's no key to check.
   */
  fun approvalKeyUsable(): Boolean = true
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

  /** Empty when this device holds no approval key (no strong biometric enrolled). */
  val approvalPublicKeyDER: ByteArray

  /** A DER ECDSA signature over the SHA-256 of [message], by the signing key. */
  fun sign(message: ByteArray): ByteArray

  /**
   * The approval key, ready to sign once a prompt unlocks it. Throws [ApprovalKeyRenewed] when the phone turned the key
   * off, after making a new one.
   */
  fun approvalSignature(): Signature
}

/** Asks the person at the phone to confirm: fingerprint, face or the screen lock. */
interface Authenticator {
  /** Whether this phone has something to ask with. */
  val canAuthenticate: Boolean

  /** True when they confirmed; false when they didn't or it was cancelled. */
  suspend fun authenticate(reason: String): Boolean

  /** Asks with a strong biometric only, and unlocks [signature] for one use. True when it may sign now. */
  suspend fun authenticate(reason: String, signature: Signature): Boolean
}

/**
 * Signs the owner's answer with keys that never leave the phone.
 *
 * It holds two keys (kiff/tafs#233). The **signing key** has no access control: it signs what
 * reads or takes authority away (Refuse, Halt all), which may run with no one at the screen. The
 * **approval key** signs only what grants authority (Approve, issuing a Card, starting a trial,
 * Resume): the Keystore lets it sign once per fingerprint, given through the prompt, so the key
 * checks the prompt, not the app's code.
 *
 * [demoWithoutLock] lets a debug build on a phone with no screen lock through, as iOS's Simulator
 * has no Face ID: never in a release.
 */
class DeviceSigner(
  private val keys: SigningKeys,
  private val authenticator: Authenticator,
  private val demoWithoutLock: Boolean = false,
) : ApprovalSigning {
  override val publicKeyDER: ByteArray get() = keys.publicKeyDER
  override val approvalPublicKeyDER: ByteArray get() = keys.approvalPublicKeyDER

  override suspend fun sign(reason: String, built: () -> ByteArray): String {
    if (!authenticator.canAuthenticate) {
      // No screen lock set on a debug phone: let the demo through.
      if (!demoWithoutLock) throw SignerException("Set a screen lock on this phone to approve")
    } else if (!authenticator.authenticate(reason)) {
      throw SignerException("Not confirmed")
    }
    return signConfirmed(built())
  }

  override fun signConfirmed(message: ByteArray): String = encode(keys.sign(message))

  override suspend fun signApproval(reason: String, built: () -> ByteArray): String {
    if (keys.approvalPublicKeyDER.isEmpty()) {
      // A debug phone with no screen lock has no approval key either: the demo signs with the one key it has.
      if (demoWithoutLock && !authenticator.canAuthenticate) return signConfirmed(built())
      // Never the screen lock alone for what grants authority.
      throw SignerException(NO_FINGERPRINT)
    }
    val signature = keys.approvalSignature()
    // A finger not recognized, or the prompt closed, while the key is still good: trying again may do, and a new sign-in does if
    // the fingerprints changed.
    if (!authenticator.authenticate(reason, signature)) throw SignerException(NOT_RECOGNIZED)
    return try {
      signature.update(built())
      encode(signature.sign())
    } catch (e: Exception) {
      throw SignerException("The key could not sign", e)
    }
  }

  override fun approvalKeyUsable(): Boolean {
    if (keys.approvalPublicKeyDER.isEmpty()) return true
    return try {
      keys.approvalSignature()
      true
    } catch (_: ApprovalKeyRenewed) {
      false
    } catch (_: Exception) {
      true
    }
  }

  private fun encode(der: ByteArray): String = Base64.getEncoder().encodeToString(der)

  companion object {
    const val NO_FINGERPRINT = "Add a fingerprint to this phone to approve from it. Refusing still works."
    const val NOT_RECOGNIZED = "Try again, or sign in again if your fingerprints changed."
  }
}
