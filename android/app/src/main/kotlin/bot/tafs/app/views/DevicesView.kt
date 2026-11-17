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
import androidx.compose.foundation.layout.fillMaxSize
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
import bot.tafs.app.kiff.Passkey
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
 * but this one. With [pair], "Add a device" scans a computer's, or a new phone's, QR ([scan]), asks whether the computer shows the same check
 * code, and on yes pairs it (docs/android-app.md, Pairing a computer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(
  state: AppState,
  refresh: suspend () -> Unit,
  remove: suspend (Device) -> Boolean,
  onClose: () -> Unit,
  addDevice: (() -> Unit)? = null,
  confirmPair: () -> Unit = {},
  cancelPair: () -> Unit = {},
  addPasskey: (() -> Unit)? = null,
  removePasskey: suspend (Passkey) -> Boolean = { false },
) {
  val scope = rememberCoroutineScope()
  var removing by remember { mutableStateOf<Device?>(null) }
  var removingPasskey by remember { mutableStateOf<Passkey?>(null) }
  // The device whose QR was read, while the person compares its code, and what came of it: the model's, so the sheet rebuilt
  // after Google's scanner or the fingerprint still shows them.
  val asking = state.pairAsking
  val pairLine = state.pairLine
  val pairing = state.pairBusy
  LaunchedEffect(Unit) { refresh() }
  // Back on screen after leaving it (Google's scanner, another app, the phone locked): read again, so a device added meanwhile
  // shows without closing Devices and opening it again.
  var wasAway by remember { mutableStateOf(false) }
  androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { wasAway = true }
  androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
    if (wasAway) {
      wasAway = false
      scope.launch { refresh() }
    }
  }
  DriveSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    // Full height from the first frame, as Connect apps: the list is short, and a half-height sheet left Home showing above it.
    Column(Modifier.fillMaxSize().navigationBarsPadding().testTag(DEVICES_SHEET)) {
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
        if (addDevice != null) {
          Text(
            Words.Settings.Devices.pair,
            style = scaled(15, FontWeight.SemiBold, if (pairing) Theme.dim else Theme.amber),
            modifier = Modifier.heightIn(min = 44.dp).testTag(PAIR_BUTTON).confirms().tapping(enabled = !pairing, onClick = addDevice)
              .padding(horizontal = 4.dp, vertical = 10.dp),
          )
        }
        Text(
          Words.Settings.Devices.note,
          style = scaled(13, color = Theme.dim),
          modifier = Modifier.padding(horizontal = 4.dp),
        )
        // The account's passkeys, as KIFF lists them: one on this phone lets a computer approve through it, by the computer's QR.
        if (addPasskey != null || state.passkeys.isNotEmpty()) {
          Text(Words.Settings.Devices.passkeysTitle, style = scaled(20, FontWeight.Bold, Theme.ink), modifier = Modifier.padding(start = 4.dp, top = 16.dp))
          state.passkeysError?.let { Panel { Text(it, style = scaled(14, color = Theme.no)) } }
          state.passkeyLine?.let { Panel(Modifier.testTag(PASSKEY_LINE)) { Text(it, style = scaled(14, color = Theme.ink)) } }
          state.passkeys.forEach { passkey ->
            Panel {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(passkey.name.ifEmpty { Words.Settings.Devices.unnamed }, style = scaled(15, FontWeight.SemiBold, Theme.ink), modifier = Modifier.weight(1f))
                if (passkey.removable) {
                  Text(Words.Settings.Devices.passkeyRemove, style = scaled(15, FontWeight.SemiBold, Theme.no), modifier = Modifier.heightIn(min = 44.dp).confirms().tapping { removingPasskey = passkey }.padding(top = 8.dp))
                } else {
                  // A computer's own passkey goes with its computer: signing the computer out removes it.
                  Text(Words.Settings.Devices.passkeyWithComputer, style = scaled(13, color = Theme.dim))
                }
              }
            }
          }
          // KIFF takes a passkey from owners and admins only: a member isn't offered one.
          if (addPasskey != null && state.passkeyMayAdd != false) {
            Text(
              Words.Settings.Devices.passkeyAdd,
              style = scaled(15, FontWeight.SemiBold, if (state.passkeyBusy) Theme.dim else Theme.amber),
              modifier = Modifier.heightIn(min = 44.dp).testTag(PASSKEY_BUTTON).confirms().tapping(enabled = !state.passkeyBusy, onClick = addPasskey)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            )
          }
          Text(Words.Settings.Devices.passkeyNote, style = scaled(13, color = Theme.dim), modifier = Modifier.padding(horizontal = 4.dp))
        }
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
  removingPasskey?.let { passkey ->
    DriveAlert(
      onDismissRequest = { removingPasskey = null },
      title = { Text(Words.Settings.Devices.passkeyRemoveNamed(name = passkey.name)) },
      confirmButton = {
        TextButton(onClick = { removingPasskey = null; scope.launch { removePasskey(passkey) } }, modifier = Modifier.confirms()) { Text(Words.Settings.Devices.passkeyRemove, color = Theme.no) }
      },
      dismissButton = { TextButton(onClick = { removingPasskey = null }) { Text(Words.Common.cancel) } },
    )
  }
  asking?.let { qr ->
    // The check code, before the fingerprint: a page showing its own QR can't get this phone to pair another key unnoticed.
    DriveAlert(
      onDismissRequest = cancelPair,
      // "Add a device", never its name: the name is only what it says it is, so it comes after the code (as on iOS).
      title = { Text(pairTitle(), modifier = Modifier.testTag(PAIR_TITLE)) },
      text = {
        // The check code first: the name is only what the other device says it is, so the code on its screen is what binds this
        // yes to the device in front of the person (16 Nov). Then the owner's line, the name as its claim, what yes gives.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          pairLines(qr).forEachIndexed { i, line ->
            if (i == 0) Text(line, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(PAIR_CHECK)) else Text(line)
          }
        }
      },
      confirmButton = {
        TextButton(onClick = confirmPair, modifier = Modifier.confirms()) { Text(Words.Settings.Devices.pairYes, color = Theme.amber) }
      },
      dismissButton = { TextButton(onClick = cancelPair) { Text(Words.Common.cancel) } },
    )
  }
}

/** The approve-pairing sheet's title: what's being done, with no name, so nothing the other device claims comes before the code. */
internal fun pairTitle(): String = Words.Settings.Devices.pair

/**
 * The approve-pairing sheet's lines, in order, as on iOS: the check code to compare; that it adds only this device and nothing
 * else changes; the name as the device's own claim ("It calls itself …"), never as proof; what saying yes gives; and that it's
 * only this one.
 */
internal fun pairLines(qr: PairQr): List<String> = listOf(
  if (qr.isPhone) Words.Settings.Devices.pairCheckPhone(code = qr.checkCode.orEmpty()) else Words.Settings.Devices.pairCheck(code = qr.checkCode.orEmpty()),
  Words.Settings.Devices.pairAddOnly,
  Words.Settings.Devices.pairClaims(name = qr.name),
  pairGives(qr),
  if (qr.isPhone) Words.Settings.Devices.pairOnlyNowPhone else Words.Settings.Devices.pairOnlyNow,
)

/** What pairing gives the computer, by what approves on it (§5.1's `kind`). */
internal fun pairGives(qr: PairQr): String = when {
  qr.isPhone -> Words.Settings.Devices.pairGivesPhone(device = qr.name)
  // A computer whose device key is in software never approves: it shows the tafs and sends each approval to this phone.
  qr.keyStore == "software" -> Words.Settings.Devices.pairGivesView(device = qr.name)
  // A Windows computer approves with Windows Hello as its own platform passkey (§2.6).
  qr.kind == "hello" || (qr.kind == "passkey" && qr.credential != null && qr.platform == "windows") -> Words.Settings.Devices.pairGivesHello(device = qr.name)
  qr.kind == "enclave" -> Words.Settings.Devices.pairGivesEnclave(device = qr.name)
  else -> Words.Settings.Devices.pairGivesPasskey(device = qr.name)
}

internal const val DEVICES_SHEET = "devices-sheet"
internal const val PAIR_BUTTON = "devices.pair"
internal const val PAIR_CHECK = "devices.pair.check"
internal const val PAIR_TITLE = "devices.pair.title"
internal const val PAIR_LINE = "devices.pair.line"
internal const val PASSKEY_BUTTON = "devices.passkey.add"
internal const val PASSKEY_LINE = "devices.passkey.line"
