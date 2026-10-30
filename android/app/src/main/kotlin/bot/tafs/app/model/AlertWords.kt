package bot.tafs.app.model

/** What Settings says of alerts. */
object AlertWords {
  const val NOT_IN_BUILD = "Alerts aren't set up in this build of tafs."

  /** Where alerts stand on this phone. */
  fun status(available: Boolean, ready: Boolean): String = if (!available) "Not available" else if (ready) "On" else "Off"

  /** The line under the alerts' rows: what they are, or why they can't be. */
  fun foot(available: Boolean, canAnswerHolds: Boolean?): String? = when {
    !available -> NOT_IN_BUILD
    canAnswerHolds == false -> "Alerts go to the people who may answer held calls. Your role on this account can't."
    else -> "When a taf needs you, this phone tells you even with tafs closed. Nothing is sent until you approve."
  }
}
