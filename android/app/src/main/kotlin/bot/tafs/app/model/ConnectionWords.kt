package bot.tafs.app.model

import bot.tafs.app.kiff.Grant

/** What a taf's connections say. */
object ConnectionWords {
  /** Where a connection stands. */
  fun status(g: Grant): String = when {
    g.connected -> "Connected"
    g.holdsSignIn -> "Signed in · Coach is choosing its tools"
    else -> g.status.replace('_', ' ').replaceFirstChar { it.uppercase() }
  }

  /** The tools the taf has through it, in words; Coach's own reads are not the taf's. */
  fun tools(g: Grant): String = g.request.tools.filter { it.tafs }.joinToString(" · ") { it.words }

  /** What planque last did, as it writes it ("Approved from tafs on Android phone"). */
  fun lastEvent(g: Grant): String? = g.events.lastOrNull()?.what?.takeIf { it.isNotBlank() }

  fun disconnectBody(g: Grant, taf: String): String =
    "${g.vendor}'s tools leave KIFF and the sign-in is revoked at the vendor, so $taf can't use them. Connecting again asks you to approve and sign in again."
}
