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

  fun disconnectBody(g: Grant, taf: String): String =
    "${g.vendor}'s tools leave KIFF and the sign-in is revoked at the vendor, so $taf can't use them. Connecting again asks you to approve and sign in again."
}
