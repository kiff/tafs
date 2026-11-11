package bot.tafs.app

import androidx.lifecycle.lifecycleScope
import bot.tafs.app.model.Words
import bot.tafs.app.drive.DriveAlert
import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import bot.tafs.app.push.AlertNotifier
import bot.tafs.app.push.AlertPayload
import kotlinx.coroutines.CompletableDeferred
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import bot.tafs.app.model.ThemePreference
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import bot.tafs.app.app.AppModel
import bot.tafs.app.app.Mode
import bot.tafs.app.app.SampleStore
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.signing.Biometry
import bot.tafs.app.views.AccountActions
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.CoachChatActions
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.FileActions
import bot.tafs.app.views.CoachScreen
import bot.tafs.app.views.DeleteAccountSheet
import bot.tafs.app.views.DevicesSheet
import bot.tafs.app.views.PeopleActions
import bot.tafs.app.views.PeopleGroupsSheet
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.HaltSheet
import bot.tafs.app.views.HomeView
import bot.tafs.app.views.TrialScreen
import bot.tafs.app.views.VersionActions
import bot.tafs.app.views.VersionsSheet
import bot.tafs.app.views.CreditsRunsSheet
import bot.tafs.app.views.UsageSheet
import bot.tafs.app.views.CoachInstalls
import bot.tafs.app.views.LocalAnswerPage
import bot.tafs.app.views.LocalImproveInCoach
import bot.tafs.app.views.LocalSignInAgain
import bot.tafs.app.views.ApproveAgain
import bot.tafs.app.views.LocalApproveAgain
import bot.tafs.app.views.LocalReceipt
import bot.tafs.app.views.CalendarView
import bot.tafs.app.views.OverviewView
import bot.tafs.app.views.LocalCoachInstalls
import bot.tafs.app.views.NeedsYouView
import bot.tafs.app.views.OnboardingView
import bot.tafs.app.views.RecordView
import bot.tafs.app.views.SettingsSheet
import bot.tafs.app.views.SignInSheet
import bot.tafs.app.views.TafActions
import bot.tafs.app.views.TafDetailView
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import bot.tafs.app.drive.driveStopZone

/** A FragmentActivity: the system's biometric prompt shows on one. */
class MainActivity : FragmentActivity() {
  private val app get() = application as TafsApplication

  /** The answer to the notification permission prompt, while it is up. */
  private var permissionAnswer: CompletableDeferred<Boolean>? = null
  private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> permissionAnswer?.complete(granted) }

  /** This activity in the drive's list of the app's windows. */
  private var driveEntry: bot.tafs.app.drive.DriveInput.Entry? = null

  /** The taf whose screen is open now, for what a driven step touched. */
  @Volatile
  var drivenTaf: String? = null

  /** Where a tapped alert asked to go, until the model has it. */
  private var openFromAlert by mutableStateOf<String?>(null)

  /** Play's flexible in-app update; null where Play isn't there to ask. */
  private val updates: bot.tafs.app.push.PlayUpdates? by lazy { runCatching { bot.tafs.app.push.PlayUpdates(this) }.getOrNull() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The words in this screen's language, which follows the app's own when the person picked one: a language changed while
    // the app was away recreates the screen, and with it this (docs/languages.md).
    bot.tafs.app.model.Res.install(bot.tafs.app.design.AndroidWords.of(this))
    // The bars follow the app's own Light/Dark/System choice from the first frame, not only the phone's setting; TafsTheme
    // keeps them in step when the choice changes.
    // Edge to edge without the window's deprecated bar colours (docs/android-app.md): the theme makes the bars transparent
    // below Android 15, which draws edge to edge on its own; here the app draws behind them and picks their icons' shade.
    val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val light = !ThemePreference.from(getSharedPreferences("tafs.display", MODE_PRIVATE).getString("theme", null)).dark(systemDark)
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).run {
      isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
    }
    // The prompt for approving shows on this activity while it is on screen.
    app.authenticator.attach(this)
    // So does Google Play's purchase sheet, where buying is on.
    app.playStore?.attach(this)
    // So does the one asking to show notifications.
    app.push.askPermission = {
      val answer = CompletableDeferred<Boolean>()
      permissionAnswer = answer
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
      answer.await()
    }
    openFromAlert = intent?.getStringExtra(AlertNotifier.EXTRA_OPEN)
    deliverSignIn(intent)
    val store = SampleStore(File(filesDir, "sample.json"))
    // While Coach drives, the person's input on this window is caught before any view has it (docs/drive.md), and Coach's back
    // is the activity's own: never past the first screen, which would leave the app.
    bot.tafs.app.drive.DriveInput.guard(window)
    driveEntry = bot.tafs.app.drive.DriveInput.Entry(window.decorView, window) {
      onBackPressedDispatcher.hasEnabledCallbacks().also { if (it) onBackPressedDispatcher.onBackPressed() }
    }.also { bot.tafs.app.drive.DriveInput.windows.add(0, it) }
    setContent {
      TafsTheme {
        val model: AppModel = viewModel(factory = AppModel.Factory(store, app.signer, app.kiff, app.cloud, app.secrets, app.deviceName, app.push, app.planque, app.vendorSignIn, app.purchases, app.playBilling, java.io.File(cacheDir, "attachments")))
        // Read again each time the app comes back: a fingerprint enrolled in Settings meanwhile is named at once.
        var biometry by remember { mutableStateOf(Biometry.of(this@MainActivity)) }
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { biometry = Biometry.of(this@MainActivity) }
        // Firebase gave the phone a new token: the model tells tafs cloud.
        DisposableEffect(model) {
          app.tokenListener = model::tokenArrived
          app.changeListener = { model.changed(it.change, it.tafId) }
          onDispose {
            app.tokenListener = null
            app.changeListener = null
          }
        }
        // The approval window (kiff/tafs#494) closes after 30 s in the background, and at once when the screen turns off.
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { model.wentToBackground() }
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) { model.cameToForeground() }
        DisposableEffect(model) {
          val screenOff = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
              model.stopDrive("locked")
              model.screenLocked()
            }
          }
          registerReceiver(screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
          onDispose { unregisterReceiver(screenOff) }
        }
        // Coach drives the app (docs/drive.md): this activity's windows are the drive's, while it's on screen.
        DisposableEffect(model) {
          val overlay = bot.tafs.app.drive.DriveOverlay(this@MainActivity, Theme.amber.toArgb()) { model.stopDrive("stopped") }
          model.driveHost.current = bot.tafs.app.drive.AndroidDriveHost(this@MainActivity, overlay) { drivenTaf }
          bot.tafs.app.drive.DriveInput.personsInput = { why -> model.stopDrive(why) }
          bot.tafs.app.drive.DriveInput.coachStep = { model.drive.stepInFlight }
          onDispose {
            model.driveHost.current = null
            overlay.show(bot.tafs.app.drive.DriveState.Off)
            bot.tafs.app.drive.DriveInput.personsInput = {}
          }
        }
        // The app leaving the screen ends a drive at once: a Custom Tab, a system dialog, the share sheet, Recents and Home all
        // pause this activity, and a translucent one never stops it. Its own dialogs and sheets don't pause it.
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { model.stopDrive("left_app") }
        LaunchedEffect(openFromAlert, model) {
          model.requestOpen(openFromAlert)
          openFromAlert = null
        }
        val logos = remember(model) { bot.tafs.app.design.LogoStore(java.io.File(cacheDir, "logos"), model::logo) }
        val stage = updates?.stage?.collectAsState()?.value ?: bot.tafs.app.push.UpdateStage.None
        val updateRow = bot.tafs.app.push.AppUpdateRow(stage, update = { updates?.start(this@MainActivity) }, restart = { updates?.restart() })
        CompositionLocalProvider(LocalBiometry provides biometry, bot.tafs.app.design.LocalLogos provides logos, bot.tafs.app.push.LocalAppUpdate provides updateRow) { RootView(model) }
      }
    }
  }

  // A vendor's sign-in waits while its tab is in front; the app back with no link from it means the tab was closed.
  override fun onPause() {
    super.onPause()
    app.vendorSignIn.paused()
  }

  override fun onResume() {
    super.onResume()
    app.vendorSignIn.resumed(lifecycleScope)
    // A newer tafs on the track is offered on Home; Play answers "none" for a build it didn't install.
    runCatching { updates?.check() }
  }

  /** An alert was tapped while the app was up. */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    openFromAlert = intent.getStringExtra(AlertNotifier.EXTRA_OPEN)
    deliverSignIn(intent)
  }

  /** A vendor's sign-in came back (`tafs://planque/signed-in?...`): the step waiting on it goes on. */
  private fun deliverSignIn(intent: Intent?) {
    val link = intent?.data ?: return
    if (link.scheme == "tafs" && link.host == "planque") app.vendorSignIn.deliver(link.toString())
  }

  override fun onDestroy() {
    driveEntry?.let { bot.tafs.app.drive.DriveInput.windows.remove(it) }
    bot.tafs.app.drive.DriveInput.unguard(window)
    app.authenticator.detach(this)
    app.push.askPermission = null
    runCatching { updates?.close() }
    super.onDestroy()
  }
}

enum class Tab(private val named: () -> String, val icon: Int) {
  Tafs({ Words.Home.title }, R.drawable.ic_stack),
  NeedsYou({ Words.NeedsYou.title }, R.drawable.ic_bell),
  Record({ Words.Record.title }, R.drawable.ic_list);

  /** Read each time, in the app's language now. */
  val title: String get() = named()
}

@Composable
fun RootView(model: AppModel) {
  val state by model.state.collectAsState()
  // Where the owner left off: signed in to KIFF, the saved sample, or the first screen.
  LaunchedEffect(model) { model.restore() }
  var signingIn by rememberSaveable { mutableStateOf(false) }
  val rootScope = rememberCoroutineScope()
  // The approval-key line's Sign in again asks first, as Settings' ways out of KIFF do.
  var confirmingSignInAgain by rememberSaveable { mutableStateOf(false) }
  // Whether this phone can approve, read again each time the app comes back (a fingerprint added in settings, say).
  val context = androidx.compose.ui.platform.LocalContext.current
  var readiness by remember { mutableStateOf(bot.tafs.app.views.approvalReadiness(context)) }
  val recheck = { readiness = bot.tafs.app.views.approvalReadiness(context) }
  androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { recheck() }
  // Before sign-in, only when this phone can't approve yet: Set up approvals on this device, or sign in without.
  var settingUpApprovals by rememberSaveable { mutableStateOf(false) }
  // A fingerprint added meanwhile: on to sign-in at once.
  LaunchedEffect(readiness, settingUpApprovals) {
    if (settingUpApprovals && readiness == bot.tafs.app.model.ApprovalReadiness.Ready) { settingUpApprovals = false; signingIn = true }
  }

  bot.tafs.app.views.ProvideApprovalReadiness(state.mode, readiness, recheck) {
  Box(Modifier.fillMaxSize().background(Theme.background)) {
    when {
      // Nothing draws until it is known, so the first screen never flashes up for someone signed in.
      !state.ready -> Unit
      !state.signedIn && settingUpApprovals -> bot.tafs.app.views.ApprovalsSetupScreen(
        readiness, onCheckAgain = recheck, onContinue = { settingUpApprovals = false; signingIn = true },
      )
      !state.signedIn -> OnboardingView(
        onSignIn = { if (readiness == bot.tafs.app.model.ApprovalReadiness.Ready) signingIn = true else settingUpApprovals = true },
        onSample = model::startSample,
      )
      else -> CompositionLocalProvider(
        LocalAnswerPage provides model::answerPage,
        LocalImproveInCoach provides (if (state.mayUseCoach) model::improveInCoach else null),
        LocalSignInAgain provides { confirmingSignInAgain = true },
        // Approve again in the browser, signed in to a KIFF that can re-link the phone; else the line offers Sign in again.
        LocalApproveAgain provides (
          if (state.mode == Mode.kiff && !state.relinkUnavailable) {
            ApproveAgain(state.relink, state.relinkProblem, model::startRelink, model::cancelRelink, model::nudgeSignIn)
          } else {
            null
          }
        ),
        // KIFF's receipt for a Record line, signed in: the sample has none.
        LocalReceipt provides (if (state.mode == Mode.kiff) model::receipt else null),
        bot.tafs.app.views.LocalCardUnitActions provides remember(model) {
          bot.tafs.app.views.CardUnitActions(discuss = model::discussCardUnit, dismiss = model::dismissCardUnit)
        },
        bot.tafs.app.views.LocalYourStepActions provides remember(model) { bot.tafs.app.views.YourStepActions(answer = model::answerYourStep) },
        LocalCoachInstalls provides remember(model) {
          CoachInstalls(
            begin = model::beginCoachInstall,
            use = { ready, shown -> model.useVersion(ready.version, ready.tafId, shown, approving = ready.call) },
            card = { version, taf -> model.versionCard(version, taf) },
            cancel = model::cancelSwitch,
          )
        },
      ) {
        SignedInView(model, onSignIn = { signingIn = true })
      }
    }
  }
  if (signingIn) SignInSheet(model, onClose = { signingIn = false })
  // Alerts were on for this account before this phone signed out, and didn't come back on: said once, with the way back.
  if (state.alertsOffSinceSignOut && state.lastError == null) {
    DriveAlert(
      onDismissRequest = model::dismissAlertsOff,
      title = { Text(Words.Settings.AlertsOff.titleDevice(bot.tafs.app.model.ThisDevice.word)) },
      text = { Text(Words.Settings.AlertsOff.text) },
      confirmButton = { TextButton({ model.dismissAlertsOff(); rootScope.launch { model.turnOnAlerts() } }) { Text(Words.Settings.AlertsOff.turnOn, color = Theme.amber) } },
      dismissButton = { TextButton(model::dismissAlertsOff) { Text(Words.Common.notNow, color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
  if (confirmingSignInAgain) {
    bot.tafs.app.views.ConfirmSignOut(bot.tafs.app.views.SignOut.SignInAgain, onDismiss = { confirmingSignInAgain = false }) {
      confirmingSignInAgain = false
      rootScope.launch { model.signOutAtKiff() }
    }
  }

  // Why something wasn't done, in words, until it is read.
  state.lastError?.let { error ->
    DriveAlert(
      onDismissRequest = model::dismissError,
      title = { Text(Words.Common.notDone) },
      text = { Text(error) },
      confirmButton = { TextButton(model::dismissError) { Text("OK", color = Theme.amber) } },
      containerColor = Theme.panel,
    )
  }
  // This device's time zone, asked once when the account's differs.
  val offer = state.timeZoneOffer
  if (offer != null && state.lastError == null && state.signedIn) {
    val zoneScope = rememberCoroutineScope()
    DriveAlert(
      onDismissRequest = {},
      title = { Text(Words.Settings.TimeZoneOffer.title(zone = offer)) },
      text = { Text(Words.Settings.TimeZoneOffer.textDevice(device = bot.tafs.app.model.ThisDevice.word, zone = state.kiffAccount?.timeZone ?: "UTC")) },
      confirmButton = { TextButton({ zoneScope.launch { model.answerTimeZoneOffer(use = true) } }) { Text(Words.Settings.TimeZoneOffer.use(zone = offer), color = Theme.amber) } },
      dismissButton = { TextButton({ zoneScope.launch { model.answerTimeZoneOffer(use = false) } }) { Text(Words.Common.notNow, color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
}
}

/** Whose tafs are on screen: the sample's, or a KIFF account's, by its tenant. */
internal fun accountShown(state: bot.tafs.app.app.AppState): String = "${state.mode}/${state.kiffAccount?.tenantId.orEmpty()}"

@Composable
private fun SignedInView(model: AppModel, onSignIn: () -> Unit) {
  val state by model.state.collectAsState()
  val scope = rememberCoroutineScope()
  var tab by rememberSaveable { mutableStateOf(Tab.Tafs) }
  // The taf open on the Tafs tab: the screens inside a tab keep its bar, as a navigation stack does.
  var openTaf by rememberSaveable { mutableStateOf<String?>(null) }
  // The taf a door's knock alert opened, to show on Activity once, where its knocks are answered.
  var openOnActivity by rememberSaveable { mutableStateOf<String?>(null) }
  var showingSettings by rememberSaveable { mutableStateOf(false) }
  var deletingAccount by rememberSaveable { mutableStateOf(false) }
  var showingDevices by rememberSaveable { mutableStateOf(false) }
  var showingConnectApps by rememberSaveable { mutableStateOf(false) }
  var showingPeople by rememberSaveable { mutableStateOf(false) }
  // The Halt screen, of one taf or (with none) of every taf; and Usage, the account's or one taf's.
  var haltOpen by rememberSaveable { mutableStateOf(false) }
  var haltTaf by rememberSaveable { mutableStateOf<String?>(null) }
  var usageOpen by rememberSaveable { mutableStateOf(false) }
  // A recipe's sheet, opened from Home with no taf.
  var recipeOpen by rememberSaveable { mutableStateOf<String?>(null) }
  var galleryOpen by rememberSaveable { mutableStateOf(false) }
  var versionsTaf by rememberSaveable { mutableStateOf<String?>(null) }
  val versionActions = remember(model) {
    VersionActions(
      load = model::loadVersions,
      prepare = { version, taf, redraft, stop -> model.prepareSwitch(version, taf, redraft, stop) },
      card = { version, taf -> model.versionCard(version, taf) },
      use = { version, taf, shown -> model.useVersion(version, taf, shown) },
      cancel = model::cancelSwitch,
      loadTrial = model::loadTrial,
      trialCard = { version, taf -> model.trialCard(version, taf) },
      startTrying = { version, taf, shown -> model.startTrying(version, taf, shown) },
      installTrial = model::installTrial,
      goBack = model::goBackFromTrial,
    )
  }
  // The taf whose open trial is on screen: its chat, in place of the taf's page.
  var trialTaf by rememberSaveable { mutableStateOf<String?>(null) }
  val account = remember(model) {
    AccountActions(
      halt = model::halt,
      refreshHalt = model::refreshHalt,
      resumeHalt = model::resumeHalt,
      refreshUsage = model::refreshUsage,
      buy = { model.buy(it) },
      restorePurchases = model::restorePurchases,
      askForPack = model::askForPack,
      withdrawRequest = model::withdrawRequest,
      approveRequest = { product, request -> model.buy(product, request) },
      declineRequest = model::declineRequest,
      raiseLimit = model::raiseLimit,
      readBalance = model::readBalance,
      showCreditsPeriod = model::showCreditsPeriod,
      openTafCredits = model::openTafCredits,
      moreTafCredits = model::moreTafCredits,
      openCreditsRun = model::openCreditsRun,
    )
  }
  val people = remember(model) {
    PeopleActions(
      refresh = model::refreshPeople,
      createGroup = { name -> model.changePeople(Words.People.createGroup(name = name)) { it.createGroup(name) } },
      renameGroup = { group, name -> model.changePeople(Words.People.renameGroup(group = group.name, name = name)) { it.renameGroup(group.id, name) } },
      deleteGroup = { group -> model.changePeople(Words.People.deleteGroup(group = group.name)) { it.deleteGroup(group.id) } },
      setMember = { group, subject, role ->
        val who = model.state.value.person(subject)?.label ?: subject
        val what = if (group.members.any { it.subject == subject }) (if (role == "manager") Words.People.makeRoleManager(who = who, group = group.name) else Words.People.makeRoleMember(who = who, group = group.name)) else Words.People.addToGroup(who = who, group = group.name)
        model.changePeople(what) { it.setGroupMember(group.id, subject, role) }
      },
      removeMember = { group, subject ->
        val who = model.state.value.person(subject)?.label ?: subject
        model.changePeople(Words.People.removeFromGroup(who = who, group = group.name)) { it.removeGroupMember(group.id, subject) }
      },
      shares = model::shares,
      setShares = { agent, name, add, remove -> model.setShares(agent, name, add, remove) },
    )
  }
  val context = androidx.compose.ui.platform.LocalContext.current
  val doorActions = remember(model) {
    DoorActions(
      load = model::loadDoors,
      add = { taf, app -> model.addDoor(taf, app) },
      loadSlackChannels = model::loadSlackChannels,
      addSlack = { taf, app, grant, channel -> model.addSlackDoor(taf, app, grant, channel) },
      addBot = { taf, app, grant -> model.addBotDoor(taf, app, grant) },
      setOpen = { taf, door, open -> model.setDoorOpen(taf, door, open) },
      remove = { taf, door -> model.removeDoor(taf, door) },
      pair = { taf, door, sender, name -> model.pairDoor(taf, door, sender, name) },
      unpair = { taf, door, key -> model.unpairDoor(taf, door, key) },
      answer = { taf, knock, action, say -> model.answerKnock(taf, knock, action, say) },
      dismissError = model::dismissDoorsError,
      loadKnob = model::loadKnob,
      runNow = { taf -> model.runNow(taf); model.loadKnob(taf) },
      approveOnce = { call -> model.answer(call, bot.tafs.app.model.Answer.ApproveOnce) },
      changeKnobSize = { taf, setting -> model.changeKnobSize(taf, setting) },
      confirmKnobChange = { taf -> model.confirmKnobChange(taf) },
      setPaused = model::setPaused,
      askCoach = model::improveInCoach,
      approveEvent = model::approveKnobEvent,
      openRimRequest = model::openRimRequest,
      removeFollowUp = model::removeFollowUp,
    )
  }
  // Coach: the account's Setup, in place of the list. Closing never ends a conversation.
  var coachOpen by rememberSaveable { mutableStateOf(false) }
  // Overview, on a tablet: beside the list, until a taf is picked.
  var overviewOpen by rememberSaveable { mutableStateOf(false) }
  // The calendar of the tafs' scheduled work (kiff/tafs#534): from the Overview on a tablet, from the home's header on a phone.
  var calendarOpen by rememberSaveable { mutableStateOf(false) }
  // Another account (signed in from the sample, or switched): the Tafs tab opens on its list, nothing of the last one left open.
  val whose = accountShown(state)
  var shownAccount by rememberSaveable { mutableStateOf(whose) }
  LaunchedEffect(whose) {
    if (whose == shownAccount) return@LaunchedEffect
    shownAccount = whose
    openTaf = null; trialTaf = null; versionsTaf = null; haltTaf = null; overviewOpen = false; calendarOpen = false; coachOpen = false
  }
  // Coach drives the app (docs/drive.md): its chat minimizes into the Coach button while it drives, and opens again at the end
  // with what it did. Each screen the person would see is Coach's `screen_shown`.
  val driving by model.drive.state.collectAsState()
  LaunchedEffect(driving::class) {
    when (driving) {
      is bot.tafs.app.drive.DriveState.On -> coachOpen = false
      is bot.tafs.app.drive.DriveState.Over -> { tab = Tab.Tafs; trialTaf = null; coachOpen = true }
      bot.tafs.app.drive.DriveState.Off -> Unit
    }
  }
  val drivingActivity = androidx.compose.ui.platform.LocalContext.current as? MainActivity
  LaunchedEffect(openTaf, state.tafs) { drivingActivity?.drivenTaf = state.tafs.firstOrNull { it.id == openTaf }?.name }
  LaunchedEffect(tab, openTaf, coachOpen, showingSettings, overviewOpen, calendarOpen) { bot.tafs.app.drive.DriveSignals.emit("screen_shown") }
  val detailState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
  val coachVisibility = remember { androidx.compose.animation.core.MutableTransitionState(false) }
  LaunchedEffect(coachOpen) { coachVisibility.targetState = coachOpen }
  val recordActions = remember(model) {
    bot.tafs.app.views.RecordActions(
      loadHint = model::loadRecordHint,
      search = { q, more -> model.searchRecord(q, more) },
      openCase = model::openRecordCase,
      closeCase = model::closeRecordCase,
      export = model::exportReceipts,
    )
  }
  val actions = remember(model) {
    TafActions(
      answer = { call, answer -> model.answer(call, answer) },
      togglePause = model::togglePause,
      revoke = model::revoke,
      loadMemory = model::loadMemory,
      forget = model::forget,
      loadChat = model::loadChat,
      sendChat = { text, tafId, withFiles, onNotSent -> model.sendChat(text, tafId, withFiles, onNotSent) },
      issuer = model.issuer,
      doors = doorActions,
      connections = ConnectionActions(refresh = model::refreshConnections, disconnect = model::disconnect, reconnect = model::startReconnect, reconnectId = model::startReconnectId, replaceKey = model::openReplaceKey, siteSignIn = model::openSiteSignIn),
      stopChat = model::stopChat,
      retryUnsent = model::retryUnsent,
      editUnsent = model::editUnsent,
      deleteUnsent = model::deleteUnsent,
      files = FileActions(attach = model::attach, refuse = model::refuseFile, remove = model::removeFile, open = model::openFile),
      runNow = { model.runNow(it) },
      loadReport = model::loadReport,
      loadWork = model::loadWork,
      setDraft = model::setDraft,
      sendAgain = model::sendAgain,
      askCoachAbout = model::askCoachAbout,
      openPlan = { usageOpen = true },
      setPromise = model::setPromise,
      openRequest = model::openRequest,
      setPaused = model::setPaused,
      remove = model::removeTaf,
      report = model::reportAnswer,
      setResults = model::setResults,
      loadSetup = model::loadTafSetup,
      loadActivity = model::loadActivityPage,
      loadRuns = model::loadRunPage,
      loadAllMemory = model::loadAllMemory,
      coach = CoachActions(
        answer = model::answerCoach,
        approve = model::startApproval,
        cancelSignIn = { (context.applicationContext as TafsApplication).vendorSignIn.cancel() },
        signInAgain = model::startSignInAgain,
        improve = model::improveTaf,
        openTaf = { coachOpen = false; openTaf = it },
        openVersions = { coachOpen = false; openTaf = it; versionsTaf = it },
        loadProfileWords = model::loadProfileWords,
        loadKnob = model::loadKnob,
        confirmKnob = model::confirmKnobChange,
        approveDrive = model::approveDrive,
      ),
    )
  }
  // A tapped alert goes where it said: a held call to Needs you, a request for tasks to Usage, a door's alert to its taf.
  LaunchedEffect(state.requestedOpen, state.tafs) {
    val open = state.requestedOpen ?: return@LaunchedEffect
    val cloudId = AlertPayload.tafOf(open)
    when {
      open == AlertPayload.NEEDS_YOU -> { tab = Tab.NeedsYou; showingSettings = false }
      open == AlertPayload.USAGE -> usageOpen = true
      cloudId != null -> {
        // The list may not be read yet: the taf opens once it's there, and an alert for a taf no longer listed opens the list.
        val taf = state.tafs.firstOrNull { it.cloudId == cloudId }
        if (taf == null && state.tafs.isEmpty()) return@LaunchedEffect
        tab = Tab.Tafs; showingSettings = false; coachOpen = false; openTaf = taf?.id
        if (AlertPayload.opensActivity(open)) openOnActivity = taf?.id
      }
    }
    model.openRequestTaken()
  }
  // Back in the app, KIFF is read again: a hold made meanwhile shows, and a failed refresh's tries start over. The first
  // resume is the launch, which reads already.
  var resumedOnce by remember { mutableStateOf(false) }
  androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
    if (resumedOnce) scope.launch { model.refresh() } else resumedOnce = true
  }
  // Back closes the open taf before it leaves the app.
  BackHandler(enabled = tab == Tab.Tafs && openTaf != null && !coachOpen) { openTaf = null }
  BackHandler(enabled = tab == Tab.Tafs && calendarOpen && openTaf == null && !coachOpen) { calendarOpen = false }
  BackHandler(enabled = tab == Tab.Tafs && trialTaf != null) { trialTaf = null }
  BackHandler(enabled = tab == Tab.Tafs && coachOpen) { coachOpen = false }
  // Coach asked for from another screen (a hold's "Change the limit in Coach mode"): it opens here, on the Tafs tab.
  LaunchedEffect(state.coachRequested) {
    if (state.coachRequested) {
      tab = Tab.Tafs
      coachOpen = true
      model.coachOpened()
    }
  }

  // The keyboard pushes the screen up, and the tab bar leaves while it is up, as iOS's does.
  val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
  // While Coach drives, the keyboard stays down: Coach types through the field itself, and an open keyboard could commit text
  // after a step ended, which would read as the person's (docs/drive.md, "Typing").
  val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
  LaunchedEffect(keyboardUp, bot.tafs.app.drive.DriveInput.driving) { if (keyboardUp && bot.tafs.app.drive.DriveInput.driving) keyboard?.hide() }
  // A tablet, either way up, or a large phone on its side: the list and what's open side by side.
  val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= WIDE_DP
  // The list beside what's open, folded away by the owner: kept across rotation.
  var listFolded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

  // The bar's two places that open rather than switch: Account (today's Settings) and Coach, for whoever may use it.
  val openAccount = { showingSettings = true }
  val openCoach: (() -> Unit)? = if (state.mayUseCoach) ({
    model.openCoachOn(if (tab == Tab.Tafs) openTaf else null); tab = Tab.Tafs; trialTaf = null; coachOpen = true
  }) else null
  // A tab tapped again goes back to its start, as a phone's tab bar does: Tafs, from a taf's screen, is the list again.
  val onTab: (Tab) -> Unit = { t ->
    if (tappedAgainGoesToStart(tab, t)) {
      openTaf = null; trialTaf = null; calendarOpen = false; overviewOpen = false; coachOpen = false
    }
    tab = t
  }
  Row(Modifier.fillMaxSize().background(Theme.background).imePadding()) {
    // Landscape and tablets: the same places as a rail on the left, Coach at its foot.
    if (wide) {
      TabRail(tab, state, onTab = onTab, onAccount = openAccount, onCoach = openCoach)
      Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.line))
    }
  Column(Modifier.weight(1f).fillMaxHeight()) {
    Box(Modifier.weight(1f)) {
      when (tab) {
        Tab.Tafs -> {
          val trying = trialTaf
          val home: @Composable () -> Unit = {
            HomeView(
              state,
              onNeedsYou = { tab = Tab.NeedsYou },
              // The sample plays its next round; signed in, KIFF is read again.
              onPlayNext = { if (state.mode == Mode.sample) model.playNextDemoActions() else model.refresh() },
              onOpenTaf = { coachOpen = false; trialTaf = null; overviewOpen = false; calendarOpen = false; openTaf = it },
              onCalendar = if (state.mode == Mode.kiff && state.tafs.isNotEmpty() && !wide) ({ coachOpen = false; trialTaf = null; calendarOpen = true }) else null,
              onHaltAll = { haltTaf = null; haltOpen = true },
              // A halted taf's chip: the account's Halt when the account is halted (that's what resumes it), else the taf's.
              onHalted = { id -> haltTaf = if (!state.haltOf(null).running) null else id; haltOpen = true },
              onUsage = { usageOpen = true },
              onRecipe = { recipeOpen = it.id },
              onNewTaf = if (state.mode == Mode.kiff && state.gallery != null) ({ galleryOpen = true }) else null,
            )
          }
          val detail: @Composable (String) -> Unit = { id ->
            detailState.SaveableStateProvider(id) { TafDetailView(
              id, state, actions, onBack = { openTaf = null },
              onHalt = { haltTaf = it; haltOpen = true },
              // A taf's Usage row opens its own credits this period: its runs, over its page.
              onUsage = { id -> state.taf(id)?.cloudId?.let { cloudId -> scope.launch { model.openTafCredits(cloudId) } } },
              onVersions = { versionsTaf = it },
              people = people,
              onConnectApps = { showingConnectApps = true },
              showBack = !wide,
              openActivity = openOnActivity == id,
              onActivityOpened = { if (openOnActivity == id) openOnActivity = null },
            ) }
          }
          val calendar: @Composable (Boolean) -> Unit = { phone ->
            CalendarView(
              state, onShow = model::showCalendar, load = model::loadCalendar, tafIdForCloud = model::tafIdForCloud,
              onOpenTaf = { calendarOpen = false; overviewOpen = false; openTaf = it },
              onBack = if (phone) ({ calendarOpen = false }) else null,
            )
          }
          val open: (@Composable () -> Unit)? = when {
            trying != null -> ({ TrialScreen(trying, state, versionActions, actions, onBack = { trialTaf = null }) })
            calendarOpen && openTaf == null && !coachOpen && !wide -> ({ calendar(true) })
            openTaf != null || coachOpen || coachVisibility.currentState || !coachVisibility.isIdle -> ({
              Box(Modifier.fillMaxSize()) {
                openTaf?.let { detail(it) }
                androidx.compose.animation.AnimatedVisibility(
                  visibleState = coachVisibility,
                  enter = androidx.compose.animation.slideInHorizontally { it },
                  exit = androidx.compose.animation.slideOutHorizontally { it },
                ) {
                  Box(Modifier.fillMaxSize().background(Theme.background)) {
                    CoachScreen(state, actions, loadCoach = model::loadCoach, onBack = { coachOpen = false },
                      chats = remember(model) { CoachChatActions(
                        newChat = model::newCoachChat, open = model::openCoachChat, load = model::loadCoachChats,
                        loadArchived = model::loadArchivedCoachChats, archive = model::archiveCoachChat, unarchive = model::unarchiveCoachChat,
                        giveTools = model::giveCoachTools, notNowTools = model::notNowCoachTools,
                      ) })
                  }
                }
              }
            })
            else -> null
          }
          if (wide) {
            // A tablet (as iOS's HomeSplitView): the list beside what's open. It opens on the list's first taf until another is
            // picked.
            Row(Modifier.fillMaxSize()) {
              // The list can fold away so what's open takes the width (the owner, 5 Nov), and comes back from the handle.
              if (!listFolded) androidx.compose.foundation.layout.Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight()) {
                Box(Modifier.weight(1f)) { home() }
                // Overview: today across the tafs, at the foot of the list (as iOS's sidebar row).
                Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.line))
                androidx.compose.material3.Text(
                  Words.Home.Overview.title,
                  style = scaled(15, androidx.compose.ui.text.font.FontWeight.SemiBold, if (overviewOpen) Theme.amber else Theme.amberText),
                  modifier = Modifier.fillMaxWidth().clickable { coachOpen = false; trialTaf = null; calendarOpen = false; overviewOpen = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                )
              }
              FoldHandle(folded = listFolded) { listFolded = !listFolded }
              Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                  open != null -> open()
                  calendarOpen -> calendar(false)
                  overviewOpen -> OverviewView(state, loadReport = model::loadReport, onOpenCalendar = { calendarOpen = true }) { overviewOpen = false; openTaf = it }
                  state.tafs.isNotEmpty() -> detail(state.tafs.first().id)
                  else -> Unit
                }
              }
            }
          } else {
            (open ?: home)()
          }
        }
        Tab.NeedsYou -> NeedsYouView(state, onAnswer = { call, answer -> model.answer(call, answer) }, onRefresh = { model.refresh() }, onAnswerUnsure = { call, happened -> model.answerDidThisHappen(call, happened) }, onCheckUnsure = { model.checkDidThisHappen(it) })
        Tab.Record -> RecordView(
          state,
          record = recordActions,
          tafActions = actions,
          onConnectApps = { showingConnectApps = true },
        )
      }
    }
    if (showingSettings) {
      SettingsSheet(
        state,
        onSignIn = { showingSettings = false; onSignIn() },
        onSignOut = { model.signOutAtKiff() },
        onSignInAgain = { model.signOutAtKiff() },
        onUsage = { usageOpen = true },
        onTurnOnAlerts = { scope.launch { model.turnOnAlerts() } },
        onAlertSound = model::setAlertSound,
        onToggleAlerts = { on -> scope.launch { if (on) { model.turnOnAlerts(); model.loadPhoneAlerts() } else model.turnOffAlerts() } },
        onAlertKind = { kind, on -> scope.launch { model.setAlertKind(kind, on) } },
        onLoadAlerts = { scope.launch { model.loadPhoneAlerts() } },
        onLoadTimeZones = { query -> scope.launch { model.loadTimeZones(query) } },
        onPickTimeZone = { zone -> scope.launch { model.setAccountTimeZone(zone) } },
        onLoadSuggestions = { scope.launch { model.loadSuggestions() } },
        onShowSuggestions = { show -> scope.launch { model.setSuggestions(show) } },
        onOpenAlertTaf = { cloudId ->
          state.tafs.firstOrNull { it.cloudId == cloudId }?.let {
            showingSettings = false; tab = Tab.Tafs; coachOpen = false; openTaf = it.id
          }
        },
        onDevices = { showingSettings = false; showingDevices = true },
        onConnectApps = { showingSettings = false; showingConnectApps = true },
        onPeople = { showingSettings = false; showingPeople = true },
        onUseDeviceZone = { scope.launch { model.useThisDevicesTimeZone() } },
        onDeleteAccount = { showingSettings = false; deletingAccount = true },
        onExport = model::exportChats,
        onClose = { showingSettings = false },
      )
    }
    if (showingPeople) PeopleGroupsSheet(state, people, onClose = { showingPeople = false })
    if (showingDevices) {
      DevicesSheet(state, refresh = model::refreshDevices, remove = model::removeDevice, onClose = { showingDevices = false })
    }
    if (showingConnectApps) {
      // Connect is for whoever manages the account's Cards; anyone else sees what's connected.
      bot.tafs.app.views.ConnectAppsSheet(
        state, load = model::loadConnectApps, loadMore = model::loadMoreConnectApps,
        onConnect = if (state.canManageCards) ({ app, taf -> scope.launch { model.startConnectApp(app, taf) } }) else null,
        onAskCoach = { showingConnectApps = false; coachOpen = true },
        onClose = { showingConnectApps = false; model.closeListApproval() },
        // The taf it's signed in for, by its grant: that taf's Coach chooses its actions.
        onChooseActions = { app ->
          showingConnectApps = false
          state.grants.firstOrNull { it.id == app.grant }?.request?.agent?.let(model::improveInCoach) ?: run { coachOpen = true }
        },
        // One connection disconnected (the fingerprint asked first), then the list read again with the account's connections.
        onDisconnect = if (state.canManageCards) ({ id -> scope.launch { if (model.disconnect(id)) model.loadConnectApps(null) } }) else null,
        // Removed tafs' connections, cleared at once (one fingerprint), then the list read again.
        onClearRemoved = if (state.canManageCards) ({ ids -> scope.launch { model.clearRemovedConnections(ids); model.loadConnectApps(null) } }) else null,
        // Gap 13: a new key on the key sheet over the list, or a sign-in again, each behind the fingerprint.
        onReplaceKey = if (state.canManageCards) model::openReplaceKey else null,
        onSignInAgain = if (state.canManageCards) model::startReconnect else null,
        onConnectedNow = { grant -> model.dismissConnectedNow(); showingConnectApps = false; model.improveInCoach(grant.request.agent) },
        dismissConnectedNow = model::dismissConnectedNow,
      )
    }
    // Its approval, over Connect apps' list, or a new key over a taf's own connection (gap 13): KIFF's words, the signature,
    // then the vendor's page or planque's one read with the key.
    state.listApproval?.let { grantId ->
      bot.tafs.app.views.ConnectApprovalSheet(
        state, state.grants.firstOrNull { it.id == grantId },
        loadWords = model::loadProfileWords,
        onApprove = { grant -> model.startListApproval(grant.id, grant.digest) },
        onClose = model::closeListApproval,
        needsKey = state.listApprovalNeedsKey,
        onGiveKey = { grant, key -> model.startGiveKey(grant.id, grant.digest, key) },
      )
    }
    // A browser connection's live sign-in view, as planque serves it.
    state.siteSignIn?.let { view -> bot.tafs.app.views.SiteSignInScreen(view, onClose = model::closeSiteSignIn) }
    // A request from the dial's rim or Activity, step by step.
    state.openRequest?.let { (tafId, detail) ->
      // "Try the reply now" is the door reply's; a place of the taf's own goes again from its work.
      val canSend = state.manages(tafId) && detail.deliveries.any { it.sendAgain && it.place == "door_reply" }
      bot.tafs.app.views.RequestSheet(
        detail,
        sending = detail.id in state.sendingAgain,
        onTryReply = if (canSend) ({ scope.launch { model.sendAgain(tafId, detail.id); model.openRequest(tafId, detail.id) } }) else null,
        onAskCoach = if (state.mayUseCoach) ({ model.closeRequest(); model.improveInCoach(tafId) }) else null,
        onClose = model::closeRequest,
      )
    }
    if (deletingAccount) {
      DeleteAccountSheet(state, delete = model::deleteAccount, clearError = model::clearDeleteAccountError, onClose = { deletingAccount = false })
    }
    if (haltOpen) HaltSheet(haltTaf, state, account, onClose = { haltOpen = false })
    versionsTaf?.let { taf ->
      VersionsSheet(taf, state, versionActions, onOpenTrial = { versionsTaf = null; trialTaf = taf; tab = Tab.Tafs; openTaf = taf }, onClose = { versionsTaf = null })
    }
    if (galleryOpen) state.gallery?.let { gallery ->
      bot.tafs.app.views.GallerySheet(
        gallery, canUseCoach = state.mayUseCoach,
        onOpen = { recipeOpen = it.id },
        onOwnWords = { galleryOpen = false; model.openCoachEmpty() },
        onClose = { galleryOpen = false },
      )
    }
    recipeOpen?.let { id ->
      // From Home with no taf, the gallery, or the strip: the same sheet, with tafs cloud's words for it.
      val recipes = state.recipes
      val recipe = recipes?.recipes?.firstOrNull { it.id == id } ?: state.gallery?.recipes?.firstOrNull { it.id == id }
      val labels = recipes?.labels ?: state.gallery?.labels
      // A recipe no longer listed (the account has a taf now, or the list was read again) shows no sheet.
      if (recipe != null && labels != null) bot.tafs.app.views.RecipeSheet(recipe, labels, canHand = state.mayUseCoach, onHand = { recipeOpen = null; galleryOpen = false; model.handRecipeToCoach(recipe) }, onClose = { recipeOpen = null })
    }
    if (usageOpen) UsageSheet(state, account, onClose = { usageOpen = false; model.closeTafCredits() })
    // A taf's runs: over Plan & usage from its Credits row, or over its page from its Usage row.
    state.tafCredits?.let { CreditsRunsSheet(it, state, account, onClose = model::closeTafCredits) }
    if (!keyboardUp && !wide) {
      HorizontalDivider(color = Theme.line)
      TabBar(tab, state, onTab = onTab, onAccount = openAccount, onCoach = openCoach)
    }
  }
  }
}

/** The bar under every screen: Account, the three tabs, and Coach. 56 dp, above the system's gesture bar, as iOS's tab bar. */
@Composable
internal fun TabBar(tab: Tab, state: bot.tafs.app.app.AppState, onTab: (Tab) -> Unit, onAccount: () -> Unit, onCoach: (() -> Unit)?) {
  NavigationBar(
    Modifier.navigationBarsPadding().height(56.dp),
    containerColor = Theme.background,
    tonalElevation = 0.dp,
    windowInsets = WindowInsets(0),
  ) {
    NavigationBarItem(
      selected = false,
      onClick = onAccount,
      icon = { Icon(painterResource(R.drawable.ic_person), contentDescription = null, modifier = Modifier.size(20.dp)) },
      label = { Text(ACCOUNT, style = scaled(11), maxLines = 1, softWrap = false) },
      colors = barColors(),
    )
    Tab.entries.forEach { t ->
      NavigationBarItem(
        selected = tab == t,
        onClick = { onTab(t) },
        icon = { TabIcon(t, state) },
        label = { Text(t.title, style = scaled(11), maxLines = 1, softWrap = false) },
        colors = barColors(),
      )
    }
    onCoach?.let { open -> Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { CoachButton(open) } }
  }
}

/** The same places as a rail, for landscape and tablets: Account, the three tabs, then Coach at the foot. */
@Composable
private fun TabRail(tab: Tab, state: bot.tafs.app.app.AppState, onTab: (Tab) -> Unit, onAccount: () -> Unit, onCoach: (() -> Unit)?) {
  // A thin rail (the owner, 5 Nov: landscape's was a fifth of the screen): a fixed width, one-line labels, Coach compact.
  androidx.compose.material3.NavigationRail(
    Modifier.width(RAIL_WIDTH).fillMaxHeight().navigationBarsPadding().statusBarsPadding(),
    containerColor = Theme.background,
  ) {
    androidx.compose.material3.NavigationRailItem(
      selected = false,
      onClick = onAccount,
      icon = { Icon(painterResource(R.drawable.ic_person), contentDescription = null, modifier = Modifier.size(20.dp)) },
      label = { Text(ACCOUNT, style = scaled(11), maxLines = 1, softWrap = false) },
      colors = railColors(),
    )
    Tab.entries.forEach { t ->
      androidx.compose.material3.NavigationRailItem(
        selected = tab == t,
        onClick = { onTab(t) },
        icon = { TabIcon(t, state) },
        label = { Text(t.title, style = scaled(11), maxLines = 1, softWrap = false) },
        colors = railColors(),
      )
    }
    Spacer(Modifier.weight(1f))
    onCoach?.let { CoachButton(it, Modifier.padding(bottom = 12.dp), compact = true) }
  }
}

/** The rail's width: room for an icon and a one-line label, whatever the text size. */
private val RAIL_WIDTH = 72.dp

/** The line between the list and what's open, with the control that folds the list away or brings it back. */
@Composable
private fun FoldHandle(folded: Boolean, onToggle: () -> Unit) {
  Box(Modifier.width(24.dp).fillMaxHeight()) {
    Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(Theme.line))
    Text(
      if (folded) "›" else "‹",
      style = scaled(18, androidx.compose.ui.text.font.FontWeight.SemiBold, Theme.dim),
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(24.dp, 44.dp)
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(Theme.panel)
        .clickable(onClickLabel = if (folded) Words.Common.showTheList else Words.Common.hideTheList, onClick = onToggle)
        .semantics { role = androidx.compose.ui.semantics.Role.Button; contentDescription = if (folded) Words.Common.showTheList else Words.Common.hideTheList }
        .wrapContentSize(Alignment.Center),
    )
  }
}

/** A tab's icon; Needs you with its count. */
@Composable
private fun TabIcon(t: Tab, state: bot.tafs.app.app.AppState) {
  val icon = @Composable { Icon(painterResource(t.icon), contentDescription = null, modifier = Modifier.size(20.dp)) }
  val waiting = state.needsYouCount
  if (t == Tab.NeedsYou && waiting > 0) {
    BadgedBox(badge = { Badge(containerColor = Theme.no, contentColor = Theme.panel) { Text("$waiting") } }) { icon() }
  } else {
    icon()
  }
}

/** Coach, at the bar's end: filled, ink with a white label, so it reads as the one place to make or change a taf. */
@Composable
private fun CoachButton(onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
  // While Coach drives, the button shows it's acting, and the person's touch on it stops the drive (`stopped`).
  val acting = bot.tafs.app.drive.DriveInput.driving
  Text(
    COACH,
    style = scaled(if (compact) 11 else 13, androidx.compose.ui.text.font.FontWeight.SemiBold, Theme.background),
    maxLines = 1,
    softWrap = false,
    modifier = modifier.heightIn(min = 40.dp).then(Modifier.driveStopZone()).clip(androidx.compose.foundation.shape.CircleShape)
      .background(if (acting) Theme.amber else Theme.ink)
      .clickable(onClick = onClick).semantics { role = androidx.compose.ui.semantics.Role.Button }
      .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = 10.dp),
  )
}

@Composable
private fun barColors() = NavigationBarItemDefaults.colors(
  selectedIconColor = Theme.amber,
  selectedTextColor = Theme.amber,
  unselectedIconColor = Theme.dim,
  unselectedTextColor = Theme.dim,
  indicatorColor = Theme.amber.copy(alpha = 0.14f),
)

@Composable
private fun railColors() = androidx.compose.material3.NavigationRailItemDefaults.colors(
  selectedIconColor = Theme.amber,
  selectedTextColor = Theme.amber,
  unselectedIconColor = Theme.dim,
  unselectedTextColor = Theme.dim,
  indicatorColor = Theme.amber.copy(alpha = 0.14f),
)

/** Whether tapping [tapped] while on [current] goes back to its start: Tafs tapped again closes the taf on screen. */
internal fun tappedAgainGoesToStart(current: Tab, tapped: Tab): Boolean = current == tapped && tapped == Tab.Tafs

/** The bar's two places that open: the account (Settings) and Coach. */
val ACCOUNT: String get() = Words.Settings.title
val COACH: String get() = Words.Coach.title

/** From this width the Tafs tab shows the list beside what's open, as iOS on an iPad: a tablet either way up, or a large phone on its side. */
private const val WIDE_DP = 720

/** The list's width beside what's open: iOS's sidebar. */
private val SIDEBAR_WIDTH = 360.dp
