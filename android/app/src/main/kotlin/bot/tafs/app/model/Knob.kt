package bot.tafs.app.model

/**
 * A taf's dial ("The knob, end to end", K1 to K5): the job in the face, three rings around it, and a rim that counts today's
 * work. What it holds comes from tafs cloud's knob API; the app only lays it out and sends back what the owner tapped.
 */
data class Knob(
  /** The job in the face, as one word or two: "Refund". */
  val does: String,
  /** What the taf does and how it's set, in one sentence, under the dial while nothing is chosen. */
  val overview: String,
  val rings: List<KnobRing>,
  val rim: KnobRim = KnobRim(),
  /** Today's work in one line: "12 done today · 1 working · 1 retrying its reply". */
  val summary: String? = null,
)

/** One ring: Wakes it (inner), When, or Results go (outer), each with its own stops. */
data class KnobRing(val kind: Kind, val stops: List<KnobStop>) {
  enum class Kind(val title: String) { WakesIt("Wakes it"), When("When"), ResultsGo("Results go") }
}

/** A stop on a ring: Email, Run now, Weekdays, Notion… */
data class KnobStop(
  val id: String,
  val label: String,
  /** Whether it's on: an open door, a schedule that runs, a place results go. */
  val on: Boolean,
  /** The face's big word when it's chosen ("Open"), as tafs cloud sends it; none, the face shows the stop's label. */
  val state: String?,
  /** What tapping the face does, said under it: "Tap to close". Null when the face does nothing for this stop. */
  val tap: String?,
  /** What this stop means for this taf, in one sentence, under the dial. */
  val about: String,
)

/** Today's work around the rim, as counts: done, working, retrying, and waiting on the owner. */
data class KnobRim(val done: Int = 0, val working: Int = 0, val retrying: Int = 0, val needsYou: Int = 0) {
  val total: Int get() = done + working + retrying + needsYou
}

/** What the face shows: the job, a chosen stop, or a held call that waits on the owner. */
sealed interface KnobFace {
  data object Overview : KnobFace
  data class Chosen(val ring: KnobRing.Kind, val stop: KnobStop) : KnobFace
  data class Held(val call: HeldCall) : KnobFace
}

/** The dial's own words, the same on iPhone, iPad and Android. */
object KnobWords {
  const val DOES = "DOES"
  const val TAP_A_RING = "Tap a ring"
  const val APPROVE_ONCE = "Tap to approve once"
  const val OVERVIEW = "Overview"
  const val ASK_COACH = "Ask Coach"
  const val PAUSE = "Pause"
  const val RESUME = "Resume"
  const val MAKE_LIVE = "Make it live"
  const val DRAFT = "The dial Coach drafted"
  const val READING_DRAFT = "Reading the dial Coach drafted…"
  const val REPLACED = "This dial was replaced by a newer one."
  const val CHANGE = "Change"
  const val ONE_CONFIRMATION = "One confirmation sets all three rings."

  /** Over Coach's dial card: "Refunds will work like this". */
  fun draftTitle(taf: String): String = "$taf will work like this"

  /** The card's confirmation, with this phone's own prompt: "Confirm with your fingerprint". */
  fun confirmWith(biometry: String): String = "Confirm with $biometry"
  const val NOT_NOW = "Not now"

  /** Over a dial change waiting to be made live: "Version 8, not live yet". */
  fun changeTitle(version: Int): String = "Version $version, not live yet"

  /** The face's top line for a chosen stop: "WAKES IT · SLACK". */
  fun chosen(ring: KnobRing.Kind, stop: KnobStop): String = "${ring.title} · ${stop.label}".uppercase()

  /** A held call's top line on the face: KIFF's summary of it, as it is ("REFUND ORDER #4409"); the app shortens nothing. */
  fun heldTop(call: HeldCall): String = call.proposed.summary.trim().uppercase()

  /** How TalkBack reads a stop: "Slack, Wakes it, open". */
  fun spoken(ring: KnobRing.Kind, stop: KnobStop): String = listOfNotNull(stop.label, ring.title, stop.state?.lowercase()).joinToString(", ")
}
