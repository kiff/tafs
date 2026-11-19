package bot.tafs.app.kiff

import bot.tafs.app.model.InstantAsString
import java.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNames

// A taf's doors (docs/doors.md): where work reaches it from outside the app, with the answer going back the same way.
// tafs cloud keeps them; the phone opens, pairs and closes them with the owner's session. Read leniently: a field
// tafs cloud adds later, or one the app can't read, never stops a door being shown.

/**
 * A sender the owner paired with a door; tafs pairs the owner's own sign-in email itself. On a door "From another application"
 * (`api`), an application's key: its id (`key_id` there), name, last four characters and when it was made and last used, never
 * the key itself, which tafs cloud shows once, when it's made (docs/doors.md).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DoorKey(
  @JsonNames("key_id") val id: String,
  val name: String? = null,
  /** Paired by tafs: the owner's own sign-in email. */
  val auto: Boolean = false,
  val last4: String? = null,
  @SerialName("made_at") val madeAt: String? = null,
  @SerialName("last_used_at") val lastUsedAt: String? = null,
)

/** One door: an email address or a WhatsApp number. */
@Serializable
data class Door(
  val id: String,
  val app: String,
  val kind: String,
  val label: String,
  val open: Boolean = false,
  val address: String = "",
  /** "proven": it can tell who wrote, so it holds keys. "none": everything knocks. */
  val proof: String = "none",
  /** How it knows who's writing, in a line. */
  val how: String = "",
  val keys: List<DoorKey> = emptyList(),
  /** Today's messages from the owner and paired senders, and their cap. */
  val today: Int = 0,
  val limit: Int = 0,
  val resting: Boolean = false,
  /** Today's messages from anyone else, and their own cap. */
  @SerialName("others_today") val othersToday: Int? = null,
  @SerialName("others_limit") val othersLimit: Int? = null,
  /** tafs cloud's words for the door, shown as they are: where it stands, what a tap on it does, and where its settings are. */
  val words: DoorText? = null,
  /** A Slack or Telegram door's bot connection, a Slack door's channel, and planque's subscription: removing the door removes that subscription first. */
  val grant: String? = null,
  val channel: String? = null,
  val trigger: String? = null,
  /** A door "From another application" (`api`): today's requests and those waiting. None on any other door. */
  val counts: DoorCounts? = null,
  /** A door "From another application" (`api`): the last request's state and when. None on any other door. */
  @SerialName("last_request") val lastRequest: DoorLastRequest? = null,
) {
  val holdsKeys: Boolean get() = proof == "proven"
}

@Serializable
data class DoorCounts(val today: Int? = null, val waiting: Int? = null)

/** An `api` door's last request: its state, when, and tafs cloud's word for the state in the reader's language, drawn as given. */
@Serializable
data class DoorLastRequest(val status: String, val at: String, val words: String? = null)

/** Row 4 of the API trigger (kiff/work#16): an `api` door's screens and its signed calls. Off until row 2 is live. */
object ApiDoorFeature {
  const val on = false
}

/**
 * A key an `api` door's New key made: the key itself, in this one answer only (tafs cloud keeps only its hash), held by the app
 * until its screen closes, never stored. Then its id, last four and when it was made, as the list shows it from then on.
 */
@Serializable
data class IssuedKey(val id: String, val key: String, val last4: String, @SerialName("made_at") val madeAt: String)

/** A door's words from tafs cloud (docs/doors.md, "The door's words"). */
@Serializable
data class DoorText(
  val state: String? = null,
  val tap: String? = null,
  val settings: String? = null,
  /** Pairing by hand: the field's hint. */
  @SerialName("pair_hint") val pairHint: String? = null,
  /** Pairing from a sender's own message, when that's the door's way: said on its panel. */
  @SerialName("pair_from_knock") val pairFromKnock: String? = null,
)

/** An app a door can open on here. */
@Serializable
data class DoorApp(
  val id: String,
  val label: String,
  val kind: String,
  val proof: String = "none",
  val how: String = "",
  /** A door that listens through one of the owner's bot connections: planque's provider for it, and the step's words. */
  val bot: DoorBot? = null,
)

/** Which connections a bot door listens through (planque's `provider`), and tafs cloud's words for picking one. */
@Serializable
data class DoorBot(val provider: String, val words: DoorBotWords = DoorBotWords())

/** `none`: no such connection for the taf yet, and how to make one. `pick`: the question over the list. */
@Serializable
data class DoorBotWords(val none: String = "", val pick: String = "")

/** Someone without a key wrote: the owner decides. */
@Serializable
data class Knock(
  val id: String,
  val door: String,
  val app: String,
  val from: String,
  val name: String? = null,
  /** "pass", "fail" (the sender check failed) or "none" (the door can't tell). */
  val proof: String = "none",
  val subject: String? = null,
  val text: String = "",
  val attachments: Int = 0,
  @Serializable(with = InstantAsString::class) val at: Instant,
  val state: String = "open",
  /**
   * Not a message: the line saying the door had its day's messages, from the owner and paired senders ("keys") or from
   * anyone else ("others"), and how many more were dropped.
   */
  val rest: String? = null,
  val dropped: Int = 0,
  /** Its sender was paired from it; the message still waits. */
  val paired: Boolean = false,
) {
  val isLine: Boolean get() = rest != null

  /** Who wrote, as the owner reads it. */
  val who: String get() = name?.let { "$it ($from)" } ?: from
}

/**
 * A taf's doors, the apps they can open on and the knocks waiting, each read on its own: a door, an app or a knock this build
 * can't read (a kind it doesn't know yet, say) is left out, and never hides the others (docs/doors.md).
 */
@Serializable
data class DoorsRead(
  @Serializable(with = EachDoor::class) val doors: List<Door> = emptyList(),
  @Serializable(with = EachDoorApp::class) val apps: List<DoorApp> = emptyList(),
  @Serializable(with = EachKnock::class) val knocks: List<Knock> = emptyList(),
)

/** A JSON list read one item at a time: an item that can't be read is left out, never the list. */
open class EachReadable<T>(private val item: KSerializer<T>) : KSerializer<List<T>> {
  private val list = ListSerializer(item)
  override val descriptor = list.descriptor

  override fun deserialize(decoder: Decoder): List<T> {
    val json = decoder as? JsonDecoder ?: return list.deserialize(decoder)
    val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
    return array.mapNotNull { runCatching { json.json.decodeFromJsonElement(item, it) }.getOrNull() }
  }

  override fun serialize(encoder: Encoder, value: List<T>) = list.serialize(encoder, value)
}

internal object EachDoor : EachReadable<Door>(Door.serializer())
internal object EachDoorApp : EachReadable<DoorApp>(DoorApp.serializer())
internal object EachKnock : EachReadable<Knock>(Knock.serializer())

@Serializable
internal data class DoorResponse(val door: Door)

@Serializable
internal data class KnockAnswered(val state: String = "")

/** A door's message or its answer: which door, who wrote and the email's subject; on the answer, whether it went back. */
@Serializable
data class WireDoorMark(
  val app: String,
  val from: String? = null,
  val subject: String? = null,
  /** "sent", "not_sent" or "pending"; null on the message itself. */
  val reply: String? = null,
  val why: String? = null,
)
