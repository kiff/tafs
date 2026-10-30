package bot.tafs.app.views

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
)

/**
 * A taf's connections, from planque: whose sign-ins, which tools, and what happened last. Disconnect removes the vendor's tools from
 * KIFF and revokes the sign-in at the vendor, after the owner's confirmation. Shown only where planque knows this phone.
 */
@Composable
fun ConnectionsPanel(taf: Taf, state: AppState, actions: ConnectionActions) {
  val scope = rememberCoroutineScope()
  var ending by remember { mutableStateOf<Grant?>(null) }
  LaunchedEffect(taf.id) { actions.refresh() }
  val connections = state.connections(taf.id)
  if (!state.planqueReady || connections.isEmpty()) return
  Panel {
    Eyebrow("Connected tools")
    connections.forEach { grant ->
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(grant.vendor, style = scaled(15, FontWeight.SemiBold, Theme.ink))
        Text(ConnectionWords.status(grant), style = scaled(13, color = if (grant.connected) Theme.ok else Theme.amberText))
        val tools = ConnectionWords.tools(grant)
        if (tools.isNotEmpty()) Text(tools, style = scaled(13, color = Theme.soft))
        ConnectionWords.lastEvent(grant)?.let { Text(it, style = scaled(12, color = Theme.dim)) }
        Text(
          "Disconnect",
          style = scaled(14, FontWeight.Medium, Theme.no),
          modifier = Modifier.heightIn(min = 44.dp).clickable { ending = grant }.padding(top = 8.dp),
        )
      }
    }
  }
  ending?.let { grant ->
    AlertDialog(
      onDismissRequest = { ending = null },
      title = { Text("Disconnect ${grant.vendor}?") },
      text = { Text(ConnectionWords.disconnectBody(grant, taf.name)) },
      confirmButton = { TextButton({ ending = null; scope.launch { actions.disconnect(grant.id) } }) { Text("Disconnect", color = Theme.no) } },
      dismissButton = { TextButton({ ending = null }) { Text("Cancel", color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
}
