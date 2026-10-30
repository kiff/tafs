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
import androidx.activity.enableEdgeToEdge
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
    enableEdgeToEdge()
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

  Box(Modifier.fillMaxSize().background(Theme.background)) {
    when {
      // Nothing draws until it is known, so the first screen never flashes up for someone signed in.
      !state.ready -> Unit
      !state.signedIn -> OnboardingView(onSignIn = { signingIn = true }, onSample = model::startSample)
      else -> CompositionLocalProvider(
        LocalAnswerPage provides model::answerPage,
        LocalImproveInCoach provides (if (state.mayUseCoach) model::improveInCoach else null),
        // KIFF's receipt for a Record line, signed in: the sample has none.
        LocalReceipt provides (if (state.mode == Mode.kiff) model::receipt else null),
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
      setOpen = { taf, door, open -> model.setDoorOpen(taf, door, open) },
      remove = { taf, door -> model.removeDoor(taf, door) },
      pair = { taf, door, sender, name -> model.pairDoor(taf, door, sender, name) },
      unpair = { taf, door, key -> model.unpairDoor(taf, door, key) },
      answer = { taf, knock, action, say -> model.answerKnock(taf, knock, action, say) },
      dismissError = model::dismissDoorsError,
    )
  }
  // Coach: the account's Setup, in place of the list. Closing never ends a conversation.
  var coachOpen by rememberSaveable { mutableStateOf(false) }
  // Overview, on a tablet: beside the list, until a taf is picked.
  var overviewOpen by rememberSaveable { mutableStateOf(false) }
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
      connections = ConnectionActions(refresh = model::refreshConnections, disconnect = model::disconnect, reconnect = model::reconnect),
      stopChat = model::stopChat,
      files = FileActions(attach = model::attach, refuse = model::refuseFile, remove = model::removeFile, open = model::openFile),
      runNow = { model.runNow(it) },
      loadReport = model::loadReport,
      loadWork = model::loadWork,
      sendAgain = model::sendAgain,
      setPaused = model::setPaused,
      remove = model::removeTaf,
      coach = CoachActions(
        answer = model::answerCoach,
        approve = model::approveCoach,
        cancelSignIn = { (context.applicationContext as TafsApplication).vendorSignIn.cancel() },
        start = { model.startCoach() },
        signInAgain = model::signInAgain,
        makeVersion = model::makeImprovedVersion,
        openTaf = { coachOpen = false; openTaf = it },
        openVersions = { coachOpen = false; openTaf = it; versionsTaf = it },
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
  BackHandler(enabled = tab == Tab.Tafs && openTaf != null) { openTaf = null }
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

  Column(Modifier.fillMaxSize().background(Theme.background).imePadding()) {
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
              onSettings = { showingSettings = true },
              onHaltAll = { haltTaf = null; haltOpen = true },
              onCoach = { coachOpen = true },
            )
          }
          val detail: @Composable (String) -> Unit = { id ->
            TafDetailView(
              id, state, actions, onBack = { openTaf = null },
              onHalt = { haltTaf = it; haltOpen = true },
              onUsage = { usageTaf = it; usageOpen = true },
              onVersions = { versionsTaf = it },
              people = people,
              showBack = !wide,
            )
          }
          // What's open: a trial, Coach, or a taf; null for the list alone.
          val open: (@Composable () -> Unit)? = when {
            trying != null -> ({ TrialScreen(trying, state, versionActions, actions, onBack = { trialTaf = null }) })
            coachOpen -> ({
              CoachScreen(
                state, actions, loadCoach = model::loadCoach, onBack = { coachOpen = false },
                chats = remember(model) {
                  CoachChatActions(
                    newChat = model::newCoachChat, open = model::openCoachChat, load = model::loadCoachChats,
                    loadArchived = model::loadArchivedCoachChats, archive = model::archiveCoachChat, unarchive = model::unarchiveCoachChat,
                    giveTools = model::giveCoachTools,
                  )
                },
              )
            })
            else -> openTaf?.let { id -> ({ detail(id) }) }
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
        Tab.NeedsYou -> NeedsYouView(state, onAnswer = { call, answer -> model.answer(call, answer) }, onRefresh = { model.refresh() })
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
        onDevices = { showingSettings = false; showingDevices = true },
        onPeople = { showingSettings = false; showingPeople = true },
        onUseDeviceZone = { scope.launch { model.useThisDevicesTimeZone() } },
        onDeleteAccount = { showingSettings = false; deletingAccount = true },
        onClose = { showingSettings = false },
      )
    }
    if (showingPeople) PeopleGroupsSheet(state, people, onClose = { showingPeople = false })
    if (showingDevices) {
      DevicesSheet(state, refresh = model::refreshDevices, remove = model::removeDevice, onClose = { showingDevices = false })
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
    if (!keyboardUp) HorizontalDivider(color = Theme.line)
    // 56 dp rather than Material's 80, above the system's gesture bar: it takes less of the screen, as iOS's tab bar.
    if (!keyboardUp) NavigationBar(
      Modifier.navigationBarsPadding().height(56.dp),
      containerColor = Theme.background,
      tonalElevation = 0.dp,
      windowInsets = WindowInsets(0),
    ) {
      Tab.entries.forEach { t ->
        NavigationBarItem(
          selected = tab == t,
          onClick = { tab = t },
          icon = {
            val icon = @Composable { Icon(painterResource(t.icon), contentDescription = null, modifier = Modifier.size(20.dp)) }
            if (t == Tab.NeedsYou && state.held.isNotEmpty()) {
              BadgedBox(badge = { Badge(containerColor = Theme.no) { Text("${state.held.size}") } }) { icon() }
            } else {
              icon()
            }
          },
          label = { Text(t.title, style = scaled(11)) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Theme.amber,
            selectedTextColor = Theme.amber,
            unselectedIconColor = Theme.dim,
            unselectedTextColor = Theme.dim,
            indicatorColor = Theme.amber.copy(alpha = 0.14f),
          ),
        )
      }
    }
  }
}

/** From this width the Tafs tab shows the list beside what's open, as iOS on an iPad: a tablet either way up, or a large phone on its side. */
private const val WIDE_DP = 720

/** The list's width beside what's open: iOS's sidebar. */
private val SIDEBAR_WIDTH = 360.dp
