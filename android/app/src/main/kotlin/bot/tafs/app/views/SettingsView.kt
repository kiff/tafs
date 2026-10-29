package bot.tafs.app.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import bot.tafs.app.model.AlertWords
import bot.tafs.app.model.UsageWords
import kotlinx.coroutines.launch

/** Settings: the account, how this phone is signed in, and what the app is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
  state: AppState,
  onSignIn: () -> Unit,
  onSignOut: suspend () -> Unit,
  onUsage: () -> Unit,
  onTurnOnAlerts: () -> Unit,
  onAlertSound: (Boolean) -> Unit,
  onDevices: () -> Unit,
  onPeople: () -> Unit,
  onUseDeviceZone: () -> Unit,
  onDeleteAccount: () -> Unit,
  onClose: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val uri = LocalUriHandler.current
  val context = LocalContext.current
  val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "–" }

  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Settings", style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 8.dp))
      }
      Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Section("Account") {
          Value("Mode", when (state.mode) {
            Mode.kiff -> "Signed in with KIFF"
            Mode.sample -> "Sample data"
            Mode.signedOut -> "Signed out"
          })
          if (state.mode == Mode.kiff) state.kiffAccount?.let { account ->
            Value("KIFF account", account.name)
            if (account.name != account.slug) Value("Account id", account.slug)
            account.email?.takeIf { it.isNotEmpty() }?.let { Value("Email", it) }
          }
          if (state.mode == Mode.sample) Action("Sign in with KIFF", Theme.ink, onSignIn)
          // The account's groups and who is in each.
          if (state.mode == Mode.kiff) Action("People & groups", Theme.ink, onPeople)
          if (state.mode == Mode.kiff) Action("Devices", Theme.ink, onDevices)
          // Another account this person is on: KIFF's sign-in page lists each one.
          if (state.mode == Mode.kiff) Action("Switch account…", Theme.ink, onSignIn)
          Action(if (state.mode == Mode.kiff) "Sign out of KIFF" else "Clear the sample", Theme.no) {
            scope.launch {
              onSignOut()
              onClose()
            }
          }
        }
        // A Card's day ends at midnight here, and every time the owner reads is in it; set from the phone as a signed call.
        if (state.mode == Mode.kiff) state.kiffAccount?.let { account ->
          val accountZone = account.timeZone ?: "UTC"
          val device = remember { java.time.ZoneId.systemDefault().id }
          Section("Time zone") {
            Value("Time zone", accountZone)
            if (accountZone != device) Action("Use this phone's: $device", Theme.amberText, onUseDeviceZone)
            state.timeZoneError?.let { Text(it, style = scaled(13, color = Theme.no), modifier = Modifier.padding(12.dp)) }
            Text("Holds' deadlines, Activity, the Record and what your tafs write use this time zone.", style = scaled(13, color = Theme.dim), modifier = Modifier.padding(12.dp))
          }
        }
        // A held call reaches this phone with tafs closed: only where the build has Firebase's settings.
        if (state.mode == Mode.kiff) {
          Section("Alerts") {
            Value("Alerts", AlertWords.status(state.alertsAvailable, state.alertsReady))
            if (state.alertsAvailable && !state.alertsReady) Action(if (state.turningOnAlerts) "Turning on…" else "Turn on alerts", Theme.amberText) { if (!state.turningOnAlerts) onTurnOnAlerts() }
            if (state.alertsAvailable && state.alertsReady) {
              Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Sound", style = scaled(16, color = Theme.ink))
                Switch(checked = state.alertSound, onCheckedChange = onAlertSound, colors = SwitchDefaults.colors(checkedTrackColor = Theme.ok))
              }
            }
            AlertWords.foot(state.alertsAvailable, state.canAnswerHolds)?.let { Text(it, style = scaled(13, color = Theme.dim), modifier = Modifier.padding(12.dp)) }
          }
        }
        // This period's tasks, and what the tafs did that counted.
        Section("Usage") {
          Action(UsageWords.TITLE, Theme.ink, onUsage)
        }
        Section("How it works") {
          Text(
            "Every action a taf takes goes through KIFF, which checks its Card before anything reaches a tool. Your answers are signed on this phone.",
            style = scaled(14, color = Theme.soft),
            modifier = Modifier.padding(12.dp),
          )
        }
        Section("Legal") {
          Action("Terms", Theme.amberText) { uri.openUri("https://kiff.dev/terms#tafs") }
          Action("Privacy policy", Theme.amberText) { uri.openUri("https://kiff.dev/privacy#tafs") }
        }
        Section("About") {
          Value("Version", version)
          Text("They taf. You kiff.", style = scaled(15, color = Theme.dim), modifier = Modifier.padding(12.dp))
        }
        // Set apart from everyday actions, and only for someone whose role may.
        if (state.canDeleteAccount) {
          Section("Delete account") {
            Action("Delete account…", Theme.no, onDeleteAccount)
          }
        }
      }
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title.uppercase(), style = scaled(12, color = Theme.dim, mono = true), modifier = Modifier.padding(horizontal = 4.dp))
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Theme.panel).border(BorderStroke(1.dp, Theme.line), shape)) {
      content()
    }
  }
}

@Composable
private fun Value(label: String, value: String) {
  Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = scaled(16, color = Theme.ink))
    Text(value, style = scaled(16, color = Theme.dim), modifier = Modifier.padding(start = 8.dp))
  }
  HorizontalDivider(color = Theme.line, modifier = Modifier.padding(start = 12.dp))
}

@Composable
private fun Action(label: String, color: Color, onClick: () -> Unit) {
  Text(
    label,
    style = scaled(16, color = color),
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
  )
  HorizontalDivider(color = Theme.line, modifier = Modifier.padding(start = 12.dp))
}
