package bot.tafs.app.model

import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.NextStep

/**
 * What a not-done Activity row's next step does (kiff/tafs#476, gap 10), the same as iOS's. tafs cloud says which step and its
 * words; the app only finds what the button opens. A kind it doesn't know is its words alone, with no button.
 */
object NextStepWords {
  sealed interface Action {
    /** The connection's vendor signed it out: sign in again, as on its taf's Connected apps. */
    data class SignInAgain(val grant: Grant) : Action
    /** Connect apps, where the owner connects that app again (a refused key is replaced there). */
    data object ConnectApps : Action
    /** Coach on the taf, with these words in the box for the owner to send. */
    data class AskCoach(val words: String) : Action
    /** A line: try later, or a kind this app doesn't know. */
    data object Line : Action
  }

  /** The connection whose tools include [tool]: the taf's own first, else any of the account's. */
  fun connectionFor(tool: String, tafId: String, grants: List<Grant>): Grant? {
    if (tool.isBlank()) return null
    val live = grants.filter { it.status !in setOf("disconnected", "failed") && tool in actionsOf(it) }
    return live.firstOrNull { it.request.agent == tafId } ?: live.firstOrNull()
  }

  /** A grant's tools as KIFF names them, as [bot.tafs.app.app.tafsUsing] reads them. */
  private fun actionsOf(g: Grant): Set<String> = (g.tools ?: g.request.tools.filter { it.tafs }.map { it.action }).toSet()

  /** What [next] does for the taf [tafId]; [fallback] is Coach's first message when tafs cloud sent none. */
  fun action(next: NextStep, tafId: String, grants: List<Grant>, fallback: String): Action = when (next.kind) {
    "reconnect" -> connectionFor(next.tool, tafId, grants)?.takeIf { it.needsReconnect }?.let { Action.SignInAgain(it) } ?: Action.ConnectApps
    "ask_coach" -> Action.AskCoach(next.message?.takeIf { it.isNotBlank() } ?: fallback)
    else -> Action.Line
  }
}
