package bot.tafs.app.model

/** The decision for one proposed action. */
sealed interface Decision {
  data object Allowed : Decision
  data class Held(val reason: String) : Decision
  data class Refused(val reason: String) : Decision
}

/**
 * Checks a proposed action against a taf's Card and what it has used.
 *
 * In production KIFF makes this decision at its gateway, before any call reaches a tool; this copy
 * drives the sample and lets the app explain a decision offline. The phone never decides.
 */
object CardCheck {
  fun decide(proposed: ProposedAction, taf: Taf): Decision {
    if (taf.card.revoked) return Decision.Refused("Card revoked")
    val rule = taf.card.rules.firstOrNull { it.action == proposed.action }
      ?: return Decision.Refused("Not on this taf's Card")
    if (rule.never) return Decision.Refused("${rule.label}: never allowed")
    if (rule.alwaysAsk) return Decision.Held("${rule.label}: always asks you")

    val breaches = mutableListOf<String>()
    val amount = proposed.amount
    if (rule.perAction != null && amount != null && amount > rule.perAction) {
      breaches += "Over ${rule.perAction.formatted} each"
    }
    if (rule.perWindow != null && amount != null) {
      val used = taf.used[rule.action] ?: Money.ZERO
      if (used + amount > rule.perWindow) breaches += "Over ${rule.perWindow.formatted} ${rule.window.label}"
    }
    if (rule.countPerWindow != null && (taf.counted[rule.action] ?: 0) + 1 > rule.countPerWindow) {
      breaches += "Over ${rule.countPerWindow} ${rule.window.label}"
    }
    if (breaches.isEmpty()) return Decision.Allowed

    val reason = breaches.joinToString(", ")
    return if (rule.outside == Outside.hold) Decision.Held(reason) else Decision.Refused(reason)
  }

  /** Counts an allowed or approved action against the Card's windows. */
  fun draw(proposed: ProposedAction, taf: Taf): Taf {
    val used = proposed.amount?.let { taf.used + (proposed.action to ((taf.used[proposed.action] ?: Money.ZERO) + it)) } ?: taf.used
    return taf.copy(used = used, counted = taf.counted + (proposed.action to ((taf.counted[proposed.action] ?: 0) + 1)))
  }
}
