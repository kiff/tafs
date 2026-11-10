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
  enum class Kind {
    WakesIt, What, ResultsGo;

    val title: String get() = when (this) { WakesIt -> Words.Knob.Ring.`when`; What -> Words.Knob.Ring.what; ResultsGo -> Words.Knob.Ring.where }
  }
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
  val WHY get() = Words.Knob.why
  val APPROVE_ONCE get() = Words.Knob.approveOnce
  val SEARCH get() = Words.Knob.search
  val SHOWING get() = Words.Knob.showing
  val ALL get() = Words.Knob.allServices
  val THIS_TAF get() = Words.Knob.thisTaf
  val CAPTION get() = Words.Knob.caption
  val ASK_COACH get() = Words.Coach.ask
  val ASK_COACH_TO_CHANGE get() = Words.Knob.askCoachToChange
  val TAP_A_RING get() = Words.Knob.tapARing
  val USE_SIZE get() = Words.Knob.useSize
  val BACK_TO_WHY get() = Words.Knob.backToWhy
  val BACK get() = Words.Common.back
  val LOWER get() = Words.Knob.lower
  val PAUSE get() = Words.Knob.pause
  val RESUME get() = Words.Knob.resume
  val RAISE get() = Words.Knob.raise
  val NEXT get() = Words.Common.next
  val NOT_READ_BACK get() = Words.Knob.notReadBack
  val DRAFT get() = Words.Knob.draft
  val READING_DRAFT get() = Words.Knob.readingDraft
  val REPLACED get() = Words.Knob.replaced
  val LIVE get() = Words.Knob.live
  val CHANGE get() = Words.Knob.change
  val ONE_CONFIRMATION get() = Words.Knob.oneConfirmation
  val EVENT_FILTERS get() = Words.Knob.eventFilters
  val EVENT_CHANGED get() = Words.Knob.eventChanged
  val CONNECT_FIRST get() = Words.Knob.connectFirst
  val NOT_RECORDED get() = Words.Knob.notRecorded

  /** The approval's button, with this phone's own prompt: "Approve with your fingerprint". */
  fun approveWith(biometry: String): String = Words.Approval.approveWith(biometry)

  /** Over Coach's dial card: "Refunds will work like this". */
  fun draftTitle(taf: String): String = Words.Knob.draftTitle(taf)

  /** The card's confirmation, with this phone's own prompt: "Confirm with your fingerprint". */
  fun confirmWith(biometry: String): String = Words.Knob.confirmWith(biometry)

  /** The changes collected from − and +, made live in one switch: "Make 2 changes live with your fingerprint". */
  fun makeLive(changes: Int, biometry: String): String =
    Words.Knob.makeLive(count = changes, biometry = biometry)

  /** Over the dial while a change waits to go live: "DRAFT · V9 · NOT LIVE". */
  fun notLive(version: Int): String = Words.Knob.notLive(version.toString())

  /** A connection in "Showing ▾", with its stops: "Slack · 2 stops". */
  fun withStops(name: String, stops: Int): String = Words.Knob.withStops(count = stops, name = name)

  /** The face's top line for a chosen stop: "WHEN · SLACK". */
  fun chosen(ring: KnobRing, stop: KnobStop): String = "${ring.name} · ${stop.label}".uppercase()

  /** A held call's top line on the face: KIFF's summary of it, as it is ("REFUND ORDER #4409"); the app shortens nothing. */
  fun heldTop(call: HeldCall): String = call.proposed.summary.trim().uppercase()

  /** How TalkBack reads a stop: "Slack, When, open". */
  fun spoken(ring: KnobRing, stop: KnobStop): String = listOfNotNull(stop.label, ring.name, stop.state?.lowercase()).joinToString(", ")
}
