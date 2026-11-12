package bot.tafs.app.model

import bot.tafs.app.kiff.AgentDraft
import bot.tafs.app.kiff.AllowChange
import bot.tafs.app.kiff.AllowOffer
import bot.tafs.app.kiff.DraftCard

/**
 * "Allow this from now on" on a held call (docs/allow-from-now-on.md). Whether to offer it is the app's own rule, from the held
 * call KIFF sent, with no request; what it offers is tafs cloud's; what's signed is KIFF's draft, which the phone reads itself and
 * signs only when it is what was offered.
 */
object HoldAllow {
  /** The breaches a Card change can lift. A "never" is refused, not held, so it never shows here. */
  val ALLOWABLE = setOf("per_action_exceeded", "limit_reached", "resource_not_on_card")

  /** A hold (not a currency mismatch, not Coach's install) whose every breach a Card change can lift. */
  fun allowable(call: HeldCall): Boolean =
    call.install == null && call.kind != "card_unit" && call.breachOutcomes.isNotEmpty() && call.breachOutcomes.all { it in ALLOWABLE }

  /**
   * The device signs what the person saw: KIFF's draft differs from the Cards in force only in the Cards the offer's changes name,
   * each carrying the offered figure. A Card retired, a Card that revises none in force, another Card changed, a grant added or
   * taken away, or a change of a kind the contract doesn't name: it isn't what was offered, and nothing is signed.
   */
  fun matches(draft: AgentDraft, offer: AllowOffer): Boolean {
    if (!draft.retires.isNullOrEmpty()) return false
    val named = offer.changes.map { it.mandateId }.toSet()
    if (named.isEmpty()) return false
    val cards = draft.cards ?: return false
    val changed = mutableSetOf<String>()
    for (card in cards) {
      val revises = card.revises ?: return false
      if (card.isUnchanged) continue
      if (revises !in named) return false
      val current = card.current ?: return false
      if (card.grants.orEmpty().map { it.action }.toSet() != current.grants.orEmpty().map { it.action }.toSet()) return false
      if (offer.changes.filter { it.mandateId == revises }.any { !carries(card, it) }) return false
      changed += revises
    }
    return changed == named
  }

  /** The offered figure, where the change's kind puts it on the Card. */
  private fun carries(card: DraftCard, c: AllowChange): Boolean {
    fun sameUnit(unit: String?) = c.unit == null || unit == null || unit == c.unit
    return when (c.kind) {
      "per_action" -> c.after != null && card.grants.orEmpty().any { g -> g.perActionMax.orEmpty().any { it.max == c.after && sameUnit(it.unit) } }
      "total", "count" -> c.after != null && card.aggregates.orEmpty().any { it.limit == c.after && sameUnit(it.unit) }
      // A value added to a scope: no figure to compare, and the grants' actions are the same as in force.
      "resource" -> true
      else -> false
    }
  }
}
