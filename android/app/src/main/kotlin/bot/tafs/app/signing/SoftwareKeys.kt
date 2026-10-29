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
  private val approval: KeyPair = newPair()

  override val publicKeyDER: ByteArray get() = signing.public.encoded
  override val approvalPublicKeyDER: ByteArray get() = approval.public.encoded

  override fun sign(message: ByteArray, approval: Boolean): ByteArray {
    val key = if (approval) this.approval else signing
    return Signature.getInstance("SHA256withECDSA").run {
      initSign(key.private)
      update(message)
      sign()
    }
  }

  private fun newPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
}
