package bot.tafs.app.push

import java.time.Instant

/**
 * What an alert carries when tafs cloud sends it to this phone through Firebase Cloud Messaging: a data message, so the phone
 * builds the notification itself. It carries no request parameters, as on iOS: a held call's taf, amount and deadline are read
 * from KIFF with the phone's own session when it is opened.
 *
 *   kind        "alert" shows a notification; "answered" takes one away (a held call someone answered)
 *   title,body  what an alert says
 *   open        where tapping it goes: "needs-you" or "usage"
 *   hold_id, request_id   what the alert is about: a later alert with the same one replaces it
 *   level       "interrupt" (a hold, a halt: sounds, comes through) or "quiet" (no sound)
 *   expires_at  a held call isn't worth showing once it has expired
 */
sealed interface Alert {
  /** A notification to show. */
  data class Show(val title: String, val body: String, val open: String, val tag: String, val loud: Boolean, val expiresAt: Instant?) : Alert

  /** A notification to take away: its call was answered. */
  data class Clear(val tag: String) : Alert
}

object AlertPayload {
  const val NEEDS_YOU = "needs-you"
  const val USAGE = "usage"
  private val OPEN = setOf(NEEDS_YOU, USAGE)

  /** What a message asks of the phone; null for one it doesn't understand, so a message of a newer kind never breaks the phone. */
  fun parse(data: Map<String, String>, now: Instant = Instant.now()): Alert? {
    val tag = data["hold_id"]?.takeIf { it.isNotBlank() } ?: data["request_id"]?.takeIf { it.isNotBlank() } ?: ""
    return when (data["kind"]) {
      "answered" -> if (tag.isEmpty()) null else Alert.Clear(tag)
      "alert" -> {
        val open = data["open"]?.takeIf { it in OPEN } ?: NEEDS_YOU
        val expires = data["expires_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        // A held call that has expired is not shown: there is nothing to answer.
        if (expires != null && !expires.isAfter(now)) return null
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: if (open == USAGE) "tafs" else "A taf needs you"
        val body = data["body"]?.takeIf { it.isNotBlank() } ?: if (open == USAGE) "Open Usage to see." else "Open Needs you to answer."
        Alert.Show(title, body, open, tag.ifEmpty { "alert" }, loud = data["level"] != "quiet", expiresAt = expires)
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
