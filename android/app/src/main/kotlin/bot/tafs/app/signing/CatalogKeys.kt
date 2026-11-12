package bot.tafs.app.signing

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The catalog operator's key (docs/android-app.md, "Catalog operator"): a P-256 key that signs only catalog revisions, apart from
 * every account's keys, each use behind a strong biometric. The connection server knows it by its public key (base64 SPKI DER),
 * enrolled once by whoever keeps the operators list; it never stands in for an account's approval key, nor that key for it.
 */
interface CatalogKeys {
  /** The key's public half, SPKI DER; empty while none is made. */
  val publicKeyDER: ByteArray

  /** Makes the key, once; a key already made is kept. */
  fun make()

  /** The key, ready to sign once a prompt unlocks it. */
  fun signature(): Signature
}

/** The catalog key in Android's Keystore: made once, never leaves it, and a new fingerprint turns it off. */
class KeystoreCatalogKeys : CatalogKeys {
  private val store = KeyStore.getInstance(PROVIDER).apply { load(null) }

  override val publicKeyDER: ByteArray
    get() = if (store.containsAlias(ALIAS)) store.getCertificate(ALIAS).publicKey.encoded else ByteArray(0)

  override fun make() {
    if (store.containsAlias(ALIAS)) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      try {
        generate(strongBox = true)
        return
      } catch (_: StrongBoxUnavailableException) {
      } catch (_: java.security.ProviderException) {
      }
    }
    generate(strongBox = false)
  }

  override fun signature(): Signature {
    val key = store.getKey(ALIAS, null) as? PrivateKey ?: throw SignerException(bot.tafs.app.model.Words.Approval.noKey)
    return Signature.getInstance(ALGORITHM).apply { initSign(key) }
  }

  private fun generate(strongBox: Boolean) {
    val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_SHA256)
      // Once per use, a strong biometric only, and turned off by a new enrolment: as the approval key.
      .setUserAuthenticationRequired(true)
      .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
      .setInvalidatedByBiometricEnrollment(true)
      .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
      .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
      initialize(spec)
      generateKeyPair()
    }
  }

  private companion object {
    const val PROVIDER = "AndroidKeyStore"
    const val ALGORITHM = "SHA256withECDSA"
    const val ALIAS = "tafs.catalog-key.v1"
  }
}
