package bot.tafs.wear

/**
 * Dictation that isn't cut off midway (row 44, A; the owner's Galaxy Watch review: "it stops while I'm still talking"). The
 * system's recognizer ends a stretch on its own silence rule, or says it heard no match; the watch keeps listening across
 * those, joining what each stretch heard, and ends only when the person taps ✓ (Stop, or the 30 s limit: [stopping]) or after
 * [quietMs] with no new words. A recognizer that fails outright (no network, busy) ends it with what was heard so far.
 */
internal class Dictation(private val now: () -> Long, private val quietMs: Long = QUIET_MS) {
  private val parts = mutableListOf<String>()
  private var lastWordsAt = 0L

  /** ✓ or the 30 s limit: the next end is the last. */
  var stopping = false

  /** Cancel: nothing heard is sent, even if the recognizer answers late (a result, or its error on a cancel). */
  private var cancelled = false

  sealed interface Next {
    /** Listen again: the person is still talking, or paused for less than [quietMs]. */
    data object Listen : Next

    /** Done, with everything heard (blank: nothing was). */
    data class Done(val text: String) : Next

    /** Cancelled: nothing goes anywhere. */
    data object Cancelled : Next
  }

  fun begin() {
    parts.clear()
    lastWordsAt = now()
    stopping = false
    cancelled = false
  }

  fun cancel() {
    cancelled = true
    stopping = true
  }

  /** The words as they come: what earlier stretches heard, then this one's so far. */
  fun partial(words: String?): String {
    if (!words.isNullOrBlank()) lastWordsAt = now()
    return shown(words)
  }

  /** A stretch ended with [words] (none on "no match" or the recognizer's own silence timeout). */
  fun ended(words: String?): Next {
    if (cancelled) return Next.Cancelled
    if (!words.isNullOrBlank()) {
      parts += words.trim()
      lastWordsAt = now()
    }
    return if (!stopping && now() - lastWordsAt < quietMs) Next.Listen else Next.Done(shown(null))
  }

  /** What was heard so far, across stretches. */
  fun heardSoFar(): String = shown(null)

  /** The recognizer failed outright: done, with what was heard so far. */
  fun failed(): Next = if (cancelled) Next.Cancelled else Next.Done(shown(null))

  private fun shown(current: String?): String = (parts + listOfNotNull(current?.trim())).filter { it.isNotBlank() }.joinToString(" ")

  companion object {
    /** About 8 s of quiet ends it, as WhatsApp's recording does when the person has clearly finished. */
    const val QUIET_MS = 8_000L

    /**
     * The recognizer's errors where the watch records the rest itself and the phone turns it into text (row 44, A): no network,
     * the server, the language, the recognizer busy. Not the microphone's own errors, nor a missing permission.
     */
    fun fallsBackToPhone(error: Int): Boolean = error in setOf(
      android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
      android.speech.SpeechRecognizer.ERROR_SERVER, android.speech.SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
      android.speech.SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
      android.speech.SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
    )

    /** The recognizer's errors that mean a quiet stretch, not a failure: listen on. */
    fun isQuiet(error: Int): Boolean =
      error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
  }
}
