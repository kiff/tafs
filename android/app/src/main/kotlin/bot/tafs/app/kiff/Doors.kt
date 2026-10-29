package bot.tafs.app.kiff

import bot.tafs.app.model.InstantAsString
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A taf's doors (docs/doors.md): where work reaches it from outside the app, with the answer going back the same way.
// tafs cloud keeps them; the phone opens, pairs and closes them with the owner's session. Read leniently: a field
// tafs cloud adds later, or one the app can't read, never stops a door being shown.

/** A sender the owner paired with a door; tafs pairs the owner's own sign-in email itself. */
@Serializable
data class DoorKey(
  val id: String,
  val name: String? = null,
  /** Paired by tafs: the owner's own sign-in email. */
  val auto: Boolean = false,
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
) {
  val holdsKeys: Boolean get() = proof == "proven"
}

/** An app a door can open on here. */
@Serializable
data class DoorApp(
  val id: String,
  val label: String,
  val kind: String,
  val proof: String = "none",
  val how: String = "",
)

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

@Serializable
data class DoorsRead(
  val doors: List<Door> = emptyList(),
  val apps: List<DoorApp> = emptyList(),
  val knocks: List<Knock> = emptyList(),
)

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
