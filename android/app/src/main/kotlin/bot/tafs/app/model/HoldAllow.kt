package bot.tafs.app.model

import bot.tafs.app.kiff.AgentDraft
import bot.tafs.app.kiff.AllowChange
import bot.tafs.app.kiff.AllowOffer
import bot.tafs.app.kiff.DraftAggregate
import bot.tafs.app.kiff.DraftCap
import bot.tafs.app.kiff.DraftCard
import bot.tafs.app.kiff.DraftGrant
import bot.tafs.app.kiff.DraftTerms

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
   * The device signs what the person saw. KIFF's draft is for [agent] (and, when KIFF says, made from held call [hold]); it retires
   * no Card and adds none; each Card it changes is one the offer names, and reads exactly as that Card in force with the offered
   * limit moved from its figure before to its figure after, in the same unit (a per-call cap on the held [action]): every other action, cap, total, window and what
   * happens past a limit is the same. A `resource` change has no figure the phone can read, so its Card reads as in force. A Card
   * marked unchanged must read as in force too. Anything else isn't what was offered, and nothing is signed.
   */
  fun matches(draft: AgentDraft, offer: AllowOffer, agent: String, hold: String?, action: String): Boolean {
    if (agent.isEmpty() || draft.agentId != agent) return false
    if (draft.fromHold != null && draft.fromHold != hold) return false
    if (!draft.retires.isNullOrEmpty()) return false
    val named = offer.changes.map { it.mandateId }.toSet()
    if (named.isEmpty()) return false
    val cards = draft.cards ?: return false
    val changed = mutableSetOf<String>()
    for (card in cards) {
      val revises = card.revises ?: return false
      val current = card.current ?: return false
      val terms = Terms.of(card)
      if (card.unchanged == true) {
        if (terms != Terms.of(current)) return false
        continue
      }
      if (revises !in named || revises in changed) return false
      val expected = offer.changes.filter { it.mandateId == revises }
        .fold(setOf(Terms.of(current))) { all, c -> all.flatMap { applied(it, c, action) }.toSet() }
      if (terms !in expected) return false
      changed += revises
    }
    return changed == named
  }

  /** A Card's readable terms, in an order of their own: KIFF's lines and a grant's title are words, not terms. */
  private data class Terms(val grants: List<Pair<String, List<DraftCap>>>, val aggregates: List<DraftAggregate>, val onExceed: String?) {
    companion object {
      fun of(card: DraftCard) = of(card.grants, card.aggregates, card.onExceed)
      fun of(terms: DraftTerms) = of(terms.grants, terms.aggregates, terms.onExceed)
      private fun of(grants: List<DraftGrant>?, aggregates: List<DraftAggregate>?, onExceed: String?) = Terms(
        grants.orEmpty().map { g -> g.action to g.perActionMax.orEmpty().sortedWith(capOrder) }.sortedWith(compareBy({ it.first }, { it.second.toString() })),
        aggregates.orEmpty().sortedWith(aggregateOrder),
        onExceed,
      )
      private val capOrder = compareBy<DraftCap>({ it.quantity }, { it.unit }, { it.max }, { it.scale })
      private val aggregateOrder = compareBy<DraftAggregate>({ it.quantity }, { it.window }, { it.unit }, { it.limit }, { it.scale })
    }
  }

  /**
   * Every way [c] can apply to [t]: one limit of its kind, at its figure before and in its unit, moved to its figure after. A
   * per-call cap is the held call's [action]'s.
   */
  private fun applied(t: Terms, c: AllowChange, action: String): List<Terms> {
    val before = c.before
    val after = c.after
    return when (c.kind) {
      "per_action" -> {
        if (before == null || after == null) return emptyList()
        t.grants.flatMapIndexed { gi, (granted, caps) ->
          if (granted != action) return@flatMapIndexed emptyList()
          caps.mapIndexedNotNull { ci, cap ->
            if (cap.max != before || cap.unit != c.unit) return@mapIndexedNotNull null
            val moved = caps.toMutableList().also { it[ci] = cap.copy(max = after) }
            Terms.of(DraftCard(grants = t.grants.mapIndexed { i, g -> DraftGrant(g.first, perActionMax = if (i == gi) moved else g.second) }, aggregates = t.aggregates, onExceed = t.onExceed))
          }
        }
      }
      "total", "count" -> {
        if (before == null || after == null) return emptyList()
        t.aggregates.mapIndexedNotNull { ai, a ->
          if (a.limit != before || a.unit != c.unit || (c.window != null && c.window != a.window)) return@mapIndexedNotNull null
          t.copy(aggregates = t.aggregates.toMutableList().also { it[ai] = a.copy(limit = after) }).let { m ->
            Terms.of(DraftCard(grants = m.grants.map { DraftGrant(it.first, perActionMax = it.second) }, aggregates = m.aggregates, onExceed = m.onExceed))
          }
        }
      }
      // A value added to a scope: nothing the phone can read differs.
      "resource" -> listOf(t)
      else -> emptyList()
    }
  }
}
