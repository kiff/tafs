package bot.tafs.app.model

import bot.tafs.app.model.RecordEntry.Kind

/** What a Record line's sheet says of it (kiff/tafs#167), as iOS's `ReceiptView` says it: KIFF's facts in words, never its codes. */
object ReceiptWords {
  fun label(kind: Kind): String = when (kind) {
    Kind.done -> "Done within the Card"
    Kind.approved -> "Approved once by you"
    Kind.refused -> "Refused"
    Kind.held -> "Held for you"
    Kind.unknown -> "Outcome unknown"
    Kind.notDone -> "Not done"
    Kind.sending -> "In progress"
    Kind.revoked -> "Card revoked"
    Kind.started -> "Taf started"
    Kind.inTafs -> "In tafs"
  }

  /** The line's state at the top: who answered, when someone did. */
  fun state(entry: RecordEntry): String {
    val answer = entry.answer ?: return label(entry.kind)
    return if (answer.decision == "refused") "Refused by ${entry.answerer}" else "Approved once by ${entry.answerer}"
  }

  fun outcome(entry: RecordEntry): String {
    val money = entry.amountText?.let { " $it" } ?: ""
    return when (entry.kind) {
      Kind.done, Kind.approved -> if (entry.amountText == null) "Done." else "Done.$money moved."
      Kind.refused -> if (entry.answer?.decision == "refused") "Not sent. Refused by ${entry.answerer}." else "Not sent. Its Card refused it."
      Kind.held -> "Waiting for your answer."
      else -> entry.detail
    }
  }

  /** How KIFF decided, in a line under its receipt. */
  fun decision(entry: RecordEntry): String {
    val answer = entry.answer
    if (answer != null) return if (answer.decision == "refused") "held → refused by ${entry.answerer}" else "held → approved once by ${entry.answerer}"
    return when (entry.kind) {
      Kind.refused -> "refused by the Card"
      // Still waiting: the Card held it, it didn't allow it.
      Kind.held -> "held for you by the Card"
      else -> if (entry.ownerApproved == true) "held → approved by you" else "allowed by the Card"
    }
  }

  fun decidedBy(entry: RecordEntry, signedIn: Boolean): String {
    val answer = entry.answer
    if (answer != null) {
      var how = if (entry.answeredByOwner) "You" else entry.answerer.replaceFirstChar { it.uppercase() }
      if (answer.signed == true) how += ", signed on ${answer.device ?: "your device"}"
      return how
    }
    return when {
      entry.kind == Kind.approved || entry.ownerApproved == true -> "You, approving once"
      entry.kind == Kind.refused && entry.signature != null -> "You"
      entry.kind == Kind.revoked -> "You, revoking the Card"
      entry.kind == Kind.started -> "You, issuing the Card"
      signedIn -> "KIFF, against the Card"
      else -> "The Card"
    }
  }
}
