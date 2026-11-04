package bot.tafs.app

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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import bot.tafs.app.model.ThemePreference
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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
import bot.tafs.app.views.TafUsageSheet
import bot.tafs.app.views.TrialScreen
import bot.tafs.app.views.VersionActions
import bot.tafs.app.views.VersionsSheet
import bot.tafs.app.views.UsageSheet
import bot.tafs.app.views.CoachInstalls
import bot.tafs.app.views.LocalAnswerPage
import bot.tafs.app.views.LocalImproveInCoach
import bot.tafs.app.views.LocalSignInAgain
import bot.tafs.app.views.ApproveAgain
import bot.tafs.app.views.LocalApproveAgain
import bot.tafs.app.views.LocalReceipt
import bot.tafs.app.views.OverviewView
import bot.tafs.app.views.LocalCoachInstalls
import bot.tafs.app.views.NeedsYouView
import bot.tafs.app.views.NewTafFlow
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
import androidx.compose.ui.draw.clip

/** A FragmentActivity: the system's biometric prompt shows on one. */
class MainActivity : FragmentActivity() {
  private val app get() = application as TafsApplication

  /** The answer to the notification permission prompt, while it is up. */
  private var permissionAnswer: CompletableDeferred<Boolean>? = null
  private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> permissionAnswer?.complete(granted) }

  /** Where a tapped alert asked to go, until the model has it. */
  private var openFromAlert by mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The bars follow the app's own Light/Dark/System choice from the first frame, not only the phone's setting; TafsTheme
    // keeps them in step when the choice changes.
    val bars = when (ThemePreference.from(getSharedPreferences("tafs.display", MODE_PRIVATE).getString("theme", null))) {
      ThemePreference.Light -> SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
      ThemePreference.Dark -> SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
      ThemePreference.System -> SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
    }
    enableEdgeToEdge(bars, bars)
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
    setContent {
      TafsTheme {
        val model: AppModel = viewModel(factory = AppModel.Factory(store, app.signer, app.kiff, app.cloud, app.secrets, app.deviceName, app.push, app.planque, app.vendorSignIn, app.purchases, app.playBilling, java.io.File(cacheDir, "attachments")))
        val biometry = remember { Biometry.of(this@MainActivity) }
        // Firebase gave the phone a new token: the model tells tafs cloud.
        DisposableEffect(model) {
          app.tokenListener = model::tokenArrived
          app.changeListener = { model.changed(it.change, it.tafId) }
          onDispose {
            app.tokenListener = null
            app.changeListener = null
          }
        }
        LaunchedEffect(openFromAlert, model) {
          model.requestOpen(openFromAlert)
          openFromAlert = null
        }
        CompositionLocalProvider(LocalBiometry provides biometry) { RootView(model) }
      }
    }
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
    app.authenticator.detach(this)
    app.push.askPermission = null
    super.onDestroy()
  }
}

enum class Tab(val title: String, val icon: Int) {
  Tafs("Tafs", R.drawable.ic_stack),
  NeedsYou("Needs you", R.drawable.ic_bell),
  Record("Record", R.drawable.ic_list),
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
    AlertDialog(
      onDismissRequest = model::dismissAlertsOff,
      title = { Text("Alerts are off on this phone") },
      text = { Text("They went off when this phone signed out. Turn them on to get an alert when a taf needs you.") },
      confirmButton = { TextButton({ model.dismissAlertsOff(); rootScope.launch { model.turnOnAlerts() } }) { Text("Turn on", color = Theme.amber) } },
      dismissButton = { TextButton(model::dismissAlertsOff) { Text("Not now", color = Theme.soft) } },
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
    AlertDialog(
      onDismissRequest = model::dismissError,
      title = { Text("Not done") },
      text = { Text(error) },
      confirmButton = { TextButton(model::dismissError) { Text("OK", color = Theme.amber) } },
      containerColor = Theme.panel,
    )
  }
  // This device's time zone, asked once when the account's differs.
  val offer = state.timeZoneOffer
  if (offer != null && state.lastError == null && state.signedIn) {
    val zoneScope = rememberCoroutineScope()
    AlertDialog(
      onDismissRequest = {},
      title = { Text("Use $offer for times in tafs?") },
      text = { Text("Holds' deadlines, Activity, the Record and what your tafs write would use this phone's time zone. Your account uses ${state.kiffAccount?.timeZone ?: "UTC"} now.") },
      confirmButton = { TextButton({ zoneScope.launch { model.answerTimeZoneOffer(use = true) } }) { Text("Use $offer", color = Theme.amber) } },
      dismissButton = { TextButton({ zoneScope.launch { model.answerTimeZoneOffer(use = false) } }) { Text("Not now", color = Theme.soft) } },
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
  var showingNew by rememberSaveable { mutableStateOf(false) }
  var showingSettings by rememberSaveable { mutableStateOf(false) }
  var deletingAccount by rememberSaveable { mutableStateOf(false) }
  var showingDevices by rememberSaveable { mutableStateOf(false) }
  var showingConnectApps by rememberSaveable { mutableStateOf(false) }
  var showingPeople by rememberSaveable { mutableStateOf(false) }
  // The Halt screen, of one taf or (with none) of every taf; and Usage, the account's or one taf's.
  var haltOpen by rememberSaveable { mutableStateOf(false) }
  var haltTaf by rememberSaveable { mutableStateOf<String?>(null) }
  var usageOpen by rememberSaveable { mutableStateOf(false) }
  var usageTaf by rememberSaveable { mutableStateOf<String?>(null) }
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
      refreshTafUsage = model::refreshTafUsage,
      buy = { model.buy(it) },
      restorePurchases = model::restorePurchases,
      askForPack = model::askForPack,
      withdrawRequest = model::withdrawRequest,
      approveRequest = { product, request -> model.buy(product, request) },
      declineRequest = model::declineRequest,
      goOnAI = model::goOnAI,
      readAIUse = model::readAIUse,
    )
  }
  val people = remember(model) {
    PeopleActions(
      refresh = model::refreshPeople,
      createGroup = { name -> model.changePeople("Create the group $name") { it.createGroup(name) } },
      renameGroup = { group, name -> model.changePeople("Rename ${group.name} to $name") { it.renameGroup(group.id, name) } },
      deleteGroup = { group -> model.changePeople("Delete the group ${group.name}") { it.deleteGroup(group.id) } },
      setMember = { group, subject, role ->
        val who = model.state.value.person(subject)?.label ?: subject
        val what = if (group.members.any { it.subject == subject }) "Make $who a $role of ${group.name}" else "Add $who to ${group.name}"
        model.changePeople(what) { it.setGroupMember(group.id, subject, role) }
      },
      removeMember = { group, subject ->
        val who = model.state.value.person(subject)?.label ?: subject
        model.changePeople("Remove $who from ${group.name}") { it.removeGroupMember(group.id, subject) }
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
  // Another account (signed in from the sample, or switched): the Tafs tab opens on its list, nothing of the last one left open.
  val whose = accountShown(state)
  var shownAccount by rememberSaveable { mutableStateOf(whose) }
  LaunchedEffect(whose) {
    if (whose == shownAccount) return@LaunchedEffect
    shownAccount = whose
    openTaf = null; trialTaf = null; versionsTaf = null; haltTaf = null; usageTaf = null; overviewOpen = false; coachOpen = false
  }
  val detailState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
  val coachVisibility = remember { androidx.compose.animation.core.MutableTransitionState(false) }
  LaunchedEffect(coachOpen) { coachVisibility.targetState = coachOpen }
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
      connections = ConnectionActions(refresh = model::refreshConnections, disconnect = model::disconnect, reconnect = model::startReconnect, reconnectId = model::startReconnectId),
      stopChat = model::stopChat,
      files = FileActions(attach = model::attach, refuse = model::refuseFile, remove = model::removeFile, open = model::openFile),
      runNow = { model.runNow(it) },
      loadReport = model::loadReport,
      loadWork = model::loadWork,
      setDraft = model::setDraft,
      sendAgain = model::sendAgain,
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
        start = { model.startCoach() },
        signInAgain = model::startSignInAgain,
        improve = model::improveTaf,
        openTaf = { coachOpen = false; openTaf = it },
        openVersions = { coachOpen = false; openTaf = it; versionsTaf = it },
        loadProfileWords = model::loadProfileWords,
        loadKnob = model::loadKnob,
        confirmKnob = model::confirmKnobChange,
      ),
    )
  }
  // A tapped alert goes where it said: a held call to Needs you, a request for tasks to Usage, a door's alert to its taf.
  LaunchedEffect(state.requestedOpen, state.tafs) {
    val open = state.requestedOpen ?: return@LaunchedEffect
    val cloudId = AlertPayload.tafOf(open)
    when {
      open == AlertPayload.NEEDS_YOU -> { tab = Tab.NeedsYou; showingSettings = false }
      open == AlertPayload.USAGE -> { usageTaf = null; usageOpen = true }
      cloudId != null -> {
        // The list may not be read yet: the taf opens once it's there, and an alert for a taf no longer listed opens the list.
        val taf = state.tafs.firstOrNull { it.cloudId == cloudId }
        if (taf == null && state.tafs.isEmpty()) return@LaunchedEffect
        tab = Tab.Tafs; showingSettings = false; coachOpen = false; openTaf = taf?.id
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
  // A tablet, either way up, or a large phone on its side: the list and what's open side by side.
  val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= WIDE_DP

  // The bar's two places that open rather than switch: Account (today's Settings) and Coach, for whoever may use it.
  val openAccount = { showingSettings = true }
  val openCoach: (() -> Unit)? = if (state.mayUseCoach) ({
    model.openCoachOn(if (tab == Tab.Tafs) openTaf else null); tab = Tab.Tafs; trialTaf = null; coachOpen = true
  }) else null
  Row(Modifier.fillMaxSize().background(Theme.background).imePadding()) {
    // Landscape and tablets: the same places as a rail on the left, Coach at its foot.
    if (wide) {
      TabRail(tab, state, onTab = { tab = it }, onAccount = openAccount, onCoach = openCoach)
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
              onOpenTaf = { coachOpen = false; trialTaf = null; overviewOpen = false; openTaf = it },
              onNewTaf = { showingNew = true },
              onHaltAll = { haltTaf = null; haltOpen = true },
              onUsage = { usageTaf = null; usageOpen = true },
            )
          }
          val detail: @Composable (String) -> Unit = { id ->
            detailState.SaveableStateProvider(id) { TafDetailView(
              id, state, actions, onBack = { openTaf = null },
              onHalt = { haltTaf = it; haltOpen = true },
              onUsage = { usageTaf = it; usageOpen = true },
              onVersions = { versionsTaf = it },
              people = people,
              onConnectApps = { showingConnectApps = true },
              showBack = !wide,
            ) }
          }
          val open: (@Composable () -> Unit)? = when {
            trying != null -> ({ TrialScreen(trying, state, versionActions, actions, onBack = { trialTaf = null }) })
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
                        giveTools = model::giveCoachTools,
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
              androidx.compose.foundation.layout.Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight()) {
                Box(Modifier.weight(1f)) { home() }
                // Overview: today across the tafs, at the foot of the list (as iOS's sidebar row).
                Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.line))
                androidx.compose.material3.Text(
                  "Overview",
                  style = scaled(15, androidx.compose.ui.text.font.FontWeight.SemiBold, if (overviewOpen) Theme.amber else Theme.amberText),
                  modifier = Modifier.fillMaxWidth().clickable { coachOpen = false; trialTaf = null; overviewOpen = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                )
              }
              Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.line))
              Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                  open != null -> open()
                  overviewOpen -> OverviewView(state, loadReport = model::loadReport) { overviewOpen = false; openTaf = it }
                  state.tafs.isNotEmpty() -> detail(state.tafs.first().id)
                  else -> Unit
                }
              }
            }
          } else {
            (open ?: home)()
          }
        }
        Tab.NeedsYou -> NeedsYouView(state, onAnswer = { call, answer -> model.answer(call, answer) }, onRefresh = { model.refresh() }, onAnswerUnsure = { call, happened -> model.answerDidThisHappen(call, happened) })
        Tab.Record -> RecordView(state)
      }
    }
    if (showingNew) {
      NewTafFlow(
        draftFrom = model::draft,
        issuer = model.issuer,
        colorIndex = state.tafs.size,
        onIssue = { draft ->
          model.start(draft)?.also { taf ->
            openTaf = taf.id
            tab = Tab.Tafs
          }
        },
        onClose = { showingNew = false },
      )
    }
    if (showingSettings) {
      SettingsSheet(
        state,
        onSignIn = { showingSettings = false; onSignIn() },
        onSignOut = { model.signOutAtKiff() },
        onSignInAgain = { model.signOutAtKiff() },
        onUsage = { usageTaf = null; usageOpen = true },
        onTurnOnAlerts = { scope.launch { model.turnOnAlerts() } },
        onAlertSound = model::setAlertSound,
        onToggleAlerts = { on -> scope.launch { if (on) { model.turnOnAlerts(); model.loadPhoneAlerts() } else model.turnOffAlerts() } },
        onAlertKind = { kind, on -> scope.launch { model.setAlertKind(kind, on) } },
        onLoadAlerts = { scope.launch { model.loadPhoneAlerts() } },
        onLoadTimeZones = { query -> scope.launch { model.loadTimeZones(query) } },
        onPickTimeZone = { zone -> scope.launch { model.setAccountTimeZone(zone) } },
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
        onClose = { showingConnectApps = false },
        // The taf it's signed in for, by its grant: that taf's Coach chooses its actions.
        onChooseActions = { app ->
          showingConnectApps = false
          state.grants.firstOrNull { it.id == app.grant }?.request?.agent?.let(model::improveInCoach) ?: run { coachOpen = true }
        },
        onConnectedNow = { grant -> model.dismissConnectedNow(); showingConnectApps = false; model.improveInCoach(grant.request.agent) },
        dismissConnectedNow = model::dismissConnectedNow,
      )
      // Its approval, over the list: KIFF's words, the signature, then the vendor's page.
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
    }
    // A request from the dial's rim or Activity, step by step.
    state.openRequest?.let { (tafId, detail) ->
      val canSend = state.manages(tafId) && detail.deliveries.any { it.sendAgain }
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
    if (usageOpen) {
      val taf = usageTaf
      if (taf == null) UsageSheet(state, account, onClose = { usageOpen = false })
      else TafUsageSheet(taf, state, account, onClose = { usageOpen = false })
    }
    if (!keyboardUp && !wide) {
      HorizontalDivider(color = Theme.line)
      TabBar(tab, state, onTab = { tab = it }, onAccount = openAccount, onCoach = openCoach)
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
      label = { Text(ACCOUNT, style = scaled(11)) },
      colors = barColors(),
    )
    Tab.entries.forEach { t ->
      NavigationBarItem(
        selected = tab == t,
        onClick = { onTab(t) },
        icon = { TabIcon(t, state) },
        label = { Text(t.title, style = scaled(11)) },
        colors = barColors(),
      )
    }
    onCoach?.let { open -> Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { CoachButton(open) } }
  }
}

/** The same places as a rail, for landscape and tablets: Account, the three tabs, then Coach at the foot. */
@Composable
private fun TabRail(tab: Tab, state: bot.tafs.app.app.AppState, onTab: (Tab) -> Unit, onAccount: () -> Unit, onCoach: (() -> Unit)?) {
  androidx.compose.material3.NavigationRail(
    Modifier.fillMaxHeight().navigationBarsPadding().statusBarsPadding(),
    containerColor = Theme.background,
  ) {
    androidx.compose.material3.NavigationRailItem(
      selected = false,
      onClick = onAccount,
      icon = { Icon(painterResource(R.drawable.ic_person), contentDescription = null, modifier = Modifier.size(20.dp)) },
      label = { Text(ACCOUNT, style = scaled(11)) },
      colors = railColors(),
    )
    Tab.entries.forEach { t ->
      androidx.compose.material3.NavigationRailItem(
        selected = tab == t,
        onClick = { onTab(t) },
        icon = { TabIcon(t, state) },
        label = { Text(t.title, style = scaled(11)) },
        colors = railColors(),
      )
    }
    Spacer(Modifier.weight(1f))
    onCoach?.let { CoachButton(it, Modifier.padding(bottom = 12.dp)) }
  }
}

/** A tab's icon; Needs you with its count. */
@Composable
private fun TabIcon(t: Tab, state: bot.tafs.app.app.AppState) {
  val icon = @Composable { Icon(painterResource(t.icon), contentDescription = null, modifier = Modifier.size(20.dp)) }
  val waiting = state.held.size + state.cardUnitExceptions.size
  if (t == Tab.NeedsYou && waiting > 0) {
    BadgedBox(badge = { Badge(containerColor = Theme.no, contentColor = Theme.panel) { Text("$waiting") } }) { icon() }
  } else {
    icon()
  }
}

/** Coach, at the bar's end: filled, ink with a white label, so it reads as the one place to make or change a taf. */
@Composable
private fun CoachButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Text(
    COACH,
    style = scaled(13, androidx.compose.ui.text.font.FontWeight.SemiBold, Theme.background),
    modifier = modifier.heightIn(min = 40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Theme.ink)
      .clickable(onClick = onClick).semantics { role = androidx.compose.ui.semantics.Role.Button }
      .padding(horizontal = 14.dp, vertical = 10.dp),
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

/** The bar's two places that open: the account (Settings) and Coach. */
const val ACCOUNT = "Account"
const val COACH = "Coach"

/** From this width the Tafs tab shows the list beside what's open, as iOS on an iPad: a tablet either way up, or a large phone on its side. */
private const val WIDE_DP = 720

/** The list's width beside what's open: iOS's sidebar. */
private val SIDEBAR_WIDTH = 360.dp
