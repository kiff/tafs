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
