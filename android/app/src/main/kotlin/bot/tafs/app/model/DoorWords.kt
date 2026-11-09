package bot.tafs.app.model

import bot.tafs.app.kiff.Door
import bot.tafs.app.kiff.DoorKey
import bot.tafs.app.kiff.Knock

/** How a door's app is named and marked. */
object DoorNames {
  fun label(app: String): String = when (app) {
    "email" -> Words.Door.App.email
    "whatsapp" -> "WhatsApp"
    else -> app.take(1).uppercase() + app.drop(1)
  }

  /** The letter in a door's badge. */
  fun mark(app: String): String = when (app) {
    "email" -> "@"
    "whatsapp" -> "W"
    else -> app.take(1).uppercase()
  }
}

/** What the Doors screens and the chat's knocks say, in the words iOS's has. */
object DoorWords {
  /** The Slack bot's trigger a channel door subscribes to. */
  const val SLACK_CHANNEL_MESSAGES = "SLACKBOT_CHANNEL_MESSAGE_RECEIVED"

  /** No Slack bot connected for this taf yet: it's connected like any app. */
  val SLACK_CONNECT_FIRST get() = Words.Door.Slack.connectFirst

  val SLACK_WHICH_BOT get() = Words.Door.Slack.whichBot
  val SLACK_WHICH_CHANNEL get() = Words.Door.Slack.whichChannel
  val SLACK_NO_CHANNELS get() = Words.Door.Slack.noChannels

  /** A Slack bot connection planque gives no account for. */
  val SLACK_NO_ACCOUNT get() = Words.Door.Slack.noAccount

  /** What the prompt says for a Slack door: what's being allowed, in words. */
  fun slackReason(taf: String, channel: String): String = Words.Door.Slack.reason(taf, channel.removePrefix("#"))

    val WHATSAPP_ELSEWHERE get() = Words.Door.whatsappElsewhere

  fun footer(taf: String): String = Words.Door.footer(taf)

  fun noDoorYet(taf: String): String = Words.Door.noDoorYet(taf)

  fun addIntro(taf: String): String =
    Words.Door.addIntro(taf)

  /** Where a door stands today. */
  fun status(d: Door): String = if (d.open) (if (d.resting) Words.Door.Status.resting else Words.Door.Status.open) else Words.Door.Status.closed

  /** The door's day: from the owner and paired senders. */
  fun dayLine(d: Door): String =
    if (d.resting) Words.Door.Day.resting(count = d.limit)
    else Words.Door.Day.open(count = d.limit, today = d.today.toString())

  fun othersLine(d: Door): String? {
    val others = d.othersToday ?: return null
    val cap = d.othersLimit ?: return null
    return Words.Door.Day.others(count = cap, others = others.toString())
  }

  /** A key as the owner reads it: the name, with the address under it only when the name isn't the address; "You" only on this person's own key. */
  fun keyName(key: DoorKey, myEmail: String?): Pair<String, String?> {
    val mine = key.auto && myEmail != null && key.id.equals(myEmail, ignoreCase = true)
    // Another person's own paired key never reads "You", whatever name it was stored under.
    val name = if (mine) Words.Door.Key.you else key.name?.takeIf { !it.equals(key.id, ignoreCase = true) && !(key.auto && it.equals("You", ignoreCase = true)) }
    return if (name != null) name to key.id else key.id to null
  }

  fun keysTitle(d: Door): String = if (d.holdsKeys) Words.Door.Key.paired else Words.Door.Key.noOnePaired

  fun keysFoot(d: Door): String = Words.Door.Key.foot(d.how)

  fun noKeys(d: Door, taf: String): String = Words.Door.Key.none(d.how, taf)

  /** tafs cloud's hint for pairing by hand; these only stand in for an older tafs cloud that sends none. */
  fun pairHint(d: Door): String = d.words?.pairHint ?: when (d.kind) {
    "email" -> Words.Door.PairHint.email
    "whatsapp" -> Words.Door.PairHint.phone
    else -> Words.Door.PairHint.other(d.label)
  }

  fun pairIntro(d: Door): String = Words.Door.pairIntro(d.label.lowercase())

  fun removeTitle(d: Door): String = Words.Door.removeTitle(d.label.lowercase())

  fun removeBody(taf: String): String = Words.Door.removeBody(taf)

  // MARK: Knocks

  /** The top line of a knock: "● KNOCK · BY EMAIL · DIDN'T CHECK OUT". */
  fun knockTitle(k: Knock): String =
    DoorNames.label(k.app).uppercase().let { if (k.paired) Words.Door.Knock.pairedBy(it) else Words.Door.Knock.by(it) } +
      if (k.proof == "fail") " · " + Words.Door.Knock.didntCheckOut else ""

  /** Under a paired sender's message, kept from its knock: it waits for the owner, as a message, not a stranger. */
  val PAIRED_LINE get() = Words.Door.Knock.pairedLine

  /** The line saying a door had its day's messages, and how many more were dropped. */
  fun restLine(k: Knock): String {
    return if (k.rest == "keys") Words.Door.Knock.restKeys(count = k.dropped)
    else Words.Door.Knock.restOthers(count = k.dropped)
  }

  fun wrote(k: Knock): String = k.subject?.let { Words.Door.Knock.wroteAbout(k.who, it) } ?: Words.Door.Knock.wrote(k.who)

  fun attachments(k: Knock): String? =
    if (k.attachments > 0) Words.Door.Knock.attachments(count = k.attachments) else null

  /** What the door could not prove about who wrote. */
  fun proofNote(k: Knock): String? = when (k.proof) {
    "fail" -> Words.Door.Knock.proofFailed(k.from)
    "none" -> Words.Door.Knock.proofNone
    else -> null
  }

  /** "Do it" only where the sender check didn't fail. */
  fun canDoIt(k: Knock): Boolean = k.proof != "fail"

  /** "Pair Mara": only for a sender the door proved, not yet paired. */
  fun pairLabel(k: Knock): String? =
    if (k.proof == "pass" && !k.paired) (k.name?.split(" ")?.first()?.let { Words.Door.Knock.pair(it) } ?: Words.Door.Knock.pairThem) else null

  val DO_IT_DEFAULT get() = Words.Door.DoIt.default

  fun doItTitle(taf: String?): String = taf?.let { Words.Door.DoIt.title(it) } ?: Words.Door.DoIt.titleYourTaf

  fun doItIntro(k: Knock, taf: String?): String =
    taf?.let { Words.Door.DoIt.intro(k.who, it) } ?: Words.Door.DoIt.introIt(k.who)

  // MARK: The chat's stamps

  /** The line over a door's message, and under its answer: where it came from, and how it went back. */
  fun stamp(mark: DoorMark, fromOwner: Boolean): String {
    val label = DoorNames.label(mark.app)
    if (fromOwner) return Words.Door.Stamp.by(label.uppercase()) + (mark.from?.let { " · $it" } ?: "")
    return when (mark.reply) {
      "sent" -> Words.Door.Stamp.sentBack(label.uppercase())
      "not_sent" -> Words.Door.Stamp.notSentBack + (mark.why?.let { " · $it" } ?: "")
      else -> Words.Door.Stamp.goesBack(label.uppercase())
    }
  }
}
