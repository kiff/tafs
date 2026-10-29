package bot.tafs.app.signing

import android.os.Build
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
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
 * The **approval key** needs the screen lock or a biometric to be used: the Keystore checks that,
 * not the app. A phone with no secure lock screen can't hold one, so it has none.
 */
class KeystoreKeys : SigningKeys {
  private val store: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

  init {
    ensure(SIGNING_ALIAS, approval = false)
    // Without a secure lock screen the Keystore refuses a key that needs one.
    val approval = runCatching { ensure(APPROVAL_ALIAS, approval = true) }
    // Which keys this phone holds, so a missing approval key can be told from a bug.
    Log.i(TAG, "signing key: yes; approval key: ${if (store.containsAlias(APPROVAL_ALIAS)) "yes" else "no (${approval.exceptionOrNull()?.javaClass?.simpleName})"}")
  }

  override val publicKeyDER: ByteArray get() = publicOf(SIGNING_ALIAS)

  override val approvalPublicKeyDER: ByteArray get() = if (store.containsAlias(APPROVAL_ALIAS)) publicOf(APPROVAL_ALIAS) else ByteArray(0)

  override fun sign(message: ByteArray, approval: Boolean): ByteArray {
    val alias = if (approval) APPROVAL_ALIAS else SIGNING_ALIAS
    try {
      val key = store.getKey(alias, null) as? PrivateKey ?: throw SignerException("No key to sign with")
      return Signature.getInstance("SHA256withECDSA").run {
        initSign(key)
        update(message)
        sign()
      }
    } catch (e: SignerException) {
      throw e
    } catch (e: Exception) {
      // Not confirmed in time, or the key was invalidated (the lock screen was removed, a new
      // fingerprint was enrolled): nothing is signed.
      throw SignerException("The key could not sign", e)
    }
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
          // Usable for a step's window after the owner confirms with a biometric or the screen lock;
          // lost if a new fingerprint is enrolled, as iOS's biometryCurrentSet.
          setUserAuthenticationRequired(true)
          setUserAuthenticationParameters(
            DeviceSigner.STEP_WINDOW.seconds.toInt(),
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
          )
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
    const val SIGNING_ALIAS = "tafs.signing-key"
    const val APPROVAL_ALIAS = "tafs.approval-key"
  }
}
