package bot.tafs.app.views

import bot.tafs.app.design.tapping
import bot.tafs.app.model.Words
import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.drive.DriveAlert
import android.text.format.DateUtils
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.Device
import bot.tafs.app.kiff.PairQr
import bot.tafs.app.kiff.Pairing
import bot.tafs.app.model.ErrorWords
import java.time.Instant
import kotlinx.coroutines.launch

/** "Last used 3 hours ago", or "Not used yet": a device says when it was last used; a session from before devices, when last seen. */
private fun lastUsed(device: Device): String {
  val at = (device.lastUsedAt ?: device.lastSeenAt)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return Words.Settings.Devices.notUsed
  val now = System.currentTimeMillis()
  if (now - at.toEpochMilli() < DateUtils.MINUTE_IN_MILLIS) return Words.Settings.Devices.usedJustNow
  return Words.Settings.Devices.lastUsed(DateUtils.getRelativeTimeSpanString(at.toEpochMilli(), now, DateUtils.MINUTE_IN_MILLIS).toString())
}

/**
 * Settings, Devices: the devices signed in as this person on the account, each with when it was last used, and Sign out for any
 * but this one. With [pair], "Pair a computer" scans a computer's QR ([scan]), asks whether the computer shows the same check
 * code, and on yes pairs it (docs/android-app.md, Pairing a computer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(
  state: AppState,
  refresh: suspend () -> Unit,
  remove: suspend (Device) -> Boolean,
  onClose: () -> Unit,
  scan: (suspend () -> String?)? = null,
  pair: (suspend (PairQr) -> Pairing?)? = null,
) {
  val scope = rememberCoroutineScope()
  var removing by remember { mutableStateOf<Device?>(null) }
  // The computer whose QR was read, while the person compares its check code; then what came of it.
  var asking by remember { mutableStateOf<PairQr?>(null) }
  var pairLine by remember { mutableStateOf<String?>(null) }
  var pairing by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { refresh() }
  DriveSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(Words.Settings.Devices.title, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        Text(Words.Common.done, style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).tapping(onClick = onClose).padding(top = 8.dp))
      }
      Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        state.devicesError?.let { Panel { Text(it, style = scaled(14, color = Theme.no)) } }
        pairLine?.let { Panel(Modifier.testTag(PAIR_LINE)) { Text(it, style = scaled(14, color = Theme.ink)) } }
        state.devices.forEach { device ->
          Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.shown ?: Words.Settings.Devices.unnamed, style = scaled(15, FontWeight.SemiBold, Theme.ink))
                Text(lastUsed(device), style = scaled(13, color = Theme.dim))
              }
              if (device.thisDevice) {
                Text(Words.Settings.Devices.thisDeviceTitle, style = scaled(13, color = Theme.dim))
              } else {
                Text(Words.Settings.Devices.signOut, style = scaled(15, FontWeight.SemiBold, Theme.no), modifier = Modifier.heightIn(min = 44.dp).confirms().tapping { removing = device }.padding(top = 8.dp))
              }
            }
          }
        }
        if (scan != null && pair != null) {
          Text(
            Words.Settings.Devices.pair,
            style = scaled(15, FontWeight.SemiBold, if (pairing) Theme.dim else Theme.amber),
            modifier = Modifier.heightIn(min = 44.dp).testTag(PAIR_BUTTON).confirms().tapping(enabled = !pairing) {
              pairLine = null
              scope.launch {
                val text = try {
                  scan()
                } catch (e: kotlinx.coroutines.CancellationException) {
                  throw e
                } catch (e: Exception) {
                  pairLine = Words.Settings.Devices.pairScannerFailed(why = ErrorWords.of(e))
                  return@launch
                } ?: return@launch
                PairQr.read(text)?.let { asking = it } ?: run { pairLine = Words.Settings.Devices.pairNotAPairing }
              }
            }.padding(horizontal = 4.dp, vertical = 10.dp),
          )
        }
        Text(
          Words.Settings.Devices.note,
          style = scaled(13, color = Theme.dim),
          modifier = Modifier.padding(horizontal = 4.dp),
        )
      }
    }
  }
  removing?.let { device ->
    DriveAlert(
      onDismissRequest = { removing = null },
      title = { Text(device.shown?.let { Words.Settings.Devices.signOutNamed(it) } ?: Words.Settings.Devices.signOutThis) },
      confirmButton = {
        TextButton(onClick = { removing = null; scope.launch { remove(device) } }, modifier = Modifier.confirms()) { Text(Words.Settings.Devices.signOut, color = Theme.no) }
      },
      dismissButton = { TextButton(onClick = { removing = null }) { Text(Words.Common.cancel) } },
    )
  }
  asking?.let { qr ->
    // The check code, before the fingerprint: a page showing its own QR can't get this phone to pair another key unnoticed.
    DriveAlert(
      onDismissRequest = { asking = null },
      title = { Text(Words.Settings.Devices.pairNamed(device = qr.name)) },
      text = {
        // What saying yes gives that computer, before the code: the code protects against a swapped QR only if the person
        // knows why it matters.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(pairGives(qr))
          Text(Words.Settings.Devices.pairOnlyNow)
          Text(Words.Settings.Devices.pairCheck(code = qr.checkCode.orEmpty()), fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(PAIR_CHECK))
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            asking = null
            pairing = true
            scope.launch {
              try {
                pair?.invoke(qr)?.let { pairLine = pairedLine(qr, it) }
              } finally {
                pairing = false
              }
            }
          },
          modifier = Modifier.confirms(),
        ) { Text(Words.Settings.Devices.pairYes, color = Theme.amber) }
      },
      dismissButton = { TextButton(onClick = { asking = null }) { Text(Words.Common.cancel) } },
    )
  }
}

/** What pairing gives the computer, by what approves on it (§5.1's `kind`). */
internal fun pairGives(qr: PairQr): String = when (qr.kind) {
  "hello" -> Words.Settings.Devices.pairGivesHello(device = qr.name)
  "enclave" -> Words.Settings.Devices.pairGivesEnclave(device = qr.name)
  else -> Words.Settings.Devices.pairGivesPasskey(device = qr.name)
}

/**
 * KIFF's answer, as the person reads it: paired with the code they checked, or, when KIFF paired another code than the one
 * shown, that, so they can sign the computer out.
 */
internal fun pairedLine(qr: PairQr, pairing: Pairing): String {
  val shown = qr.checkCode.orEmpty()
  return if (pairing.code.isNotBlank() && pairing.code != shown) {
    Words.Settings.Devices.pairCodeDiffers(device = qr.name, kiff = pairing.code, shown = shown)
  } else {
    Words.Settings.Devices.pairDone(device = qr.name, code = shown)
  }
}

internal const val PAIR_BUTTON = "devices.pair"
internal const val PAIR_CHECK = "devices.pair.check"
internal const val PAIR_LINE = "devices.pair.line"
