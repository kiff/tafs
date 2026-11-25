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
 *   change      what changed (docs/approvals.md, "Live updates"): "taf", "halted", "resumed" or "knock", with taf_id; or
 *               "watch_ask", with ask_id: an ask from the Wear OS watch changed, for the phone to pass on to the watch; or
 *               "watch_inbox": the watch Inbox's marks changed, for the phone to pass on to the watch; or "taf_chat", with
 *               taf_id: a message went into the person's chat with that taf, for the phone to keep for the watch
 *   title,body  what an alert says
 *   open        where tapping it goes: "needs-you", "usage", or "taf" (a door's alert) with taf_id, the taf's id in tafs cloud
 *   hold_id, request_id   what the alert is about: a later alert with the same one replaces it
 *   level       "interrupt" (a hold, a halt: sounds, comes through) or "quiet" (no sound)
 *   expires_at  a held call isn't worth showing once it has expired
 */
/**
 * The phone's notification channels (docs/android-app.md, Alerts). A held call stays on Needs you's, even when tafs cloud marks it
 * urgent (`brief_kind`), so the settings an owner gave that channel hold: Android never carries one channel's settings to another.
 */
enum class AlertChannel {
  /** A held call, or any alert that sounds: interrupting. */
  NEEDS_YOU,

  /** What can't wait for the next brief (`brief_kind: urgent`) and isn't a held call: interrupting, with Needs you's sound. */
  URGENT,

  /** Your brief is ready (`brief_kind: brief`): quiet. */
  BRIEFS,

  /** Everything quiet besides: no sound. */
  OTHER,
}

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
    /** Which of the phone's channels it goes on, so the person can set each kind apart in the phone's settings. */
    val channel: AlertChannel = if (loud) AlertChannel.NEEDS_YOU else AlertChannel.OTHER,
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

  /**
   * An ask from the Wear OS watch changed in tafs cloud (docs/watch-wear.md, Ask tafs): the phone reads it and passes it to the
   * watch. Nothing is shown on the phone.
   */
  data class WatchAsk(val askId: String) : Alert

  /**
   * The watch Inbox's read and dismissed marks changed in tafs cloud (row 21): the phone reads them and keeps them for the watch.
   * Nothing is shown on the phone.
   */
  data object WatchInbox : Alert

  /**
   * A message went into the person's chat with a taf, from any device, or the run it started answered, paused or ended
   * (`taf_chat`, with [tafId] the taf's id in tafs cloud; never Coach's chat). The phone keeps the chat for the watch, when the
   * watch has opened it. Nothing is shown on the phone.
   */
  data class TafChat(val tafId: String) : Alert
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

  /** An ask from the Wear OS watch changed: the phone passes it to the watch, and shows nothing itself. */
  const val WATCH_ASK = "watch_ask"

  /** The watch Inbox's marks changed: the phone passes them to the watch, and shows nothing itself. */
  const val WATCH_INBOX = "watch_inbox"

  /** A taf's chat changed: the phone keeps it for the watch, when the watch has opened it, and shows nothing itself. */
  const val TAF_CHAT = "taf_chat"

  /** Where a door's alert goes: the taf, by its id in tafs cloud. */
  fun taf(cloudId: String) = "$TAF:$cloudId"

  private const val KNOCK = "knock"

  /** Where a door's knock goes: its taf, on Activity, where its knocks are answered. */
  fun knock(cloudId: String) = "$KNOCK:$cloudId"

  /** The taf a destination opens, by its id in tafs cloud; null for any other destination. */
  fun tafOf(open: String?): String? =
    open?.let { o -> listOf("$TAF:", "$KNOCK:").firstOrNull { o.startsWith(it) }?.let { o.removePrefix(it) } }?.takeIf { it.isNotEmpty() }

  /**
   * Where the Wear OS watch's Open on phone goes (docs/watch-wear.md): `tafs://open/needs-you` opens Needs you, and
   * `tafs://open/taf/{id}` that taf's chat. Anything else, or a link that isn't the watch's, opens the app as it is.
   */
  fun openedFromWatch(link: String?): String? = when {
    link == "tafs://open/$NEEDS_YOU" -> NEEDS_YOU
    // A taf's chat on the watch (row 28): that taf, by the id the phone gave the watch, opened on its chat.
    link != null && link.startsWith(WATCH_TAF) -> link.removePrefix(WATCH_TAF).takeIf { WATCH_TAF_ID.matches(it) }?.let(::taf)
    else -> null
  }

  /** The watch's Open on phone for a taf's chat: `tafs://open/taf/{id}`, the phone's own id for the taf. */
  private const val WATCH_TAF = "tafs://open/taf/"
  private val WATCH_TAF_ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")

  /** Whether a destination opens its taf on Activity: a door's knock does, as the knocks are answered there. */
  fun opensActivity(open: String?): Boolean = open?.startsWith("$KNOCK:") == true

  /**
   * The channel an alert goes on: a held call's stays Needs you's (its sound as its `level` says); else Your brief's alert goes on
   * Briefs and an urgent one on Urgent; anything else by its sound, as before.
   */
  private fun channelOf(data: Map<String, String>, briefKind: String?, loud: Boolean): AlertChannel = when {
    data["hold_id"]?.isNotBlank() == true -> if (loud) AlertChannel.NEEDS_YOU else AlertChannel.OTHER
    briefKind == "brief" -> AlertChannel.BRIEFS
    briefKind == "urgent" -> AlertChannel.URGENT
    loud -> AlertChannel.NEEDS_YOU
    else -> AlertChannel.OTHER
  }

  /** What a message asks of the phone; null for one it doesn't understand, so a message of a newer kind never breaks the phone. */
  fun parse(data: Map<String, String>, now: Instant = Instant.now()): Alert? {
    // A step handed to its owner is tagged by its hand-off, so its reminder replaces its first alert and no other.
    val tag = data["hold_id"]?.takeIf { it.isNotBlank() } ?: data["request_id"]?.takeIf { it.isNotBlank() }
      ?: data["handoff"]?.takeIf { it.isNotBlank() }?.let { "step:$it" } ?: ""
    return when (data["kind"]) {
      "answered" -> if (tag.isEmpty()) null else Alert.Clear(tag)
      "background" -> if (data["change"] == WATCH_ASK) data["ask_id"]?.takeIf(String::isNotBlank)?.let { Alert.WatchAsk(it) }
        else if (data["change"] == WATCH_INBOX) Alert.WatchInbox
        else if (data["change"] == TAF_CHAT) data["taf_id"]?.takeIf(String::isNotBlank)?.let { Alert.TafChat(it) }
        else data["change"]?.takeIf { it in CHANGES }?.let { Alert.Changed(it, data["taf_id"]?.takeIf(String::isNotBlank)) }
      "alert" -> {
        val tafId = data["taf_id"]?.takeIf { it.isNotBlank() }
        // A door's knock (tafs cloud's `knock`, the knock's id) opens its taf on Activity; any other taf alert, its taf as before.
        val knocked = data["knock"]?.isNotBlank() == true
        val open = if (data["open"] == TAF && tafId != null) (if (knocked) knock(tafId) else taf(tafId)) else data["open"]?.takeIf { it in OPEN } ?: NEEDS_YOU
        val expires = data["expires_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        // A held call that has expired is not shown: there is nothing to answer.
        if (expires != null && !expires.isAfter(now)) return null
        // tafs cloud's words can name a payee or a merchant: no direction character reorders them beside an amount.
        val title = data["title"]?.let(bot.tafs.app.kiff.KiffMapping::visible)?.takeIf { it.isNotBlank() } ?: if (open == NEEDS_YOU) Words.Alert.needsYou else Words.App.name
        val body = data["body"]?.let(bot.tafs.app.kiff.KiffMapping::visible)?.takeIf { it.isNotBlank() } ?: when {
          open == USAGE -> Words.Push.openUsage
          tafId != null && open != NEEDS_YOU -> Words.Push.openTaf
          else -> Words.Push.openNeedsYou
        }
        // Your brief's own alert, or an urgent one: the brief is read again with the app open. One brief's alert replaces the last.
        val briefKind = data["brief_kind"]?.takeIf { it in BRIEF_KINDS }
        // A door's alerts with nothing else to name them are one per taf: the latest replaces the one before.
        val tagged = tag.ifEmpty { if (briefKind == "brief") BRIEF else tafOf(open)?.let(::taf) ?: "alert" }
        val loud = data["level"] != "quiet"
        Alert.Show(title, body, open, tagged, loud = loud, expiresAt = expires, brief = briefKind != null, channel = channelOf(data, briefKind, loud))
      }
      // The brief's alert, or an urgent one (Missions design v2.1): the brief, whose urgent card leads it, unless it names where it
      // opens. Urgency is tafs cloud's: its `level` says whether it sounds, as any alert's does.
      "brief", "urgent" -> {
        val open = data["open"]?.takeIf { it in OPEN } ?: BRIEF
        val expires = data["expires_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (expires != null && !expires.isAfter(now)) return null
        val title = data["title"]?.let(bot.tafs.app.kiff.KiffMapping::visible)?.takeIf { it.isNotBlank() } ?: Words.Alert.needsYou
        val body = data["body"]?.let(bot.tafs.app.kiff.KiffMapping::visible)?.takeIf { it.isNotBlank() } ?: Words.Push.openNeedsYou
        val loud = data["level"] != "quiet"
        Alert.Show(title, body, open, tag.ifEmpty { data["kind"].orEmpty() }, loud = loud, expiresAt = expires, brief = true, channel = channelOf(data, data["kind"], loud))
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

  /** Whether notifications are allowed now, asking nothing: the re-check when the person comes back from Settings. */
  fun enabledNow(): Boolean = false

  /** This phone's push token; null when it can't be had. */
  suspend fun token(): String?
}

/** A build with no Firebase settings: alerts are off, and the app says so. */
object NoPush : PushPlatform {
  override val available: Boolean get() = false
  override suspend fun allowed(): Boolean = false
  override suspend fun token(): String? = null
}
