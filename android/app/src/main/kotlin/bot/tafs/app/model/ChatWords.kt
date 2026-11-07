package bot.tafs.app.model

/** A chat's own words for a message that wasn't sent (kiff/tafs#476), the same as iOS's. */
object ChatWords {
  const val NOT_SENT = "Not sent"
  const val RETRY = "Retry"
  const val EDIT = "Edit"
  const val DELETE = "Delete"
  /** Under what tafs cloud holds of the owner's (docs/chat.md): it goes by itself once the taf is done. */
  const val HELD = "Waits until it's done, then goes as your next message"

  /** A taf's `ask_coach` card's button (kiff/tafs#476, gap 15). */
  const val ASK_COACH = "Ask Coach"
}
