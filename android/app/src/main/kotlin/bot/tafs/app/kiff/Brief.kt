package bot.tafs.app.kiff

import bot.tafs.app.model.Words
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * "Your brief" (Missions design v2.1 §7): what tafs got done and what needs the person, twice a day, from `GET /v1/brief?label=`.
 * Every word, count, order and urgency is tafs cloud's; the app draws it and decides nothing. Read leniently, field by field: a
 * part the app can't read is left out, never the brief with it (as iOS's `TafsCloud.Brief`).
 */
data class Brief(
  val at: String? = null,
  val nextAt: String? = null,
  val decisions: List<Decision> = emptyList(),
  /** More decisions than the brief shows, in tafs cloud's words ("2 more decisions"). */
  val more: Counted? = null,
  val done: List<Done> = emptyList(),
  val nextMove: NextMove? = null,
  /** The things that wait and need nothing now, in one line: today's Needs you list. */
  val rest: Counted? = null,
  /**
   * Coach's chats that wait on this person's answer, in tafs cloud's words ("17 Coach chats wait for your answer."): Needs
   * you's `coach` part, so the brief's numbers add up to the badge. Only when there are some.
   */
  val coachWaiting: Counted? = null,
  /** The day's credits, in tafs cloud's words. */
  val credits: String? = null,
  /** The account's labels, as Home sends them, filtering the brief. */
  val labels: List<HomeLabel> = emptyList(),
  val words: BriefWords = BriefWords(),
) {
  /** Whether there's anything to draw: a brief with no decision, nothing done and no next move is none, and Needs you stays. */
  val drawable: Boolean get() = decisions.isNotEmpty() || done.isNotEmpty() || nextMove != null

  /** What a decision is about, opened as the app opens each kind today: a held call, a step, a connection or a proposal. */
  data class Ref(val kind: String = "", val id: String = "", val taf: String = "")

  data class Answer(
    val words: String = "",
    val answer: String = "",
    /** At most one, Coach's pick; with no `why`, the answers show as equals. */
    val recommended: Boolean = false,
    /** A decline ("Don't send", "Not now"): drawn as one, never read as a no by the app. */
    val decline: Boolean = false,
  )

  data class Decision(
    val id: String = "",
    val question: String = "",
    val answers: List<Answer> = emptyList(),
    /** Why Coach recommends its answer; none, and no answer is drawn as recommended. */
    val why: String? = null,
    val meanwhile: String = "",
    /** When it must be decided by, in words ("by Fri 12:00"). */
    val deadline: String? = null,
    val urgent: Boolean = false,
    /** "Later": moves it to the next brief; left out when it can't be put off. */
    val later: String? = null,
    /** Why it can't wait, when `later` is left out. */
    val cantWait: String? = null,
    /** A line under the answers ("Pay the 3 opens three approvals, one after the other."). */
    val note: String? = null,
    val ref: Ref = Ref(),
  ) {
    /** Coach's pick, drawn first and filled; only with its why. */
    val recommended: Answer? get() = if (why == null) null else answers.firstOrNull { it.recommended }

    /** "Later", as drawn: never on an urgent decision, whatever was sent. */
    val laterShown: String? get() = if (urgent) null else later

    /** One the app can draw: an id, a question and at least one answer. */
    val usable: Boolean get() = id.isNotEmpty() && question.isNotEmpty() && answers.isNotEmpty()
  }

  /**
   * One item a job works on (an invoice, a ticket), in tafs cloud's line: what was done, what it waits on, when it's next checked.
   * Closed only when the source says so.
   */
  data class Case(val name: String = "", val words: String = "", val state: String = "", val nextCheck: String? = null) {
    val closed: Boolean get() = state == "closed"
    val waiting: Boolean get() = state == "waiting"
  }

  data class Done(
    val mission: String? = null,
    val cases: List<Case> = emptyList(),
    /** What it's worth, in words ("€3,400 due back"). */
    val value: String? = null,
    /** The mission whose log holds the evidence. */
    val log: String? = null,
  )

  data class NextMove(val words: String = "", val start: String = "", val dismiss: String = "", val cases: List<String> = emptyList()) {
    val usable: Boolean get() = words.isNotEmpty() && start.isNotEmpty() && dismiss.isNotEmpty()
  }

  /** A count and its words: "more" decisions, the "rest" that need nothing now. */
  data class Counted(val count: Int = 0, val words: String = "")

  companion object {
    /** The brief as tafs cloud sends it. Not an object at all is no brief (it throws); anything inside it is read leniently. */
    fun parse(raw: String): Brief {
      val c = KiffClient.json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException("brief")
      return Brief(
        at = c.text("at"),
        nextAt = c.text("next_at"),
        decisions = c.objects("decisions").map(::decision).filter { it.usable },
        more = c.obj("more")?.let(::counted)?.takeIf { it.count > 0 && it.words.isNotEmpty() },
        done = c.objects("done").map(::done).filter { it.cases.isNotEmpty() },
        nextMove = c.obj("next_move")?.let(::nextMove)?.takeIf { it.usable },
        rest = c.obj("rest")?.let(::counted)?.takeIf { it.words.isNotEmpty() },
        coachWaiting = c.obj("coach_waiting")?.let(::counted)?.takeIf { it.count > 0 && it.words.isNotEmpty() },
        credits = c.obj("credits")?.text("words"),
        labels = c.objects("labels").map(::label).filter { it.id.isNotEmpty() && it.name.isNotEmpty() },
        words = c.obj("words")?.let(::briefWords) ?: BriefWords(),
      )
    }

    private fun decision(c: JsonObject) = Decision(
      id = c.text("id").orEmpty(),
      question = c.text("question").orEmpty(),
      answers = c.objects("answers").map { a ->
        Answer(a.text("words").orEmpty(), a.text("answer").orEmpty(), a.bool("recommended") ?: false, a.bool("decline") ?: false)
      }.filter { it.words.isNotEmpty() },
      why = c.text("why"),
      meanwhile = c.text("meanwhile").orEmpty(),
      // The line to draw ("by Fri 12:00"), in the person's language and the account's zone: `deadline_words`, never the time
      // `deadline` is (KIFF's expiry, for a machine). With no words there's no deadline line.
      deadline = c.text("deadline_words"),
      urgent = c.bool("urgent") ?: false,
      // `{words}`, as v2.1 sends it; a plain line from an earlier draft reads the same.
      later = c.obj("later")?.text("words") ?: c.text("later"),
      // `now_why`, as the route serves it; `cant_wait` from an earlier draft reads the same.
      cantWait = c.text("now_why") ?: c.text("cant_wait"),
      note = c.text("note"),
      ref = c.obj("ref")?.let { Ref(it.text("kind").orEmpty(), it.text("id").orEmpty(), it.text("taf").orEmpty()) } ?: Ref(),
    )

    private fun done(c: JsonObject) = Done(
      mission = c.text("mission"),
      cases = c.objects("cases").map { Case(it.text("name").orEmpty(), it.text("words").orEmpty(), it.text("state").orEmpty(), it.text("next_check")) }
        .filter { it.words.isNotEmpty() || it.name.isNotEmpty() },
      value = c.text("value"),
      log = c.text("log"),
    )

    private fun nextMove(c: JsonObject) = NextMove(
      words = c.text("words").orEmpty(),
      start = c.text("start").orEmpty(),
      dismiss = c.text("dismiss").orEmpty(),
      cases = (c["cases"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList(),
    )

    private fun counted(c: JsonObject) = Counted(c.int("count") ?: 0, c.text("words").orEmpty())

    /** A label chip, as Home sends it: `waiting` a count, and a yes from an earlier draft reads as one waiting. */
    internal fun label(c: JsonObject) = HomeLabel(
      id = c.text("id").orEmpty(),
      name = c.text("name").orEmpty(),
      color = c.text("color"),
      count = c.int("count") ?: 0,
      waiting = c.int("waiting") ?: if (c.bool("waiting") == true) 1 else 0,
      headline = c.obj("headline")?.text("words")?.let { HomeHeadline(it) },
      mission = c.obj("mission")?.let { m -> HomeLabelMission(m.text("id").orEmpty(), m.text("name").orEmpty(), m.text("goal")) }?.takeIf { it.id.isNotEmpty() },
    )

    private fun briefWords(c: JsonObject) = BriefWords(
      tab = c.text("tab").orEmpty(), title = c.text("title").orEmpty(), `when` = c.text("when").orEmpty(),
      decisions = c.text("decisions").orEmpty(), done = c.text("done").orEmpty(), nextMove = c.text("next_move").orEmpty(),
      recommends = c.text("recommends").orEmpty(), meanwhile = c.text("meanwhile").orEmpty(), evidence = c.text("evidence").orEmpty(),
      all = c.text("all").orEmpty(), urgent = c.text("urgent").orEmpty(),
    )
  }
}

/**
 * The brief's headings, in the person's language ("Your brief", "Morning brief · 08:00 · next at 18:00", "Decisions · 2", "Done
 * since last evening", "Next move", "Coach recommends", "Meanwhile:", "Evidence"). Every heading is tafs cloud's (§7.5): the
 * app's own words stand in only where it sent none.
 */
data class BriefWords(
  val tab: String = "",
  val title: String = "",
  val `when`: String = "",
  val decisions: String = "",
  val done: String = "",
  val nextMove: String = "",
  val recommends: String = "",
  val meanwhile: String = "",
  val evidence: String = "",
  val all: String = "",
  val urgent: String = "",
) {
  val titleShown: String get() = title.ifEmpty { tab.ifEmpty { Words.Brief.title } }
  val decisionsShown: String get() = decisions.ifEmpty { Words.Brief.decisions }
  val doneShown: String get() = done.ifEmpty { Words.Brief.done }
  val nextMoveShown: String get() = nextMove.ifEmpty { Words.Brief.nextMove }
  val recommendsShown: String get() = recommends.ifEmpty { Words.Brief.recommends }
  val meanwhileShown: String get() = meanwhile.ifEmpty { Words.Brief.meanwhile }
  val evidenceShown: String get() = evidence.ifEmpty { Words.Brief.evidence }
  val urgentShown: String get() = urgent.ifEmpty { Words.Brief.urgent }
}

/** A line of text, trimmed; none when absent, blank or of another shape. */
private fun JsonObject.text(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** A list whose items are read one by one: one that isn't an object is left out, never the list. */
private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
