package bot.tafs.app.model

/** What Settings says of alerts. */
object AlertWords {
  val NOT_IN_BUILD get() = Words.Alert.notInBuild
  val ON_THIS_PHONE get() = Words.Alert.onThisDevice(ThisDevice.word)
  val SOUND get() = Words.Alert.sound
  val SOUND_WORDS get() = Words.Alert.soundWords
  val WITH_SOUND get() = Words.Alert.withSound
  val SILENT get() = Words.Alert.silent
  val WHAT_ALERTS get() = Words.Alert.whatAlerts

  /** The lock screen's two kinds, as examples: a hold, loud; an answer, silent. */
  val LOUD_EXAMPLE get() = Words.Alert.loudExample
  val SILENT_EXAMPLE get() = Words.Alert.silentExample

  /** What alerts the owner, at its level (`push/Alerts.kt`): the event, and how it comes. */
  val NEEDS_YOU get() = Words.Alert.needsYou
  val ALL_HALTED get() = Words.Alert.allHalted
  val ANSWERED get() = Words.Alert.answered
  val NOT_SENT get() = Words.Alert.notSent
  val ALWAYS get() = Words.Alert.always

  /** "3 tafs ›": the tafs whose results alert the phones. */
  fun tafs(n: Int): String = Words.Alert.tafs(count = n)

  /** Where alerts stand on this phone. */
  fun status(available: Boolean, ready: Boolean): String =
    if (!available) Words.Alert.Status.notAvailable else if (ready) Words.Alert.Status.on else Words.Alert.Status.off

  /** The line under the alerts' rows: what they are, or why they can't be. */
  fun foot(available: Boolean, canAnswerHolds: Boolean?): String? = when {
    !available -> NOT_IN_BUILD
    canAnswerHolds == false -> Words.Alert.Foot.cantAnswerHolds
    else -> Words.Alert.Foot.tellsYouDevice(ThisDevice.word)
  }
}
