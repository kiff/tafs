package bot.tafs.app.kiff

/** Why KIFF didn't take an answer, in words, never its code (the 789 retest: "self_review"). */
object AnswerRefusals {
  /** KIFF's reasons a person may not answer a held call, in words. */
  fun refusalWords(code: String): String? = when (code) {
    "self_review" -> "You asked for this, so someone else on the account approves it. You can still refuse it."
    "separation_of_duties_role" -> "Only an approver answers held calls on this account."
    "separation_of_duties_key" -> "Held calls are answered by an approver signed in, not by a key."
    "card_changer" -> "You changed the Card this was held against, so another approver answers it."
    "hold_check_unavailable" -> "KIFF couldn't check who may answer just now. Try again."
    "role_forbidden" -> "Your role on this account doesn't answer held calls. Someone who approves on it does."
    else -> null
  }

  /** What to say when KIFF refused an answer: its reason in words when it is one of those, else its own message. */
  fun answerRefusedWords(error: Throwable): String {
    if (error is KiffError.Server) {
      val words = refusalWords(error.message.orEmpty()) ?: error.reason?.let(::refusalWords)
      if (words != null) return words
    }
    return "KIFF didn't take the answer: ${error.message}"
  }

  /**
   * KIFF's reason this person may never answer the call, which stays so: not a refusal that a moment later
   * could take ("hold_check_unavailable"), nor any other error.
   */
  fun lastingRefusal(error: Throwable): String? {
    if (error !is KiffError.Server) return null
    return listOfNotNull(error.message, error.reason).firstOrNull { it != "hold_check_unavailable" && refusalWords(it) != null }
  }

  /**
   * KIFF refuses only this person's approval: they asked for the call, and a refusal grants nothing, so they
   * may still refuse it. Every other reason takes all the answers.
   */
  fun refusesOnly(code: String): Boolean = code == "self_review"
}
