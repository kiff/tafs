package bot.tafs.app.signing

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Two P-256 keys held in memory. For tests and the demo: a signature made with them proves the
 * format, never that a person confirmed, so a release never uses them.
 */
class SoftwareKeys : SigningKeys {
  private val signing: KeyPair = newPair()
  private var approval: KeyPair = newPair()

  /** Set to have the next approval find its key turned off, as a phone does when a fingerprint is added. */
  var invalidateApproval = false

  override val publicKeyDER: ByteArray get() = signing.public.encoded
  override val approvalPublicKeyDER: ByteArray get() = approval.public.encoded

  override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
    initSign(signing.private)
    update(message)
    sign()
  }

  override fun approvalSignature(): Signature {
    if (invalidateApproval) {
      invalidateApproval = false
      approval = newPair()
      throw ApprovalKeyRenewed()
    }
    return Signature.getInstance("SHA256withECDSA").apply { initSign(approval.private) }
  }

  private fun newPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
}
