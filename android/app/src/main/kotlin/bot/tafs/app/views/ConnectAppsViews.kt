package bot.tafs.app.views

import bot.tafs.app.drive.secret
import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.driveTyped
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.drive.DriveAlert
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Connect apps' own words; every app's name and state comes from tafs cloud. */
object ConnectAppsWords {
  const val TITLE = "Connect apps"
  const val SEARCH = "Search apps"
  const val CONNECTED = "Connected ✓"
  const val CONNECT = "Connect"
  const val SIGN_IN_AGAIN = "Sign in again"
  const val NOT_LISTED = "Not listed? Ask Coach"
  const val NOT_LISTED_WHY = "Coach can connect an app that isn't here, from its own docs."
  const val NONE = "No app by that name. Coach can connect one that isn't listed."
  const val MORE = "Show more apps"
  const val DISCONNECT = "Disconnect"

  /** A connection's row, under its taf's heading: the vendor account planque gives, else the app's name. */
  fun rowTitle(app: String, grant: bot.tafs.app.kiff.Grant): String = grant.account?.takeIf { it.isNotBlank() } ?: app

  /** A connection that needs the person: a sign-in waiting, signed out, or a last call that failed. Its taf comes first. */
  fun needsAttention(state: AppState, grant: bot.tafs.app.kiff.Grant): Boolean =
    grant.waitingSignIn || grant.needsReconnect || bot.tafs.app.model.ConnectionWords.lastFailure(grant, state.record) != null

  /** The other tafs whose live Card has a rule on one of its tools, as a warning above Disconnect; null when none. */
  fun alsoOnCards(state: AppState, grant: bot.tafs.app.kiff.Grant): String? =
    state.tafsUsing(grant, except = grant.request.agent).map { it.name }.takeIf { it.isNotEmpty() }
      ?.let { if (it.size == 1) "Also on ${bot.tafs.app.model.ConnectionWords.possessive(it[0])} Card" else "Also on the Cards of ${it.joinToString(" and ")}" }

  /** The other tafs whose Card draws on a connection's tools: disconnecting it stops them too. */
  fun alsoStops(state: AppState, grant: bot.tafs.app.kiff.Grant): List<String> =
    state.tafsUsing(grant, except = grant.request.agent).map { it.name }

  /** The title over an app's connections. */
  fun yourConnections(app: String) = "Your $app connections"

  /** What disconnecting one does, said before the fingerprint. */
  const val DISCONNECT_WHY = "Disconnecting one removes its tools from that taf only, and from any taf whose Card uses them. Other connections stay."

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
  onConnect: ((ConnectApps.App, String?) -> Unit)?,
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
  /** The connection that just came back signed in, and leaving that note. */
  onConnectedNow: (bot.tafs.app.kiff.Grant) -> Unit = {},
  dismissConnectedNow: () -> Unit = {},
) {
  var query by rememberSaveable { mutableStateOf("") }
  // An app is connected for a taf: with several, the owner picks which first; with none, there's nothing to connect it for.
  var picking by androidx.compose.runtime.remember { mutableStateOf<ConnectApps.App?>(null) }
  // A connected app's connections, each with the taf it serves (or none, once its taf was removed) and Disconnect.
  var managing by androidx.compose.runtime.remember { mutableStateOf<ConnectApps.App?>(null) }
  var confirmingDisconnect by androidx.compose.runtime.remember { mutableStateOf<bot.tafs.app.kiff.Grant?>(null) }
  var confirmingCancel by androidx.compose.runtime.remember { mutableStateOf<bot.tafs.app.kiff.Grant?>(null) }
  var confirmingClear by androidx.compose.runtime.remember { mutableStateOf<List<String>?>(null) }
  val tafs = state.connectableTafs
  val connect: ((ConnectApps.App) -> Unit)? = onConnect?.takeIf { tafs.isNotEmpty() }?.let { start ->
    { app -> if (tafs.size > 1 && !app.needsReconnect) picking = app else start(app, tafs.singleOrNull()?.id) }
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
              modifier = Modifier.heightIn(min = 44.dp).clickable { onConnectedNow(grant) }.padding(12.dp),
            )
          }
        },
        dismissButton = {
          Text(bot.tafs.app.model.ConnectWords.DONE, style = scaled(15, FontWeight.SemiBold, Theme.dim), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = dismissConnectedNow).padding(12.dp))
        },
      )
    }
    managing?.let { app ->
      val (sections, removed, others) = byTaf(state, connectionsOf(state, app))
      DriveAlert(
        onDismissRequest = { managing = null },
        containerColor = Theme.background,
        title = { Text(ConnectAppsWords.yourConnections(app.title), style = scaled(20, FontWeight.Bold, Theme.ink)) },
        text = {
          Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onDisconnect != null) Text(ConnectAppsWords.DISCONNECT_WHY, style = scaled(13, color = Theme.soft))
            // Each taf's name over its connections, so no row repeats it; then those whose taf the app can't name, under words that
            // claim nothing about it, with the same row.
            val groups = sections.map { it.taf to it.grants } + listOfNotNull(others.takeIf { it.isNotEmpty() }?.let { bot.tafs.app.model.ConnectionWords.OTHER_CONNECTIONS to it })
            groups.forEach { (heading, grants) ->
            Text(heading, style = scaled(13, FontWeight.SemiBold, Theme.dim), modifier = Modifier.padding(top = 4.dp).semantics { heading() })
            grants.forEach { g ->
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                  Text(ConnectAppsWords.rowTitle(app.title, g), style = scaled(16, FontWeight.SemiBold, Theme.ink))
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
                      modifier = Modifier.heightIn(min = 44.dp).confirms().clickable(enabled = state.connecting == null) { act(g) }.padding(top = 10.dp),
                    )
                  }
                  state.coachNotes[g.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
                }
                // A sign-in that never came back is cancelled, not disconnected: nothing was connected.
                if (onDisconnect != null) Text(
                  if (g.waitingSignIn) bot.tafs.app.model.ConnectionWords.CANCEL_SIGN_IN else ConnectAppsWords.DISCONNECT,
                  style = scaled(15, FontWeight.SemiBold, Theme.no),
                  modifier = Modifier.heightIn(min = 44.dp).confirms().clickable { if (g.waitingSignIn) confirmingCancel = g else confirmingDisconnect = g }.padding(top = 10.dp),
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
                  Text(ConnectAppsWords.rowTitle(app.title, g), style = scaled(16, FontWeight.SemiBold, Theme.ink))
                  // The removed taf it was made for, by the name planque keeps.
                  g.agentName?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.soft)) }
                  g.statusWords?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.dim)) }
                  listed[g.id]?.usedBy?.let { users -> bot.tafs.app.model.ConnectionWords.usedBy(users.map { it.name }) }?.let { Text(it, style = scaled(13, color = Theme.soft)) }
                }
              }
              if (onClearRemoved != null && clearable.isNotEmpty()) Text(
                if (state.clearingRemoved) "Clearing…" else bot.tafs.app.model.ConnectionWords.CLEAR_REMOVED,
                style = scaled(15, FontWeight.SemiBold, if (state.clearingRemoved) Theme.dim else Theme.no),
                modifier = Modifier.heightIn(min = 44.dp).confirms().clickable(enabled = !state.clearingRemoved) { confirmingClear = clearable }.padding(top = 10.dp),
              )
            }
            state.clearNote?.let { Text(it, style = scaled(13, color = Theme.ink), modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
          }
        },
        confirmButton = {},
        dismissButton = {
          Text(bot.tafs.app.model.ConnectWords.DONE, style = scaled(15, FontWeight.SemiBold, Theme.dim), modifier = Modifier.heightIn(min = 44.dp).clickable { managing = null }.padding(12.dp))
        },
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
        dismissButton = { androidx.compose.material3.TextButton({ confirmingDisconnect = null }) { Text("Cancel", color = Theme.soft) } },
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
          androidx.compose.material3.TextButton({ confirmingCancel = null; onDisconnect?.invoke(g.id) }, modifier = Modifier.confirms()) { Text(bot.tafs.app.model.ConnectionWords.CANCEL_IT, color = Theme.no) }
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
        dismissButton = { androidx.compose.material3.TextButton({ confirmingClear = null }) { Text("Cancel", color = Theme.soft) } },
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
            Text(bot.tafs.app.model.ConnectWords.oneTaf(app.title), style = scaled(14, color = Theme.soft))
            tafs.forEach { taf ->
              Text(
                taf.name,
                style = scaled(16, FontWeight.SemiBold, Theme.amber),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { picking = null; onConnect?.invoke(app, taf.id) }.padding(top = 14.dp),
              )
            }
          }
        },
        confirmButton = {},
        dismissButton = {
          Text("Cancel", style = scaled(15, FontWeight.SemiBold, Theme.dim), modifier = Modifier.heightIn(min = 44.dp).clickable { picking = null }.padding(12.dp))
        },
      )
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(ConnectAppsWords.TITLE, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 8.dp))
      }
      Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(query, driveTyped({ query = it }), Modifier.fillMaxWidth(), placeholder = { Text(ConnectAppsWords.SEARCH) }, singleLine = true)
        if (onConnect != null && tafs.isEmpty()) Text(bot.tafs.app.model.ConnectWords.NO_TAF, style = scaled(14, color = Theme.soft))
        state.connectAppsError?.let { Panel { Text(it, style = scaled(14, color = Theme.no)) } }
        val apps = state.connectApps
        apps?.apps?.forEach { app -> AppRow(app, connect, onChooseActions, onManage = if (connectionsOf(state, app).isNotEmpty()) ({ a: ConnectApps.App -> managing = a }) else null) }
        if (apps != null && apps.apps.isEmpty() && query.isNotBlank()) Text(ConnectAppsWords.NONE, style = scaled(14, color = Theme.soft))
        // The managed provider's catalog comes a page at a time.
        if (apps?.nextCursor != null) {
          val more = androidx.compose.runtime.rememberCoroutineScope()
          Text(
            ConnectAppsWords.MORE,
            style = scaled(15, FontWeight.SemiBold, Theme.amber),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { more.launch { loadMore() } }.padding(top = 10.dp),
          )
        }
        if (apps?.byKey == true) {
          Panel {
            Text(
              ConnectAppsWords.NOT_LISTED,
              style = scaled(15, FontWeight.SemiBold, Theme.ink),
              modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onAskCoach).padding(top = 10.dp),
            )
            Text(ConnectAppsWords.NOT_LISTED_WHY, style = scaled(13, color = Theme.soft))
          }
        }
      }
    }
  }
}

@Composable
private fun AppRow(app: ConnectApps.App, onConnect: ((ConnectApps.App) -> Unit)?, onChooseActions: (ConnectApps.App) -> Unit = {}, onManage: ((ConnectApps.App) -> Unit)? = null) {
  Panel {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      // The app's initial on a tile; tafs cloud names the app, the tile is only a marker.
      Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tileColor(app.title)), contentAlignment = Alignment.Center) {
        Text(app.title.take(1).uppercase(), style = scaled(15, FontWeight.ExtraBold, Color.White))
      }
      // Its title alone: the provider's own id for it means nothing to a person; an app not set up yet says so under it.
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(app.title, style = scaled(15, FontWeight.SemiBold, if (app.needsSetup) Theme.soft else Theme.ink))
        app.words?.takeIf { app.needsSetup && it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.soft)) }
      }
      when {
        // Connected: its connections, each with Disconnect, a tap away.
        app.connected -> Text(
          ConnectAppsWords.CONNECTED,
          style = scaled(14, FontWeight.SemiBold, Theme.ok),
          modifier = if (onManage != null) Modifier.heightIn(min = 44.dp).clickable(onClickLabel = "Manage") { onManage(app) }.padding(top = 12.dp) else Modifier,
        )
        // Not set up yet: nothing to tap, only tafs cloud's line under the name.
        app.needsSetup -> Unit
        // Signed in, its actions still to choose: the next step is the taf's Coach, not Connect again.
        app.signedIn -> Text(
          bot.tafs.app.model.ConnectWords.SIGNED_IN_ROW,
          style = scaled(14, FontWeight.SemiBold, Theme.amber),
          modifier = Modifier.heightIn(min = 44.dp).clickable { onChooseActions(app) }.padding(top = 10.dp),
        )
        onConnect != null -> Text(
          if (app.needsReconnect) ConnectAppsWords.SIGN_IN_AGAIN else ConnectAppsWords.CONNECT,
          style = scaled(15, FontWeight.SemiBold, if (app.needsReconnect) Theme.amberText else Theme.amber),
          modifier = Modifier.heightIn(min = 44.dp).clickable { onConnect(app) }.padding(top = 10.dp),
        )
        app.needsReconnect -> Text(ConnectAppsWords.SIGN_IN_AGAIN, style = scaled(14, color = Theme.amberText))
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
) {
  // Kept only while the sheet is open, never saved: it goes to planque once.
  var key by androidx.compose.runtime.remember(grant?.id) { mutableStateOf("") }
  DriveSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(bot.tafs.app.model.ConnectWords.APPROVE_TITLE, style = scaled(24, FontWeight.Bold, Theme.ink, display = true))
      if (grant == null) {
        Text("Reading what it asks for…", style = scaled(14, color = Theme.soft))
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
            OutlinedTextField(
              key, driveTyped({ key = it }), Modifier.fillMaxWidth().secret(),
              placeholder = { Text(bot.tafs.app.model.ConnectWords.KEY) },
              singleLine = true,
              enabled = !busy,
              visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
              keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false,
              ),
            )
          }
          ApprovalGate {
            val biometry = bot.tafs.app.design.LocalBiometry.current
            bot.tafs.app.design.PrimaryButton(
              when {
                busy -> state.connectStep ?: "Approving…"
                needsKey -> bot.tafs.app.model.ConnectWords.giveKeyWith(biometry)
                else -> "Approve with $biometry"
              },
              { if (needsKey) onGiveKey(grant, key) else onApprove(grant) },
              enabled = !busy && (!profiled || words != null) && (!needsKey || key.isNotBlank()),
            )
          }
        }
        Text(
          bot.tafs.app.model.ConnectWords.CANCEL,
          style = scaled(15, FontWeight.SemiBold, Theme.dim),
          modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 10.dp),
        )
      }
    }
  }
}
