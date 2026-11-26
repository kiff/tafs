package bot.tafs.wear

/**
 * The one thing the watch keeps on disk (docs/watch-wear.md): Needs you's count and when it was read, for the tile. Never a
 * held call, an amount, a name or a taf. It's cleared when the phone says it's signed out, and whenever the watch is locked or
 * has no screen lock: Wear OS locks a watch taken off the wrist, so a count never shows off it.
 */
class TileCount(private val store: Store) {
  /** Where the count is kept: the watch's own preferences, or a map in a test. */
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
    // A held call whose hold has ended no longer counts, though the phone hasn't said so yet (row 29).
    return store.read()?.let { (count, at) -> Shown((count - store.ends().count { it <= now }).coerceAtLeast(0), at) }
  }
}

/** [TileCount.Store] in the watch's preferences: two numbers, nothing else. */
class PreferencesTileStore(context: android.content.Context) : TileCount.Store {
  private val prefs = context.getSharedPreferences("tafs.tile", android.content.Context.MODE_PRIVATE)

  override fun read(): Pair<Int, Long>? =
    if (prefs.contains(COUNT)) prefs.getInt(COUNT, 0) to prefs.getLong(AT, 0) else null

  override fun write(count: Int, at: Long) {
    prefs.edit().putInt(COUNT, count).putLong(AT, at).apply()
  }

  override fun ends(): List<Long> = prefs.getString(ENDS, null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()

  override fun writeEnds(ends: List<Long>) {
    prefs.edit().putString(ENDS, ends.joinToString(",")).apply()
  }

  override fun clear() {
    prefs.edit().clear().apply()
  }

  private companion object {
    const val COUNT = "count"
    const val AT = "at"
    const val ENDS = "ends"
  }
}
