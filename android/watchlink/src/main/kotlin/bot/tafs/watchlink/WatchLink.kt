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
 * phone was away lands later; and each carries when it was made, so the phone drops one older than [MAX_AGE_MS].
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

  /** Your tafs, each in one line, and whether every taf is halted. */
  @Serializable @SerialName("tafs")
  data class Tafs(val tafs: List<TafLine>, @SerialName("all_halted") val allHalted: Boolean) : WatchReply

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
}

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
data class TafLine(@SerialName("taf_id") val tafId: String, val name: String, val state: String, val halted: Boolean)

object WatchLink {
  /** Every path is under this one: a newer version gets a new prefix, and this phone refuses it as unknown. */
  const val PREFIX = "/tafs/watch/v1/"
  const val NEEDS_YOU = PREFIX + "needs-you"
  const val TAFS = PREFIX + "tafs"
  const val REFUSE = PREFIX + "refuse"
  const val HALT = PREFIX + "halt"
  const val COACH = PREFIX + "coach"

  /** A request older than this is dropped (a watch out of reach, a message held up). */
  const val MAX_AGE_MS = 30_000L

  /** How far ahead of the phone's clock a request may say it was made: the two clocks are synced, but not to the millisecond. */
  const val MAX_SKEW_MS = 5_000L

  /** The longest message to Coach the watch may send. */
  const val MAX_COACH_TEXT = 2_000

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
    }
    return path to body.encodeToByteArray()
  }

  /** What the phone did with a message: the one request it may act on, or why it refused it. */
  sealed interface Accepted {
    data class Yes(val request: WatchRequest) : Accepted
    data class No(val reason: NotAccepted) : Accepted
  }

  /**
   * The phone's side: [path] and [data] as they arrived, checked before anything is read or signed. Only the five requests
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
    is WatchRequest.NeedsYou, is WatchRequest.Tafs -> true
  }

  /** The phone's answer, as bytes for the Data Layer. */
  fun encodeReply(reply: WatchReply): ByteArray = replies.encodeToString(WatchReply.serializer(), reply).encodeToByteArray()

  /** The watch's side: the phone's answer, or null for one it doesn't understand (a newer phone), which it shows as Open on phone. */
  fun decodeReply(data: ByteArray): WatchReply? =
    runCatching { replies.decodeFromString(WatchReply.serializer(), data.decodeToString()) }.getOrNull()

  /** Replies tolerate fields a newer phone adds; requests, which the phone acts on, don't. */
  private val replies = Json { ignoreUnknownKeys = true; classDiscriminator = "kind"; explicitNulls = false }
}
