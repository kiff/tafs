package bot.tafs.app.kiff

import bot.tafs.app.model.Words

/** Why KIFF didn't take an answer, in words, never its code (the 789 retest: "self_review"). */
object AnswerRefusals {
  /** KIFF's reasons a person may not answer a held call, in words. */
  fun refusalWords(code: String): String? = when (code) {
    "self_review" -> Words.NeedsYou.Refused.selfReview
    "separation_of_duties_role" -> Words.NeedsYou.Refused.approverOnly
    "separation_of_duties_key" -> Words.NeedsYou.Refused.signedInApprover
    "card_changer" -> Words.NeedsYou.Refused.cardChanger
    "hold_check_unavailable" -> Words.NeedsYou.Refused.checkUnavailable
    "role_forbidden" -> Words.NeedsYou.Refused.role
    else -> null
  }

  /** What to say when KIFF refused an answer: its reason in words when it is one of those, else its own message. */
  fun answerRefusedWords(error: Throwable): String {
    if (error is KiffError.Server) {
      val words = refusalWords(error.message.orEmpty()) ?: error.reason?.let(::refusalWords)
      if (words != null) return words
    }
    return Words.NeedsYou.Refused.other(why = error.message.toString())
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
