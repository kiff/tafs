package bot.tafs.app.views

import bot.tafs.app.model.Words
import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.DriveAlert
import bot.tafs.app.model.Chat
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.R
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.MarkdownText
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Segmented
import bot.tafs.app.design.StateChip
import bot.tafs.app.design.TafAvatar
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.Answer
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.MemoryNote
import bot.tafs.app.model.RunReport
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.ReportResult
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafState
import bot.tafs.app.model.shortTime
import kotlinx.coroutines.launch

/**
 * Chat first: talking to a taf is the main thing. Each tab has one job: Activity, what it did and what waits for you; Card, only
 * what's on its Card; Setup, how it runs.
 */
private enum class Section {
  Chat, Activity, Card, Setup;

  val title: String get() = when (this) {
    Chat -> Words.Taf.Section.chat
    Activity -> Words.Taf.Section.activity
    Card -> Words.Taf.Section.card
    Setup -> Words.Taf.Section.setup
  }
}

/** What the screen asks of the model: its changes, so the screen holds no state of the app's. */
class TafActions(
  val answer: suspend (HeldCall, Answer) -> Unit,
  val togglePause: (String) -> Unit,
  val revoke: (String) -> Unit,
  val loadMemory: (String) -> Unit,
  val forget: (MemoryNote, String) -> Unit,
  val loadChat: suspend (String) -> Unit,
  /** Sends a message: its words, the chat, whether the tray's files go with it (Send), and what to do with words that didn't go. */
  val sendChat: (String, String, Boolean, (String) -> Unit) -> Unit,
  val issuer: String,
  val doors: DoorActions,
  /** What Coach's cards ask of the model: answers, and approvals. */
  val coach: CoachActions,
  /** The taf's connections, read from planque, and ending one. */
  val connections: ConnectionActions,
  /** Stops what the taf is doing in its chat. */
  val stopChat: suspend (String) -> Unit = {},
  /** A message that wasn't sent: Retry sends it once; Edit gives its words back for the box, or null when it went already. */
  val retryUnsent: (String, ChatMessage) -> Unit = { _, _ -> },
  val editUnsent: (String, ChatMessage) -> String? = { _, _ -> null },
  /** Deletes a message that wasn't sent, for good. */
  val deleteUnsent: (String, ChatMessage) -> Unit = { _, _ -> },
  /** Photos and files on a message (docs/attachments.md). */
  val files: FileActions = FileActions(),
  /** Runs it now, outside its schedule (tafs cloud). */
  val runNow: suspend (String) -> Unit = {},
  /** Reads its latest run and report. */
  val loadReport: suspend (String) -> Unit = {},
  /** What the owner wrote in a chat and hasn't sent, kept by the app: the chat, and its words. */
  val setDraft: (String, String) -> Unit = { _, _ -> },
  /** Reads where its work stands (docs/reliability.md), and sends a delivery again by its run and place. */
  val loadWork: suspend (String) -> Unit = {},
  val sendAgain: suspend (String, String, String) -> Unit = { _, _, _ -> },
  /** Opens Coach about a taf with tafs cloud's words in the message box (taf, words). */
  val askCoachAbout: (String, String) -> Unit = { _, _ -> },
  /** Opens a request step by step (taf, run). */
  val openRequest: suspend (String, String) -> Unit = { _, _ -> },
  /** Pauses or resumes its schedule. */
  val setPaused: suspend (String, Boolean) -> Unit = { _, _ -> },
  /** Removes it for good, after one confirmation: what to tell the owner, null when nothing was done. */
  val remove: suspend (String) -> String? = { null },
  /** Reports an answer Coach or the taf wrote: the chat, the answer, a reason and an optional note. */
  val report: suspend (String, ChatMessage, String, String?) -> ReportResult = { _, _, _, _ -> ReportResult.NotSent("") },
  /** Where its results go, of tafs' own places (taf, places). */
  val setResults: suspend (String, List<String>) -> Unit = { _, _ -> },
  val loadSetup: suspend (String) -> Unit = {},
  val loadActivity: suspend (String, String, Boolean, String) -> Unit = { _, _, _, _ -> },
  val loadRuns: suspend (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
  val loadAllMemory: (String) -> Unit = {},
)

@Composable
fun TafDetailView(
  tafId: String,
  state: AppState,
  actions: TafActions,
  onBack: () -> Unit,
  onHalt: (String) -> Unit,
  onUsage: (String) -> Unit,
  onVersions: (String) -> Unit,
  people: PeopleActions? = null,
  /** Connect apps, from Setup's "Connect an app…". */
  onConnectApps: () -> Unit = {},
  /** Back to the list; not shown beside it, on a tablet. */
  showBack: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val taf = state.taf(tafId)
  // What sits above the tabs folds away while the page scrolls down, and comes back on the way up, so the chat has the screen.
  var folded by remember { mutableStateOf(false) }
  val folding = remember {
    object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
      override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
        if (available.y < -4f) folded = true else if (available.y > 4f) folded = false
        return androidx.compose.ui.geometry.Offset.Zero
      }
    }
  }
  Column(modifier.fillMaxSize().statusBarsPadding()) {
    if (taf == null) {
      if (showBack) BackBar(onBack)
      Text(Words.Taf.gone, style = scaled(15, color = Theme.dim), modifier = Modifier.padding(16.dp))
      return@Column
    }
    val canChat = state.canChat(taf.id)
    val sections = Section.entries.filter { it != Section.Chat || canChat }
    var section by rememberSaveable { mutableStateOf(sections.first()) }
    var activitySelection by rememberSaveable(taf.id) { mutableStateOf("") }
    // tafs cloud's setup words a connection's state ("Connected"); planque's state is read on its own. Read the setup again
    // whenever planque's state for one of the taf's connections changes, so the two never disagree on screen.
    val grantsNow = bot.tafs.app.model.ConnectionWords.setupKey(taf.id, state.grants)
    if (state.mode == Mode.kiff && taf.cloudId != null) LaunchedEffect(taf.id, grantsNow) { actions.loadSetup(taf.id) }
    var frontCard by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRevoke by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    // What removing it did, shown once it's done.
    var removal by rememberSaveable { mutableStateOf<String?>(null) }
    val removeScope = rememberCoroutineScope()
    val cards = taf.shownCards
    // A wide screen in landscape (as iOS on an iPad): beside the chat or the activity, a second column with the call waiting for
    // the owner and the Cards, when the chat keeps at least a phone's width beside it.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val pageWidth = config.screenWidthDp - (if (showBack) 0 else 361)
    val side = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
      section != Section.Card && section != Section.Setup && pageWidth - SIDE_WIDTH - 32 >= CHAT_AT_LEAST

    Header(taf, if (showBack) onBack else null)
    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      // Who it's shared with, and Share: the taf's manager's to see. It folds with the page.
      if (people != null && taf.cloudId != null) {
        androidx.compose.animation.AnimatedVisibility(visible = !folded) { ShareLine(taf.id, state, people) }
      }
      Segmented(sections, section, { it.title }) { section = it }
      // The Cards are one line above the chat and the activity; it opens the Card tab (kiff/tafs#211), and folds with the page.
      if (section != Section.Card && !side) {
        (cards.firstOrNull { it.id == frontCard } ?: cards.firstOrNull())?.let {
          androidx.compose.animation.AnimatedVisibility(visible = !folded) {
            CardBar(it, cards.size, Theme.tafColor(taf.colorIndex), taf.card.revoked) { section = Section.Card }
          }
        }
      }
    }

    Row(Modifier.weight(1f).nestedScroll(folding)) {
    Column(Modifier.weight(1f).fillMaxHeight()) {
    if (section == Section.Chat) {
      // The thread scrolls on its own, with the field under it.
      TafChatView(taf.id, state, actions, Modifier.weight(1f))
    } else if (section == Section.Activity && state.mode == Mode.kiff && taf.cloudId != null) {
      TafActivityList(taf, state, actions, activitySelection, { activitySelection = "" }, Modifier.weight(1f), onConnectApps = onConnectApps)
    } else {
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        when (section) {
          Section.Activity -> Activity(taf, state, actions)
          Section.Card -> {
            // Only what's on its Card: the Card, its limits and how they're counted, its usage, Halt and Revoke.
            val managesHere = state.mode == Mode.kiff && taf.cloudId != null && state.manages(taf.id)
            CardTab(taf, state.issuer(taf), actions, frontCard, { frontCard = it }, manages = managesHere, onRevoke = { confirmRevoke = true })
            // The clock its limits' days and its times are in, in tafs cloud's words.
            taf.timeZoneWords?.let { Text(it, style = scaled(13, color = Theme.dim), modifier = Modifier.padding(horizontal = 4.dp)) }
            taf.scheduleTimeZoneWords?.let { Text(it, style = scaled(13, color = Theme.amberText), modifier = Modifier.padding(horizontal = 4.dp)) }
            // Halting is a job of its own: its row opens its screen. Its usage is the account's Usage, with this taf's count.
            // Only what this person manages, as KIFF says: a Viewer sees no Halt, refused only once tapped.
            if (state.canHalt(taf.id) && state.manages(taf.id)) {
              val version = state.tafSetups[taf.id]?.versions
              VersionsRow({ onVersions(taf.id) }, title = version?.title ?: Words.Version.changesRow, summary = version?.summary)
            }
            if (state.canHalt(taf.id) && state.manages(taf.id) && taf.state != TafState.revoked) HaltRow(taf.id, state.haltOf(taf.id), { onHalt(taf.id) })
            if (state.canHalt(taf.id)) UsageRow { onUsage(taf.id) }
          }
          Section.Setup -> {
            // How it runs; removing it for good (its Cards revoked, its schedule, chats, run reports, memory and key deleted, its
            // name freed) is its last row.
            val managesHere = state.mode == Mode.kiff && taf.cloudId != null && state.manages(taf.id)
            SetupTab(taf, state, actions, onVersions = { onVersions(taf.id) }, onConnectApps = onConnectApps, onRemove = if (managesHere) ({ confirmRemove = true }) else null, onActivity = { activitySelection = it; section = Section.Activity })
          }
          Section.Chat -> Unit
        }
      }
    }
    }
    if (side) {
      Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.line))
      SideColumn(taf, state, actions, frontCard, { frontCard = it }) { section = Section.Card }
    }
    }

    if (confirmRevoke) {
      DriveAlert(
        onDismissRequest = { confirmRevoke = false },
        title = { Text(Words.Taf.revokeTitle(taf.name)) },
        text = { Text(Words.Taf.revokeBody) },
        confirmButton = {
          TextButton({ confirmRevoke = false; actions.revoke(taf.id) }, modifier = Modifier.confirms()) { Text(Words.Taf.revokeAndStop, color = Theme.no) }
        },
        dismissButton = { TextButton({ confirmRevoke = false }) { Text(Words.Common.cancel, color = Theme.soft) } },
        containerColor = Theme.panel,
      )
    }
    if (confirmRemove) {
      DriveAlert(
        onDismissRequest = { confirmRemove = false },
        title = { Text(Words.Taf.removeTitle(taf.name)) },
        text = { Text(Words.Taf.removeBody) },
        confirmButton = {
          TextButton({
            confirmRemove = false
            removeScope.launch { actions.remove(taf.id)?.let { removal = it } }
          }, modifier = Modifier.confirms()) { Text(Words.Taf.Remove.button, color = Theme.no) }
        },
        dismissButton = { TextButton({ confirmRemove = false }) { Text(Words.Common.cancel, color = Theme.soft) } },
        containerColor = Theme.panel,
      )
    }
    removal?.let { words ->
      DriveAlert(
        onDismissRequest = { removal = null },
        title = { Text(Words.Taf.removeNamed(taf.name)) },
        text = { Text(words) },
        confirmButton = { TextButton({ removal = null }) { Text(Words.Common.ok, color = Theme.amberText) } },
        containerColor = Theme.panel,
      )
    }
  }
}

/** The way back to the list: the screens inside a tab keep the tab bar, as a navigation stack does. */
@Composable
internal fun BackBar(onBack: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onBack).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = null, tint = Theme.amber, modifier = Modifier.size(20.dp))
    Text(Words.Common.back, style = scaled(17, color = Theme.amber))
  }
}

/**
 * The taf's header, one row: back (where the page isn't beside the list), its avatar and name, and its state. Its tools aren't
 * listed here: the Card tab says what it may do, in KIFF's words.
 */
@Composable
private fun Header(taf: Taf, onBack: (() -> Unit)?) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = if (onBack == null) 12.dp else 0.dp, end = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onBack != null) {
      Box(Modifier.size(44.dp).clickable(onClickLabel = Words.Common.back, onClick = onBack), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = Words.Common.back, tint = Theme.amber, modifier = Modifier.size(20.dp))
      }
    }
    TafAvatar(taf, 30.dp)
    Text(taf.name, style = scaled(18, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    StateChip(taf.state)
  }
}

/**
 * What it did and what it's waiting for: what waits for you first (its holds, its doors' knocks), then its work and last run, then
 * its Record by day. Each request opens step by step; each call opens what KIFF signed.
 */
@Composable
private fun Activity(taf: Taf, state: AppState, actions: TafActions) {
  state.heldCalls(taf.id).forEach { HeldCallCard(it, state, actions.answer) }
  if (state.mode == Mode.kiff && taf.cloudId != null) {
    if (state.manages(taf.id)) {
      LaunchedEffect(taf.id) { actions.doors.load(taf.id) }
      KnocksPanel(taf, state, actions.doors)
    }
    RunsPanel(taf, taf.id in state.runsInFlight, actions, state.runNowQuiet[taf.id])
    WorkPanel(taf, state, actions)
    RunReportPanel(taf.id, state.reports[taf.id], actions)
  }
  ActivityRecord(state.entries(taf.id), state)
}

@Composable
private fun CardTab(taf: Taf, issuer: String, actions: TafActions, front: String?, onFront: (String) -> Unit, manages: Boolean, onRevoke: () -> Unit) {
  val cards = taf.shownCards
  val shown = cards.firstOrNull { it.id == front } ?: cards.firstOrNull()
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    CardStack(cards, issuer, Theme.tafColor(taf.colorIndex), front, onFront, revoked = taf.card.revoked)
  }
  // All of the front Card's limits: the face shows the main one.
  CardLines(shown?.rules ?: emptyList())
  // Its totals for each item apart ("1 call in any 7 days for each invoice id"), as tafs cloud says them.
  shown?.eachItem?.forEach { Text(it, style = scaled(14, color = Theme.ink), modifier = Modifier.fillMaxWidth()) }
  // How this is counted: KIFF's lines for each tool's profile, as approved, under its limits.
  shown?.counted?.takeIf { it.isNotEmpty() }?.let { HowCounted(it) }
  // How much of its totals is from calls the tool refused, in KIFF's words: for a live, active Card only.
  shown?.takeIf(::countsForReal)?.let(::pendingOf)?.forEach { Text(it, style = scaled(13, color = Theme.soft)) }
  // Why its amount has no currency, in KIFF's words, and where to fix it: Coach, for whoever manages the taf.
  shown?.total?.unitWords?.let { words ->
    Text(words, style = scaled(13, color = Theme.soft), modifier = Modifier.fillMaxWidth())
    LocalImproveInCoach.current?.takeIf { manages }?.let { improve ->
      Text(
        bot.tafs.app.model.ConnectionWords.RECONNECT_IN_COACH,
        style = scaled(13, FontWeight.SemiBold, Theme.amberText),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { improve(taf.id) }.padding(vertical = 12.dp),
      )
    }
  }
  // Revoke: in the sample its own; signed in, KIFF's, for whoever manages it. Pausing is in Setup.
  if (taf.state != TafState.revoked && (taf.cloudId == null || manages)) {
    OutlineButton(Words.Taf.revoke, onRevoke, Modifier.fillMaxWidth().confirms(), color = Theme.no)
  }
}

/** When it runs, with Run now and Pause or Resume; a taf that works from its chat has no schedule to run or pause. */
@Composable
internal fun RunsPanel(taf: Taf, inFlight: Boolean, actions: TafActions, quiet: String? = null) {
  val scope = rememberCoroutineScope()
  // Set on the tap itself, before the request starts, so a second tap can't send one.
  var tapped by remember { mutableStateOf(false) }
  Panel {
    Eyebrow(Words.Taf.runs)
    if (taf.worksFromChat) {
      Text(Words.Taf.worksFromChat, style = scaled(15, color = Theme.ink))
    } else {
      Text(if (taf.paused) Words.Taf.State.paused else taf.nextRun?.let { Words.Taf.nextRunAt(shortTime(it)) } ?: Words.Taf.onItsSchedule, style = scaled(15, color = Theme.ink))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val busy = inFlight || tapped
        OutlineButton(if (busy) Words.Taf.startingRun else Words.Taf.runNow, {
          if (!tapped) {
            tapped = true
            scope.launch {
              actions.runNow(taf.id)
              tapped = false
            }
          }
        }, Modifier.weight(1f), enabled = !busy)
        OutlineButton(if (taf.paused) Words.Taf.resume else Words.Taf.pause, { scope.launch { actions.setPaused(taf.id, !taf.paused) } }, Modifier.weight(1f).confirms())
      }
      // Run now found nothing new: tafs cloud's words, as they are.
      quiet?.let { Text(it, style = scaled(13, color = Theme.soft)) }
    }
  }
}

/**
 * Where the taf's work stands, as tafs cloud says it: its line, then its recent requests and skipped firings, newest first, each in
 * tafs cloud's words. A request needing the owner is amber, a failed one red; a skipped firing isn't a failure, so it's dim. A door
 * reply given up on offers Send again to whoever manages the taf.
 */
@Composable
private fun WorkPanel(taf: Taf, state: AppState, actions: TafActions) {
  val scope = rememberCoroutineScope()
  LaunchedEffect(taf.id) { actions.loadWork(taf.id) }
  val work = state.works[taf.id]
  val error = state.workErrors[taf.id]
  if (work == null && error == null) return
  Panel {
    Eyebrow(Words.Taf.itsWork)
    work?.summary?.line?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(15, color = Theme.ink)) }
    work?.let { Chat.workRows(it) }?.forEach { row ->
      when (row) {
        is Chat.WorkRow.Skipped -> Text(row.firing.words, style = scaled(13, color = Theme.dim))
        is Chat.WorkRow.Request -> Column(
          // A tap opens it step by step, as from the dial's rim.
          Modifier.fillMaxWidth().clickable { scope.launch { actions.openRequest(taf.id, row.request.id) } },
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          val r = row.request
          Text(r.line, style = scaled(14, color = when (r.state) {
            "needs_you" -> Theme.amberText
            "failed" -> Theme.no
            else -> Theme.soft
          }))
          r.deliveries.forEach { d ->
            Text(d.line, style = scaled(12, color = if (d.sendAgain) Theme.amberText else Theme.dim))
            if (d.sendAgain && state.manages(taf.id)) {
              OutlineButton(
                if (r.id in state.sendingAgain) Words.Work.sendingAgain else Words.Work.sendAgain,
                { scope.launch { actions.sendAgain(taf.id, r.id, d.sendPlace) } },
                Modifier.fillMaxWidth(),
                enabled = r.id !in state.sendingAgain,
              )
            }
            // Still not delivered: Coach, asked in tafs cloud's words.
            d.coachWords?.takeIf { d.openCoach && state.mayUseCoach }?.let { words ->
              Text(
                RequestWords.ASK_COACH,
                style = scaled(14, FontWeight.SemiBold, Theme.amberText),
                modifier = Modifier.heightIn(min = 44.dp).clickable { actions.askCoachAbout(taf.id, words) }.padding(vertical = 10.dp),
              )
            }
          }
        }
      }
    }
    error?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
}

/** The taf's latest run on tafs cloud and, once it stops, what it reported. */
@Composable
private fun RunReportPanel(tafId: String, report: RunReport?, actions: TafActions) {
  val scope = rememberCoroutineScope()
  LaunchedEffect(tafId) { actions.loadReport(tafId) }
  Panel {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Eyebrow(Words.Taf.lastRun)
      Spacer(Modifier.weight(1f))
      Text(
        Words.Taf.refresh,
        style = scaled(13, color = Theme.amberText),
        modifier = Modifier.heightIn(min = 44.dp).clickable { scope.launch { actions.loadReport(tafId) } }.padding(top = 12.dp),
      )
    }
    if (report == null) {
      CircularProgressIndicator(Modifier.size(18.dp), color = Theme.amber, strokeWidth = 2.dp)
    } else {
      report.at?.let { at ->
        val how = if (report.finished) Words.Taf.Report.finished else if (report.status == "running") Words.Taf.Report.stillRunning else Words.Taf.Report.noSession
        Text("${shortTime(at)} · $how", style = scaled(12, color = Theme.dim, mono = true))
      }
      when {
        // A taf writes markdown: shown as the chat shows it, never raw (kiff/tafs#96).
        report.text.isNotEmpty() -> MarkdownText(report.text, size = 14, modifier = Modifier.fillMaxWidth())
        report.session == null && report.error == null -> Text(Words.Taf.Report.noRunYet, style = scaled(14, color = Theme.dim))
        !report.finished && report.error == null -> Text(Words.Taf.Report.whenRunEnds, style = scaled(14, color = Theme.dim))
      }
      report.error?.let { Text(it, style = scaled(13, color = Theme.no)) }
    }
  }
}

/** What the taf has written down about how the owner likes the job done. */
@Composable
internal fun MemoryPanel(tafId: String, notes: List<MemoryNote>?, error: String?, actions: TafActions, load: Boolean = true) {
  if (load) LaunchedEffect(tafId) { actions.loadMemory(tafId) }
  // The note waiting for the owner's yes: forgetting can't be undone (the 755 retest).
  var forgetting by remember { mutableStateOf<MemoryNote?>(null) }
  Panel {
    Eyebrow(Words.Taf.Memory.title)
    if (notes != null) {
      if (notes.isEmpty()) {
        Text(Words.Taf.Memory.empty, style = scaled(14, color = Theme.soft))
      }
      notes.forEach { note ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(note.content, style = scaled(15, color = Theme.ink))
            Text("${note.title} · ${shortTime(note.updatedAt)}", style = scaled(12, color = Theme.soft))
          }
          Text(
            Words.Taf.Memory.forget,
            style = scaled(14, FontWeight.SemiBold, Theme.soft),
            modifier = Modifier.heightIn(min = 44.dp).confirms().clickable { forgetting = note },
          )
        }
      }
      Text(
        Words.Taf.Memory.explains,
        style = scaled(12, color = Theme.soft),
      )
    } else if (error == null) {
      CircularProgressIndicator(Modifier.size(18.dp), color = Theme.amber, strokeWidth = 2.dp)
    }
    error?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
  forgetting?.let { note ->
    DriveAlert(
      onDismissRequest = { forgetting = null },
      title = { Text(Words.Taf.Memory.forgetTitle) },
      text = { Text(Words.Taf.Memory.forgetBody(note.content)) },
      confirmButton = { TextButton({ forgetting = null; actions.forget(note, tafId) }, Modifier.confirms()) { Text(Words.Taf.Memory.forget, color = Theme.no) } },
      dismissButton = { TextButton({ forgetting = null }) { Text(Words.Common.cancel, color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
}

/** The second column's width, and the least the chat keeps beside it: as iOS's, the largest iPhone's width. */
private const val SIDE_WIDTH = 360
private const val CHAT_AT_LEAST = 430

/** Beside the chat or the activity on a wide screen: what waits for the owner, then the Cards, and a way to all of them. */
@Composable
private fun SideColumn(taf: Taf, state: AppState, actions: TafActions, front: String?, onFront: (String) -> Unit, onAll: () -> Unit) {
  Column(
    Modifier.width(SIDE_WIDTH.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    val held = state.heldCalls(taf.id)
    if (held.isNotEmpty()) {
      Eyebrow(Words.NeedsYou.title, color = Theme.amber)
      held.forEach { HeldCallCard(it, state, actions.answer) }
    }
    val cards = taf.shownCards
    Eyebrow(Words.Card.heading(count = cards.size))
    CardStack(cards, state.issuer(taf), Theme.tafColor(taf.colorIndex), front, onFront, revoked = taf.card.revoked)
    OutlineButton(Words.Card.allOf(count = cards.size), onAll, Modifier.fillMaxWidth())
  }
}

/** "How this is counted": one row under the Card's limits that opens each tool's KIFF lines, shown as the owner approved them. */
@Composable
private fun HowCounted(counted: List<bot.tafs.app.kiff.CountedTool>) {
  var open by rememberSaveable { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      (if (open) "▾ " else "▸ ") + bot.tafs.app.model.CardWords.HOW_COUNTED,
      style = scaled(14, FontWeight.SemiBold, Theme.ink),
      modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = !open }.padding(vertical = 12.dp),
    )
    if (open) counted.forEach { tool ->
      Text(tool.title.ifBlank { tool.tool }, style = scaled(13, FontWeight.SemiBold, Theme.ink))
      tool.lines.forEach { Text(it, style = scaled(13, color = Theme.soft)) }
    }
  }
}
