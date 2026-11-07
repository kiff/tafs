package bot.tafs.app.model

import bot.tafs.app.kiff.Grant

/** What a taf's connections say. */
object ConnectionWords {
  /** Under a Card total whose currency waits on a tool that isn't connected (KIFF's `unit_words`), for whoever manages the taf. */
  const val RECONNECT_IN_COACH = "Reconnect it in Coach."

  /**
   * A connection's last failed call (gap 13): the newest call through one of its tools that wasn't done, its taf's own first,
   * in KIFF's words for it. Null when none is in the record.
   */
  fun lastFailure(g: Grant, record: List<bot.tafs.app.model.RecordEntry>): String? {
    val actions = (g.tools ?: g.request.tools.filter { it.tafs }.map { it.action }).toSet()
    if (actions.isEmpty()) return null
    val failed = record.filter { it.kind == RecordEntry.Kind.notDone && it.action in actions }
    val last = (failed.filter { it.tafId == g.request.agent }.ifEmpty { failed }).maxByOrNull { it.at } ?: return null
    return "Last failed: ${last.title} · ${last.detail}"
  }

  /** Ends a grant whose sign-in never came back (it's never connected); the owner connects it again afterwards. */
  const val CANCEL_SIGN_IN = "Cancel"
  fun cancelTitle(g: Grant): String = "Cancel the sign-in to ${g.vendor}?"
  fun cancelBody(g: Grant): String = "Nothing is connected yet. Connect ${g.vendor} again from Coach or Connect apps when you're ready."

  /** A sign-in that never came back, cancelled: its confirmation's buttons. */
  const val CANCEL_IT = "Cancel the sign-in"
  const val KEEP_WAITING = "Keep waiting"

  /**
   * Connect apps by taf (the owner's ask): the connections whose taf the app can't name, last. That may be a removed taf's, a
   * taf this person doesn't see, or an agent that isn't a taf, so the words claim none of them.
   */
  const val OTHER_CONNECTIONS = "Other connections"

  const val REPLACE_KEY = "Replace the key"
  const val SIGN_IN_AGAIN = "Sign in again"

  /** In place of Sign in again on a provider's connection ([Grant.signsInThroughProvider]), as planque says it. */
  fun throughProvider(g: Grant): String =
    "${g.vendor} signs in through its connection provider. To sign in again, disconnect it and connect it again."

  /** Where a connection stands. */
  fun status(g: Grant): String = when {
    g.connected -> "Connected"
    g.needsReconnect -> signedOut(g)
    g.holdsSignIn -> "Signed in · Coach is choosing its tools"
    g.waitingSignIn -> "Waiting for the sign-in at ${g.vendor}"
    else -> g.status.replace('_', ' ').replaceFirstChar { it.uppercase() }
  }

  /** A connection its vendor signed out, in planque's words; before planque sends them, the app's. */
  fun signedOut(g: Grant): String = g.statusWords?.takeIf { it.isNotBlank() } ?: "Signed out by ${g.vendor} · sign in again"

  /** The tools the taf has through it, in words; Coach's own reads are not the taf's. */
  fun tools(g: Grant): String = g.request.tools.filter { it.tafs }.joinToString(" · ") { it.words }

  /** What planque last did, as it writes it ("Approved from tafs on Android phone"). */
  fun lastEvent(g: Grant): String? = g.events.lastOrNull()?.what?.takeIf { it.isNotBlank() }

  /** A name's possessive: "Refunds'", "Invoice reminders'", "Chargebacks'"; else "Coach's". */
  fun possessive(name: String): String = if (name.endsWith("s")) "$name'" else "$name's"

  /**
   * Disconnect's question, naming the one taf it ends ([taf], or none once its taf was removed), and [alsoStops], the
   * other tafs whose Card draws on its tools, which stop too; the fingerprint prompt asks the same.
   */
  fun disconnectTitle(g: Grant, taf: String?, alsoStops: List<String> = emptyList()): String {
    val asked = taf?.let { "Disconnect ${g.vendor} from $it?" } ?: "Disconnect ${g.vendor}?"
    return if (alsoStops.isEmpty()) asked else "$asked ${alsoStops.joinToString(" and ")} ${if (alsoStops.size == 1) "stops" else "stop"} too."
  }

  /** What stops and what doesn't: this taf's connection ends, with any other taf whose Card draws on its tools; other connections to the vendor stay. */
  fun disconnectBody(g: Grant, taf: String?, alsoStops: List<String> = emptyList()): String =
    (taf?.let { "${possessive(it)} ${g.vendor} tools stop working." } ?: "No taf uses this ${g.vendor} connection now. Its tools leave KIFF.") +
      when (alsoStops.size) {
        0 -> ""
        1 -> " ${possessive(alsoStops[0])} Card also uses these tools, so it stops too."
        else -> " The Cards of ${alsoStops.joinToString(" and ")} also use these tools, so they stop too."
      } +
      " Other connections to ${g.vendor} stay connected. If no other connection uses this sign-in, it's revoked at ${g.vendor}." +
      " Connecting again asks you to approve and sign in again."
}
