package bot.tafs.app.push

import java.time.Instant

/**
 * What an alert carries when tafs cloud sends it to this phone through Firebase Cloud Messaging: a data message, so the phone
 * builds the notification itself. It carries no request parameters, as on iOS: a held call's taf, amount and deadline are read
 * from KIFF with the phone's own session when it is opened.
 *
 *   kind        "alert" shows a notification; "answered" takes one away (a held call someone answered); "background" with
 *               change says what changed on the account, for the app to read again
 *   change      what changed (docs/approvals.md, "Live updates"): "taf", "halted", "resumed" or "knock", with taf_id
 *   title,body  what an alert says
 *   open        where tapping it goes: "needs-you", "usage", or "taf" (a door's alert) with taf_id, the taf's id in tafs cloud
 *   hold_id, request_id   what the alert is about: a later alert with the same one replaces it
 *   level       "interrupt" (a hold, a halt: sounds, comes through) or "quiet" (no sound)
 *   expires_at  a held call isn't worth showing once it has expired
 */
sealed interface Alert {
  /** A notification to show. */
  data class Show(val title: String, val body: String, val open: String, val tag: String, val loud: Boolean, val expiresAt: Instant?) : Alert

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
  private const val TAF = "taf"
  private val OPEN = setOf(NEEDS_YOU, USAGE)

  /** The changes tafs cloud tells the phones of; one of a newer kind is ignored, and the next return to the app reads it. */
  val CHANGES = setOf("taf", "halted", "resumed", "knock")

  /** Where a door's alert goes: the taf, by its id in tafs cloud. */
  fun taf(cloudId: String) = "$TAF:$cloudId"

  /** The taf a destination opens, by its id in tafs cloud; null for any other destination. */
  fun tafOf(open: String?): String? = open?.takeIf { it.startsWith("$TAF:") }?.removePrefix("$TAF:")?.takeIf { it.isNotEmpty() }

  /** What a message asks of the phone; null for one it doesn't understand, so a message of a newer kind never breaks the phone. */
  fun parse(data: Map<String, String>, now: Instant = Instant.now()): Alert? {
    val tag = data["hold_id"]?.takeIf { it.isNotBlank() } ?: data["request_id"]?.takeIf { it.isNotBlank() } ?: ""
    return when (data["kind"]) {
      "answered" -> if (tag.isEmpty()) null else Alert.Clear(tag)
      "background" -> data["change"]?.takeIf { it in CHANGES }?.let { Alert.Changed(it, data["taf_id"]?.takeIf(String::isNotBlank)) }
      "alert" -> {
        val tafId = data["taf_id"]?.takeIf { it.isNotBlank() }
        val open = if (data["open"] == TAF && tafId != null) taf(tafId) else data["open"]?.takeIf { it in OPEN } ?: NEEDS_YOU
        val expires = data["expires_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        // A held call that has expired is not shown: there is nothing to answer.
        if (expires != null && !expires.isAfter(now)) return null
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: if (open == NEEDS_YOU) "A taf needs you" else "tafs"
        val body = data["body"]?.takeIf { it.isNotBlank() } ?: when {
          open == USAGE -> "Open Usage to see."
          tafId != null && open != NEEDS_YOU -> "Open the taf to see."
          else -> "Open Needs you to answer."
        }
        // A door's alerts with nothing else to name them are one per taf: the latest replaces the one before.
        Alert.Show(title, body, open, tag.ifEmpty { tafOf(open)?.let(::taf) ?: "alert" }, loud = data["level"] != "quiet", expiresAt = expires)
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
