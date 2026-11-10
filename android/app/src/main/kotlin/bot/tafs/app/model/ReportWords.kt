package bot.tafs.app.model

/** Reporting an answer Coach or a taf wrote, from inside the app (docs/chat.md): its reasons and what the sheet says. */
object ReportWords {
  /** tafs cloud's reasons, by value, with the words the sheet shows for each. */
  val REASONS get() = listOf(
    "offensive" to Words.Report.Reason.offensive, "harmful" to Words.Report.Reason.harmful,
    "wrong" to Words.Report.Reason.wrong, "other" to Words.Report.Reason.other,
  )

  /** The longest note tafs cloud takes. */
  const val NOTE_MAX = 1000

  val MENU get() = Words.Report.menu
  val THANKS get() = Words.Report.thanks
  val ALREADY get() = Words.Report.already

  fun notSent(why: String) = Words.Report.notSent(why)
}

/** How a report went: sent (for the first time, or again), or not sent, with why. */
sealed interface ReportResult {
  data class Sent(val first: Boolean) : ReportResult
  data class NotSent(val why: String) : ReportResult
}

/** Under a finished answer from Coach or a taf: Copy, and More actions (which holds Report this answer). */
object AnswerWords {
  val COPY get() = Words.Common.copy
  val COPIED get() = Words.Common.copied
  /** What TalkBack says Copy does. */
  val COPY_LABEL get() = Words.Chat.Answer.copyLabel
  val MORE get() = Words.Chat.Answer.more
}
