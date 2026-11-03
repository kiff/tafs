package bot.tafs.app.model

/** What Settings says of alerts. */
object AlertWords {
  const val NOT_IN_BUILD = "Alerts aren't set up in this build of tafs."
  const val ON_THIS_PHONE = "Alerts on this phone"
  const val SOUND = "Sound"
  const val SOUND_WORDS = "For a hold or a halt only"
  const val WITH_SOUND = "With sound"
  const val SILENT = "Silent"
  const val WHAT_ALERTS = "What alerts you"

  /** The lock screen's two kinds, as examples: a hold, loud; an answer, silent. */
  const val LOUD_EXAMPLE = "Chargebacks needs you · Dispute #4825 · €640 is over its limit"
  const val SILENT_EXAMPLE = "Refunds answered maria@… · By email"

  /** What alerts the owner, at its level (`push/Alerts.kt`): the event, and how it comes. */
  const val NEEDS_YOU = "A taf needs you"
  const val ALL_HALTED = "Every taf halted"
  const val ANSWERED = "A taf answered someone"
  const val NOT_SENT = "A reply wasn't sent"
  const val ALWAYS = "Sound, always"

  /** "3 tafs ›": the tafs whose results alert the phones. */
  fun tafs(n: Int): String = if (n == 1) "1 taf ›" else "$n tafs ›"

  /** Where alerts stand on this phone. */
  fun status(available: Boolean, ready: Boolean): String = if (!available) "Not available" else if (ready) "On" else "Off"

  /** The line under the alerts' rows: what they are, or why they can't be. */
  fun foot(available: Boolean, canAnswerHolds: Boolean?): String? = when {
    !available -> NOT_IN_BUILD
    canAnswerHolds == false -> "Alerts go to the people who may answer held calls. Your role on this account can't."
    else -> "When a taf needs you, this phone tells you even with tafs closed. Nothing is sent until you approve."
  }
}
