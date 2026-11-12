package bot.tafs.app.model

import bot.tafs.app.kiff.AgentDraft
import bot.tafs.app.kiff.AllowChange
import bot.tafs.app.kiff.AllowOffer
import bot.tafs.app.kiff.DraftAggregate
import bot.tafs.app.kiff.DraftCap
import bot.tafs.app.kiff.DraftCard
import bot.tafs.app.kiff.DraftGrant
import bot.tafs.app.kiff.DraftResources
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
   * limit moved from its figure before to its figure after, in the same unit (a per-call cap on the held [action]; a total in the
   * offer's window, when it names one), or, for a `resource` change, the held action's scope with exactly the offered value added.
   * Every other term is the same: each action, its caps, the states and resources it's limited to and its next steps; each total,
   * its window, what it counts and whether it's per item; what happens past a limit; the Card's holders and when it's valid. A
   * Card marked unchanged must read as in force too. Anything else isn't what was offered, and nothing is signed.
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
      val inForce = Terms.of(current)
      val terms = Terms.of(card, inForce)
      if (card.unchanged == true) {
        if (terms != inForce) return false
        continue
      }
      if (revises !in named || revises in changed) return false
      val expected = offer.changes.filter { it.mandateId == revises }
        .fold(setOf(inForce)) { all, c -> all.flatMap { applied(it, c, action) }.toSet() }
      if (terms !in expected) return false
      changed += revises
    }
    return changed == named
  }

  /** A window as KIFF keeps it: one a draft doesn't name is the calendar day. */
  private fun keptWindow(w: String) = if (w.isEmpty() || w == "day") "calendar_day" else w

  /** One action's terms, in an order of their own: a grant's title is words, not terms. */
  private data class Grant(
    val action: String,
    val caps: List<DraftCap>,
    val states: List<String>,
    val resources: DraftResources?,
    val nextSteps: List<String>,
  )

  /** One total's terms: its window as KIFF keeps it, and what it counts as KIFF would count it. */
  private data class Total(
    val quantity: String,
    val limit: Long,
    val window: String,
    val unit: String?,
    val scale: Int?,
    val measure: String,
    val per: String?,
  )

  /** A Card's readable terms, as KIFF compares them (its `sameTerms`). KIFF's lines are words, not terms. */
  private data class Terms(
    val grants: List<Grant>,
    val totals: List<Total>,
    val onExceed: String?,
    val subjects: List<String>?,
    val validFrom: java.time.Instant?,
    val validUntil: java.time.Instant?,
  ) {
    companion object {
      /** A draft's Card: holders it doesn't name are the Card's in force, as KIFF issues a revision. */
      fun of(card: DraftCard, inForce: Terms) =
        of(card.grants, card.aggregates, card.onExceed, card.subjects ?: inForce.subjects, card.validFrom, card.validUntil)

      fun of(terms: DraftTerms) = of(terms.grants, terms.aggregates, terms.onExceed, terms.subjects, terms.validFrom, terms.validUntil)

      fun of(grants: List<Grant>, totals: List<Total>, onExceed: String?, subjects: List<String>?, from: java.time.Instant?, until: java.time.Instant?) =
        Terms(
          grants.map { it.copy(caps = it.caps.sortedWith(capOrder)) }.sortedBy { it.toString() },
          totals.sortedBy { it.toString() },
          onExceed,
          subjects,
          from,
          until,
        )

      private fun of(grants: List<DraftGrant>?, aggregates: List<DraftAggregate>?, onExceed: String?, subjects: List<String>?, from: String?, until: String?) = of(
        grants.orEmpty().map { g ->
          Grant(
            g.action,
            g.perActionMax.orEmpty(),
            g.entityStates.orEmpty().sorted(),
            g.resources?.let { it.copy(values = it.values.sorted()) },
            g.nextSteps.orEmpty().sorted(),
          )
        },
        aggregates.orEmpty().map { a ->
          Total(a.quantity, a.limit, keptWindow(a.window), a.unit, a.scale, a.measure?.takeIf { it.isNotEmpty() } ?: defaultMeasure(a.quantity), a.per?.takeIf { it.isNotEmpty() })
        },
        onExceed,
        subjects,
        instant(from),
        instant(until),
      )


      /** What a total counts when it doesn't say: calls for a count, what was done for a sum (KIFF's `EffectiveMeasure`). */
      private fun defaultMeasure(quantity: String) = if (quantity.trim() == "count") "attempts" else "effects"

      /** A time as KIFF writes it, compared as the instant it names; one that can't be read never matches another. */
      private fun instant(s: String?): java.time.Instant? = s?.takeIf { it.isNotEmpty() }?.let {
        runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrElse { java.time.Instant.MIN }
      }

      private val capOrder = compareBy<DraftCap>({ it.quantity }, { it.unit }, { it.max }, { it.scale })
    }
  }

  /**
   * Every way [c] can apply to [t]: one limit of its kind, at its figure before and in its unit, moved to its figure after (a
   * per-call cap is the held call's [action]'s); or, for a `resource` change, the held action's scope on the offered parameter,
   * with exactly the offered value added.
   */
  private fun applied(t: Terms, c: AllowChange, action: String): List<Terms> {
    val before = c.before
    val after = c.after
    fun with(grants: List<Grant> = t.grants, totals: List<Total> = t.totals) = Terms.of(grants, totals, t.onExceed, t.subjects, t.validFrom, t.validUntil)
    return when (c.kind) {
      "per_action" -> {
        if (before == null || after == null) return emptyList()
        t.grants.flatMapIndexed { gi, g ->
          if (g.action != action) return@flatMapIndexed emptyList()
          g.caps.mapIndexedNotNull { ci, cap ->
            if (cap.max != before || cap.unit != c.unit) return@mapIndexedNotNull null
            val moved = g.copy(caps = g.caps.toMutableList().also { it[ci] = cap.copy(max = after) })
            with(grants = t.grants.toMutableList().also { it[gi] = moved })
          }
        }
      }
      "total", "count" -> {
        if (before == null || after == null) return emptyList()
        t.totals.mapIndexedNotNull { ai, a ->
          if (a.limit != before || a.unit != c.unit || (c.window != null && keptWindow(c.window) != a.window)) return@mapIndexedNotNull null
          with(totals = t.totals.toMutableList().also { it[ai] = a.copy(limit = after) })
        }
      }
      "resource" -> {
        val parameter = c.parameter ?: return emptyList()
        val value = c.value ?: return emptyList()
        t.grants.mapIndexedNotNull { gi, g ->
          val scope = g.resources ?: return@mapIndexedNotNull null
          if (g.action != action || scope.parameter != parameter || value in scope.values) return@mapIndexedNotNull null
          val widened = g.copy(resources = scope.copy(values = (scope.values + value).sorted()))
          with(grants = t.grants.toMutableList().also { it[gi] = widened })
        }
      }
      else -> emptyList()
    }
  }
}
