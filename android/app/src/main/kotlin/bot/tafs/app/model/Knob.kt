package bot.tafs.app.model

/**
 * A taf's dial, as settled ("The knob, end to end"): When · What · Where around its Why, and a rim that counts today's work.
 * Coach builds it; the dial shows it, and its only controls are a remote's. What it holds comes from tafs cloud's knob API.
 */
data class Knob(
  /** The face when nothing is chosen: the taf's purpose, its Why ("Refund double charges"). */
  val does: String,
  /** What the taf does, in one sentence, under the dial while nothing is chosen. */
  val overview: String,
  val rings: List<KnobRing>,
  val rim: KnobRim = KnobRim(),
  /** Today's work in one line: "12 done today · 1 working · 1 retrying its reply". */
  val summary: String? = null,
) {
  /** Every stop, ring by ring from the inside, each in its ring's order: what ◀ and ▶ step through. */
  val stops: List<Pair<KnobRing, KnobStop>> get() = rings.flatMap { ring -> ring.stops.map { ring to it } }
}

/** One ring, inner to outer: When (what starts it), What (the recipe), Where (where results go), named as tafs cloud names it. */
data class KnobRing(val kind: Kind, val stops: List<KnobStop>, val name: String = kind.title) {
  enum class Kind(val title: String) { WakesIt("When"), What("What"), ResultsGo("Where") }
}

/** A stop on a ring: Email, 1 · Check, Sender… */
data class KnobStop(
  val id: String,
  val label: String,
  /** Whether it's on: an open door, a schedule that runs, a place results go. */
  val on: Boolean,
  /** The face's big word when it's chosen ("Open"), as tafs cloud sends it; none, the face shows the stop's label. */
  val state: String?,
  /** What pressing the face (OK) does, said under it: "Tap to close". Null when OK does nothing for this stop. */
  val tap: String?,
  /** What this stop means for this taf, in one sentence, under the dial. */
  val about: String,
  /** Its one choice's value now ("09:00"), when it has one for − and +. */
  val size: String? = null,
)

/**
 * A door's look on its When stop, from the taf's doors: its arc lit when open, dashed when closed, grey when resting, amber
 * while a knock waits, and a light for a moment when a message arrives.
 */
data class DoorLook(val open: Boolean, val resting: Boolean = false, val knocking: Boolean = false, val arrived: Boolean = false)

/** Today's work around the rim, as counts: done, working, retrying, and waiting on the owner. */
data class KnobRim(val done: Int = 0, val working: Int = 0, val retrying: Int = 0, val needsYou: Int = 0) {
  val total: Int get() = done + working + retrying + needsYou
}

/** What the face shows: the Why, a chosen stop, or a held call that waits on the owner. */
sealed interface KnobFace {
  data object Overview : KnobFace
  data class Chosen(val ring: KnobRing, val stop: KnobStop) : KnobFace
  data class Held(val call: HeldCall) : KnobFace
}

/** The dial's own words, the same on iPhone, iPad and Android; every stop's words are tafs cloud's. */
object KnobWords {
  const val WHY = "WHY"
  const val APPROVE_ONCE = "Tap to approve once"
  const val SEARCH = "Ask Coach or find anything"
  const val SHOWING = "Showing"
  const val ALL = "All services"
  const val THIS_TAF = "This taf"
  const val CAPTION = "When starts it, What it does in order, Where the result goes."
  const val ASK_COACH = "Ask Coach"
  const val ASK_COACH_TO_CHANGE = "Ask Coach to change"
  const val SET_UP_BY_COACH = "Coach set this up"
  const val BACK = "Back"
  const val LOWER = "Lower"
  const val PAUSE = "Pause"
  const val RESUME = "Resume"
  const val RAISE = "Raise"
  const val NEXT = "Next"
  const val DRAFT = "The dial Coach drafted"
  const val READING_DRAFT = "Reading the dial Coach drafted…"
  const val REPLACED = "This dial was replaced by a newer one."
  const val CHANGE = "Change"
  const val ONE_CONFIRMATION = "One confirmation sets all three rings."

  /** Over Coach's dial card: "Refunds will work like this". */
  fun draftTitle(taf: String): String = "$taf will work like this"

  /** The card's confirmation, with this phone's own prompt: "Confirm with your fingerprint". */
  fun confirmWith(biometry: String): String = "Confirm with $biometry"

  /** The changes collected from − and +, made live in one switch: "Make 2 changes live with your fingerprint". */
  fun makeLive(changes: Int, biometry: String): String =
    "Make ${if (changes == 1) "1 change" else "$changes changes"} live with $biometry"

  /** The face's top line for a chosen stop: "WHEN · SLACK". */
  fun chosen(ring: KnobRing, stop: KnobStop): String = "${ring.name} · ${stop.label}".uppercase()

  /** A held call's top line on the face: KIFF's summary of it, as it is ("REFUND ORDER #4409"); the app shortens nothing. */
  fun heldTop(call: HeldCall): String = call.proposed.summary.trim().uppercase()

  /** How TalkBack reads a stop: "Slack, When, open". */
  fun spoken(ring: KnobRing, stop: KnobStop): String = listOfNotNull(stop.label, ring.name, stop.state?.lowercase()).joinToString(", ")
}
