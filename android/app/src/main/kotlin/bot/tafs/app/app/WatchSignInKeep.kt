package bot.tafs.app.app

/**
 * Where a watch's sign-in ask waits for this phone's person (watch row 18, docs/watch-wear.md): its one-time id and when it came,
 * so the question is still asked after the system ends the process that took it. An ask past [WAITS_MS] is gone: the watch takes
 * no answer after ten minutes either.
 */
interface WatchSignInKeep {
  fun keep(ask: String, at: Long)

  /** The ask still waiting at [now], or null (none, or past [WAITS_MS], which is then forgotten). */
  fun waiting(now: Long): String?

  fun clear()

  companion object {
    const val WAITS_MS = 10 * 60_000L
  }
}

/** [WatchSignInKeep] in the app's private preferences: the ask's id and its time, nothing of the account. */
class PreferencesWatchSignInKeep(context: android.content.Context) : WatchSignInKeep {
  private val prefs = context.getSharedPreferences("tafs.watch-sign-in", android.content.Context.MODE_PRIVATE)

  override fun keep(ask: String, at: Long) {
    prefs.edit().putString(ASK, ask).putLong(AT, at).apply()
  }

  override fun waiting(now: Long): String? {
    val ask = prefs.getString(ASK, null) ?: return null
    val at = prefs.getLong(AT, 0)
    if (now - at !in 0..WatchSignInKeep.WAITS_MS) {
      clear()
      return null
    }
    return ask
  }

  override fun clear() {
    prefs.edit().clear().apply()
  }

  private companion object {
    const val ASK = "ask"
    const val AT = "at"
  }
}
