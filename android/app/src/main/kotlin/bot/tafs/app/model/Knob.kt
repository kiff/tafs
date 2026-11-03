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
  /** A door stop's look, as tafs cloud sends it: open (its arc lit), closed (dashed), resting (grey) or knock (amber). */
  val look: String? = null,
)

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
  const val TAP_A_RING = "Tap a ring"
  const val USE_SIZE = "Use − and +"
  const val BACK_TO_WHY = "‹ Why"
  const val BACK = "Back"
  const val LOWER = "Lower"
  const val PAUSE = "Pause"
  const val RESUME = "Resume"
  const val RAISE = "Raise"
  const val NEXT = "Next"
  const val NOT_READ_BACK = "The change was made but couldn't be read back. Tap to read the dial again."
  const val DRAFT = "The dial Coach drafted"
  const val READING_DRAFT = "Reading the dial Coach drafted…"
  const val REPLACED = "A newer dial replaced this one."
  const val LIVE = "This dial is live."
  const val CHANGE = "Change"
  const val ONE_CONFIRMATION = "One confirmation sets all three rings."
  const val EVENT_FILTERS = "It listens for"
  const val EVENT_CHANGED = "This event changed. It's shown again as it is now: approve that one."
  const val CONNECT_FIRST = "Connect this app for the taf before approving its event."
  const val NOT_RECORDED = "The connection is listening, but the dial hasn't recorded it yet. Approve again to record it."

  /** The approval's button, with this phone's own prompt: "Approve with your fingerprint". */
  fun approveWith(biometry: String): String = "Approve with $biometry"

  /** Over Coach's dial card: "Refunds will work like this". */
  fun draftTitle(taf: String): String = "$taf will work like this"

  /** The card's confirmation, with this phone's own prompt: "Confirm with your fingerprint". */
  fun confirmWith(biometry: String): String = "Confirm with $biometry"

  /** The changes collected from − and +, made live in one switch: "Make 2 changes live with your fingerprint". */
  fun makeLive(changes: Int, biometry: String): String =
    "Make ${if (changes == 1) "1 change" else "$changes changes"} live with $biometry"

  /** Over the dial while a change waits to go live: "DRAFT · V9 · NOT LIVE". */
  fun notLive(version: Int): String = "DRAFT · V$version · NOT LIVE"

  /** A connection in "Showing ▾", with its stops: "Slack · 2 stops". */
  fun withStops(name: String, stops: Int): String = "$name · $stops ${if (stops == 1) "stop" else "stops"}"

  /** The face's top line for a chosen stop: "WHEN · SLACK". */
  fun chosen(ring: KnobRing, stop: KnobStop): String = "${ring.name} · ${stop.label}".uppercase()

  /** A held call's top line on the face: KIFF's summary of it, as it is ("REFUND ORDER #4409"); the app shortens nothing. */
  fun heldTop(call: HeldCall): String = call.proposed.summary.trim().uppercase()

  /** How TalkBack reads a stop: "Slack, When, open". */
  fun spoken(ring: KnobRing, stop: KnobStop): String = listOfNotNull(stop.label, ring.name, stop.state?.lowercase()).joinToString(", ")
}
