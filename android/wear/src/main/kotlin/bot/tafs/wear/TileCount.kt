package bot.tafs.wear

/**
 * The tile's count (docs/watch-wear.md): Needs you's count, when it was read and when each held call in it ends, kept encrypted
 * ([EncryptedTileStore]). Never a held call's taf or words, an amount or a name. It's cleared when the phone says it's signed out, and whenever the watch is locked or
 * has no screen lock: Wear OS locks a watch taken off the wrist, so a count never shows off it.
 */
class TileCount(private val store: Store) {
  /** Where the count is kept: the watch's encrypted file, or a map in a test. */
  interface Store {
    fun read(): Pair<Int, Long>?
    fun write(count: Int, at: Long)
    fun clear()

    /** When each held call in the count ends (ms): past it, the count drops it on the watch's own clock. Times, nothing else. */
    fun ends(): List<Long> = emptyList()

    fun writeEnds(ends: List<Long>) = Unit
  }

  data class Shown(val count: Int, val at: Long)

  fun saw(count: Int, at: Long, ends: List<Long> = emptyList()) {
    store.write(count, at)
    store.writeEnds(ends)
  }

  fun signedOut() = store.clear()

  /** What the tile may show now: the count only while the watch has a screen lock and is unlocked; otherwise nothing, and it's forgotten. */
  fun shown(unlocked: Boolean, now: Long = System.currentTimeMillis()): Shown? {
    if (!unlocked) {
      store.clear()
      return null
    }
    val (count, at) = store.read() ?: return null
    // A held call whose hold has ended no longer counts, though the phone hasn't said so yet (row 29); its time goes with it.
    val ends = store.ends()
    val passed = ends.count { it <= now }
    if (passed == 0) return Shown(count, at)
    val left = (count - passed).coerceAtLeast(0)
    store.write(left, at)
    store.writeEnds(ends.filter { it > now })
    return Shown(left, at)
  }
}

/**
 * [TileCount.Store] in the app's private files (`watch-tile.bin`), encrypted at rest as the Inbox is ([KeystoreCacheStore]: AES-GCM
 * under the watch's Keystore key), as the Privacy page says of the tile: one JSON object, the count, when it was read and when each
 * held call in it ends (bare times, no taf or call). What won't decrypt reads as nothing and is cleared. 0.4.9's plain
 * `tafs.tile` preferences ([legacy]) are read once, written encrypted, and cleared.
 */
class EncryptedTileStore(private val text: EncryptedText, legacy: android.content.SharedPreferences? = null) : TileCount.Store {
  init {
    if (legacy != null && legacy.contains(COUNT)) {
      save(legacy.getInt(COUNT, 0), legacy.getLong(AT, 0), emptyList())
      legacy.edit().clear().commit()
    }
  }

  private fun load(): Triple<Int, Long, List<Long>>? = runCatching {
    val o = org.json.JSONObject(text.read() ?: return null)
    val ends = o.optJSONArray(ENDS)?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty()
    Triple(o.getInt(COUNT), o.getLong(AT), ends)
  }.getOrElse {
    text.clear()
    null
  }

  private fun save(count: Int, at: Long, ends: List<Long>) {
    text.write(org.json.JSONObject().put(COUNT, count).put(AT, at).put(ENDS, org.json.JSONArray(ends)).toString())
  }

  override fun read(): Pair<Int, Long>? = load()?.let { (count, at, _) -> count to at }

  override fun write(count: Int, at: Long) = save(count, at, load()?.third.orEmpty())

  override fun ends(): List<Long> = load()?.third.orEmpty()

  override fun writeEnds(ends: List<Long>) {
    val (count, at, _) = load() ?: return
    save(count, at, ends)
  }

  override fun clear() = text.clear()

  companion object {
    private const val COUNT = "count"
    private const val AT = "at"
    private const val ENDS = "ends"

    /** The real one: encrypted under the watch's Keystore key, taking in 0.4.9's plain `tafs.tile` once. */
    fun of(context: android.content.Context): EncryptedTileStore =
      EncryptedTileStore(EncryptedText(KeystoreCacheStore(context, "watch-tile.bin")), context.getSharedPreferences("tafs.tile", android.content.Context.MODE_PRIVATE))
  }
}
