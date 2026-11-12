package bot.tafs.app.views

import bot.tafs.app.model.Words
import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.DriveAlert
import androidx.compose.foundation.layout.fillMaxWidth
import bot.tafs.app.design.OutlineButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.Grant
import bot.tafs.app.model.ConnectionWords
import bot.tafs.app.model.Taf
import kotlinx.coroutines.launch

/** What a taf's connections ask of the model. */
class ConnectionActions(
  val refresh: suspend () -> Unit,
  val disconnect: suspend (String) -> Boolean,
  /** Signs in again to a connection its vendor signed out: one approval, then the vendor's page. */
  val reconnect: (Grant) -> Unit = {},
  val reconnectId: (String) -> Unit = {},
  /** Gives a key-based connection a new key, on the key sheet, behind the fingerprint (gap 13). */
  val replaceKey: (Grant) -> Unit = {},
  /** A browser connection's sign-in to a site: asks planque for its live view, by the connection's id. */
  val siteSignIn: suspend (String) -> Unit = {},
)

/**
 * A taf's connections, from planque: whose sign-ins, which tools, and what happened last. Disconnect removes the vendor's tools from
 * KIFF and revokes the sign-in at the vendor, after the owner's confirmation. Shown only where planque knows this phone.
 */
@Composable
fun ConnectionsPanel(taf: Taf, state: AppState, actions: ConnectionActions) {
  val scope = rememberCoroutineScope()
  var ending by remember { mutableStateOf<Grant?>(null) }
  var cancelling by remember { mutableStateOf<Grant?>(null) }
  LaunchedEffect(taf.id) { actions.refresh() }
  val connections = state.connections(taf.id)
  if (!state.planqueReady || connections.isEmpty()) return
  Panel {
    Eyebrow(Words.Connect.connectedTools)
    connections.forEach { grant ->
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(grant.vendor, style = scaled(15, FontWeight.SemiBold, Theme.ink))
        Text(ConnectionWords.status(grant), style = scaled(13, color = if (grant.connected) Theme.ok else Theme.amberText))
        val tools = ConnectionWords.tools(grant)
        if (tools.isNotEmpty()) Text(tools, style = scaled(13, color = Theme.soft))
        ConnectionWords.lastEvent(grant)?.let { Text(it, style = scaled(12, color = Theme.dim)) }
        if (grant.needsReconnect && state.approvalKeyLost) {
          ApprovalKeyLine()
        } else if (grant.needsReconnect) {
          val busy = state.connecting != null
          OutlineButton(
            if (state.connecting == grant.id) state.connectStep ?: Words.Connect.openingSignIn else Words.Connect.signInAgain,
            { actions.reconnect(grant) },
            Modifier.fillMaxWidth(),
            enabled = !busy,
          )
          state.coachNotes[grant.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
        }
        // A sign-in that never came back is cancelled, not disconnected: nothing was connected.
        Text(
          ConnectionWords.endButton(grant),
          style = scaled(14, FontWeight.Medium, Theme.no),
          modifier = Modifier.heightIn(min = 44.dp).confirms().clickable { if (grant.cancellable) cancelling = grant else ending = grant }.padding(top = 8.dp),
        )
      }
    }
  }
  cancelling?.let { grant ->
    DriveAlert(
      onDismissRequest = { cancelling = null },
      title = { Text(ConnectionWords.cancelTitle(grant)) },
      text = { Text(ConnectionWords.cancelBody(grant)) },
      confirmButton = { TextButton({ cancelling = null; scope.launch { actions.disconnect(grant.id) } }, modifier = Modifier.confirms()) { Text(ConnectionWords.cancelButton(grant), color = Theme.no) } },
      dismissButton = { TextButton({ cancelling = null }) { Text(Words.Connect.CancelSignIn.keepWaiting, color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
  ending?.let { grant ->
    DriveAlert(
      onDismissRequest = { ending = null },
      title = { Text(ConnectionWords.disconnectTitle(grant, taf.name, ConnectAppsWords.alsoStops(state, grant))) },
      text = { Text(ConnectionWords.disconnectBody(grant, taf.name, ConnectAppsWords.alsoStops(state, grant))) },
      confirmButton = { TextButton({ ending = null; scope.launch { actions.disconnect(grant.id) } }, modifier = Modifier.confirms()) { Text(Words.Connect.disconnectButton, color = Theme.no) } },
      dismissButton = { TextButton({ ending = null }) { Text(Words.Common.cancel, color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
}
