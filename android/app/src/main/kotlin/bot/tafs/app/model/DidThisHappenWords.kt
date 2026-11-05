package bot.tafs.app.model

import bot.tafs.app.kiff.UnsureCall

/** "Did this happen?" in the app's words, where KIFF sends none: the question, the answers, and why KIFF can't tell. */
object DidThisHappenWords {
  const val TITLE = "Did this happen?"
  const val NO = "No"
  const val YES = "Yes, it did"
  const val WHAT_NO_DOES = "No gives the Card its room back. Yes keeps it counted."

  /** Why KIFF asks: KIFF's own line, and the app's only from a KIFF that sends none. */
  fun why(call: UnsureCall): String = call.statusWords ?: when (call.status) {
    UnsureCall.REFUSED -> "The tool refused it, but nothing shows it had no effect."
    UnsureCall.PROBATION -> "This is one of the first calls of a new money operation, so KIFF asks you what happened."
    else -> "Its answer was lost, so KIFF can't tell whether it went through."
  }

  /** The link to the vendor's own record of the call, where KIFF gives one. */
  fun record(vendor: String?): String = vendor?.takeIf { it.isNotBlank() }?.let { "See it at $it" } ?: "See the vendor's record"

  /** What the prompt says while the owner answers. */
  fun reason(happened: Boolean): String = if (happened) "Answer: yes, it happened" else "Answer: no, it didn't happen"
}
