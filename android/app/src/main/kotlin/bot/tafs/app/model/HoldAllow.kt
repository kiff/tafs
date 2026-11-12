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
    val card: Card,
  ) {
    /** The Card's own terms beside its grants and totals, as KIFF's `sameTerms` compares them. */
    data class Card(
      val holders: List<String>?,
      val validFrom: java.time.Instant?,
      val validUntil: java.time.Instant?,
      /** Domain and environment, compared as KIFF does, without case; none is "". */
      val domain: String,
      val environment: String,
      val location: String?,
      /** How long a held call waits, in seconds; KIFF's default for none. */
      val holdSeconds: Long,
      /** A revoked Card is never the same as any other: true never matches. */
      val revoked: Boolean,
    ) {
      override fun equals(other: Any?): Boolean =
        other is Card && !revoked && !other.revoked && holders == other.holders && validFrom == other.validFrom &&
          validUntil == other.validUntil && domain == other.domain && environment == other.environment && location == other.location &&
          holdSeconds == other.holdSeconds

      override fun hashCode(): Int = listOf(holders, validFrom, validUntil, domain, environment, location, holdSeconds).hashCode()
    }

    companion object {
      /**
       * A draft's Card as KIFF issues it over the Card in force: holders it doesn't name are the Card's (unless it names
       * another first holder), and so are its time zone and how long a hold waits; its domain and environment are as written.
       */
      fun of(card: DraftCard, inForce: Terms) = read(
        card.grants, card.aggregates, card.onExceed,
        Card(
          holders = card.subjects ?: card.subject?.takeIf { it.isNotBlank() && it.trim() != inForce.card.holders?.firstOrNull() }?.let { listOf(it.trim()) } ?: inForce.card.holders,
          validFrom = instant(card.validFrom),
          validUntil = instant(card.validUntil),
          domain = card.domain.orEmpty().trim().lowercase(),
          environment = card.environment.orEmpty().trim().lowercase(),
          location = card.location?.trim()?.takeIf { it.isNotEmpty() } ?: inForce.card.location,
          holdSeconds = card.holdExpiry?.trim()?.takeIf { it.isNotEmpty() && it != "keep" }?.let { duration(it) } ?: inForce.card.holdSeconds,
          revoked = card.revokedAt != null,
        ),
      )

      fun of(terms: DraftTerms) = read(
        terms.grants, terms.aggregates, terms.onExceed,
        Card(
          holders = terms.subjects ?: terms.subject?.let { listOf(it) },
          validFrom = instant(terms.validFrom),
          validUntil = instant(terms.validUntil),
          domain = terms.domain.orEmpty().trim().lowercase(),
          environment = terms.environment.orEmpty().trim().lowercase(),
          location = terms.location?.trim()?.takeIf { it.isNotEmpty() },
          holdSeconds = terms.holdExpirySeconds?.takeIf { it > 0 } ?: DEFAULT_HOLD_SECONDS,
          revoked = terms.revokedAt != null,
        ),
      )

      fun of(grants: List<Grant>, totals: List<Total>, onExceed: String?, card: Card) =
        Terms(
          grants.map { it.copy(caps = it.caps.sortedWith(capOrder)) }.sortedBy { it.toString() },
          totals.sortedBy { it.toString() },
          onExceed,
          card,
        )

      /** How long a hold waits when a Card doesn't say: ten minutes, KIFF's default. */
      private const val DEFAULT_HOLD_SECONDS = 600L

      /** A duration as KIFF reads one ("10m", "24h", "1h30m", "90s"); one it can't read never matches another. */
      private fun duration(s: String): Long {
        val parts = Regex("(\\d+)([hms])").findAll(s).toList()
        if (parts.isEmpty() || parts.joinToString("") { it.value } != s) return -1
        return parts.sumOf { m -> m.groupValues[1].toLong() * when (m.groupValues[2]) { "h" -> 3600L; "m" -> 60L; else -> 1L } }.takeIf { it > 0 } ?: DEFAULT_HOLD_SECONDS
      }

      private fun read(grants: List<DraftGrant>?, aggregates: List<DraftAggregate>?, onExceed: String?, card: Card) = of(
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
        card,
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
    fun with(grants: List<Grant> = t.grants, totals: List<Total> = t.totals) = Terms.of(grants, totals, t.onExceed, t.card)
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
