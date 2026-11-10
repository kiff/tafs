package bot.tafs.app.views

import bot.tafs.app.model.Words
import bot.tafs.app.drive.driveTyped
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.drive.DriveAlert
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import bot.tafs.app.R
import bot.tafs.app.model.ThemePreference
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
  /** Ends the session at KIFF, then signs out here; false when KIFF didn't, and nothing changed. */
  onSignOut: suspend () -> Boolean,
  onSignInAgain: suspend () -> Boolean = { false },
  onUsage: () -> Unit,
  onTurnOnAlerts: () -> Unit,
  onAlertSound: (Boolean) -> Unit,
  onDevices: () -> Unit,
  onConnectApps: () -> Unit = {},
  onPeople: () -> Unit,
  onUseDeviceZone: () -> Unit,
  onDeleteAccount: () -> Unit,
  /** The person's own chats, for their own copy: offered as two files through the share sheet. */
  onExport: suspend () -> bot.tafs.app.model.ExportResult = { bot.tafs.app.model.ExportResult.NotExported("") },
  onClose: () -> Unit,
  onToggleAlerts: (Boolean) -> Unit = { if (it) onTurnOnAlerts() },
  onAlertKind: (String, Boolean) -> Unit = { _, _ -> },
  onLoadAlerts: () -> Unit = {},
  onLoadTimeZones: (String) -> Unit = {},
  onPickTimeZone: (String) -> Unit = {},
  onOpenAlertTaf: (String) -> Unit = {},
  onLoadSuggestions: () -> Unit = {},
  onShowSuggestions: (Boolean) -> Unit = {},
) {
  val scope = rememberCoroutineScope()
  val uri = LocalUriHandler.current
  val context = LocalContext.current
  val scroll = rememberScrollState()
  var confirming by remember { mutableStateOf<SignOut?>(null) }
  var zonePicker by remember { mutableStateOf(false) }
  var zoneQuery by remember { mutableStateOf("") }
  var answeringTafs by remember { mutableStateOf(false) }
  val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { versionWords(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it), it.versionName) } }.getOrNull() ?: "–" }
  LaunchedEffect(state.mode) { if (state.mode == Mode.kiff) { onLoadAlerts(); onLoadSuggestions() } }
  if (zonePicker) LaunchedEffect(zoneQuery) { kotlinx.coroutines.delay(250); onLoadTimeZones(zoneQuery) }

  DriveSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(Words.Settings.title, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        TextButton(onClose) { Text(Words.Common.done, color = Theme.amberText) }
      }
      Box(Modifier.weight(1f)) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Section(Words.Settings.yourAccount) {
            Value(Words.Settings.mode, when (state.mode) { Mode.kiff -> Words.Settings.modeKiff; Mode.sample -> Words.Settings.modeSample; Mode.signedOut -> Words.Settings.modeSignedOut })
            if (state.mode == Mode.kiff) state.kiffAccount?.let { account ->
              Value(Words.Settings.kiffAccount, account.name)
              Value(Words.Settings.accountId, account.slug)
              account.email?.takeIf(String::isNotEmpty)?.let { Value(Words.Settings.signedInAs, it) }
            }
            if (state.mode == Mode.sample) Action(Words.SignIn.withKiff, Theme.ink, onSignIn)
            if (state.approvalKeyLost) {
              Text(Words.Settings.signInAgainWhy, style = scaled(13, color = Theme.amberText), modifier = Modifier.padding(12.dp))
              Action(Words.Connect.signInAgain, Theme.amberText) { confirming = SignOut.SignInAgain }
            }
          }
          if (state.mode == Mode.kiff) Section(Words.Settings.peopleApps) {
            Action(Words.People.title, Theme.ink, onPeople)
            Action(Words.Settings.Devices.title, Theme.ink, onDevices)
            Action(ConnectAppsWords.TITLE, Theme.ink, onConnectApps)
          }
          if (state.mode == Mode.kiff) Section(Words.Settings.plan) { PlanRow(state, onUsage) }
          if (state.mode == Mode.sample) Section(Words.Usage.label) { Action(UsageWords.TITLE, Theme.ink, onUsage) }
          if (state.mode == Mode.kiff) {
            Section(Words.Settings.alerts) {
              LockScreenExample(AlertWords.WITH_SOUND, AlertWords.LOUD_EXAMPLE)
              LockScreenExample(AlertWords.SILENT, AlertWords.SILENT_EXAMPLE)
              val on = state.phoneAlerts?.enabled ?: state.alertsReady
              AccountSwitch(AlertWords.ON_THIS_PHONE, "", on, state.alertsAvailable && !state.phoneAlertsBusy && !state.turningOnAlerts, onToggleAlerts)
              AccountSwitch(AlertWords.SOUND, AlertWords.SOUND_WORDS, state.alertSound, on && !state.phoneAlertsBusy, onAlertSound)
              AlertWords.foot(state.alertsAvailable, state.canAnswerHolds)?.let { Text(it, style = scaled(13, color = Theme.dim), modifier = Modifier.padding(12.dp)) }
              state.phoneAlertsError?.let { Text(it, style = scaled(13, color = Theme.no), modifier = Modifier.padding(12.dp)) }
            }
            Section(AlertWords.WHAT_ALERTS) {
              val preferences = state.phoneAlerts
              if (preferences == null) Action(Words.Settings.readAlerts, Theme.amberText, onLoadAlerts)
              preferences?.kinds?.forEach { choice ->
                AccountSwitch(choice.title, choice.words, choice.enabled, preferences.enabled && !state.phoneAlertsBusy) { onAlertKind(choice.id, it) }
                if (choice.id == "answered" && preferences.answering.tafs.isNotEmpty()) Action(preferences.answering.title + " ›", Theme.amberText) { answeringTafs = true }
              }
            }
          }
          // What a new Coach conversation offers, as tafs cloud keeps it: its words say what the switch does now.
          if (state.mode == Mode.kiff && state.mayUseCoach) state.suggestions?.let { s ->
            Section(Words.Coach.title) {
              val manages = state.kiffAccount?.capabilities?.contains("cards.manage") == true
              AccountSwitch(Words.Settings.showSuggestions, s.words, s.show, manages && !state.suggestionsBusy, onShowSuggestions)
              state.suggestionsError?.let { Text(it, style = scaled(13, color = Theme.no), modifier = Modifier.padding(12.dp)) }
            }
          }
          Section(Words.Settings.themeTitle) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              ThemePreference.entries.forEach { choice ->
                Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (Theme.preference == choice) Theme.ink else Theme.raised)
                  .clickable { Theme.choose(context, choice) }.padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                  val color = if (Theme.preference == choice) Theme.background else Theme.ink
                  if (choice != ThemePreference.System) Icon(painterResource(if (choice == ThemePreference.Light) R.drawable.ic_sun else R.drawable.ic_moon), null, tint = color, modifier = Modifier.size(16.dp))
                  Text(choice.title, style = scaled(14, FontWeight.Medium, color))
                }
              }
            }
          }
          if (state.mode == Mode.kiff) Section(Words.Settings.timeZoneTitle) {
            Value(Words.Settings.timeZoneTitle, state.kiffAccount?.timeZone ?: "UTC")
            Action(Words.Settings.chooseTimeZone, Theme.amberText) { zonePicker = true }
            Action(Words.Settings.useDeviceZone(bot.tafs.app.model.ThisDevice.word, java.time.ZoneId.systemDefault().id), Theme.amberText, onUseDeviceZone)
            state.timeZoneError?.let { Text(it, style = scaled(13, color = Theme.no), modifier = Modifier.padding(12.dp)) }
          }
          if (state.mode == Mode.kiff) Section(Words.Settings.yourChats) { ExportChatsRow(onExport) }
          Section(Words.Settings.legal) {
            Action(Words.Settings.terms, Theme.amberText) { uri.openUri("https://kiff.dev/terms#tafs") }
            Action(Words.Settings.privacyPolicy, Theme.amberText) { uri.openUri("https://kiff.dev/privacy#tafs") }
          }
          Section(Words.Settings.about) { Value(Words.Settings.version, version); Text(Words.Home.tagline, style = scaled(15, color = Theme.dim), modifier = Modifier.padding(12.dp)) }
          if (state.canDeleteAccount) Section(Words.Settings.DeleteAccount.title) { Action(Words.Settings.DeleteAccount.row, Theme.no, onDeleteAccount) }
          Section(Words.Settings.accountActions) {
            // One account per phone: another account is signing out, then signing in with it.
            Action(if (state.mode == Mode.kiff) Words.Settings.signOut else Words.Settings.clearSample, Theme.no) {
              if (state.mode == Mode.kiff) confirming = SignOut.SignOut else scope.launch { if (onSignOut()) onClose() }
            }
            state.signOutError?.let { Text(it, style = scaled(13, color = Theme.no), modifier = Modifier.padding(12.dp)) }
          }
        }
        if (scroll.canScrollForward) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Theme.background))))
      }
    }
  }
  if (zonePicker) DriveAlert(
    onDismissRequest = { zonePicker = false }, title = { Text(Words.Settings.timeZoneTitle) }, containerColor = Theme.panel,
    text = { Column {
      androidx.compose.material3.OutlinedTextField(zoneQuery, driveTyped({ zoneQuery = it }), label = { Text(Words.Settings.searchTimeZones) }, singleLine = true, modifier = Modifier.fillMaxWidth())
      LazyColumn(Modifier.heightIn(max = 320.dp)) { items(state.timeZones, key = { it.id }) { zone ->
        TextButton({ zonePicker = false; onPickTimeZone(zone.id) }, Modifier.fillMaxWidth()) { Text(zone.title, color = Theme.ink, modifier = Modifier.fillMaxWidth()) }
      } }
    } }, confirmButton = { TextButton({ zonePicker = false }) { Text(Words.Common.done, color = Theme.amberText) } },
  )
  if (answeringTafs) DriveAlert(onDismissRequest = { answeringTafs = false }, title = { Text(AlertWords.ANSWERED) }, containerColor = Theme.panel,
    text = { Column { state.phoneAlerts?.answering?.tafs?.forEach { taf -> TextButton({ answeringTafs = false; onClose(); onOpenAlertTaf(taf.id) }) { Text(taf.name, color = Theme.amberText) } } } },
    confirmButton = { TextButton({ answeringTafs = false }) { Text(Words.Common.done, color = Theme.amberText) } })
  confirming?.let { way -> ConfirmSignOut(way, { confirming = null }) {
    confirming = null
    scope.launch { when (way) {
      SignOut.SignOut -> if (onSignOut()) onClose()
      SignOut.SignInAgain -> if (onSignInAgain()) onClose()
    } }
  } }
}

@Composable
private fun AccountSwitch(title: String, words: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) { Text(title, style = scaled(16, color = Theme.ink)); if (words.isNotBlank()) Text(words, style = scaled(13, color = Theme.dim)) }
    Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = Theme.ok), modifier = Modifier.semantics { contentDescription = title })
  }
}

/** The ways out of KIFF this phone offers, each confirmed first. */
enum class SignOut {
  SignOut, SignInAgain;

  val title: String get() = when (this) { SignOut -> Words.Settings.SignOutOfKiff.title; SignInAgain -> Words.Settings.SignInAgainConfirm.title }
  val text: String get() = when (this) { SignOut -> Words.Settings.SignOutOfKiff.bodyDevice(bot.tafs.app.model.ThisDevice.word); SignInAgain -> Words.Settings.SignInAgainConfirm.bodyDevice(bot.tafs.app.model.ThisDevice.word) }
  val confirm: String get() = when (this) { SignOut -> Words.Settings.signOut; SignInAgain -> Words.Settings.SignInAgainConfirm.confirm }
}

/** Asks before this phone signs out of KIFF, so a stray tap never ends the session. Cancel leaves everything as it is. */
@Composable
fun ConfirmSignOut(way: SignOut, onDismiss: () -> Unit, onConfirm: () -> Unit) {
  DriveAlert(
    onDismissRequest = onDismiss,
    title = { Text(way.title) },
    text = { Text(way.text) },
    confirmButton = { androidx.compose.material3.TextButton(onConfirm) { Text(way.confirm, color = Theme.no) } },
    dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text(Words.Common.cancel, color = Theme.soft) } },
    containerColor = Theme.panel,
  )
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

/** The plan's line as tafs cloud words it (`plan_line`), in its tone, and Plan & usage under it; just the title until it's read. */
@Composable
private fun PlanRow(state: AppState, onUsage: () -> Unit) {
  val balance = state.balance
  Column(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onUsage).padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    if (balance != null) {
      Text(balance.planLine, style = scaled(16, FontWeight.SemiBold, planColor(balance.planTone, calm = Theme.ink)))
      Text(UsageWords.TITLE, style = scaled(13, color = Theme.amberText))
    } else {
      Text(UsageWords.TITLE, style = scaled(16, color = Theme.ink))
    }
  }
}

/** A lock-screen alert, as an example: how it comes, and its words. */
@Composable
private fun LockScreenExample(how: String, words: String) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Theme.raised).padding(10.dp)) {
    Text("${Words.App.name} · ${how.lowercase()}", style = scaled(11, color = Theme.dim, mono = true))
    Text(words, style = scaled(14, color = Theme.ink))
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

/** The build, as Account shows it: "1.0 (26)", the version and then the build number. */
internal fun versionWords(build: Long, version: String?): String = "${version ?: "–"} ($build)"
