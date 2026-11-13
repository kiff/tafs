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

  /**
   * What the taf's setup read follows: each of its connections' id and planque's state. The setup is read again when it changes,
   * so tafs cloud's words for a connection ("Connected") never stay behind planque's (signed out, signed in again).
   */
  fun setupKey(tafId: String, grants: List<Grant>): List<String> =
    grants.filter { it.request.agent == tafId }.map { "${it.id}:${it.status}:${it.signedIn}" }

  /** Ends a grant whose sign-in never came back (it's never connected); the owner connects it again afterwards. */
  val CANCEL_SIGN_IN get() = Words.Common.cancel
  /**
   * The app as the person knows it: tafs cloud's Connect apps title for this connection when it's listed, else planque's vendor
   * name. Never a server's URL when either is known.
   */
  fun appName(g: Grant, apps: bot.tafs.app.kiff.ConnectApps?): String =
    apps?.apps?.firstOrNull { it.grant == g.id || (g.entry != null && it.entry == g.entry) }?.title?.takeIf { it.isNotBlank() } ?: g.vendor

  /** The taf a connection is for, by its name: the app's taf for that agent, else the name planque keeps; never its agent id. */
  fun tafName(g: Grant, taf: (String) -> String?): String? =
    taf(g.request.agent)?.takeIf { it.isNotBlank() } ?: g.agentName?.takeIf { it.isNotBlank() }

  /** The prompt read last before connecting: "Connect Stripe to Disputes", or "Connect Stripe" when the taf has no name yet. */
  fun connectPrompt(g: Grant, apps: bot.tafs.app.kiff.ConnectApps?, taf: (String) -> String?): String {
    val app = appName(g, apps)
    return tafName(g, taf)?.let { Words.Connect.Prompt.connect(vendor = app, agent = it) } ?: Words.Connect.Prompt.connectApp(vendor = app)
  }

  /** The same, for a connection by key: "Give Stripe its key for Disputes". */
  fun giveKeyPrompt(g: Grant, apps: bot.tafs.app.kiff.ConnectApps?, taf: (String) -> String?): String {
    val app = appName(g, apps)
    return tafName(g, taf)?.let { Words.Connect.Prompt.giveKey(vendor = app, agent = it) } ?: Words.Connect.Prompt.giveKeyApp(vendor = app)
  }

  fun cancelTitle(g: Grant): String = if (g.pendingRequest) Words.Connect.CancelRequest.title(g.vendor) else Words.Connect.CancelSignIn.title(g.vendor)

  /** The row's word, and the confirmation's, for what's cancelled: the request, or the sign-in. */
  fun cancelButton(g: Grant): String = if (g.pendingRequest) Words.Connect.CancelRequest.button else Words.Connect.CancelSignIn.button

  /** The row's word for ending a grant: Cancel for what never connected, else Remove access. */
  fun endButton(g: Grant): String = when {
    g.pendingRequest -> Words.Connect.CancelRequest.button
    g.waitingSignIn -> CANCEL_SIGN_IN
    else -> Words.Connect.disconnectButton
  }
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
    (taf?.let { Words.Connect.Disconnect.toolsStop(it, g.vendor) } ?: Words.Connect.Disconnect.noTafUses(g.vendor)) +
      when (alsoStops.size) {
        0 -> ""
        1 -> " " + Words.Connect.Disconnect.alsoCard(alsoStops[0])
        else -> " " + Words.Connect.Disconnect.alsoCards(PeopleWords.all(alsoStops))
      } +
      " " + (if (g.takesKey) Words.Connect.Disconnect.restKey(g.vendor) else Words.Connect.Disconnect.rest(g.vendor))
}
