package bot.tafs.watchlink

/**
 * One log line about held calls on their way between the phone and the watch (docs/watch-wear.md, Delivery), written the same on
 * both sides so a held call that never shows on the wrist can be followed through logcat: where it went, how many, and how long
 * each hold had left. Never a taf's name, what it wants, an amount, a reason or an id: only counts and seconds.
 */
object HeldLog {
  const val TAG = "tafs.held"

  /**
   * `<step>: count=<n> calls=<n> ends_in_s=[<s>,…]` and any [notes] after it (`mark=held`, `secure=no`). A hold with no end (an
   * older phone) is `?`; one already ended is negative.
   */
  fun line(step: String, count: Int, calls: List<HeldLine>, now: Long, vararg notes: Pair<String, Any?>): String {
    val ends = calls.joinToString(",", "[", "]") { call -> call.endsAt?.let { ((it - now) / 1000).toString() } ?: "?" }
    val extra = notes.joinToString("") { (key, value) -> " $key=${value ?: "none"}" }
    return "$step: count=$count calls=${calls.size} ends_in_s=$ends$extra"
  }

  /**
   * How a kept item's account mark stands against the one the watch holds, never the mark itself: `same`, `other`, `phone-none`
   * (an older phone) or `watch-none` (the watch holds none yet).
   */
  fun mark(held: String?, sent: String?): String = when {
    sent == null -> "phone-none"
    held == null -> "watch-none"
    sent == held -> "same"
    else -> "other"
  }

  /** Why nothing went or was taken: `<step>: <why>` and any [notes]. */
  fun skipped(step: String, why: String, vararg notes: Pair<String, Any?>): String =
    "$step: $why" + notes.joinToString("") { (key, value) -> " $key=${value ?: "none"}" }
}
