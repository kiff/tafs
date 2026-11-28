package bot.tafs.app.views

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import bot.tafs.app.design.tapping
import bot.tafs.app.model.Words
import bot.tafs.app.drive.secret
import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.driveTyped
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.drive.DriveAlert
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.tafsUsing
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.ConnectApps
import bot.tafs.app.kiff.displayName
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Connect apps' own words; every app's name and state comes from tafs cloud. */
/** Whom a Connect is for. */
sealed interface ConnectFor {
  /** The taf the list was opened from. */
  data class Taf(val id: String) : ConnectFor
  /** The person picks, in "Which taf is this for?". */
  data object Ask : ConnectFor
  /** No taf can have it: Connect is off, and "Make one first" says why. */
  data object NoTaf : ConnectFor
}

/**
 * A connection goes only to a taf the person named: the one Connect apps was opened from, else their pick, asked even when
 * one taf is all there is (as iOS does), `needed_by` only narrowing the list. Never a guess: a guessed taf takes an app its Card
 * never asked for. None at all is Ask Coach.
 */
fun connectFor(openedFrom: String?, connectable: List<String>): ConnectFor =
  if (openedFrom != null) ConnectFor.Taf(openedFrom) else if (connectable.isEmpty()) ConnectFor.NoTaf else ConnectFor.Ask

/**
 * The tafs "Which taf?" lists for [app]: those tafs cloud says need it (`needed_by`, by tafs cloud id), among the ones the person
 * manages here; every one of them from a tafs cloud before `needed_by`. tafs cloud decides; this only matches its ids.
 */
fun tafsFor(app: ConnectApps.App, connectable: List<bot.tafs.app.model.Taf>): List<bot.tafs.app.model.Taf> =
  app.neededBy?.map { it.taf }?.toSet()?.let { needed -> connectable.filter { it.cloudId in needed } } ?: connectable

object ConnectAppsWords {
  val TITLE get() = Words.Connect.Apps.title

  /**
   * An app's second line. For one of the account's own, who uses it, in tafs cloud's words (`used_by_words`): its status and
   * action are already beside it, so the line says only that ("Not used by any taf"). Sent empty (only a taf this person can't
   * see uses it), the line says how it connects and whose it is, never its status again. Not sent (unknown, or a tafs cloud
   * before it): tafs cloud's `line` as sent. Without either, the same words put together here: how it connects, whose it is when
   * tafs cloud says, and which tafs use it ([usedBy]).
   */
  fun secondLine(app: ConnectApps.App, usedBy: List<String>): String {
    val words = app.usedByWords?.takeIf { app.yours }
    if (words != null) {
      return words.ifBlank { listOfNotNull(if (app.byKey) Words.Connect.How.key else Words.Connect.How.signIn, app.whose?.takeIf { it.isNotBlank() }).joinToString(" · ") }
    }
    return app.line?.takeIf { it.isNotBlank() } ?: listOfNotNull(
      if (app.byKey) Words.Connect.How.key else Words.Connect.How.signIn,
      app.whose?.takeIf { it.isNotBlank() },
      // Where it stands, as on iPhone and iPad: connected, or to sign in again.
      if (app.connected) Words.Connect.Status.connected else if (app.needsReconnect) Words.Connect.signInAgain else null,
      // Only an app that's yours is used by a taf.
      usedBy.takeIf { app.yours && it.isNotEmpty() }?.let { Words.Connect.Apps.usedBy(tafs = bot.tafs.app.model.Res.list(it)) },
    ).joinToString(" · ")
  }
  val SEARCH get() = Words.Connect.Apps.search
  val CONNECTED get() = Words.Connect.Apps.connected
  val CONNECT get() = Words.Connect.Apps.connect
  val SIGN_IN_AGAIN get() = Words.Connect.signInAgain
  val NOT_LISTED get() = Words.Connect.Apps.notListed
  val NOT_LISTED_WHY get() = Words.Connect.Apps.notListedWhy
  val NONE get() = Words.Connect.Apps.none
  val MORE get() = Words.Connect.Apps.more
  val DISCONNECT get() = Words.Connect.disconnectButton

  /** A connection's row, under its taf's heading: the vendor account planque gives, else the app's name. */
  fun rowTitle(app: String, grant: bot.tafs.app.kiff.Grant): String = grant.account?.takeIf { it.isNotBlank() } ?: app

  /** A connection that needs the person: a sign-in waiting, signed out, or a last call that failed. Its taf comes first. */
  fun needsAttention(state: AppState, grant: bot.tafs.app.kiff.Grant): Boolean =
    grant.waitingSignIn || grant.needsReconnect || bot.tafs.app.model.ConnectionWords.lastFailure(grant, state.record) != null

  /** The other tafs whose live Card has a rule on one of its tools, as a warning above Disconnect; null when none. */
  fun alsoOnCards(state: AppState, grant: bot.tafs.app.kiff.Grant): String? =
    state.tafsUsing(grant, except = grant.request.agent).map { it.name }.takeIf { it.isNotEmpty() }
      ?.let { if (it.size == 1) Words.Connect.Apps.alsoOnOne(it[0]) else Words.Connect.Apps.alsoOnMany(bot.tafs.app.model.Res.list(it)) }

  /** The other tafs whose Card draws on a connection's tools: disconnecting it stops them too. */
  fun alsoStops(state: AppState, grant: bot.tafs.app.kiff.Grant): List<String> =
    state.tafsUsing(grant, except = grant.request.agent).map { it.name }

  /** The title over an app's connections. */
  fun yourConnections(app: String) = Words.Connect.Apps.yourConnections(app)

  /** What disconnecting one does, said before the fingerprint. */
  val DISCONNECT_WHY get() = Words.Connect.Apps.disconnectWhy

  /** The taf a connection was set up for, by name; null once that taf was removed. */
  fun tafOf(state: AppState, grant: bot.tafs.app.kiff.Grant): String? =
    state.tafs.firstOrNull { it.id == grant.request.agent && it.state != bot.tafs.app.model.TafState.revoked }?.name
}

/**
 * The account's live connections to [app]: by the app planque names for each (its `entry`, which planque gives every grant,
 * a vendor's own sign-in included), or the grant tafs cloud named for it.
 */
fun connectionsOf(state: AppState, app: ConnectApps.App): List<bot.tafs.app.kiff.Grant> =
  state.grants.filter { (it.entry == app.entry || it.id == app.grant) && it.status !in setOf("disconnected", "failed") }

/**
 * Whether an app's row opens its connections: when tafs cloud marks it connected, and also when the phone holds a connection
 * to it tafs cloud doesn't count as one, such as a sign-in kept but never approved (`listed`, a declined card's). That gives
 * no taf any access, so `connected` keeps its meaning and the app stays where tafs cloud puts it; but it's the owner's to end,
 * and only the connections' sheet has Disconnect.
 */
fun opensConnections(state: AppState, app: ConnectApps.App): Boolean = app.connected || connectionsOf(state, app).isNotEmpty()

/** An app's connections by taf: one section per taf, its name as the heading, with its connections in the order planque lists them. */
data class TafConnections(val taf: String, val grants: List<bot.tafs.app.kiff.Grant>)

/** An app's connections as the sheet shows them: [sections] by taf, then [removed] tafs', then [others]. */
data class ByTaf(val sections: List<TafConnections>, val removed: List<bot.tafs.app.kiff.Grant>, val others: List<bot.tafs.app.kiff.Grant>)

/**
 * [connections] in the order the sheet shows them: the tafs with one that needs the person first, then the rest by name; then the
 * removed tafs', only those whose agent tafs cloud names as removed (`removed_tafs`); then the rest, whose taf the app can't name.
 * A taf missing from this person's list isn't proof it was removed (one they don't see, an agent that isn't a taf), so only the
 * server's word puts a connection under removed. Only the order is the app's; every word on a row is planque's or KIFF's.
 */
fun byTaf(state: AppState, connections: List<bot.tafs.app.kiff.Grant>): ByTaf {
  val (live, rest) = connections.partition { ConnectAppsWords.tafOf(state, it) != null }
  val removedAgents = state.connectApps?.removedTafs.orEmpty().map { it.agent }.toSet()
  val (removed, others) = rest.partition { it.request.agent in removedAgents }
  val sections = live.groupBy { ConnectAppsWords.tafOf(state, it)!! }.map { (taf, grants) -> TafConnections(taf, grants) }
    .sortedWith(compareBy<TafConnections> { s -> if (s.grants.any { ConnectAppsWords.needsAttention(state, it) }) 0 else 1 }.thenBy { it.taf.lowercase() })
  return ByTaf(sections, removed, others)
}

/**
 * Connect apps (A1): tafs cloud's catalog, first as it orders it, then KIFF's own apps for Google, Microsoft and Slack. Each
 * row says whether it's connected; Connect starts one ([onConnect], only for someone who manages Cards). An app nobody listed
 * goes to Coach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectAppsSheet(
  state: AppState,
  load: suspend (String?) -> Unit,
  loadMore: suspend () -> Unit = {},
  /** Starts an app's connection: the app, the taf it's for (none to sign in again), and the owner's login where the app asks one. */
  onConnect: ((ConnectApps.App, String?, String?) -> Unit)?,
  onAskCoach: () -> Unit,
  onClose: () -> Unit,
  /** A signed-in app's next step: its taf's Coach, to choose its actions (the app, and its grant's taf when known). */
  onChooseActions: (ConnectApps.App) -> Unit = { onAskCoach() },
  /** Disconnects one of a connected app's connections (signed, with the owner's fingerprint); null for someone who can't. */
  onDisconnect: ((String) -> Unit)? = null,
  /** Clears the connections of tafs removed since, at once (one fingerprint); null for someone who can't. */
  onClearRemoved: ((List<String>) -> Unit)? = null,
  /** Gives a key-based connection a new key, or signs a sign-in one in again (gap 13); null for someone who can't. */
  onReplaceKey: ((bot.tafs.app.kiff.Grant) -> Unit)? = null,
  onSignInAgain: ((bot.tafs.app.kiff.Grant) -> Unit)? = null,
  // "Your Chrome" 2: planque's `use_another_chrome` (how "chrome"), to the Chrome picked; true when it moved.
  onUseAnotherChrome: (suspend (bot.tafs.app.kiff.Grant, bot.tafs.app.kiff.Chrome) -> Boolean)? = null,
  /** The connection that just came back signed in, and leaving that note. */
  onConnectedNow: (bot.tafs.app.kiff.Grant) -> Unit = {},
  dismissConnectedNow: () -> Unit = {},
  /** Reads planque's connections again: a connected row whose connections the phone doesn't hold reads them before it opens. */
  onReadConnections: suspend () -> Unit = {},
  /** A saved sign-in's Sign in again: planque's live sign-in view for that connection (docs/api.md, `site_sign_in`). */
  onSiteSignIn: ((String) -> Unit)? = null,
) {
  val readScope = androidx.compose.runtime.rememberCoroutineScope()
  var query by rememberSaveable { mutableStateOf("") }
  // An app is connected for a taf: with several, the owner picks which first; with none, there's nothing to connect it for.
  var picking by androidx.compose.runtime.remember { mutableStateOf<ConnectApps.App?>(null) }
  // An app whose key goes with the owner's login (`login`): its email, asked once the taf is chosen, before anything starts.
  var askingLogin by androidx.compose.runtime.remember { mutableStateOf<Pair<ConnectApps.App, String>?>(null) }
  val startFor: (ConnectApps.App, String) -> Unit = { app, taf -> if (app.login) askingLogin = app to taf else onConnect?.invoke(app, taf, null) }
  // A connected app's connections, each with the taf it serves (or none, once its taf was removed) and Disconnect.
  var managing by androidx.compose.runtime.remember { mutableStateOf<ConnectApps.App?>(null) }
  var confirmingDisconnect by androidx.compose.runtime.remember { mutableStateOf<bot.tafs.app.kiff.Grant?>(null) }
  var confirmingCancel by androidx.compose.runtime.remember { mutableStateOf<bot.tafs.app.kiff.Grant?>(null) }
  var confirmingClear by androidx.compose.runtime.remember { mutableStateOf<List<String>?>(null) }
  // A connection planque describes (title, who uses it, health, actions): its row opens this detail first, and a removal asks again.
  var detail by androidx.compose.runtime.remember { mutableStateOf<bot.tafs.app.kiff.Grant?>(null) }
  var confirmingRemove by androidx.compose.runtime.remember { mutableStateOf<Pair<bot.tafs.app.kiff.Grant, bot.tafs.app.kiff.GrantAction>?>(null) }
  // A connection whose Chrome is being picked, with planque's words for the action.
  var movingChrome by androidx.compose.runtime.remember { mutableStateOf<Pair<bot.tafs.app.kiff.Grant, String>?>(null) }
  var moving by androidx.compose.runtime.remember { mutableStateOf(false) }
  val tafs = state.connectableTafs
  val connect: ((ConnectApps.App) -> Unit)? = onConnect?.takeIf { tafs.isNotEmpty() }?.let { start ->
    { app ->
      // Signing in again keeps its grant's taf; a new connection goes to the taf the person picks, among those tafs cloud says
      // need the app, asked even with one.
      if (app.needsReconnect) start(app, null, null)
      else when (val to = connectFor(openedFrom = null, connectable = tafsFor(app, tafs).map { it.id })) {
        is ConnectFor.Taf -> startFor(app, to.id)
        ConnectFor.Ask -> picking = app
        ConnectFor.NoTaf -> onAskCoach()
      }
    }
  }
  // Read again as the search changes, once typing pauses.
  LaunchedEffect(query) {
    if (query.isNotEmpty()) delay(300)
    load(query.ifBlank { null })
  }
  DriveSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    // A sign-in that just came back: said at once, over the list, with the next step.
    state.connectedNow?.let { grant ->
      val taf = state.tafs.firstOrNull { it.id == grant.request.agent }?.name
      DriveAlert(
        onDismissRequest = dismissConnectedNow,
        containerColor = Theme.background,
        text = {
          Text(
            if (grant.listed) bot.tafs.app.model.ConnectWords.signedInFor(grant.vendor, taf) else bot.tafs.app.model.ConnectWords.connectedFor(grant.vendor, taf),
            style = scaled(16, color = Theme.ink),
          )
        },
        confirmButton = {
          if (grant.listed) {
            Text(
              bot.tafs.app.model.ConnectWords.CHOOSE_ACTIONS,
              style = scaled(15, FontWeight.SemiBold, Theme.amber),
              modifier = Modifier.heightIn(min = 44.dp).tapping { onConnectedNow(grant) }.padding(12.dp),
            )
          }
        },
        dismissButton = {
          Text(bot.tafs.app.model.ConnectWords.DONE, style = scaled(15, FontWeight.SemiBold, Theme.dim), modifier = Modifier.heightIn(min = 44.dp).tapping(onClick = dismissConnectedNow).padding(12.dp))
        },
      )
    }
    managing?.let { app ->
      val (sections, removed, others) = byTaf(state, connectionsOf(state, app))
      // Its last connection removed: the sheet closes, never "The apps couldn't be read." over a list that's simply empty now.
      val none = sections.isEmpty() && removed.isEmpty() && others.isEmpty()
      var hadSome by androidx.compose.runtime.remember(app.entry) { mutableStateOf(false) }
      LaunchedEffect(app.entry, none, state.connectionsError) {
        if (!none) hadSome = true else if (hadSome && closesWhenEmptied(state.connectionsError)) managing = null
      }
      DriveAlert(
        onDismissRequest = { managing = null },
        containerColor = Theme.background,
        title = { Text(ConnectAppsWords.yourConnections(app.displayName), style = scaled(20, FontWeight.Bold, Theme.ink)) },
        text = {
          Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // None on the phone even after reading again: why, in words (the read's failure, or that planque isn't set up here).
            if (none && !(hadSome && closesWhenEmptied(state.connectionsError))) {
              Text(state.connectionsError?.let { Words.Connect.appsNotRead(why = it) }
                ?: if (!state.planqueReady) bot.tafs.app.model.CoachWords.NOT_CONNECTABLE else Words.Connect.appsNotRead(why = ""),
                style = scaled(14, color = Theme.amberText))
            } else if (onDisconnect != null && sections.isNotEmpty()) Text(ConnectAppsWords.DISCONNECT_WHY, style = scaled(13, color = Theme.soft))
            // Each taf's name over its connections, so no row repeats it; then those whose taf the app can't name, under words that
            // claim nothing about it, with the same row.
            val groups = sections.map { it.taf to it.grants } + listOfNotNull(others.takeIf { it.isNotEmpty() }?.let { bot.tafs.app.model.ConnectionWords.OTHER_CONNECTIONS to it })
            groups.forEach { (heading, grants) ->
            Text(heading, style = scaled(13, FontWeight.SemiBold, Theme.dim), modifier = Modifier.padding(top = 4.dp).semantics { heading() })
            grants.forEach { g ->
              // planque describes it: its title, who uses it and its health; the row opens its detail, where its actions are.
              // One tafs cloud says was made under a taf that doesn't use the app: said, and always the app's own row, so it can be ended.
              val misplaced = app.misplaced.firstOrNull { it.grant == g.id }
              if (g.rowFromPlanque && misplaced == null) { DescribedConnectionRow(g, app.displayName, state) { detail = g }; return@forEach }
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                  Text(ConnectAppsWords.rowTitle(app.displayName, g), style = scaled(16, FontWeight.SemiBold, Theme.ink))
                  misplaced?.let { Text(Words.Connect.misplaced(taf = it.name, app = app.displayName), style = scaled(13, color = Theme.amberText)) }
                  g.statusWords?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.dim)) }
                  ConnectAppsWords.alsoOnCards(state, g)?.let { Text(it, style = scaled(13, color = Theme.soft)) }
                  // Its last failed call, in KIFF's words (gap 13), and how to fix the connection.
                  bot.tafs.app.model.ConnectionWords.lastFailure(g, state.record)?.let { Text(it, style = scaled(13, color = Theme.no)) }
                  val fix = if (g.takesKey) onReplaceKey else onSignInAgain
                  // A provider's connection isn't signed in again in place: say how, before any fingerprint.
                  if (fix != null && g.signsInThroughProvider) Text(bot.tafs.app.model.ConnectionWords.throughProvider(g), style = scaled(13, color = Theme.soft))
                  else fix?.let { act ->
                    Text(
                      if (g.takesKey) bot.tafs.app.model.ConnectionWords.REPLACE_KEY else bot.tafs.app.model.ConnectionWords.SIGN_IN_AGAIN,
                      style = scaled(15, FontWeight.SemiBold, Theme.amberText),
                      modifier = Modifier.heightIn(min = 44.dp).confirms().tapping(enabled = state.connecting == null) { act(g) }.padding(top = 10.dp),
                    )
                  }
                  state.coachNotes[g.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
                }
                // A sign-in that never came back is cancelled, not disconnected: nothing was connected.
                // While one removal is on its way, its button says so and no other starts (as on iPhone and iPad).
                if (onDisconnect != null) Text(
                  if (state.disconnecting == g.id) Words.Connect.disconnecting else bot.tafs.app.model.ConnectionWords.endButton(g),
                  style = scaled(15, FontWeight.SemiBold, if (state.disconnecting == null) Theme.no else Theme.dim),
                  modifier = Modifier.heightIn(min = 44.dp).confirms()
                    .tapping(enabled = state.disconnecting == null) { if (g.cancellable) confirmingCancel = g else confirmingDisconnect = g }.padding(top = 10.dp),
                )
              }
            }
            }
            // Removed tafs' connections, as tafs cloud names them: no sign-in again and no Disconnect each, one action that clears them.
            if (removed.isNotEmpty()) {
              // What tafs cloud says of each: listed with the tafs whose Cards still use it, or not listed (its reads failed, or no
              // signed read), and then nothing is cleared.
              val listed = state.connectApps?.removedTafs.orEmpty().flatMap { it.grants.orEmpty() }.associateBy { it.grant }
              val clearable = removed.filter { listed[it.id]?.usedBy?.isEmpty() == true }.map { it.id }
              Text(bot.tafs.app.model.ConnectionWords.FROM_REMOVED, style = scaled(13, FontWeight.SemiBold, Theme.dim), modifier = Modifier.padding(top = 4.dp).semantics { heading() })
              removed.forEach { g ->
                Column {
                  Text(ConnectAppsWords.rowTitle(app.displayName, g), style = scaled(16, FontWeight.SemiBold, Theme.ink))
                  // The removed taf it was made for, by the name planque keeps.
                  g.agentName?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.soft)) }
                  g.statusWords?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.dim)) }
                  listed[g.id]?.usedBy?.let { users -> bot.tafs.app.model.ConnectionWords.usedBy(users.map { it.name }) }?.let { Text(it, style = scaled(13, color = Theme.soft)) }
                }
              }
              if (onClearRemoved != null && clearable.isNotEmpty()) Text(
                if (state.clearingRemoved) Words.Connect.Apps.clearing else bot.tafs.app.model.ConnectionWords.CLEAR_REMOVED,
                style = scaled(15, FontWeight.SemiBold, if (state.clearingRemoved) Theme.dim else Theme.no),
                modifier = Modifier.heightIn(min = 44.dp).confirms().tapping(enabled = !state.clearingRemoved) { confirmingClear = clearable }.padding(top = 10.dp),
              )
            }
            state.clearNote?.let { Text(it, style = scaled(13, color = Theme.ink), modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
          }
        },
        confirmButton = {},
        dismissButton = {
          Text(bot.tafs.app.model.ConnectWords.DONE, style = scaled(15, FontWeight.SemiBold, Theme.dim), modifier = Modifier.heightIn(min = 44.dp).tapping { managing = null }.padding(12.dp))
        },
      )
    }
    // A connection's detail, as planque describes it: its health and who depends on it first, then only the actions planque sent.
    detail?.let { g ->
      val live = state.grants.firstOrNull { it.id == g.id } ?: g
      DriveAlert(
        onDismissRequest = { detail = null },
        containerColor = Theme.background,
        title = { Text(live.title?.takeIf { it.isNotBlank() } ?: ConnectAppsWords.rowTitle(app0(state, live), live), style = scaled(20, FontWeight.Bold, Theme.ink)) },
        text = {
          Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            live.health?.words?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(15, FontWeight.SemiBold, if (live.health?.ok == true) Theme.ok else Theme.amberText)) }
            connectionChromeLine(live)?.let { Text(it, style = scaled(14, color = Theme.soft)) }
            live.usedBy?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { bot.tafs.app.model.ConnectionWords.usedBy(it) }?.let { Text(it, style = scaled(14, color = Theme.soft)) }
            state.coachNotes[live.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
            live.actions.orEmpty().forEach { a ->
              val act: (() -> Unit)? = when (a.how) {
                "approve" -> onSignInAgain?.let { f -> { f(live) } }
                "key" -> onReplaceKey?.let { f -> { detail = null; f(live) } }
                "delete" -> onDisconnect?.let { { confirmingRemove = live to a } }
                "chrome" -> onUseAnotherChrome?.let { { detail = null; movingChrome = live to a.words } }
                else -> null
              }
              act?.let { go ->
                Text(
                  if (a.how == "approve" && state.connecting == live.id) state.connectStep ?: a.words else a.words,
                  style = scaled(15, FontWeight.SemiBold, if (a.how == "delete") Theme.no else Theme.amberText),
                  modifier = Modifier.heightIn(min = 44.dp).confirms().tapping(enabled = state.connecting == null) { go() }.padding(top = 6.dp),
                )
              }
            }
          }
        },
        confirmButton = {},
        dismissButton = {
          Text(bot.tafs.app.model.ConnectWords.DONE, style = scaled(15, FontWeight.SemiBold, Theme.dim), modifier = Modifier.heightIn(min = 44.dp).tapping { detail = null }.padding(12.dp))
        },
      )
    }
    // Another Chrome: the account's other paired Chromes, by name; one tapped is signed for behind the fingerprint and moved.
    movingChrome?.let { (g, words) ->
      val others = chromesToMoveTo(g, state.chromes)
      DriveAlert(
        onDismissRequest = { if (!moving) movingChrome = null },
        containerColor = Theme.background,
        title = { Text(words, style = scaled(20, FontWeight.Bold, Theme.ink)) },
        text = {
          Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            connectionChromeLine(g)?.let { Text(it, style = scaled(14, color = Theme.soft)) }
            others.forEach { c ->
              Text(
                c.name,
                style = scaled(16, FontWeight.SemiBold, if (moving) Theme.dim else Theme.amberText),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("$CONNECTION_CHROME-${c.id}").confirms().tapping(enabled = !moving) {
                  moving = true
                  readScope.launch {
                    val moved = onUseAnotherChrome?.invoke(g, c) == true
                    moving = false
                    if (moved) movingChrome = null
                  }
                }.padding(vertical = 10.dp),
              )
            }
          }
        },
        confirmButton = {},
        dismissButton = {
          Text(bot.tafs.app.model.ConnectWords.DONE, style = scaled(15, FontWeight.SemiBold, Theme.dim), modifier = Modifier.heightIn(min = 44.dp).tapping(enabled = !moving) { movingChrome = null }.padding(12.dp))
        },
      )
    }
    // A removal planque offers asks once more, with planque's words for what stops; only then the fingerprint.
    confirmingRemove?.let { (g, a) ->
      DriveAlert(
        onDismissRequest = { confirmingRemove = null },
        containerColor = Theme.panel,
        title = { Text(a.words) },
        text = { a.stops?.takeIf { it.isNotBlank() }?.let { Text(it) } },
        confirmButton = {
          androidx.compose.material3.TextButton({ confirmingRemove = null; detail = null; onDisconnect?.invoke(g.id) }, modifier = Modifier.confirms()) { Text(a.words, color = Theme.no) }
        },
        dismissButton = { androidx.compose.material3.TextButton({ confirmingRemove = null }) { Text(Words.Common.cancel, color = Theme.soft) } },
      )
    }
    // Disconnect asks first, naming the one taf it ends; only then the fingerprint, in the same words.
    confirmingDisconnect?.let { g ->
      val taf = ConnectAppsWords.tafOf(state, g)
      DriveAlert(
        onDismissRequest = { confirmingDisconnect = null },
        containerColor = Theme.panel,
        title = { Text(bot.tafs.app.model.ConnectionWords.disconnectTitle(g, taf, ConnectAppsWords.alsoStops(state, g))) },
        text = { Text(bot.tafs.app.model.ConnectionWords.disconnectBody(g, taf, ConnectAppsWords.alsoStops(state, g))) },
        confirmButton = {
          androidx.compose.material3.TextButton({ confirmingDisconnect = null; onDisconnect?.invoke(g.id) }, modifier = Modifier.confirms()) { Text(ConnectAppsWords.DISCONNECT, color = Theme.no) }
        },
        dismissButton = { androidx.compose.material3.TextButton({ confirmingDisconnect = null }) { Text(Words.Common.cancel, color = Theme.soft) } },
      )
    }
    // Cancelling a sign-in that never came back: its own words, as on the taf's page.
    confirmingCancel?.let { g ->
      DriveAlert(
        onDismissRequest = { confirmingCancel = null },
        containerColor = Theme.panel,
        title = { Text(bot.tafs.app.model.ConnectionWords.cancelTitle(g)) },
        text = { Text(bot.tafs.app.model.ConnectionWords.cancelBody(g)) },
        confirmButton = {
          androidx.compose.material3.TextButton({ confirmingCancel = null; onDisconnect?.invoke(g.id) }, modifier = Modifier.confirms()) { Text(bot.tafs.app.model.ConnectionWords.cancelButton(g), color = Theme.no) }
        },
        dismissButton = { androidx.compose.material3.TextButton({ confirmingCancel = null }) { Text(bot.tafs.app.model.ConnectionWords.KEEP_WAITING, color = Theme.soft) } },
      )
    }
    // Clearing removed tafs' connections asks first, then one fingerprint for all of them.
    confirmingClear?.let { ids ->
      DriveAlert(
        onDismissRequest = { confirmingClear = null },
        containerColor = Theme.panel,
        title = { Text(bot.tafs.app.model.ConnectionWords.clearTitle(ids.size)) },
        text = { Text(bot.tafs.app.model.ConnectionWords.CLEAR_BODY) },
        confirmButton = {
          androidx.compose.material3.TextButton({ confirmingClear = null; onClearRemoved?.invoke(ids) }, modifier = Modifier.confirms()) { Text(bot.tafs.app.model.ConnectionWords.CLEAR, color = Theme.no) }
        },
        dismissButton = { androidx.compose.material3.TextButton({ confirmingClear = null }) { Text(Words.Common.cancel, color = Theme.soft) } },
      )
    }
    // Which taf: asked over the list, wherever Connect was tapped, before anything starts.
    picking?.let { app ->
      DriveAlert(
        onDismissRequest = { picking = null },
        containerColor = Theme.background,
        title = { Text(bot.tafs.app.model.ConnectWords.WHICH_TAF, style = scaled(20, FontWeight.Bold, Theme.ink)) },
        text = {
          Column {
            Text(bot.tafs.app.model.ConnectWords.oneTaf(app.displayName), style = scaled(14, color = Theme.soft))
            tafsFor(app, tafs).forEach { taf ->
              Text(
                taf.name,
                style = scaled(16, FontWeight.SemiBold, Theme.amber),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tapping { picking = null; startFor(app, taf.id) }.padding(top = 14.dp),
              )
            }
          }
        },
        confirmButton = {},
        dismissButton = {
          Text(Words.Common.cancel, style = scaled(15, FontWeight.SemiBold, Theme.dim), modifier = Modifier.heightIn(min = 44.dp).tapping { picking = null }.padding(12.dp))
        },
      )
    }
    // The owner's login at the vendor (`login`), in the app's words: one email field, then Continue starts the connection with it.
    // Whether it's a well-formed address is tafs cloud's to say (`login_invalid`, said on the list), never the phone's.
    askingLogin?.let { (app, taf) ->
      var login by androidx.compose.runtime.remember(app.entry) { mutableStateOf("") }
      DriveAlert(
        onDismissRequest = { askingLogin = null },
        containerColor = Theme.background,
        title = { Text(Words.Connect.loginTitle(app.displayName), style = scaled(20, FontWeight.Bold, Theme.ink)) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Words.Connect.loginWhat(app.displayName), style = scaled(14, color = Theme.soft))
            OutlinedTextField(
              value = login,
              onValueChange = bot.tafs.app.drive.driveTyped({ login = it }),
              placeholder = { Text(Words.Connect.loginPlaceholder) },
              singleLine = true,
              keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                autoCorrectEnabled = false,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
              ),
              modifier = Modifier.fillMaxWidth().testTag(CONNECT_LOGIN_FIELD),
            )
          }
        },
        confirmButton = {
          androidx.compose.material3.TextButton(
            { askingLogin = null; onConnect?.invoke(app, taf, login.trim()) },
            enabled = login.isNotBlank(),
          ) { Text(Words.Common.`continue`, color = if (login.isNotBlank()) Theme.amber else Theme.dim) }
        },
        dismissButton = { androidx.compose.material3.TextButton({ askingLogin = null }) { Text(Words.Common.cancel, color = Theme.soft) } },
      )
    }
    // At full height from the first frame: the list it's waiting for never makes it open small and then grow (the owner on Android 97).
    Column(Modifier.fillMaxSize().navigationBarsPadding().testTag(CONNECT_APPS_SHEET)) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(ConnectAppsWords.TITLE, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        if (!bot.tafs.app.drive.LocalDrawnBeside.current) Text(Words.Common.done, style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).tapping(onClick = onClose).padding(top = 8.dp))
      }
      Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(query, driveTyped({ query = it }), Modifier.fillMaxWidth(), placeholder = { Text(ConnectAppsWords.SEARCH) }, singleLine = true)
        state.connectAppsError?.let { Panel { Text(it, style = scaled(14, color = Theme.no)) } }
        val apps = state.connectApps
        // Not read yet: said where the list goes.
        if (apps == null && state.connectAppsError == null) Text(Words.Common.loading, style = scaled(14, color = Theme.dim), modifier = Modifier.padding(top = 8.dp))
        // Yours first (connected, signed in, or to sign in again), then Discover: tafs cloud's order within each.
        val (yours, discover) = apps?.apps.orEmpty().partition { it.yours }
        val row: @Composable (ConnectApps.App) -> Unit = { app ->
          // A connected row always opens: with none of its connections on the phone, they're read again first (Android 85 and iPad
          // 2239 left the row dead when the read had failed), and the sheet then says why when there are still none.
          // An app no taf needs (tafs cloud's `needed_by` empty) offers Ask Coach, which sets one up, in place of Connect.
          val connectHere = connect?.takeIf { app.needsReconnect || tafsFor(app, tafs).isNotEmpty() }
          AppRow(app, connectHere, onChooseActions, opens = opensConnections(state, app), onManage = { a: ConnectApps.App ->
            if (connectionsOf(state, a).isNotEmpty()) managing = a else readScope.launch { onReadConnections(); managing = a }
          }, usedBy = usedBy(state, app), onAskCoach = onAskCoach.takeIf { onConnect != null && connectHere == null },
            removing = state.disconnecting?.let { id -> connectionsOf(state, app).any { it.id == id } } == true)
        }
        // How a taf uses websites (docs/api.md, "The two ways"): both ways, in tafs cloud's words, above the apps, while not searching.
        if (query.isBlank()) apps?.ways?.forEach { WayCard(it) }
        // Each saved sign-in in the cloud browser, with its health as tafs cloud words it, and Sign in again where it needs the owner.
        if (query.isBlank()) apps?.savedSignIns?.forEach { SavedSignInRow(it, onSignIn = onSiteSignIn) }
        if (yours.isNotEmpty()) {
          bot.tafs.app.design.Eyebrow(Words.Connect.Apps.yours)
          yours.forEach { row(it) }
        }
        if (discover.isNotEmpty()) {
          if (yours.isNotEmpty()) bot.tafs.app.design.Eyebrow(Words.Connect.Apps.discover)
          // Above the apps to connect, where it holds: an app is connected for a taf, so with none they can't be yet. Never above
          // Yours, whose apps are already connected (an audit read it above "Stripe · Connected").
          if (onConnect != null && tafs.isEmpty()) Text(bot.tafs.app.model.ConnectWords.NO_TAF, style = scaled(14, color = Theme.soft))
          discover.forEach { row(it) }
        }
        if (apps != null && apps.apps.isEmpty() && query.isNotBlank()) Text(ConnectAppsWords.NONE, style = scaled(14, color = Theme.soft))
        // The managed provider's catalog comes a page at a time.
        if (apps?.nextCursor != null) {
          val more = androidx.compose.runtime.rememberCoroutineScope()
          Text(
            ConnectAppsWords.MORE,
            style = scaled(15, FontWeight.SemiBold, Theme.amber),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).tapping { more.launch { loadMore() } }.padding(top = 10.dp),
          )
        }
        if (apps?.byKey == true) {
          Panel {
            Text(
              ConnectAppsWords.NOT_LISTED,
              style = scaled(15, FontWeight.SemiBold, Theme.ink),
              modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).tapping(onClick = onAskCoach).padding(top = 10.dp),
            )
            Text(ConnectAppsWords.NOT_LISTED_WHY, style = scaled(13, color = Theme.soft))
          }
        }
      }
    }
  }
}

/** A saved sign-in on one site: the site, the tafs that use it, its health line, and Sign in again where it needs the owner. */
@Composable
internal fun SavedSignInRow(saved: ConnectApps.SavedSignIn, onSignIn: ((String) -> Unit)?) {
  Panel(modifier = Modifier.testTag("saved-sign-in-" + saved.grant + "-" + saved.site)) {
    Text(saved.site, style = scaled(15, FontWeight.SemiBold, Theme.ink))
    if (saved.usedBy.isNotEmpty()) Text(bot.tafs.app.model.Res.list(saved.usedBy), style = scaled(12, color = Theme.dim))
    Text(saved.health.words.line, style = scaled(13, FontWeight.Medium, if (saved.health.needsYou) Theme.amberText else Theme.soft))
    val action = saved.signIn?.title?.takeIf { it.isNotBlank() }
    if (saved.health.needsYou && action != null && onSignIn != null) {
      Text(action, style = scaled(14, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).tapping { onSignIn(saved.grant) }.padding(top = 8.dp))
    }
  }
}

/** One way a taf uses websites, as tafs cloud words it: its title and line, two facts, and its state on this account. */
@Composable
internal fun WayCard(way: ConnectApps.Way) {
  Panel(modifier = Modifier.testTag("way-" + way.way)) {
    Text(way.words.title, style = scaled(16, FontWeight.SemiBold, Theme.ink))
    if (way.words.line.isNotBlank()) Text(way.words.line, style = scaled(14, FontWeight.Medium, Theme.soft))
    way.words.facts.filter { it.isNotBlank() }.forEach { Text("· $it", style = scaled(13, color = Theme.soft)) }
    way.words.status?.takeIf { it.isNotBlank() }?.let {
      Text(it, style = scaled(12, FontWeight.Medium, Theme.ok), modifier = Modifier.padding(top = 2.dp))
    }
  }
}

@Composable
private fun AppRow(
  app: ConnectApps.App,
  onConnect: ((ConnectApps.App) -> Unit)?,
  onChooseActions: (ConnectApps.App) -> Unit = {},
  /** Whether the row opens its connections ([opensConnections]): connected, or holding a connection on the phone. */
  opens: Boolean = app.connected,
  onManage: ((ConnectApps.App) -> Unit)? = null,
  usedBy: List<String> = emptyList(),
  /** With no taf to connect it for, the way to make one: Coach. */
  onAskCoach: (() -> Unit)? = null,
  /** One of its connections is being removed: "Removing access…" in place of "Connected" until it's done. */
  removing: Boolean = false,
) {
  // A connected app's whole row opens its connections, not only its "Connected" label (an audit: "I tapped the whole Gmail
  // row and nothing happened"); so does one whose connection the phone holds though it isn't connected, a declined sign-in's,
  // which only that sheet can end (the owner's phone, 16 Nov).
  val opensRow = if (opens && onManage != null) Modifier.tapping(onClickLabel = Words.Connect.Apps.manage) { onManage(app) } else Modifier
  Panel(opensRow) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      // The app's logo from tafs cloud, small and as it is; without one, its initial on a tile, which is only a marker.
      val logo = bot.tafs.app.design.rememberLogo(app.logo)
      if (logo != null) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
          androidx.compose.foundation.Image(logo, contentDescription = null, modifier = Modifier.size(28.dp))
        }
      } else {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tileColor(app.displayName)), contentAlignment = Alignment.Center) {
          Text(app.displayName.take(1).uppercase(), style = scaled(15, FontWeight.ExtraBold, Color.White))
        }
      }
      // Its name alone, tafs cloud's, with what this entry of it is under it when sent ("Qonto" / "Invoicing: reads, …"): the
      // provider's own id for it means nothing to a person; an app not set up yet says so under it.
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(app.displayName, style = scaled(15, FontWeight.SemiBold, if (app.needsSetup) Theme.soft else Theme.ink))
        app.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.soft), maxLines = 2) }
        app.words?.takeIf { app.needsSetup && it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.soft)) }
        // The second line, as on iPhone and iPad: tafs cloud's own when it sends it; else the same words put together here.
        if (!app.needsSetup) Text(ConnectAppsWords.secondLine(app, usedBy), style = scaled(12, color = Theme.dim), maxLines = 2)
      }
      when {
        // A removal on its way: said, never "Connected", until planque answered and the list was read again.
        removing -> Text(
          Words.Connect.disconnecting,
          style = scaled(14, FontWeight.SemiBold, Theme.dim),
          modifier = Modifier.heightIn(min = 44.dp).padding(top = 12.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        )
        // Connected: its connections, each with Disconnect, a tap away.
        app.connected -> Text(
          ConnectAppsWords.CONNECTED,
          style = scaled(14, FontWeight.SemiBold, Theme.ok),
          modifier = Modifier.heightIn(min = 44.dp).padding(top = 12.dp),
        )
        // Not set up yet: nothing to tap, only tafs cloud's line under the name.
        app.needsSetup -> Unit
        // Signed in, its actions still to choose: the next step is the taf's Coach, not Connect again.
        app.signedIn -> Text(
          bot.tafs.app.model.ConnectWords.SIGNED_IN_ROW,
          style = scaled(14, FontWeight.SemiBold, Theme.amber),
          modifier = Modifier.heightIn(min = 44.dp).tapping { onChooseActions(app) }.padding(top = 10.dp),
        )
        onConnect != null -> Text(
          if (app.needsReconnect) ConnectAppsWords.SIGN_IN_AGAIN else ConnectAppsWords.CONNECT,
          style = scaled(15, FontWeight.SemiBold, if (app.needsReconnect) Theme.amberText else Theme.amber),
          modifier = Modifier.heightIn(min = 44.dp).tapping { onConnect(app) }.padding(top = 10.dp),
        )
        app.needsReconnect -> Text(ConnectAppsWords.SIGN_IN_AGAIN, style = scaled(14, color = Theme.amberText))
        // No taf to connect it for: the way to make one, in Coach (an audit found every such row dead).
        onAskCoach != null -> Text(
          Words.Coach.ask,
          style = scaled(15, FontWeight.SemiBold, Theme.amber),
          modifier = Modifier.heightIn(min = 44.dp).tapping { onAskCoach() }.padding(top = 10.dp),
        )
      }
    }
  }
}

/** A tile's colour, the same for an app every time. */
private fun tileColor(name: String): Color = Theme.tafColor(Math.floorMod(name.hashCode(), 6))

/**
 * The approval over Connect apps: the same as Coach's card. planque's words for the connection, KIFF's words for its profile
 * (Approve waits for them), then the signature, and only then the vendor's sign-in page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectApprovalSheet(
  state: AppState,
  grant: bot.tafs.app.kiff.Grant?,
  loadWords: suspend (String) -> Unit,
  onApprove: (bot.tafs.app.kiff.Grant) -> Unit,
  onClose: () -> Unit,
  /** An API-key connection: the key is typed here and given with the same confirmation, instead of a vendor's sign-in. */
  needsKey: Boolean = false,
  onGiveKey: (bot.tafs.app.kiff.Grant, String) -> Unit = { _, _ -> },
  /** The secure sheet's words for a key grant, from tafs cloud (docs/secure-connection.md); and Disconnect from its receipt. */
  loadSecure: suspend (String) -> Unit = {},
  onDisconnect: (String) -> Unit = {},
  /** Back on the sheet with no link from the vendor's page: the same page again, as Coach's card offers. */
  onReopen: () -> Unit = {},
  /** Its Try again, once it said its connection couldn't be read ([AppState.listApprovalNotRead]). */
  onRetry: () -> Unit = {},
) {
  // Kept only while the sheet is open, never saved: it goes to planque once.
  var key by androidx.compose.runtime.remember(grant?.id) { mutableStateOf("") }
  DriveSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    // Above the keyboard, and scrolled when it's taller than what's left: the key's field is never under it.
    Column(
      Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // A key grant: the secure sheet, every word tafs cloud's. Until they're read, or if they can't be, the sheet as before.
      val secure = grant?.takeIf { needsKey }?.let { g ->
        LaunchedEffect(g.id) { loadSecure(g.id) }
        state.secureSheets[g.id]
      }
      if (grant != null && secure != null) {
        val profiled = grant.request.profile != null
        val words = state.profileWordsFor(grant)
        if (profiled) LaunchedEffect(grant.id, state.profileDigest(grant)) { loadWords(grant.id) }
        SecureKeyContent(
          state, grant, secure,
          profileWords = {
            when {
              words != null -> ProfileWordsLines(words)
              state.profileWordsErrors[grant.id] != null -> Text(state.profileWordsErrors.getValue(grant.id), style = scaled(13, color = Theme.no))
              profiled -> Text(bot.tafs.app.model.CoachWords.READING_KIFF_WORDS, style = scaled(13, color = Theme.soft))
            }
          },
          ready = !profiled || words != null,
          receipt = state.keyReceipt,
          actions = SecureKeyActions(give = onGiveKey, disconnect = onDisconnect, close = onClose),
        )
        return@Column
      }
      Text(bot.tafs.app.model.ConnectWords.APPROVE_TITLE, style = scaled(24, FontWeight.Bold, Theme.ink, display = true))
      if (grant == null && state.listApprovalNotRead) {
        // Still not in planque's list a minute on: said, with Try again, never "Reading…" for ever.
        Text(Words.Connect.approveNotRead(why = state.connectionsError ?: Words.Error.onOurSide), style = scaled(14, color = Theme.no))
        Text(
          Words.Common.tryAgain,
          style = scaled(14, FontWeight.SemiBold, Theme.amberText),
          modifier = Modifier.heightIn(min = 44.dp).tapping { onRetry() }.padding(vertical = 10.dp).testTag("connect.approval.retry"),
        )
      } else if (grant == null) {
        Text(Words.Connect.readingAsks, style = scaled(14, color = Theme.soft))
      } else {
        // planque's own words for what it will do.
        Text(grant.text, style = scaled(15, color = Theme.ink))
        Text(grant.vendor, style = scaled(13, color = Theme.dim, mono = true))
        val profiled = grant.request.profile != null
        val words = state.profileWordsFor(grant)
        if (profiled) {
          LaunchedEffect(grant.id, state.profileDigest(grant)) { loadWords(grant.id) }
          when {
            words != null -> ProfileWordsLines(words)
            state.profileWordsErrors[grant.id] != null -> Text(state.profileWordsErrors.getValue(grant.id), style = scaled(13, color = Theme.no))
            else -> Text(bot.tafs.app.model.CoachWords.READING_KIFF_WORDS, style = scaled(13, color = Theme.soft))
          }
        }
        state.coachNotes[bot.tafs.app.model.ConnectWords.cardKey(grant.id)]?.let { Text(it, style = scaled(13, color = Theme.no)) }
        if (!grant.digestMatches) {
          Text(bot.tafs.app.model.CoachWords.DIGEST_MISMATCH, style = scaled(13, color = Theme.no))
        } else {
          val busy = state.connecting == grant.id
          if (needsKey) {
            // Where the key goes, in the app's words: this phone to planque, then only the vendor's host; never Coach.
            Text(Words.Connect.keyGoesTo(host = grant.keyHost), style = scaled(13, color = Theme.soft))
            val clipboard = androidx.compose.ui.platform.LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
            OutlinedTextField(
              key,
              driveTyped({ typed ->
                // More than one character at once is a paste: the clipboard, which other apps can read, is cleared at once.
                if (typed.length - key.length > 1) runCatching { clipboard?.clearPrimaryClip() }
                key = typed
              }),
              Modifier.fillMaxWidth().secret(),
              placeholder = { Text(bot.tafs.app.model.ConnectWords.KEY) },
              singleLine = true,
              enabled = !busy,
              visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
              keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false,
              ),
            )
            Text(Words.Connect.keyClipboard, style = scaled(12, color = Theme.dim))
          }
          ApprovalGate {
            val biometry = bot.tafs.app.design.LocalBiometry.current
            bot.tafs.app.design.PrimaryButton(
              when {
                busy -> state.connectStep ?: Words.Approval.approving
                needsKey -> bot.tafs.app.model.ConnectWords.giveKeyWith(biometry)
                else -> Words.Approval.approveWith(biometry)
              },
              { if (needsKey) onGiveKey(grant, key) else onApprove(grant) },
              enabled = !busy && (!profiled || words != null) && (!needsKey || key.isNotBlank()),
            )
          }
          // The vendor's page was left before it sent tafs back: open it again, or Not now below ends the wait.
          if (busy && state.vendorPageLeft) Text(
            reopenWords(grant.vendor),
            style = scaled(15, FontWeight.SemiBold, Theme.amberText),
            modifier = Modifier.heightIn(min = 44.dp).tapping(onClick = onReopen).padding(top = 6.dp),
          )
        }
        Text(
          bot.tafs.app.model.ConnectWords.CANCEL,
          style = scaled(15, FontWeight.SemiBold, Theme.dim),
          modifier = Modifier.heightIn(min = 44.dp).tapping(onClick = onClose).padding(top = 10.dp),
        )
      }
    }
  }
}

/**
 * The tafs that use [app]: tafs cloud's `used_by` when it sends it, else read here from this phone's own connections, each by
 * the taf it serves (`byTaf`), as the app's detail lists them.
 */
fun usedBy(state: AppState, app: ConnectApps.App): List<String> =
  app.usedBy?.map { it.name }?.filter { it.isNotBlank() }?.distinct()
    ?: byTaf(state, connectionsOf(state, app)).sections.map { it.taf }

/**
 * Which of the owner's Chromes a connection runs in ("Your Chrome" 2, docs/chrome.md): "In Work Mac", by the name planque gives
 * it. None for planque's hosted browser, any other connection, or a name planque didn't send. As iOS's connection page.
 */
internal fun connectionChromeLine(g: bot.tafs.app.kiff.Grant): String? {
  val b = g.request.browser ?: return null
  if (b.driver != bot.tafs.app.kiff.GrantBrowser.EXTENSION) return null
  return b.chromeName?.trim()?.takeIf { it.isNotEmpty() }?.let { Words.Chrome.heldIn(name = it) }
}

/** The account's paired Chromes a connection can be moved to: every one but the Chrome it runs in now, in planque's order. */
internal fun chromesToMoveTo(g: bot.tafs.app.kiff.Grant, chromes: List<bot.tafs.app.kiff.Chrome>?): List<bot.tafs.app.kiff.Chrome> =
  chromes.orEmpty().filter { it.id != g.request.browser?.chrome }

internal const val CONNECTION_CHROME = "connection-chrome"

/** The app a connection belongs to, by its Connect apps title, for a detail opened from its row. */
private fun app0(state: AppState, g: bot.tafs.app.kiff.Grant): String = bot.tafs.app.model.ConnectionWords.appName(g, state.connectApps)

/**
 * A connection as planque describes it (planque's `title`, `used_by`, `health`): no id, no button beside it. A tap opens its detail,
 * where planque's actions are; Remove is never beside the row.
 */
@Composable
internal fun DescribedConnectionRow(g: bot.tafs.app.kiff.Grant, app: String, state: AppState, onOpen: () -> Unit) {
  Column(
    Modifier.fillMaxWidth().heightIn(min = 44.dp).tapping(onClick = onOpen).padding(vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(g.title?.takeIf { it.isNotBlank() } ?: ConnectAppsWords.rowTitle(app, g), style = scaled(16, FontWeight.SemiBold, Theme.ink))
    g.usedBy?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { bot.tafs.app.model.ConnectionWords.usedBy(it) }?.let { Text(it, style = scaled(13, color = Theme.soft)) }
    g.health?.words?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, color = if (g.health?.ok == true) Theme.dim else Theme.amberText)) }
    state.coachNotes[g.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
}

/** A connections sheet emptied by a removal closes, unless the read failed: then it says why. */
internal fun closesWhenEmptied(connectionsError: String?): Boolean = connectionsError == null

/** The Connect apps sheet's content, for tests: it fills the sheet's height from the first frame. */
internal const val CONNECT_APPS_SHEET = "connect-apps-sheet"

/** The owner's login at the vendor, asked before an app with `login` starts. */
internal const val CONNECT_LOGIN_FIELD = "connect-login-field"
