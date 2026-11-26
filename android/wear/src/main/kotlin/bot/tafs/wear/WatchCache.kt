package bot.tafs.wear

import bot.tafs.watchlink.TafLine
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the watch keeps so it opens at once (row 44, D; docs/watch-wear.md, "What the watch keeps"): the last Needs you count,
 * and each taf's name and whether it's halted or paused. Not Needs you's line: it's tafs cloud's words, and nothing here can
 * promise it never carries an amount. Never a held call's details, never a taf's state line (it can
 * name what a call wants, an amount included), never a taf's chat. Cleared on sign-out, whenever the watch is locked or has no
 * screen lock (Wear OS locks a watch taken off the wrist), and after [MAX_AGE_MS]. At rest it's encrypted ([KeystoreCacheStore]).
 */
class WatchCache(private val store: CacheStore, private val now: () -> Long) {
  /** What was kept: when, Needs you's count, and the tafs by name, with no state line. */
  data class Snapshot(val savedAt: Long, val count: Int, val tafs: List<TafLine>?, val allHalted: Boolean)

  fun save(needsYou: WatchModel.NeedsYouRead, tafs: WatchModel.TafsRead?) {
    val json = JSONObject()
      .put("saved_at", now())
      .put("count", needsYou.count)
      .put("all_halted", tafs?.allHalted ?: false)
    if (tafs != null) {
      json.put("tafs", JSONArray(tafs.tafs.map { JSONObject().put("id", it.tafId).put("name", it.name).put("halted", it.halted).put("paused", it.paused) }))
    }
    runCatching { store.write(json.toString()) }
  }

  /**
   * Your tafs as the phone pushed them ([bot.tafs.watchlink.KeptTafs]) with the app closed: the list replaces the kept one and
   * Needs you's count stays, so the next open draws the phone's list at once.
   */
  fun saveTafs(tafs: List<TafLine>, allHalted: Boolean) {
    save(WatchModel.NeedsYouRead(load()?.count ?: 0, "", emptyList()), WatchModel.TafsRead(tafs, allHalted))
  }

  /** The last snapshot, or none: nothing kept, unreadable, or older than [MAX_AGE_MS] (then it's cleared). */
  fun load(): Snapshot? {
    val text = runCatching { store.read() }.getOrNull() ?: return null
    val snapshot = runCatching {
      val json = JSONObject(text)
      val tafs = json.optJSONArray("tafs")?.let { list ->
        (0 until list.length()).map { i ->
          val t = list.getJSONObject(i)
          TafLine(t.getString("id"), t.getString("name"), "", t.getBoolean("halted"), paused = t.optBoolean("paused"))
        }
      }
      Snapshot(json.getLong("saved_at"), json.getInt("count"), tafs, json.optBoolean("all_halted"))
    }.getOrNull()
    if (snapshot == null || now() - snapshot.savedAt !in 0..MAX_AGE_MS) {
      clear()
      return null
    }
    return snapshot
  }

  fun clear() {
    runCatching { store.clear() }
  }

  companion object {
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
  }
}

/** Where the cache sits: encrypted on a watch, in memory in a test. */
interface CacheStore {
  fun read(): String?
  fun write(text: String)
  fun clear()
}

/**
 * Text in the app's private files, encrypted with AES-GCM under a key in the watch's Android Keystore, which never leaves it: the
 * cache ([name] `watch-cache.bin`), and the Inbox and the kept chats ([EncryptedText]). A file that won't decrypt (the key gone, a
 * reinstall) throws on [read]; each caller reads that as none. [key] is the Keystore's; a test passes its own.
 */
class KeystoreCacheStore(
  context: android.content.Context,
  name: String = "watch-cache.bin",
  private val key: () -> javax.crypto.SecretKey = ::keystoreKey,
) : CacheStore {
  private val file = java.io.File(context.filesDir, name)

  override fun read(): String? {
    if (!file.exists()) return null
    val bytes = file.readBytes()
    if (bytes.size < IV) return null
    val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, bytes, 0, IV))
    return String(cipher.doFinal(bytes, IV, bytes.size - IV), Charsets.UTF_8)
  }

  override fun write(text: String) {
    val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
    file.writeBytes(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
  }

  override fun clear() {
    file.delete()
  }

  companion object {
    private const val ALIAS = "tafs.watch.cache"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV = 12

    /** The watch's one key for what it keeps, made in its Android Keystore the first time and never leaving it. */
    fun keystoreKey(): javax.crypto.SecretKey {
      val keystore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      (keystore.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
      val generator = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
      generator.init(
        android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
          .build(),
      )
      return generator.generateKey()
    }
  }
}

/**
 * A [CacheStore] read leniently, for what the watch keeps (the Inbox, the kept chats): text that won't read back (the key gone,
 * a file damaged) is none, and is cleared, never a crash.
 */
class EncryptedText(private val store: CacheStore) {
  fun read(): String? = try {
    store.read()
  } catch (_: Exception) {
    runCatching { store.clear() }
    null
  }

  fun write(text: String) {
    runCatching { store.write(text) }
  }

  fun clear() {
    runCatching { store.clear() }
  }
}
