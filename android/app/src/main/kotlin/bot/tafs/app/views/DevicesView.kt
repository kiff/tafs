package bot.tafs.app.views

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.Device
import java.time.Instant
import kotlinx.coroutines.launch

/** "Last used 3 hours ago", or "Not used yet": a device says when it was last used; a session from before devices, when last seen. */
private fun lastUsed(device: Device): String {
  val at = (device.lastUsedAt ?: device.lastSeenAt)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "Not used yet"
  val now = System.currentTimeMillis()
  if (now - at.toEpochMilli() < DateUtils.MINUTE_IN_MILLIS) return "Last used just now"
  return "Last used " + DateUtils.getRelativeTimeSpanString(at.toEpochMilli(), now, DateUtils.MINUTE_IN_MILLIS)
}

/** Settings, Devices: the devices signed in as this person on the account, each with when it was last used, and Sign out for any but this one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(state: AppState, refresh: suspend () -> Unit, remove: suspend (Device) -> Boolean, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  var removing by remember { mutableStateOf<Device?>(null) }
  LaunchedEffect(Unit) { refresh() }
  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Devices", style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 8.dp))
      }
      Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        state.devicesError?.let { Panel { Text(it, style = scaled(14, color = Theme.no)) } }
        state.devices.forEach { device ->
          Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.name ?: "A device", style = scaled(15, FontWeight.SemiBold, Theme.ink))
                Text(lastUsed(device), style = scaled(13, color = Theme.dim))
              }
              if (device.thisDevice) {
                Text("This device", style = scaled(13, color = Theme.dim))
              } else {
                Text("Sign out", style = scaled(15, FontWeight.SemiBold, Theme.no), modifier = Modifier.heightIn(min = 44.dp).clickable { removing = device }.padding(top = 8.dp))
              }
            }
          }
        }
        Text(
          "Signing a device out ends its sign-in on this account at once. To sign out this phone, use Sign out of KIFF.",
          style = scaled(13, color = Theme.dim),
          modifier = Modifier.padding(horizontal = 4.dp),
        )
      }
    }
  }
  removing?.let { device ->
    AlertDialog(
      onDismissRequest = { removing = null },
      title = { Text("Sign ${device.name ?: "this device"} out?") },
      confirmButton = {
        TextButton(onClick = { removing = null; scope.launch { remove(device) } }) { Text("Sign out", color = Theme.no) }
      },
      dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
    )
  }
}
