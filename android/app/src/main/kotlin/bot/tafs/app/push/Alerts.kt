package bot.tafs.app.push

import bot.tafs.app.model.Words
import java.time.Instant

/**
 * What an alert carries when tafs cloud sends it to this phone through Firebase Cloud Messaging: a data message, so the phone
 * builds the notification itself. It carries no request parameters, as on iOS: a held call's taf, amount and deadline are read
 * from KIFF with the phone's own session when it is opened.
 *
 *   kind        "alert" shows a notification; "answered" takes one away (a held call someone answered); "background" with
 *               change says what changed on the account, for the app to read again; "brief" or "urgent" (Missions design v2.1)
 *               shows the brief's alert, or an urgent one, which opens Your brief unless `open` names where (an earlier
 *               draft's shape; tafs cloud sends these as "alert" with brief_kind)
 *   brief_kind  on an alert, "brief" (Your brief is ready) or "urgent" (one that came at once, a held call's): the brief is read
 *               again with the app open; with decision, the one it's about ("hold:<id>")
 *   change      what changed (docs/approvals.md, "Live updates"): "taf", "halted", "resumed" or "knock", with taf_id
 *   title,body  what an alert says
 *   open        where tapping it goes: "needs-you", "usage", or "taf" (a door's alert) with taf_id, the taf's id in tafs cloud
 *   hold_id, request_id   what the alert is about: a later alert with the same one replaces it
 *   level       "interrupt" (a hold, a halt: sounds, comes through) or "quiet" (no sound)
 *   expires_at  a held call isn't worth showing once it has expired
 */
sealed interface Alert {
  /** A notification to show. */
  data class Show(
    val title: String,
    val body: String,
    val open: String,
    val tag: String,
    val loud: Boolean,
    val expiresAt: Instant?,
    /** The brief's alert, or an urgent one: with the app open, the brief is read again. */
    val brief: Boolean = false,
  ) : Alert {
    /**
     * What this alert says on a locked phone, or a locked watch it bridges to: only that a taf needs you (`alert.needs_you`, iOS's
     * hidden-preview placeholder), never tafs cloud's title or body, which can name an amount, a merchant or a person. The same for
     * every alert, quiet ones too: a usage line can name an amount.
     */
    fun locked(): Show = copy(title = Words.Alert.needsYou, body = "")
  }

  /** A notification to take away: its call was answered. */
  data class Clear(val tag: String) : Alert

  /**
   * Something changed on the account, silently: what to read again, never what changed. [tafId] is the taf's id in tafs cloud,
   * when the change is about one.
   */
  data class Changed(val change: String, val tafId: String?) : Alert
}

object AlertPayload {
  const val NEEDS_YOU = "needs-you"
  const val USAGE = "usage"

  /** Your brief, in Needs you's place (Missions design v2.1): where a brief's alert, or an urgent one, opens. */
  const val BRIEF = "brief"
  private const val TAF = "taf"
  private val OPEN = setOf(NEEDS_YOU, USAGE, BRIEF)

  /** An alert's `brief_kind` (Missions design v2.1, §7.5): Your brief is ready, or one that came at once. */
  private val BRIEF_KINDS = setOf("brief", "urgent")

  /** The changes tafs cloud tells the phones of; one of a newer kind is ignored, and the next return to the app reads it. */
  val CHANGES = setOf("taf", "halted", "resumed", "knock")

  /** Where a door's alert goes: the taf, by its id in tafs cloud. */
  fun taf(cloudId: String) = "$TAF:$cloudId"

  private const val KNOCK = "knock"

  /** Where a door's knock goes: its taf, on Activity, where its knocks are answered. */
  fun knock(cloudId: String) = "$KNOCK:$cloudId"

  /** The taf a destination opens, by its id in tafs cloud; null for any other destination. */
  fun tafOf(open: String?): String? =
    open?.let { o -> listOf("$TAF:", "$KNOCK:").firstOrNull { o.startsWith(it) }?.let { o.removePrefix(it) } }?.takeIf { it.isNotEmpty() }

  /** Whether a destination opens its taf on Activity: a door's knock does, as the knocks are answered there. */
  fun opensActivity(open: String?): Boolean = open?.startsWith("$KNOCK:") == true

  /** What a message asks of the phone; null for one it doesn't understand, so a message of a newer kind never breaks the phone. */
  fun parse(data: Map<String, String>, now: Instant = Instant.now()): Alert? {
    // A step handed to its owner is tagged by its hand-off, so its reminder replaces its first alert and no other.
    val tag = data["hold_id"]?.takeIf { it.isNotBlank() } ?: data["request_id"]?.takeIf { it.isNotBlank() }
      ?: data["handoff"]?.takeIf { it.isNotBlank() }?.let { "step:$it" } ?: ""
    return when (data["kind"]) {
      "answered" -> if (tag.isEmpty()) null else Alert.Clear(tag)
      "background" -> data["change"]?.takeIf { it in CHANGES }?.let { Alert.Changed(it, data["taf_id"]?.takeIf(String::isNotBlank)) }
      "alert" -> {
        val tafId = data["taf_id"]?.takeIf { it.isNotBlank() }
        // A door's knock (tafs cloud's `knock`, the knock's id) opens its taf on Activity; any other taf alert, its taf as before.
        val knocked = data["knock"]?.isNotBlank() == true
        val open = if (data["open"] == TAF && tafId != null) (if (knocked) knock(tafId) else taf(tafId)) else data["open"]?.takeIf { it in OPEN } ?: NEEDS_YOU
        val expires = data["expires_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        // A held call that has expired is not shown: there is nothing to answer.
        if (expires != null && !expires.isAfter(now)) return null
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: if (open == NEEDS_YOU) Words.Alert.needsYou else Words.App.name
        val body = data["body"]?.takeIf { it.isNotBlank() } ?: when {
          open == USAGE -> Words.Push.openUsage
          tafId != null && open != NEEDS_YOU -> Words.Push.openTaf
          else -> Words.Push.openNeedsYou
        }
        // Your brief's own alert, or an urgent one: the brief is read again with the app open. One brief's alert replaces the last.
        val briefKind = data["brief_kind"]?.takeIf { it in BRIEF_KINDS }
        // A door's alerts with nothing else to name them are one per taf: the latest replaces the one before.
        val tagged = tag.ifEmpty { if (briefKind == "brief") BRIEF else tafOf(open)?.let(::taf) ?: "alert" }
        Alert.Show(title, body, open, tagged, loud = data["level"] != "quiet", expiresAt = expires, brief = briefKind != null)
      }
      // The brief's alert, or an urgent one (Missions design v2.1): the brief, whose urgent card leads it, unless it names where it
      // opens. Urgency is tafs cloud's: its `level` says whether it sounds, as any alert's does.
      "brief", "urgent" -> {
        val open = data["open"]?.takeIf { it in OPEN } ?: BRIEF
        val expires = data["expires_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (expires != null && !expires.isAfter(now)) return null
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: Words.Alert.needsYou
        val body = data["body"]?.takeIf { it.isNotBlank() } ?: Words.Push.openNeedsYou
        Alert.Show(title, body, open, tag.ifEmpty { data["kind"].orEmpty() }, loud = data["level"] != "quiet", expiresAt = expires, brief = true)
      }
      else -> null
    }
  }
}

/** The phone's side of push, apart from the app's logic, so that is tested without a phone. */
interface PushPlatform {
  /** Alerts can work in this build: Firebase is configured with the project's settings. */
  val available: Boolean

  /** Whether notifications are allowed, asking the person if they haven't been asked. */
  suspend fun allowed(): Boolean

  /** This phone's push token; null when it can't be had. */
  suspend fun token(): String?
}

/** A build with no Firebase settings: alerts are off, and the app says so. */
object NoPush : PushPlatform {
  override val available: Boolean get() = false
  override suspend fun allowed(): Boolean = false
  override suspend fun token(): String? = null
}
