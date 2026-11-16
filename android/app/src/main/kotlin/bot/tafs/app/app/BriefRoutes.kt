package bot.tafs.app.app

import bot.tafs.app.kiff.Brief
import bot.tafs.app.model.Words

/**
 * Where a brief's decision is answered (Missions design v2.1 §7): the route it already has, where its own signed answer is given.
 * The brief only opens it; it never answers, signs or chooses an answer in advance (docs/android-app.md, Your brief).
 */
object BriefRoutes {
  /** Needs you's own list, as today. */
  const val NEEDS_YOU = "needs-you"
  private const val HOLD = "hold:"
  private const val STEP = "step:"

  /** The change a brief's alert, or an urgent one, asks the open app to read again. */
  const val BRIEF_CHANGE = "brief"

  /**
   * Where [decision] is answered, whichever [answer] was tapped: a held call opens on its own card in Needs you with **no answer
   * chosen**, so the person approves on KIFF's card, with the fingerprint asked there and a fresh signature; a step opens on its own
   * panel; anything else (a connection, a proposal, or no id) opens Needs you's list. The answer tapped is deliberately not part of
   * the destination.
   */
  @Suppress("UNUSED_PARAMETER")
  fun destination(decision: Brief.Decision, answer: Brief.Answer): String {
    val id = decision.ref.id
    if (id.isEmpty()) return NEEDS_YOU
    return when (decision.ref.kind) {
      "hold" -> HOLD + id
      "step" -> STEP + id
      else -> NEEDS_YOU
    }
  }

  /** The held call a destination opens, by KIFF's id; null for any other. */
  fun holdOf(open: String?): String? = open?.takeIf { it.startsWith(HOLD) }?.removePrefix(HOLD)?.takeIf { it.isNotEmpty() }

  /** The step a destination opens, by its hand-off; null for any other. */
  fun stepOf(open: String?): String? = open?.takeIf { it.startsWith(STEP) }?.removePrefix(STEP)?.takeIf { it.isNotEmpty() }

  /**
   * Needs you's own count, shown under the brief whenever the app's list holds more than the brief's decisions, even when tafs cloud
   * sent no `rest` line: a held call the brief doesn't name is never out of reach. Null when the brief covers it all.
   */
  fun needsYouBeyondBrief(count: Int, decisions: Int): String? = if (count > decisions) Words.NeedsYou.count(count = count) else null
}
