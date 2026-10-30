package bot.tafs.app.model

import bot.tafs.app.kiff.Door
import bot.tafs.app.kiff.DoorKey
import bot.tafs.app.kiff.Knock

/** How a door's app is named and marked. */
object DoorNames {
  fun label(app: String): String = when (app) {
    "email" -> "email"
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
  const val FOOTER_ANYONE = "Anyone can write to a door. Only people you pair can give %s work, and its Card decides the rest."
  const val WHATSAPP_ELSEWHERE = "WhatsApp doors are added from tafs on an iPhone for now; once added, they show here."

  fun footer(taf: String): String = FOOTER_ANYONE.format(taf)

  fun noDoorYet(taf: String): String = "$taf has no door yet. Someone who manages it can add one."

  fun addIntro(taf: String): String =
    "Give $taf an address people can write to. It has none until you turn one on, and its Card still decides what it does."

  /** Where a door stands today. */
  fun status(d: Door): String = if (d.open) (if (d.resting) "Resting until tomorrow" else "Open") else "Closed"

  /** The door's day: from the owner and paired senders. */
  fun dayLine(d: Door): String =
    if (d.resting) "${d.limit} of ${d.limit} from you and paired senders today. More are dropped until tomorrow."
    else "${d.today} of ${d.limit} from you and paired senders today. Text only: attachments aren't opened, and the reply says so."

  fun othersLine(d: Door): String? {
    val others = d.othersToday ?: return null
    val cap = d.othersLimit ?: return null
    return "$others of $cap from anyone else today."
  }

  /** A key as the owner reads it: the name, with the address under it only when the name isn't the address; "You" only on this person's own key. */
  fun keyName(key: DoorKey, myEmail: String?): Pair<String, String?> {
    val mine = key.auto && myEmail != null && key.id.equals(myEmail, ignoreCase = true)
    // Another person's own paired key never reads "You", whatever name it was stored under.
    val name = if (mine) "You" else key.name?.takeIf { !it.equals(key.id, ignoreCase = true) && !(key.auto && it.equals("You", ignoreCase = true)) }
    return if (name != null) name to key.id else key.id to null
  }

  fun keysTitle(d: Door): String = if (d.holdsKeys) "Paired · can give work" else "No one is paired here"

  fun keysFoot(d: Door): String = "${d.how}. A message counts as theirs only when that holds; anyone else knocks."

  fun noKeys(d: Door, taf: String): String = "${d.how}, so no one here can give $taf work. Everything that comes through knocks."

  fun pairHint(d: Door): String = when (d.kind) {
    "email" -> "Email address"
    "whatsapp" -> "Phone number, with its country code"
    else -> "Who, as ${d.label} names them"
  }

  fun pairIntro(d: Door): String = "Their messages to this ${d.label.lowercase()} door give work, within the Card. Anyone else's only knock."

  fun removeTitle(d: Door): String = "Remove the ${d.label.lowercase()} door?"

  fun removeBody(taf: String): String = "Its address stops being $taf's. Messages already in its chat stay."

  // MARK: Knocks

  /** The top line of a knock: "● KNOCK · BY EMAIL · DIDN'T CHECK OUT". */
  fun knockTitle(k: Knock): String =
    (if (k.paired) "PAIRED · BY " else "● KNOCK · BY ") + DoorNames.label(k.app).uppercase() + if (k.proof == "fail") " · DIDN'T CHECK OUT" else ""

  /** Under a paired sender's message, kept from its knock: it waits for the owner, as a message, not a stranger. */
  const val PAIRED_LINE = "Paired · Do it or Ignore this message"

  /** The line saying a door had its day's messages, and how many more were dropped. */
  fun restLine(k: Knock): String {
    val more = "${k.dropped} more ${if (k.dropped == 1) "was" else "were"} dropped."
    return if (k.rest == "keys") "This door had its messages from you and paired senders for today. $more"
    else "This door had its messages from people who aren't paired for today. $more"
  }

  fun wrote(k: Knock): String = "${k.who} wrote" + (k.subject?.let { " about \"$it\"" } ?: "") + ". Nothing was done."

  fun attachments(k: Knock): String? =
    if (k.attachments > 0) "It had ${k.attachments} attachment${if (k.attachments == 1) "" else "s"}, not opened." else null

  /** What the door could not prove about who wrote. */
  fun proofNote(k: Knock): String? = when (k.proof) {
    "fail" -> "It says it's from ${k.from}, but it didn't pass the sender check, so it's treated as a stranger's."
    "none" -> "This door can't prove who wrote it."
    else -> null
  }

  /** "Do it" only where the sender check didn't fail. */
  fun canDoIt(k: Knock): Boolean = k.proof != "fail"

  /** "Pair Mara": only for a sender the door proved, not yet paired. */
  fun pairLabel(k: Knock): String? =
    if (k.proof == "pass" && !k.paired) "Pair " + (k.name?.split(" ")?.first() ?: "them") else null

  const val DO_IT_DEFAULT = "Do what it asks, within your Card."

  fun doItTitle(taf: String?): String = "Tell ${taf ?: "your taf"} what to do"

  fun doItIntro(k: Knock, taf: String?): String =
    "${k.who}'s message goes with your words, as information. ${taf ?: "It"} acts on what you say, within its Card, and doesn't write back to them unless you ask."

  // MARK: The chat's stamps

  /** The line over a door's message, and under its answer: where it came from, and how it went back. */
  fun stamp(mark: DoorMark, fromOwner: Boolean): String {
    val label = DoorNames.label(mark.app)
    if (fromOwner) return "BY ${label.uppercase()}" + (mark.from?.let { " · $it" } ?: "")
    return when (mark.reply) {
      "sent" -> "↩ SENT BACK BY ${label.uppercase()} · SAME THREAD"
      "not_sent" -> "NOT SENT BACK" + (mark.why?.let { " · $it" } ?: "")
      else -> "GOES BACK BY ${label.uppercase()} WHEN DONE"
    }
  }
}
