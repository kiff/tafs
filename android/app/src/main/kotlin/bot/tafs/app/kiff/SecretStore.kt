package bot.tafs.app.kiff

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the KIFF session and what goes with it on this phone only, as iOS's Keychain does. */
interface SecretStore {
  fun load(account: String): String?
  fun save(value: String, account: String)
  fun delete(account: String)
}

/** For tests: nothing leaves memory. */
class InMemorySecretStore : SecretStore {
  private val items = mutableMapOf<String, String>()
  override fun load(account: String) = items[account]
  override fun save(value: String, account: String) { items[account] = value }
  override fun delete(account: String) { items.remove(account) }
}

/**
 * Each value is encrypted with an AES-GCM key that lives in the Android Keystore and never leaves
 * it, and the ciphertext is kept in this app's private preferences. The file is left out of backups
 * (see backup_rules.xml): a restored copy couldn't be read on another phone anyway, and nothing of a
 * session should travel.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
  private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
  private val store: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

  override fun load(account: String): String? {
    val stored = prefs.getString(account, null) ?: return null
    return try {
      val bytes = Base64.getDecoder().decode(stored)
      val cipher = Cipher.getInstance(TRANSFORM)
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
      String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    } catch (_: Exception) {
      // The key was lost (a restore, a wiped Keystore): what it sealed is gone, as no value at all.
      delete(account)
      null
    }
  }

  override fun save(value: String, account: String) {
    val cipher = Cipher.getInstance(TRANSFORM)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
    prefs.edit().putString(account, Base64.getEncoder().encodeToString(sealed)).apply()
  }

  override fun delete(account: String) {
    prefs.edit().remove(account).apply()
  }

  private fun key(): SecretKey {
    (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
    generator.init(
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build(),
    )
    return generator.generateKey()
  }

  private companion object {
    const val FILE = "tafs_secrets"
    const val PROVIDER = "AndroidKeyStore"
    const val ALIAS = "tafs.secrets"
    const val TRANSFORM = "AES/GCM/NoPadding"
    const val IV_BYTES = 12
    const val TAG_BITS = 128
  }
}
