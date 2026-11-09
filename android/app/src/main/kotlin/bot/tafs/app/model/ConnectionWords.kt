package bot.tafs.app.model

import bot.tafs.app.kiff.Grant

/** What a taf's connections say. */
object ConnectionWords {
  /** Under a Card total whose currency waits on a tool that isn't connected (KIFF's `unit_words`), for whoever manages the taf. */
  val RECONNECT_IN_COACH get() = Words.Connect.reconnectInCoach

  /**
   * A connection's last failed call (gap 13): the newest call through one of its tools that wasn't done, its taf's own first,
   * in KIFF's words for it. Null when none is in the record.
   */
  fun lastFailure(g: Grant, record: List<bot.tafs.app.model.RecordEntry>): String? {
    val actions = (g.tools ?: g.request.tools.filter { it.tafs }.map { it.action }).toSet()
    if (actions.isEmpty()) return null
    val failed = record.filter { it.kind == RecordEntry.Kind.notDone && it.action in actions }
    val last = (failed.filter { it.tafId == g.request.agent }.ifEmpty { failed }).maxByOrNull { it.at } ?: return null
    return Words.Connect.lastFailed(last.title, last.detail)
  }

  /** Ends a grant whose sign-in never came back (it's never connected); the owner connects it again afterwards. */
  val CANCEL_SIGN_IN get() = Words.Common.cancel
  fun cancelTitle(g: Grant): String = Words.Connect.CancelSignIn.title(g.vendor)
  fun cancelBody(g: Grant): String = Words.Connect.CancelSignIn.body(g.vendor)

  /** A sign-in that never came back, cancelled: its confirmation's buttons. */
  val CANCEL_IT get() = Words.Connect.CancelSignIn.button
  val KEEP_WAITING get() = Words.Connect.CancelSignIn.keepWaiting

  /**
   * Connect apps by taf (the owner's ask): the connections whose taf the app can't name and tafs cloud doesn't name as removed, last.
   * That may be a taf this person doesn't see, an agent that isn't a taf, or a taf removed before tafs cloud named them, so the words
   * claim none of them.
   */
  val OTHER_CONNECTIONS get() = Words.Connect.otherConnections

  // The connections of tafs tafs cloud names as removed (`removed_tafs`), under their own heading, cleared at once.
  val FROM_REMOVED get() = Words.Connect.fromRemoved
  val CLEAR_REMOVED get() = Words.Connect.ClearRemoved.button
  fun clearTitle(n: Int): String = Words.Connect.ClearRemoved.title(count = n)
  val CLEAR_BODY get() = Words.Connect.ClearRemoved.body
  val CLEAR get() = Words.Connect.ClearRemoved.clear

  /**
   * How clearing went: "Cleared 2. Kept 1 for Chargebacks, whose Card uses it.", and what couldn't be, to try again. [kept]
   * connections were kept for [keptFor], the tafs whose Cards use them, by tafs cloud's names.
   */
  fun cleared(done: Int, kept: Int, keptFor: List<String>, left: Int): String {
    val names = keptFor.filter { it.isNotBlank() }.distinct()
    return listOfNotNull(
      Words.Connect.ClearRemoved.cleared(count = done).takeIf { done > 0 },
      keptLine(kept, names).takeIf { kept > 0 },
      Words.Connect.ClearRemoved.notCleared(count = left).takeIf { left > 0 },
    ).joinToString(" ")
  }

  /** A connection a live taf's Card still uses, as tafs cloud says (`used_by`): kept, and for whom. */
  fun keptLine(kept: Int, names: List<String>): String {
    val who = when (names.size) { 0 -> Words.Connect.Kept.anotherTaf; else -> PeopleWords.joined(names) }
    return if (names.size > 1) Words.Connect.Kept.forCards(count = kept, who = who) else Words.Connect.Kept.forCard(count = kept, who = who)
  }

  /** Under a removed taf's connection that Clear keeps: the tafs whose Cards still use it. */
  fun usedBy(names: List<String>): String? = names.filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }?.let { n ->
    if (n.size == 1) Words.Connect.UsedBy.one(n[0]) else Words.Connect.UsedBy.many(PeopleWords.joined(n))
  }

  val REPLACE_KEY get() = Words.Connect.replaceKey
  val SIGN_IN_AGAIN get() = Words.Connect.signInAgain

  /** In place of Sign in again on a provider's connection ([Grant.signsInThroughProvider]), as planque says it. */
  fun throughProvider(g: Grant): String =
    Words.Connect.throughProvider(g.vendor)

  /** Where a connection stands. */
  fun status(g: Grant): String = when {
    g.connected -> Words.Connect.Status.connected
    g.needsReconnect -> signedOut(g)
    g.holdsSignIn -> Words.Connect.Status.choosingTools
    g.waitingSignIn -> Words.Connect.Status.waitingSignIn(g.vendor)
    else -> g.status.replace('_', ' ').replaceFirstChar { it.uppercase() }
  }

  /** A connection its vendor signed out, in planque's words; before planque sends them, the app's. */
  fun signedOut(g: Grant): String = g.statusWords?.takeIf { it.isNotBlank() } ?: Words.Connect.Status.signedOut(g.vendor)

  /** The tools the taf has through it, in words; Coach's own reads are not the taf's. */
  fun tools(g: Grant): String = g.request.tools.filter { it.tafs }.joinToString(" · ") { it.words }

  /** What planque last did, as it writes it ("Approved from tafs on Android phone"). */
  fun lastEvent(g: Grant): String? = g.events.lastOrNull()?.what?.takeIf { it.isNotBlank() }

  /** A name's possessive: "Refunds'", "Invoice reminders'", "Chargebacks'"; else "Coach's". */
  fun possessive(name: String): String = if (name.endsWith("s")) Words.Common.possessiveS(name) else Words.Common.possessive(name)

  /**
   * Disconnect's question, naming the one taf it ends ([taf], or none once its taf was removed), and [alsoStops], the
   * other tafs whose Card draws on its tools, which stop too; the fingerprint prompt asks the same.
   */
  fun disconnectTitle(g: Grant, taf: String?, alsoStops: List<String> = emptyList()): String {
    val asked = taf?.let { Words.Connect.Disconnect.titleFrom(g.vendor, it) } ?: Words.Connect.Disconnect.title(g.vendor)
    return if (alsoStops.isEmpty()) asked else "$asked " + Words.Connect.Disconnect.alsoStops(count = alsoStops.size, names = PeopleWords.all(alsoStops))
  }

  /** What stops and what doesn't: this taf's connection ends, with any other taf whose Card draws on its tools; other connections to the vendor stay. */
  fun disconnectBody(g: Grant, taf: String?, alsoStops: List<String> = emptyList()): String =
    (taf?.let { Words.Connect.Disconnect.toolsStop(possessive(it), g.vendor) } ?: Words.Connect.Disconnect.noTafUses(g.vendor)) +
      when (alsoStops.size) {
        0 -> ""
        1 -> " " + Words.Connect.Disconnect.alsoCard(possessive(alsoStops[0]))
        else -> " " + Words.Connect.Disconnect.alsoCards(PeopleWords.all(alsoStops))
      } +
      " " + Words.Connect.Disconnect.rest(g.vendor)
}
