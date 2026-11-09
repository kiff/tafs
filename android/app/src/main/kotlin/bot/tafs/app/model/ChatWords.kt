package bot.tafs.app.model

/** A chat's own words for a message that wasn't sent (kiff/tafs#476), the same as iOS's. */
object ChatWords {
  val NOT_SENT get() = Words.Chat.notSent
  val RETRY get() = Words.Chat.retry
  val EDIT get() = Words.Chat.edit
  val DELETE get() = Words.Chat.delete
  /** Under what tafs cloud holds of the owner's (docs/chat.md): it goes by itself once the taf is done. */
  val HELD get() = Words.Chat.held

  /** A taf's `ask_coach` card's button (kiff/tafs#476, gap 15). */
  val ASK_COACH get() = Words.Coach.ask
}
