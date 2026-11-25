package bot.tafs.watchlink

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the watch may ask the phone, and nothing else (docs/watch-wear.md). The watch holds no keys and no session: it asks, and
 * the phone, which holds both, reads or signs. Nothing here grants authority. An approval has no request at all: the watch says
 * "Approve on your phone", and only the phone's fingerprint can approve.
 *
 * Each request is a live message (the Data Layer's `MessageClient.sendRequest`), never a `DataItem`, so nothing queued while the
 * phone was away lands later; and each carries when it was made, so the phone drops one older than [MAX_AGE_MS]. Only the
 * phone's results go the other way as kept items ([WatchLink.KEPT]): something to show, never something to act on.
 */
sealed interface WatchRequest {
  /** When the watch made it, in milliseconds since the epoch. */
  val madeAt: Long

  /** Needs you: the count, and each held call in the phone's words. */
  data class NeedsYou(override val madeAt: Long) : WatchRequest

  /** Your tafs: each taf's state in one line. */
  data class Tafs(override val madeAt: Long) : WatchRequest

  /** Refuse one held call, by its id. */
  data class Refuse(override val madeAt: Long, val holdId: String) : WatchRequest

  /** Halt one taf, by its id in tafs cloud, or, with [tafId] null, every taf (Halt all). */
  data class Halt(override val madeAt: Long, val tafId: String?) : WatchRequest

  /** A message to Coach, as text: the watch's speech input becomes text there, and no audio is ever sent. */
  data class Coach(override val madeAt: Long, val text: String) : WatchRequest

  /**
   * Ask tafs: the words, as text, for tafs cloud to hand to the one taf that can do it. [clientId] is the watch's own id for
   * this ask, the same on every retry, so tafs cloud never starts it twice. [replyTo] continues one of the watch's own asks.
   */
  data class Ask(
    override val madeAt: Long,
    val clientId: String,
    val text: String,
    val replyTo: String? = null,
    /**
     * Chatting with a taf (docs/watch-wear.md): the phone's id of the taf the person chose. Its words go into that taf's own
     * chat, never Coach's. A phone from before it refuses the request (an unknown field), so the words never reach Coach.
     */
    val tafId: String? = null,
  ) : WatchRequest

  /** The watch's own asks, as tafs cloud has them, updated after [since] (an ISO-8601 time), or in the last day. */
  data class Asks(override val madeAt: Long, val since: String? = null) : WatchRequest

  /**
   * Your brief, as the phone shows it in Needs you's place: a read, like [NeedsYou]. The watch asks it only while its own switch is
   * on (docs/watch-wear.md, Your brief); a phone answers it either way, as answering a read is harmless.
   */
  data class Brief(override val madeAt: Long) : WatchRequest

  /** One taf's page (Wear v2): its job, its Cards with today's use, and what of it waits on the person, by its id on the phone. */
  data class Taf(override val madeAt: Long, val tafId: String) : WatchRequest

  /**
   * Chatting with a taf (docs/watch-wear.md): the last turns of the person's own chat with the phone's taf [tafId], as the phone
   * shows them. Read only, and kept nowhere on the watch.
   */
  data class TafChat(override val madeAt: Long, val tafId: String) : WatchRequest

  /** Read more on a taf's answer: the whole answer to the watch's request [askId], read from tafs cloud only on the tap. */
  data class AskFull(override val madeAt: Long, val askId: String) : WatchRequest

  /** The calendar's [day] (`YYYY-MM-DD`, in the account's zone), or today there with [day] null: what the tafs will do then. */
  data class Calendar(override val madeAt: Long, val day: String? = null) : WatchRequest

  /** Who is signed in, and the plan's line: the watch's Account page. */
  data class Account(override val madeAt: Long) : WatchRequest

  /**
   * The watch's recognizer gave up (row 44, A): turn the recording the watch just sent over the channel [WatchLink.VOICE] +
   * [voiceId] into text with the phone's **on-device** recognizer, in [language] (the app's), and answer with the text only. The
   * phone deletes the recording whatever the outcome, and never sends it anywhere: with no on-device model, it says so.
   */
  data class Transcribe(override val madeAt: Long, val voiceId: String, val language: String) : WatchRequest

  /**
   * The Inbox's lines the person read and dismissed on any of their watches (row 21), by id alone, as tafs cloud keeps them
   * (`GET /v1/watch/inbox`). A read.
   */
  data class InboxMarks(override val madeAt: Long) : WatchRequest

  /**
   * Marks Inbox lines [read] or [dismissed] (`POST /v1/watch/inbox`), by id alone: a held call's KIFF id, or the id of the message
   * whose answer the line is. 1 to [InboxMarkIds.LIMIT] ids in all, each one tafs cloud takes. It changes no one's power.
   */
  data class InboxMark(override val madeAt: Long, val read: List<String> = emptyList(), val dismissed: List<String> = emptyList()) : WatchRequest
}

/** The ids an Inbox mark takes (row 21), checked on both ends as tafs cloud checks them. */
object InboxMarkIds {
  /** The most ids one mark takes, as tafs cloud does. */
  const val LIMIT = 100

  private val ID = Regex("^[A-Za-z0-9_-]{1,100}$")

  /** An id tafs cloud takes: 1 to 100 letters, digits, `_` or `-`. */
  fun isId(value: String): Boolean = ID.matches(value)

  /** Only the ids tafs cloud takes, each once, in order. */
  fun checked(ids: List<String>): List<String> = ids.filter(::isId).distinct()

  /** A mark to send: 1 to [LIMIT] ids in all, each one tafs cloud takes. */
  fun complete(read: List<String>, dismissed: List<String>): Boolean =
    (read + dismissed).let { all -> all.isNotEmpty() && all.size <= LIMIT && all.all(::isId) }
}

/** Why the phone refused a message before reading or signing anything. */
enum class NotAccepted {
  /** A path that isn't one of [WatchLink]'s: an approval, a newer version, anything else. */
  UNKNOWN_PATH,

  /** A body that isn't exactly what its path takes: a field missing, extra or of the wrong kind. */
  MALFORMED,

  /** Made more than [WatchLink.MAX_AGE_MS] ago: a late message is dropped, never acted on. */
  TOO_OLD,

  /** Made further ahead of the phone's clock than [WatchLink.MAX_SKEW_MS]. */
  FROM_THE_FUTURE,
}

/** The phone's answer to a request, drawn on the watch as it comes, in the phone's words. */
@Serializable
sealed interface WatchReply {
  /** Needs you: tafs cloud's count and its words, and each held call. */
  @Serializable @SerialName("needs_you")
  data class NeedsYou(val count: Int, val words: String, val calls: List<HeldLine>) : WatchReply

  /**
   * Your tafs, each in one line, and whether every taf is halted. With the account's time zone ([zone], an IANA id), so the
   * watch shows a chat's times and days as the phone does (row 44, C); none from an older phone, and the watch uses its own.
   */
  @Serializable @SerialName("tafs")
  data class Tafs(val tafs: List<TafLine>, @SerialName("all_halted") val allHalted: Boolean, val zone: String? = null) : WatchReply

  /** It went through, after KIFF or tafs cloud answered: the phone's own line for the result ("Refused", "Halted"). */
  @Serializable @SerialName("done")
  data class Done(val words: String) : WatchReply

  /** The held call was answered already, or is gone: nothing was sent again. */
  @Serializable @SerialName("already_answered")
  data object AlreadyAnswered : WatchReply

  /** It didn't go through, in the phone's words ("Not refused: …"); the watch offers Open on phone. */
  @Serializable @SerialName("failed")
  data class Failed(val words: String) : WatchReply

  /** The phone isn't signed in: the watch says so, and shows nothing it kept. */
  @Serializable @SerialName("signed_out")
  data object SignedOut : WatchReply

  /** Coach's answer; [long] when it holds more than the watch draws (a card, a setup step), so it says Open on phone. */
  @Serializable @SerialName("coach")
  data class Coach(val text: String, val long: Boolean) : WatchReply

  /** The phone refused the request itself ([NotAccepted]), before reading or signing anything. */
  @Serializable @SerialName("not_accepted")
  data class Refused(val reason: NotAccepted) : WatchReply

  /** tafs cloud took the ask: its receipt, the same for a retry of the same ask. */
  @Serializable @SerialName("asked")
  data class AskTaken(val ask: AskLine) : WatchReply

  /**
   * The phone sent the ask, but no answer came back from tafs cloud: it may have arrived. The watch offers Retry, with the same
   * id, so tafs cloud never starts it twice.
   */
  @Serializable @SerialName("unsure")
  data object Unsure : WatchReply

  /**
   * The watch's own asks, as tafs cloud has them, and whether chatting with a taf from the watch is on for the account (`taf_chat`,
   * row 13): null from a tafs cloud or a phone before it, when the watch learns it from a send's refusal instead.
   */
  @Serializable @SerialName("asks")
  data class AskList(val asks: List<AskLine>, @SerialName("taf_chat") val tafChat: Boolean? = null) : WatchReply

  /**
   * Your brief, in tafs cloud's words, as the phone shows it in Needs you's place: its title and its `when` line ("Morning brief ·
   * 08:00 · next at 18:00"), the decisions still to answer in tafs cloud's order (the urgent first), its line for more decisions,
   * what got done (its heading and at most two case names), and the rest. Nothing on it is answered from the wrist but a held
   * call's Refuse, where Needs you already allows it.
   */
  @Serializable @SerialName("brief")
  data class Brief(
    val title: String,
    @SerialName("when") val whenLine: String? = null,
    val decisions: List<DecisionLine> = emptyList(),
    /** More decisions than the brief shows, in tafs cloud's words ("2 more decisions"). */
    val more: String? = null,
    /** tafs cloud's word for an urgent decision ("Urgent"). */
    val urgent: String? = null,
    /** Done's heading, when anything got done. */
    val done: String? = null,
    /** The first two of what got done, by name. */
    @SerialName("done_cases") val doneCases: List<String> = emptyList(),
    /** The things that wait and need nothing now, in tafs cloud's line. */
    val rest: String? = null,
    /** What Needs you holds that the brief doesn't show, in the phone's words: nothing waiting is out of reach. */
    @SerialName("needs_you") val needsYou: String? = null,
  ) : WatchReply

  /** The account has no brief now: the watch shows Needs you, as without one. */
  @Serializable @SerialName("no_brief")
  data object NoBrief : WatchReply

  /**
   * One taf's page, in the phone's words: its state, its job, each of its Cards with today's use, as the phone's Card tab shows it,
   * and how many of its calls wait on the person ([waiting]). Nothing on it is changed from the wrist but Halt.
   */
  /** A taf's chat's last turns, oldest first, as the phone shows them ([WatchRequest.TafChat]). Shown, never kept. */
  @Serializable @SerialName("taf_chat")
  data class TafChat(val lines: List<ChatLine>, val zone: String? = null) : WatchReply

  /** A taf's whole answer, for Read more ([WatchRequest.AskFull]): tafs cloud's `text`, checked, at most 16 KB. Shown, never kept. */
  @Serializable @SerialName("ask_full")
  data class AskFull(val text: String) : WatchReply

  @Serializable @SerialName("taf")
  data class TafPage(
    @SerialName("taf_id") val tafId: String,
    val name: String,
    val state: String,
    val halted: Boolean,
    val job: String,
    val cards: List<CardLine> = emptyList(),
    val waiting: Int = 0,
  ) : WatchReply

  /** A calendar day, in tafs cloud's words: its line, and each event to come with its time and taf. */
  @Serializable @SerialName("calendar")
  data class CalendarDay(
    val day: String,
    val words: String,
    val events: List<EventLine> = emptyList(),
    val halted: Boolean = false,
  ) : WatchReply

  /** The recording's words, from the phone's on-device recognizer ([WatchRequest.Transcribe]); blank when it heard none. */
  @Serializable @SerialName("transcribed")
  data class Transcribed(val text: String) : WatchReply

  /** The phone has no on-device recognizer for the language: nothing was transcribed, and nothing went to a server. */
  @Serializable @SerialName("no_offline_speech")
  data object NoOfflineSpeech : WatchReply

  /** Who is signed in, the account's name, and the plan's line in tafs cloud's words, when the phone has read it. */
  @Serializable @SerialName("account")
  data class AccountPage(
    val email: String? = null,
    val account: String? = null,
    val plan: String? = null,
  ) : WatchReply

  /** The Inbox's lines read and dismissed, as tafs cloud keeps them now (row 21): ids only, a dismissed one never also read. */
  @Serializable @SerialName("inbox_marks")
  data class InboxMarks(val read: List<String> = emptyList(), val dismissed: List<String> = emptyList()) : WatchReply

  /**
   * Chatting with a taf from the watch isn't on for this account (tafs cloud's `watch_taf_chat_off`, row 13): nothing was sent.
   * The watch keeps the words and says so, never Open on phone. An older watch reads it as one it doesn't know (Open on phone).
   */
  @Serializable @SerialName("taf_chat_off")
  data object TafChatOff : WatchReply
}

/**
 * An ask's new state, kept for the watch as a Data Layer item ([WatchLink.KEPT_ASK]), so the watch gets it when it's next in
 * reach, its app open or not. [putAt] is when the phone put it, so a changed state is a changed item.
 */
@Serializable
data class KeptAsk(@SerialName("put_at") val putAt: Long, val ask: AskLine)

/**
 * A taf's chat as the phone holds it now (the last [ChatLine.KEPT] turns, as [WatchReply.TafChat] gives them), kept for the watch
 * as a Data Layer item ([WatchLink.KEPT_TAF_CHAT]) when it changes on the phone, for a taf whose chat the watch has opened, so an
 * open chat there moves as the phone's does.
 */
@Serializable
data class KeptChat(
  @SerialName("put_at") val putAt: Long,
  @SerialName("taf_id") val tafId: String,
  val lines: List<ChatLine> = emptyList(),
  val zone: String? = null,
)

/**
 * The Inbox's marks as tafs cloud keeps them now (row 21), kept for the watch as a Data Layer item ([WatchLink.KEPT_MARKS]) when
 * tafs cloud says they changed: ids only, as [WatchReply.InboxMarks].
 */
@Serializable
data class KeptMarks(
  @SerialName("put_at") val putAt: Long,
  val read: List<String> = emptyList(),
  val dismissed: List<String> = emptyList(),
)

/** One of a taf's Cards, as the phone's Card tab draws it: its title, the face's figure and line, and its rules in one line each. */
@Serializable
data class CardLine(
  val title: String,
  val figure: String? = null,
  val words: String? = null,
  val rules: List<String> = emptyList(),
)

/** One event of a calendar day: its time in the account's zone, its taf, and what it is in tafs cloud's words. */
@Serializable
data class EventLine(val time: String, val taf: String, val words: String, val count: Int = 1)

/**
 * An ask as tafs cloud has it, in its words: its state, the taf it went to once there's one, and its result in one checked line.
 * The phone passes it on as it comes, and the watch draws it as it comes.
 */
@Serializable
data class AskLine(
  val id: String,
  @SerialName("client_id") val clientId: String,
  /**
   * `accepted`, `working`, `done`, `no_taf` (no one taf could: none, or more than one, which [line] says) or `failed`; a state
   * this watch doesn't know is drawn by its [words].
   */
  val state: String,
  @SerialName("taf_name") val tafName: String? = null,
  /** The result, one line of at most 140 characters, checked by tafs cloud; only once the ask has ended. */
  val line: String? = null,
  /** The state in tafs cloud's words ("Working"). */
  val words: String,
  val updated: String,
  /** A taf chat's taf waits on the person (an approval, a question): answered on the phone, never on the watch. */
  val held: Boolean = false,
  /** A taf chat's answer went on past [line]: the rest is on the phone. */
  val more: Boolean = false,
  /**
   * `taf`: a message to a taf the person chose, never Coach's request. Nothing of it is kept on the watch: no Inbox line, no
   * place in Coach's conversation (docs/watch-wear.md). Null for Coach's, and from a tafs cloud before it.
   */
  val kind: String? = null,
) {
  /** Ended: nothing more will come of it. */
  val ended: Boolean get() = state in ENDED

  /** A message to a taf, kept nowhere on the watch. */
  val tafChat: Boolean get() = kind == TAF

  companion object {
    const val ACCEPTED = "accepted"
    const val WORKING = "working"
    const val DONE = "done"
    const val NO_TAF = "no_taf"
    const val FAILED = "failed"
    val ENDED = setOf(DONE, NO_TAF, FAILED)
    const val TAF = "taf"
  }
}

/**
 * One of the brief's decisions, as the phone shows it: its question, its taf, its deadline in words, and, when it's urgent, why it
 * can't wait. Answered on the phone; [holdId] names the held call it's about as Needs you's [HeldLine] does, so the watch offers
 * Refuse exactly where Needs you would.
 */
@Serializable
data class DecisionLine(
  val id: String,
  val question: String,
  val taf: String? = null,
  val deadline: String? = null,
  val urgent: Boolean = false,
  @SerialName("cant_wait") val cantWait: String? = null,
  /** What it's about, as tafs cloud says: `hold`, `step`, `connection`, `check`, and its id there. */
  @SerialName("ref_kind") val refKind: String = "",
  @SerialName("ref_id") val refId: String = "",
  /** The held call's id as Needs you lists it ([HeldLine.holdId]), for a decision about one the phone holds now. */
  @SerialName("hold_id") val holdId: String? = null,
)

/** A held call as the phone's Needs you shows it: its taf, what it wants, KIFF's amount and the reason, as words. */
@Serializable
data class HeldLine(
  @SerialName("hold_id") val holdId: String,
  val taf: String,
  val wants: String,
  val amount: String? = null,
  val reason: String? = null,
  /** Whether the wrist may refuse it; an approval is always the phone's. */
  val refusable: Boolean,
)

/** A taf in one line: its name, its state in tafs cloud's words, and whether it is halted. */
@Serializable
data class TafLine(
  @SerialName("taf_id") val tafId: String,
  val name: String,
  val state: String,
  val halted: Boolean,
  /** Its schedule is paused: a message still goes, and it answers it now (the owner's 2A). False from a phone before it. */
  val paused: Boolean = false,
)

/** One turn of a taf's chat as the watch shows it: the person's or the taf's, when, its words cut as an answer is, and whether more is on the phone. */
@Serializable
data class ChatLine(val id: String, val mine: Boolean, val at: String, val text: String, val more: Boolean = false) {
  companion object {
    /** The most turns the watch shows from before this visit, as Apple Watch's. */
    const val LAST = 6

    /** The most messages of one chat the phone keeps for the watch, as the watch keeps them (docs/watch-wear.md). */
    const val KEPT = 30
  }
}

object WatchLink {
  /** Every path is under this one: a newer version gets a new prefix, and this phone refuses it as unknown. */
  const val PREFIX = "/tafs/watch/v1/"
  const val NEEDS_YOU = PREFIX + "needs-you"
  const val TAFS = PREFIX + "tafs"
  const val REFUSE = PREFIX + "refuse"
  const val HALT = PREFIX + "halt"
  const val COACH = PREFIX + "coach"
  const val ASK = PREFIX + "ask"
  const val ASKS = PREFIX + "asks"

  /**
   * The phone's one message to the watch, never a request the phone answers: an ask's new state, sent when tafs cloud's push
   * says it changed. The phone refuses it as unknown if a watch ever sends it.
   */
  const val ASK_RESULT = PREFIX + "ask-result"

  /**
   * What the phone keeps for the watch as Data Layer items (`DataClient.putDataItem`, urgent), when tafs cloud's push says
   * something changed: delivered when the watch is next in reach, its app open or not, and deleted by the watch once it has kept
   * it. Never a request: the phone acts on nothing under this prefix, and the watch only stores and shows it.
   */
  const val KEPT = PREFIX + "kept/"

  /** One ask's new state, by its id in tafs cloud: `kept/ask/{id}` ([KeptAsk]). */
  const val KEPT_ASK = KEPT + "ask/"

  /** The Inbox's marks ([KeptMarks]). */
  const val KEPT_MARKS = KEPT + "inbox-marks"

  /** A taf's chat as the phone holds it, by the phone's id for the taf: `kept/taf-chat/{id}` ([KeptChat]). */
  const val KEPT_TAF_CHAT = KEPT + "taf-chat/"

  /** A taf's id on the phone, as a kept item's path may name it. */
  private val TAF_ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")
  const val BRIEF = PREFIX + "brief"
  const val TAF = PREFIX + "taf"
  const val TAF_CHAT = PREFIX + "taf-chat"
  const val ASK_FULL = PREFIX + "ask-full"
  const val CALENDAR = PREFIX + "calendar"
  const val ACCOUNT = PREFIX + "account"
  const val TRANSCRIBE = PREFIX + "transcribe"
  const val INBOX_MARKS = PREFIX + "inbox-marks"
  const val INBOX_MARK = PREFIX + "inbox-mark"

  /** A recording's channel (`ChannelClient`), by its id: the only audio that ever leaves the watch, and only to the phone. */
  const val VOICE = PREFIX + "voice/"

  /** The longest recording the phone takes: 31 s of 16 kHz mono 16-bit audio and its header. */
  const val MAX_VOICE_BYTES = 16_000L * 2 * 31 + 44

  /** What the phone app declares (its `android_wear_capabilities`), so the watch finds the phone that can answer it. */
  const val PHONE_CAPABILITY = "tafs_phone_relay"

  /** What the watch app declares, so the phone finds the watch to pass an ask's result to. */
  const val WATCH_CAPABILITY = "tafs_watch_app"

  /** Where the watch's Open on phone goes, by the phone's destinations (`AlertPayload`'s): `tafs://open/needs-you`. */
  const val OPEN_ON_PHONE = "tafs://open/"

  /** A request older than this is dropped (a watch out of reach, a message held up). */
  const val MAX_AGE_MS = 30_000L

  /** How far ahead of the phone's clock a request may say it was made: the two clocks are synced, but not to the millisecond. */
  const val MAX_SKEW_MS = 5_000L

  /** The longest message to Coach the watch may send. */
  const val MAX_COACH_TEXT = 2_000

  /** The longest ask the watch may send, as tafs cloud takes it. */
  const val MAX_ASK_TEXT = 2_000

  /** The watch's own id for an ask: 8 to 64 letters, digits, `_` or `-`, as tafs cloud takes it. */
  private val CLIENT_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

  /** An ask's id in tafs cloud, and a time the watch read: short and plain. */
  private val ID = Regex("^[A-Za-z0-9_:.+-]{1,64}$")

  /** A calendar day, `YYYY-MM-DD`, and nothing else. */
  private val DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

  /** Strict: a field the path doesn't take is refused, not ignored. */
  private val json = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = false }

  @Serializable private data class Read(@SerialName("made_at") val madeAt: Long)

  @Serializable private data class RefuseBody(@SerialName("made_at") val madeAt: Long, @SerialName("hold_id") val holdId: String)

  @Serializable
  private data class HaltBody(
    @SerialName("made_at") val madeAt: Long,
    @SerialName("taf_id") val tafId: String? = null,
    val all: Boolean? = null,
  )

  @Serializable private data class CoachBody(@SerialName("made_at") val madeAt: Long, val text: String)

  @Serializable
  private data class AskBody(
    @SerialName("made_at") val madeAt: Long,
    @SerialName("client_id") val clientId: String,
    val text: String,
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("taf_id") val tafId: String? = null,
  )

  @Serializable private data class AsksBody(@SerialName("made_at") val madeAt: Long, val since: String? = null)

  @Serializable private data class TafBody(@SerialName("made_at") val madeAt: Long, @SerialName("taf_id") val tafId: String)

  @Serializable private data class AskFullBody(@SerialName("made_at") val madeAt: Long, @SerialName("ask_id") val askId: String)

  @Serializable private data class CalendarBody(@SerialName("made_at") val madeAt: Long, val day: String? = null)

  @Serializable
  private data class TranscribeBody(@SerialName("made_at") val madeAt: Long, @SerialName("voice_id") val voiceId: String, val language: String)

  @Serializable
  private data class MarkBody(@SerialName("made_at") val madeAt: Long, val read: List<String>? = null, val dismissed: List<String>? = null)

  /** An app language's tag: "en", "pt", "zh-Hant". */
  private val LANGUAGE = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}$")

  /** A recording's id, as the watch makes it: as an ask's own id. */
  fun isVoiceId(id: String): Boolean = CLIENT_ID.matches(id)

  /** The watch's side: the path and body for [request]. */
  fun encode(request: WatchRequest): Pair<String, ByteArray> {
    val (path, body) = when (request) {
      is WatchRequest.NeedsYou -> NEEDS_YOU to json.encodeToString(Read.serializer(), Read(request.madeAt))
      is WatchRequest.Tafs -> TAFS to json.encodeToString(Read.serializer(), Read(request.madeAt))
      is WatchRequest.Refuse -> REFUSE to json.encodeToString(RefuseBody.serializer(), RefuseBody(request.madeAt, request.holdId))
      // Halt all says so in so many words: a body missing its taf is never read as every taf.
      is WatchRequest.Halt -> HALT to json.encodeToString(
        HaltBody.serializer(),
        if (request.tafId == null) HaltBody(request.madeAt, all = true) else HaltBody(request.madeAt, tafId = request.tafId),
      )
      is WatchRequest.Coach -> COACH to json.encodeToString(CoachBody.serializer(), CoachBody(request.madeAt, request.text))
      is WatchRequest.Ask -> ASK to json.encodeToString(AskBody.serializer(), AskBody(request.madeAt, request.clientId, request.text, request.replyTo, request.tafId))
      is WatchRequest.Asks -> ASKS to json.encodeToString(AsksBody.serializer(), AsksBody(request.madeAt, request.since))
      is WatchRequest.Brief -> BRIEF to json.encodeToString(Read.serializer(), Read(request.madeAt))
      is WatchRequest.Taf -> TAF to json.encodeToString(TafBody.serializer(), TafBody(request.madeAt, request.tafId))
      is WatchRequest.TafChat -> TAF_CHAT to json.encodeToString(TafBody.serializer(), TafBody(request.madeAt, request.tafId))
      is WatchRequest.AskFull -> ASK_FULL to json.encodeToString(AskFullBody.serializer(), AskFullBody(request.madeAt, request.askId))
      is WatchRequest.Calendar -> CALENDAR to json.encodeToString(CalendarBody.serializer(), CalendarBody(request.madeAt, request.day))
      is WatchRequest.Account -> ACCOUNT to json.encodeToString(Read.serializer(), Read(request.madeAt))
      is WatchRequest.Transcribe -> TRANSCRIBE to json.encodeToString(TranscribeBody.serializer(), TranscribeBody(request.madeAt, request.voiceId, request.language))
      is WatchRequest.InboxMarks -> INBOX_MARKS to json.encodeToString(Read.serializer(), Read(request.madeAt))
      is WatchRequest.InboxMark -> INBOX_MARK to json.encodeToString(
        MarkBody.serializer(),
        MarkBody(request.madeAt, request.read.ifEmpty { null }, request.dismissed.ifEmpty { null }),
      )
    }
    return path to body.encodeToByteArray()
  }

  /** What the phone did with a message: the one request it may act on, or why it refused it. */
  sealed interface Accepted {
    data class Yes(val request: WatchRequest) : Accepted
    data class No(val reason: NotAccepted) : Accepted
  }

  /**
   * The phone's side: [path] and [data] as they arrived, checked before anything is read or signed. Only the requests
   * above pass, each exactly as [encode] writes it, made within [MAX_AGE_MS] of [nowMs].
   */
  fun accept(path: String, data: ByteArray, nowMs: Long): Accepted {
    val text = runCatching { data.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return Accepted.No(NotAccepted.MALFORMED)
    val request = try {
      when (path) {
        NEEDS_YOU -> WatchRequest.NeedsYou(json.decodeFromString(Read.serializer(), text).madeAt)
        TAFS -> WatchRequest.Tafs(json.decodeFromString(Read.serializer(), text).madeAt)
        REFUSE -> json.decodeFromString(RefuseBody.serializer(), text).let { WatchRequest.Refuse(it.madeAt, it.holdId) }
        HALT -> json.decodeFromString(HaltBody.serializer(), text).let { body ->
          when {
            body.tafId != null && body.all == null -> WatchRequest.Halt(body.madeAt, body.tafId)
            body.tafId == null && body.all == true -> WatchRequest.Halt(body.madeAt, null)
            else -> return Accepted.No(NotAccepted.MALFORMED)
          }
        }
        COACH -> json.decodeFromString(CoachBody.serializer(), text).let { WatchRequest.Coach(it.madeAt, it.text) }
        ASK -> json.decodeFromString(AskBody.serializer(), text).let { WatchRequest.Ask(it.madeAt, it.clientId, it.text, it.replyTo, it.tafId) }
        ASKS -> json.decodeFromString(AsksBody.serializer(), text).let { WatchRequest.Asks(it.madeAt, it.since) }
        BRIEF -> WatchRequest.Brief(json.decodeFromString(Read.serializer(), text).madeAt)
        TAF -> json.decodeFromString(TafBody.serializer(), text).let { WatchRequest.Taf(it.madeAt, it.tafId) }
        TAF_CHAT -> json.decodeFromString(TafBody.serializer(), text).let { WatchRequest.TafChat(it.madeAt, it.tafId) }
        ASK_FULL -> json.decodeFromString(AskFullBody.serializer(), text).let { WatchRequest.AskFull(it.madeAt, it.askId) }
        CALENDAR -> json.decodeFromString(CalendarBody.serializer(), text).let { WatchRequest.Calendar(it.madeAt, it.day) }
        ACCOUNT -> WatchRequest.Account(json.decodeFromString(Read.serializer(), text).madeAt)
        TRANSCRIBE -> json.decodeFromString(TranscribeBody.serializer(), text).let { WatchRequest.Transcribe(it.madeAt, it.voiceId, it.language) }
        INBOX_MARKS -> WatchRequest.InboxMarks(json.decodeFromString(Read.serializer(), text).madeAt)
        INBOX_MARK -> json.decodeFromString(MarkBody.serializer(), text).let {
          WatchRequest.InboxMark(it.madeAt, it.read.orEmpty(), it.dismissed.orEmpty())
        }
        else -> return Accepted.No(NotAccepted.UNKNOWN_PATH)
      }
    } catch (_: IllegalArgumentException) {
      // kotlinx.serialization's SerializationException is an IllegalArgumentException.
      return Accepted.No(NotAccepted.MALFORMED)
    }
    if (!wellFormed(request)) return Accepted.No(NotAccepted.MALFORMED)
    if (nowMs - request.madeAt > MAX_AGE_MS) return Accepted.No(NotAccepted.TOO_OLD)
    if (request.madeAt - nowMs > MAX_SKEW_MS) return Accepted.No(NotAccepted.FROM_THE_FUTURE)
    return Accepted.Yes(request)
  }

  private fun wellFormed(request: WatchRequest): Boolean = when (request) {
    is WatchRequest.Refuse -> request.holdId.isNotBlank()
    is WatchRequest.Halt -> request.tafId == null || request.tafId.isNotBlank()
    is WatchRequest.Coach -> request.text.isNotBlank() && request.text.length <= MAX_COACH_TEXT
    is WatchRequest.Ask -> CLIENT_ID.matches(request.clientId) && request.text.isNotBlank() && request.text.length <= MAX_ASK_TEXT &&
      (request.replyTo == null || ID.matches(request.replyTo)) && (request.tafId == null || (request.tafId.isNotBlank() && request.tafId.length <= 128))
    is WatchRequest.Asks -> request.since == null || ID.matches(request.since)
    is WatchRequest.Taf -> request.tafId.isNotBlank() && request.tafId.length <= 128
    is WatchRequest.TafChat -> request.tafId.isNotBlank() && request.tafId.length <= 128
    is WatchRequest.AskFull -> ID.matches(request.askId)
    is WatchRequest.Calendar -> request.day == null || DAY.matches(request.day)
    is WatchRequest.Transcribe -> CLIENT_ID.matches(request.voiceId) && LANGUAGE.matches(request.language)
    is WatchRequest.InboxMark -> InboxMarkIds.complete(request.read, request.dismissed)
    is WatchRequest.NeedsYou, is WatchRequest.Tafs, is WatchRequest.Brief, is WatchRequest.Account, is WatchRequest.InboxMarks -> true
  }

  /** The phone's answer, as bytes for the Data Layer. */
  fun encodeReply(reply: WatchReply): ByteArray = replies.encodeToString(WatchReply.serializer(), reply).encodeToByteArray()

  /** The watch's side: the phone's answer, or null for one it doesn't understand (a newer phone), which it shows as Open on phone. */
  fun decodeReply(data: ByteArray): WatchReply? =
    runCatching { replies.decodeFromString(WatchReply.serializer(), data.decodeToString()) }.getOrNull()

  /** The phone's side: an ask's new state, for the watch ([ASK_RESULT]). */
  fun encodeResult(ask: AskLine): ByteArray = replies.encodeToString(AskLine.serializer(), ask).encodeToByteArray()

  /** The watch's side: an ask's new state from the phone, or null for one it can't read. */
  fun decodeResult(data: ByteArray): AskLine? =
    runCatching { replies.decodeFromString(AskLine.serializer(), data.decodeToString()) }.getOrNull()

  /** Where the phone keeps [ask] for the watch. */
  fun keptAskPath(askId: String): String = KEPT_ASK + askId

  /** The ask's id a kept item's [path] names, or null for any other path or an id tafs cloud never gives. */
  fun keptAskId(path: String): String? = path.takeIf { it.startsWith(KEPT_ASK) }?.removePrefix(KEPT_ASK)?.takeIf(ID::matches)

  fun encodeKept(kept: KeptAsk): ByteArray = replies.encodeToString(KeptAsk.serializer(), kept).encodeToByteArray()

  fun decodeKeptAsk(data: ByteArray): KeptAsk? =
    runCatching { replies.decodeFromString(KeptAsk.serializer(), data.decodeToString()) }.getOrNull()

  /** Where the phone keeps a taf's chat for the watch, by the phone's id for the taf; null for an id a path can't carry. */
  fun keptChatPath(tafId: String): String? = tafId.takeIf(TAF_ID::matches)?.let { KEPT_TAF_CHAT + it }

  /** The taf a kept chat's [path] names, or null for any other path. */
  fun keptChatTaf(path: String): String? = path.takeIf { it.startsWith(KEPT_TAF_CHAT) }?.removePrefix(KEPT_TAF_CHAT)?.takeIf(TAF_ID::matches)

  fun encodeKept(kept: KeptChat): ByteArray = replies.encodeToString(KeptChat.serializer(), kept).encodeToByteArray()

  fun decodeKeptChat(data: ByteArray): KeptChat? =
    runCatching { replies.decodeFromString(KeptChat.serializer(), data.decodeToString()) }.getOrNull()

  fun encodeKept(kept: KeptMarks): ByteArray = replies.encodeToString(KeptMarks.serializer(), kept).encodeToByteArray()

  /** The marks a kept item holds, only ids tafs cloud takes ([InboxMarkIds]); null for one it can't read. */
  fun decodeKeptMarks(data: ByteArray): KeptMarks? =
    runCatching { replies.decodeFromString(KeptMarks.serializer(), data.decodeToString()) }.getOrNull()
      ?.let { it.copy(read = InboxMarkIds.checked(it.read), dismissed = InboxMarkIds.checked(it.dismissed)) }

  /** Replies tolerate fields a newer phone adds; requests, which the phone acts on, don't. */
  private val replies = Json { ignoreUnknownKeys = true; classDiscriminator = "kind"; explicitNulls = false }
}
