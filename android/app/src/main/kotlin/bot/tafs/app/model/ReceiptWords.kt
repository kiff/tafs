package bot.tafs.app.model

import bot.tafs.app.model.RecordEntry.Kind

/** What a Record line's sheet says of it (kiff/tafs#167), as iOS's `ReceiptView` says it: KIFF's facts in words, never its codes. */
object ReceiptWords {
  fun label(kind: Kind): String = when (kind) {
    Kind.done -> Words.Receipt.Label.done
    Kind.approved -> Words.Receipt.Label.approved
    Kind.refused -> Words.Receipt.Label.refused
    Kind.held -> Words.Receipt.Label.held
    Kind.unknown -> Words.Receipt.Label.unknown
    Kind.notDone -> Words.Receipt.Label.notDone
    Kind.sending -> Words.Receipt.Label.sending
    Kind.revoked -> Words.Card.revoked
    Kind.started -> Words.Receipt.Label.started
    Kind.inTafs -> Words.Receipt.Label.inTafs
  }

  /** The line's state at the top: who answered, when someone did. */
  fun state(entry: RecordEntry): String {
    val answer = entry.answer ?: return label(entry.kind)
    return if (answer.decision == "refused") Words.Receipt.refusedBy(entry.answerer) else Words.Receipt.approvedOnceBy(entry.answerer)
  }

  fun outcome(entry: RecordEntry): String {
    return when (entry.kind) {
      Kind.done, Kind.approved -> entry.amountText?.let { Words.Receipt.Outcome.moved(it) } ?: Words.Receipt.Outcome.done
      Kind.refused -> if (entry.answer?.decision == "refused") Words.Receipt.Outcome.refusedBy(entry.answerer) else Words.Receipt.Outcome.cardRefused
      Kind.held -> Words.Receipt.Outcome.waiting
      else -> entry.detail
    }
  }

  /** How KIFF decided, in a line under its receipt. */
  fun decision(entry: RecordEntry): String {
    val answer = entry.answer
    if (answer != null) return if (answer.decision == "refused") Words.Receipt.Decision.heldRefusedBy(entry.answerer) else Words.Receipt.Decision.heldApprovedOnceBy(entry.answerer)
    return when (entry.kind) {
      Kind.refused -> Words.Receipt.Decision.refusedByCard
      // Still waiting: the Card held it, it didn't allow it.
      Kind.held -> Words.Receipt.Decision.heldByCard
      else -> if (entry.ownerApproved == true) Words.Receipt.Decision.heldApprovedByYou else Words.Receipt.Decision.allowedByCard
    }
  }

  fun decidedBy(entry: RecordEntry, signedIn: Boolean): String {
    val answer = entry.answer
    if (answer != null) {
      val how = if (entry.answeredByOwner) Words.Receipt.DecidedBy.you else entry.answerer.replaceFirstChar { it.uppercase() }
      if (answer.signed == true) return Words.Receipt.DecidedBy.signedOn(how, answer.device ?: Words.Receipt.DecidedBy.yourDevice)
      return how
    }
    return when {
      entry.kind == Kind.approved || entry.ownerApproved == true -> Words.Receipt.DecidedBy.youApprovingOnce
      entry.kind == Kind.refused && entry.signature != null -> Words.Receipt.DecidedBy.you
      entry.kind == Kind.revoked -> Words.Receipt.DecidedBy.youRevoking
      entry.kind == Kind.started -> Words.Receipt.DecidedBy.youIssuing
      signedIn -> Words.Receipt.DecidedBy.kiff
      else -> Words.Receipt.DecidedBy.card
    }
  }
}
