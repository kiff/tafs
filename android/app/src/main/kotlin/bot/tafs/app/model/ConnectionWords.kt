package bot.tafs.app.model

import bot.tafs.app.kiff.Grant

/** What a taf's connections say. */
object ConnectionWords {
  /** Under a Card total whose currency waits on a tool that isn't connected (KIFF's `unit_words`), for whoever manages the taf. */
  const val RECONNECT_IN_COACH = "Reconnect it in Coach."

  /** Where a connection stands. */
  fun status(g: Grant): String = when {
    g.connected -> "Connected"
    g.needsReconnect -> signedOut(g)
    g.holdsSignIn -> "Signed in · Coach is choosing its tools"
    else -> g.status.replace('_', ' ').replaceFirstChar { it.uppercase() }
  }

  /** A connection its vendor signed out, in planque's words; before planque sends them, the app's. */
  fun signedOut(g: Grant): String = g.statusWords?.takeIf { it.isNotBlank() } ?: "Signed out by ${g.vendor} · sign in again"

  /** The tools the taf has through it, in words; Coach's own reads are not the taf's. */
  fun tools(g: Grant): String = g.request.tools.filter { it.tafs }.joinToString(" · ") { it.words }

  /** What planque last did, as it writes it ("Approved from tafs on Android phone"). */
  fun lastEvent(g: Grant): String? = g.events.lastOrNull()?.what?.takeIf { it.isNotBlank() }

  /** Disconnect's question, naming the one taf it ends ([taf], or none once its taf was removed); the fingerprint prompt says the same. */
  fun disconnectTitle(g: Grant, taf: String?): String = taf?.let { "Disconnect ${g.vendor} from $it?" } ?: "Disconnect ${g.vendor}?"

  /** What stops and what doesn't: only this taf's connection ends, and other tafs' connections to the vendor stay. */
  fun disconnectBody(g: Grant, taf: String?): String =
    (taf?.let { "$it's ${g.vendor} tools stop working." } ?: "No taf uses this ${g.vendor} connection now. Its tools leave KIFF.") +
      " Other tafs' ${g.vendor} connections stay connected. If no other taf uses this sign-in, it's revoked at ${g.vendor}." +
      " Connecting again asks you to approve and sign in again."
}
