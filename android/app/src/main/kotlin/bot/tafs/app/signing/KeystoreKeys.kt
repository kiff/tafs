package bot.tafs.app.signing

import android.os.Build
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The phone's keys in the Android Keystore, in secure hardware where there is some (StrongBox, else
 * the TEE). The keys are kept across launches, so the key KIFF registered at sign-in is the one that
 * signs. An answer to KIFF counts only once KIFF verifies the signature against that key; the app
 * never vouches for an approval.
 *
 * The **approval key** signs once per fingerprint (or another strong biometric), given through the
 * prompt for that one signature: the Keystore checks that, not the app. Unlocking the phone counts
 * for nothing, and the screen lock alone never approves. Adding a fingerprint turns the key off for
 * good; the phone then makes a new one, which KIFF learns at the next sign-in. A phone with no
 * strong biometric enrolled has no approval key.
 */
class KeystoreKeys : SigningKeys {
  private val store: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

  init {
    ensure(SIGNING_ALIAS, approval = false)
    // The approval key of earlier builds signed for two minutes after any unlock: it never signs again.
    if (store.containsAlias(OLD_APPROVAL_ALIAS)) store.deleteEntry(OLD_APPROVAL_ALIAS)
    // Without a strong biometric enrolled the Keystore refuses a key that needs one.
    val approval = runCatching { ensure(APPROVAL_ALIAS, approval = true) }
    // Which keys this phone holds, so a missing approval key can be told from a bug.
    Log.i(TAG, "signing key: yes; approval key: ${if (store.containsAlias(APPROVAL_ALIAS)) "yes" else "no (${approval.exceptionOrNull()?.javaClass?.simpleName})"}")
  }

  override val publicKeyDER: ByteArray get() = publicOf(SIGNING_ALIAS)

  override val approvalPublicKeyDER: ByteArray
    get() {
      // A fingerprint enrolled since launch lets the phone hold one now.
      runCatching { ensure(APPROVAL_ALIAS, approval = true) }
      return if (store.containsAlias(APPROVAL_ALIAS)) publicOf(APPROVAL_ALIAS) else ByteArray(0)
    }

  override fun sign(message: ByteArray): ByteArray {
    try {
      val key = store.getKey(SIGNING_ALIAS, null) as? PrivateKey ?: throw SignerException("No key to sign with")
      return Signature.getInstance(ALGORITHM).run {
        initSign(key)
        update(message)
        sign()
      }
    } catch (e: SignerException) {
      throw e
    } catch (e: Exception) {
      throw SignerException("The key could not sign", e)
    }
  }

  override fun approvalSignature(): Signature {
    val key = try {
      store.getKey(APPROVAL_ALIAS, null) as? PrivateKey
    } catch (e: Exception) {
      throw SignerException("The key could not sign", e)
    } ?: throw SignerException(DeviceSigner.NO_FINGERPRINT)
    val signature = Signature.getInstance(ALGORITHM)
    try {
      signature.initSign(key)
    } catch (e: KeyPermanentlyInvalidatedException) {
      // A fingerprint was added, or the screen lock removed: this key never signs again. A new one takes its place.
      store.deleteEntry(APPROVAL_ALIAS)
      runCatching { ensure(APPROVAL_ALIAS, approval = true) }
      throw ApprovalKeyRenewed(e)
    } catch (e: Exception) {
      throw SignerException("The key could not sign", e)
    }
    return signature
  }

  private fun publicOf(alias: String): ByteArray = store.getCertificate(alias).publicKey.encoded

  private fun ensure(alias: String, approval: Boolean) {
    if (store.containsAlias(alias)) return
    // StrongBox first; a phone without it takes the TEE.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      try {
        generate(alias, approval, strongBox = true)
        return
      } catch (_: StrongBoxUnavailableException) {
      } catch (_: java.security.ProviderException) {
      }
    }
    generate(alias, approval, strongBox = false)
  }

  private fun generate(alias: String, approval: Boolean, strongBox: Boolean) {
    val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_SHA256)
      .apply {
        if (approval) {
          // Once per use (timeout 0), a strong biometric only, and turned off by a new enrolment, as iOS's biometryCurrentSet.
          setUserAuthenticationRequired(true)
          setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
          setInvalidatedByBiometricEnrollment(true)
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
      }
      .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
      initialize(spec)
      generateKeyPair()
    }
  }

  private companion object {
    const val TAG = "TafsKeys"
    const val PROVIDER = "AndroidKeyStore"
    const val ALGORITHM = "SHA256withECDSA"
    const val SIGNING_ALIAS = "tafs.signing-key"
    const val OLD_APPROVAL_ALIAS = "tafs.approval-key"
    const val APPROVAL_ALIAS = "tafs.approval-key.v2"
  }
}
