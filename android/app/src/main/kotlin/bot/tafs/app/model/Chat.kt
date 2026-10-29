package bot.tafs.app.model

import java.time.Instant

/** One tool a taf called while it worked on a message. */
data class WorkStep(
  val tool: String,
  val error: Boolean = false,
  /** Refused back unmade, an act sent beside a read: not a failure. */
  val notMade: Boolean = false,
)

/** How long a taf worked on a message, and the tools it called, in order, by name. */
data class Work(val ms: Int, val steps: List<WorkStep>)

/** One tool call of an answer as it streams: its call's id, the tool, what it's doing in words, and how it ended. */
data class LiveStep(val call: String?, val tool: String, val words: String, val status: Status = Status.running) {
  enum class Status {
    running, done, error, held,
    /** Refused back unmade: an act sent beside a read, so nothing reached KIFF or the tool. Not a failure. */
    notMade;

    companion object {
      /** A `step` event's status: "done", "error" or "not_made". */
      fun of(stream: String): Status = when (stream) {
        "error" -> error
        "not_made" -> notMade
        else -> done
      }
    }
  }
}

/**
 * The answer a taf is writing now, as its run streams (docs/chat.md): the chat and run it's in, for Stop and steering; the words
 * so far, what it's thinking, the step in progress and the steps done.
 */
data class LiveAnswer(
  val started: java.time.Instant,
  val session: String? = null,
  val run: String? = null,
  val text: String = "",
  val thinking: String = "",
  val activity: String? = null,
  val steps: List<LiveStep> = emptyList(),
) {
  /** A step begins. One the stream sends again (a run picked back up replays its steps) keeps the status it has. */
  fun began(tool: String, words: String, call: String?): LiveAnswer =
    if (call != null && steps.any { it.call == call }) copy(activity = words)
    else copy(activity = words, steps = steps + LiveStep(call, tool, words))

  /** A call ended: the step it began, or the latest still running when the stream didn't say which (a held call). */
  fun ended(call: String?, status: LiveStep.Status): LiveAnswer {
    val i = call?.let { c -> steps.indexOfLast { it.call == c } }?.takeIf { it >= 0 } ?: steps.indexOfLast { it.status == LiveStep.Status.running }
    if (i < 0) return this
    return copy(steps = steps.toMutableList().also { it[i] = it[i].copy(status = status) })
  }

  /** It went on after the owner answered a held call: what it did before is the thread's, with its own work. */
  fun newPart(): LiveAnswer = copy(text = "", thinking = "", activity = null, steps = emptyList())
}

/** How the latest run of a chat ended, when the chat says so under it. */
sealed interface ChatEnd {
  /** It stopped part-way: Retry sends [retry] again; [done] is what went through before it stopped, from the run's own record. */
  data class Failed(val retry: String, val done: List<bot.tafs.app.kiff.DoneCall>) : ChatEnd
  /** The owner stopped it. */
  data object Stopped : ChatEnd
  /** It stopped at its cost or turn cap (`cost_cap`, `turn_cap`), and wrote nothing for it. */
  data class Capped(val stop: String) : ChatEnd
}

/**
 * A door's message or its answer (docs/doors.md): which door, who wrote and the email's subject; on the answer, whether it
 * went back ("sent", "not_sent" or "pending").
 */
data class DoorMark(
  val app: String,
  val from: String? = null,
  val subject: String? = null,
  val reply: String? = null,
  val why: String? = null,
)

/** One line of a taf's thread: the owner's messages and the taf's replies. */
data class ChatMessage(
  val at: Instant,
  val from: From,
  val text: String,
  /** "pending" while it is on its way; "sample" for the sample's own. */
  val session: String,
  /** A run's kickoff shows as a small note, not a bubble. */
  val kind: Kind = Kind.message,
  /** What the taf did before writing it: how long it worked, and the tools it called. Null for a message written without any. */
  val work: Work? = null,
  /** It came through one of the taf's doors, or answers one that did. */
  val door: DoorMark? = null,
  /** The files the owner attached to it (docs/attachments.md). */
  val attachments: List<AttachmentRef> = emptyList(),
) {
  enum class From { owner, taf }
  enum class Kind { kickoff, message }

  /**
   * A taf's message is the same message while its text grows, so its id leaves its words out;
   * an owner's keeps them, so two sent at once stay two.
   */
  val id: String
    get() = if (from == From.taf) "$session|${at.toEpochMilli()}|taf|${kind.name}" else "$session|${at.toEpochMilli()}|owner|${text.hashCode()}"
}

/**
 * What the owner sent that tafs cloud's thread doesn't have yet (kiff/tafs#82): a reload while the answer is on
 * its way must never take the owner's message off the screen. A sent message goes once the thread has it (the same
 * words, written about when it was sent), or after [KEPT]. One copy takes the place of one sent message, oldest
 * first: two "Sí" sent close together stay two until the thread has both.
 */
object SentMessages {
  val KEPT: java.time.Duration = java.time.Duration.ofSeconds(120)

  /** The thread's messages with the sent ones it doesn't have yet, and those still waiting. */
  fun merge(messages: List<ChatMessage>, sent: List<ChatMessage>, now: Instant): Pair<List<ChatMessage>, List<ChatMessage>> {
    val taken = mutableSetOf<Int>()
    val waiting = sent.filter { s ->
      if (java.time.Duration.between(s.at, now) > KEPT) return@filter false
      val copy = messages.indices.firstOrNull { i ->
        val m = messages[i]
        i !in taken && m.from == ChatMessage.From.owner && m.text == s.text && m.at >= s.at.minus(KEPT)
      } ?: return@filter true
      taken += copy
      false
    }
    return (messages + waiting) to waiting
  }
}

/**
 * One thing in a taf's chat, in the order it happened: a message, a call held for the owner, or
 * what became of a call the owner answered.
 */
sealed interface ChatItem {
  val id: String
  val at: Instant

  data class Message(val message: ChatMessage) : ChatItem {
    override val id get() = "m|" + message.id
    override val at get() = message.at
  }

  data class Held(val call: HeldCall) : ChatItem {
    override val id get() = "k|" + call.id
    override val at get() = call.heldAt
  }

  data class Outcome(val entry: RecordEntry) : ChatItem {
    override val id get() = "k|" + entry.id
    override val at get() = entry.at
  }

  /** One of Coach's cards, where it sits in the thread. */
  data class Card(val shown: bot.tafs.app.kiff.ShownComponent, override val at: Instant) : ChatItem {
    override val id get() = "c|" + shown.id
  }
}

object Chat {
  /** What a message may be at most, as tafs cloud takes it. */
  const val LIMIT = 2000

  /**
   * One calm line of what the taf is thinking, as plain text: the last whole sentence while the next is still a fragment, else
   * the one in progress; lists and code skipped; a long one cut at a word.
   */
  fun thinkingLine(text: String, max: Int = 140): String {
    val sentences = text.lines()
      .map { it.trim().replace(Regex("""[*_`#]+"""), "").trim() }
      .filter { it.isNotEmpty() && !Regex("""^([-•+]|\d+[.)])\s""").containsMatchIn(it) && !it.any { c -> c in "{}[]<>=" } }
      .flatMap { line -> Regex("""(?<=[.!?…])\s+""").split(line) }
      .filter { it.split(" ").size >= 3 && !it.endsWith(":") }
    var line = sentences.lastOrNull() ?: return ""
    if (sentences.size > 1 && line.length < 40 && line.last() !in ".!?…") line = sentences[sentences.size - 2]
    if (line.length <= max) return line
    val cut = line.take(max)
    return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 0 } ?: cut.length).trimEnd(' ', ',', ';', ':', '.', '–', '-') + "…"
  }

  /**
   * When a message being sent is stamped: now, but never before the thread's latest, so a phone
   * clock a little behind doesn't place it above the answer it follows.
   */
  fun stamp(latest: Instant?, now: Instant): Instant =
    if (latest == null) now else maxOf(now, latest.plusMillis(1))

  /** The sample taf's first words. */
  fun hello(taf: Taf): String =
    "Hi, I'm ${taf.name}. I'm on it: ${taf.now.lowercase()}. Ask me what I did, or tell me what to do differently. I stay inside my Card either way."

  /** What a held call's outcome says in the chat. */
  fun outcomeText(entry: RecordEntry): String = when (entry.kind) {
    // Why, as the Record says it: a vendor's refusal still counts (kiff/tafs#206 6.13).
    RecordEntry.Kind.notDone -> "${entry.title}: ${entry.detail}"
    RecordEntry.Kind.sending -> "${entry.title}: sent, waiting for the tool"
    RecordEntry.Kind.unknown -> "${entry.title}: outcome unknown"
    RecordEntry.Kind.refused -> if (!entry.title.startsWith("Refused by you")) "${entry.title}: refused, not sent" else entry.title
    RecordEntry.Kind.approved -> if (!entry.title.startsWith("Approved once by you")) "${entry.title}: approved by you, done" else entry.title
    else -> entry.title
  }
}
