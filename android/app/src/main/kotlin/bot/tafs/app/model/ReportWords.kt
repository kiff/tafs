package bot.tafs.app.model

/** Reporting an answer Coach or a taf wrote, from inside the app (docs/chat.md): its reasons and what the sheet says. */
object ReportWords {
  /** tafs cloud's reasons, by value, with the words the sheet shows for each. */
  val REASONS = listOf("offensive" to "Offensive", "harmful" to "Harmful", "wrong" to "Wrong", "other" to "Other")

  /** The longest note tafs cloud takes. */
  const val NOTE_MAX = 1000

  const val MENU = "Report this answer"
  const val THANKS = "Thanks. Someone at KIFF will read your report."
  const val ALREADY = "You've already reported this answer."

  fun notSent(why: String) = "Your report wasn't sent. $why"
}

/** How a report went: sent (for the first time, or again), or not sent, with why. */
sealed interface ReportResult {
  data class Sent(val first: Boolean) : ReportResult
  data class NotSent(val why: String) : ReportResult
}
