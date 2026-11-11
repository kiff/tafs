package bot.tafs.app.views

import bot.tafs.app.model.Words
import bot.tafs.app.drive.confirms
import bot.tafs.app.model.ConnectionWords
import bot.tafs.app.drive.DriveSheet
import bot.tafs.app.drive.DriveAlert
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.*
import bot.tafs.app.kiff.*
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.Taf
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** Every label, action count, step and statistic is a service representation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectedToolsRing(taf: Taf, state: AppState, actions: TafActions, onConnect: (() -> Unit)?, onActivity: (String) -> Unit) {
  val source = state.tafSetups[taf.id]
  val error = state.tafSetupErrors[taf.id]
  var chosen by rememberSaveable(taf.id) { mutableStateOf<String?>(null) }
  // A connection's sheet opens on tafs cloud's words as they are now: its setup is read again each time one opens.
  LaunchedEffect(chosen) { if (chosen != null) actions.loadSetup(taf.id) }
  var showJob by remember { mutableStateOf(false) }
  var workflowOpen by rememberSaveable(taf.id) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val improve = LocalImproveInCoach.current
  Panel {
    Eyebrow(Words.Connect.connectedTools)
    (error ?: source?.connectionsError)?.let { Text(it, style = scaled(13, color = Theme.no)); TextButton({ scope.launch { actions.loadSetup(taf.id) } }) { Text(Words.Common.tryAgain, color = Theme.amberText) } }
    if (source == null && error == null) CircularProgressIndicator(Modifier.size(24.dp), color = Theme.amber)
    val apps = source?.connections.orEmpty()
    BoxWithConstraints(Modifier.fillMaxWidth().height(280.dp)) {
      val radius = minOf(maxWidth, maxHeight) / 2 - 42.dp
      val slots = apps.size + if (onConnect != null) 1 else 0
      val line = Theme.line; val running = Theme.ok
      Canvas(Modifier.fillMaxSize()) {
        val r = radius.toPx()
        repeat(slots) { i ->
          val angle = -Math.PI / 2 + i * 2 * Math.PI / slots
          val end = Offset(center.x + cos(angle).toFloat() * r, center.y + sin(angle).toFloat() * r)
          drawLine(line, center, end, strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
          if (i < apps.size && apps[i].running) drawArc(running, (angle * 180 / Math.PI - 18).toFloat(), 36f, false,
            topLeft = center - Offset(r, r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(3.dp.toPx()))
        }
      }
      // The ring's center stays a plain letter tile, independent of the list avatar's ring.
      Box(Modifier.align(Alignment.Center).size(54.dp).clip(RoundedCornerShape(16.dp)).background(Theme.tafColor(taf.colorIndex)).clickable { showJob = true }, contentAlignment = Alignment.Center) {
        Text(taf.initial, style = scaled(26, FontWeight.Bold, androidx.compose.ui.graphics.Color.White))
      }
      apps.forEachIndexed { i, app ->
        val angle = -Math.PI / 2 + i * 2 * Math.PI / slots
        Column(Modifier.align(Alignment.Center).offset(x = radius * cos(angle).toFloat(), y = radius * sin(angle).toFloat()).width(82.dp)
          .clip(RoundedCornerShape(14.dp)).background(Theme.panel).clickable { chosen = app.id }.padding(6.dp)
          .semantics { contentDescription = "${app.name}. ${app.actionWords}" }, horizontalAlignment = Alignment.CenterHorizontally) {
          Text(app.name, style = scaled(13, FontWeight.SemiBold, Theme.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
          Text(app.actionWords, style = scaled(11, color = if (app.running) Theme.ok else Theme.soft), maxLines = 2)
        }
      }
      onConnect?.let { connect ->
        val angle = -Math.PI / 2 + apps.size * 2 * Math.PI / slots
        Box(Modifier.align(Alignment.Center).offset(x = radius * cos(angle).toFloat(), y = radius * sin(angle).toFloat()).size(48.dp)
          .clip(CircleShape).background(Theme.raised).clickable(onClick = connect).semantics { contentDescription = Words.Connect.connectAnApp }, contentAlignment = Alignment.Center) {
          Canvas(Modifier.fillMaxSize()) { drawCircle(line, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))) }
          Text("+", style = scaled(26, color = Theme.amberText))
        }
      }
    }
    source?.workflow?.let { workflow ->
      TextButton({ workflowOpen = !workflowOpen }, Modifier.fillMaxWidth()) { Text(workflow.title + " · " + workflow.summary, color = Theme.ink, modifier = Modifier.fillMaxWidth()) }
      if (workflowOpen) {
        workflow.steps.forEach { step ->
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(step.number.toString(), style = scaled(14, FontWeight.SemiBold, if (step.running) Theme.ok else Theme.soft))
            Column {
              Text(step.label, style = scaled(15, FontWeight.SemiBold, Theme.ink))
              // Empty when it would only repeat the label, as tafs cloud sends it.
              if (step.words.isNotEmpty()) Text(step.words, style = scaled(13, color = Theme.soft))
              step.app?.let { Text(it, style = scaled(12, color = Theme.amberText)) }
            }
          }
        }
        improve?.let { TextButton({ it(taf.id) }) { Text(Words.Taf.Workflow.changeWithCoach, color = Theme.amberText) } }
      }
    }
  }
  source?.connections?.firstOrNull { it.id == chosen }?.let { app ->
    // Full height, as every sheet here: the back key closes it, rather than lowering it to half.
    DriveSheet(onDismissRequest = { chosen = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
      Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(app.name, style = scaled(26, FontWeight.Bold, Theme.ink, display = true))
        Text(app.statusWords, style = scaled(14, color = if (app.reconnect) Theme.amberText else Theme.soft))
        app.accountWords?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(14, color = Theme.ink)) }
        app.custodyWords?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, color = Theme.dim)) }
        // A browser connection: the owner signs in to a site themselves, in planque's live view (tafs cloud's words).
        app.siteSignIn?.takeIf { state.manages(taf.id) }?.let { action ->
          TextButton({ scope.launch { actions.connections.siteSignIn(app.id) } }, enabled = state.openingSiteSignIn == null) {
            Text(if (state.openingSiteSignIn == app.id) Words.Common.opening else action.title, color = Theme.amberText)
          }
          if (action.words.isNotBlank()) Text(action.words, style = scaled(13, color = Theme.soft))
        }
        if (app.reconnect && state.manages(taf.id)) TextButton({ actions.connections.reconnectId(app.id) }, enabled = state.connecting == null) { Text(Words.Connect.signInAgain, color = Theme.amberText) }
        // Gap 13, where an owner whose taf fails looks first: its last failed call, and how to fix the connection.
        state.grants.firstOrNull { it.id == app.id }?.let { grant ->
          ConnectionWords.lastFailure(grant, state.record)?.let { Text(it, style = scaled(13, color = Theme.no)) }
          if (!app.reconnect && state.manages(taf.id) && (grant.connected || grant.needsReconnect)) {
            if (grant.signsInThroughProvider) Text(ConnectionWords.throughProvider(grant), style = scaled(13, color = Theme.soft))
            else TextButton(
              { if (grant.takesKey) actions.connections.replaceKey(grant) else actions.connections.reconnect(grant) },
              enabled = state.connecting == null, modifier = Modifier.confirms(),
            ) { Text(if (grant.takesKey) ConnectionWords.REPLACE_KEY else ConnectionWords.SIGN_IN_AGAIN, color = Theme.amberText) }
          }
        }
        state.coachNotes[app.id]?.let { Text(it, color = Theme.no) }
        Eyebrow(Words.Taf.App.whatItMayDo)
        app.actions.forEach { action ->
          Text(action.title, style = scaled(16, FontWeight.SemiBold, Theme.ink))
          action.limits.forEach { Text(it, style = scaled(13, color = Theme.soft)) }
          // Empty after the first when it would repeat the same line on every action ("Ask Coach to set its workflow").
          if (action.stepWords.isNotEmpty()) Text(action.stepWords, style = scaled(13, color = Theme.dim))
        }
        app.workflowSteps.forEach { step -> Text(workflowLine(step), style = scaled(14, color = Theme.soft)) }
        // No saved workflow: said once, under a Workflow line of its own, not under any one action.
        app.workflowWords?.let { words ->
          Eyebrow(Words.Taf.Workflow.title)
          Text(words, style = scaled(14, color = Theme.soft))
        }
        TextButton({ chosen = null; onActivity(app.stats.activityQuery) }, enabled = app.actions.isNotEmpty()) { Text(app.stats.words + " ›", color = if (app.stats.complete) Theme.amberText else Theme.no) }
        if (state.manages(taf.id)) {
          improve?.let { TextButton({ chosen = null; it(taf.id) }) { Text(Words.Taf.App.addActions, color = Theme.amberText) } }
          var confirming by remember { mutableStateOf(false) }
          TextButton({ confirming = true }) { Text(Words.Connect.disconnectButton, color = Theme.no) }
          if (confirming) DriveAlert(onDismissRequest = { confirming = false }, title = { Text(Words.Connect.disconnectAppQuestion(app.name)) },
            text = { Text(Words.Connect.disconnectBody) }, containerColor = Theme.panel,
            confirmButton = { TextButton({ confirming = false; scope.launch { if (actions.connections.disconnect(app.id)) { chosen = null; actions.loadSetup(taf.id) } } }, modifier = Modifier.confirms()) { Text(Words.Connect.disconnectButton, color = Theme.no) } },
            dismissButton = { TextButton({ confirming = false }) { Text(Words.Common.cancel, color = Theme.soft) } })
        }
        Spacer(Modifier.height(20.dp))
      }
    }
  }
  if (showJob) DriveAlert(onDismissRequest = { showJob = false }, title = { Text(taf.name) }, text = { Text(taf.job) },
    confirmButton = { TextButton({ showJob = false }) { Text(Words.Common.done, color = Theme.amberText) } }, containerColor = Theme.panel)
}

@Composable
internal fun TafActivityList(taf: Taf, state: AppState, actions: TafActions, selection: String, clearSelection: () -> Unit, modifier: Modifier = Modifier, onConnectApps: () -> Unit = {}) {
  var filter by rememberSaveable(taf.id, selection) { mutableStateOf("all") }
  val key = "${taf.id}:$filter:$selection"; val cache = state.activityPages[key]
  val list = rememberLazyListState(); val scope = rememberCoroutineScope()
  LaunchedEffect(taf.id, filter, selection) { actions.loadActivity(taf.id, filter, false, selection) }
  val next = cache?.pages?.lastOrNull()?.nextCursor
  val atEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 3 } }
  // Asked again once a load ends: reaching the end while the first page refreshes would otherwise never ask for the next one.
  // The request runs in the screen's scope, so the loading it sets doesn't cancel it; a failed page waits for Try again.
  val loading = cache?.loading == true; val failed = cache?.error != null
  LaunchedEffect(atEnd, next, loading, failed, filter, selection) {
    if (atEnd && next != null && !loading && !failed) scope.launch { actions.loadActivity(taf.id, filter, true, selection) }
  }
  LazyColumn(modifier, state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    // What needs the owner first, the same on both apps: its doors' knocks (for whoever manages it, as Needs you counts them), its
    // held calls, then its work, then its runs.
    if (state.mode == Mode.kiff && state.manages(taf.id)) item(key = "knocks") {
      LaunchedEffect(taf.id) { actions.doors.load(taf.id) }
      KnocksPanel(taf, state, actions.doors)
    }
    item { state.heldCalls(taf.id).forEach { HeldCallCard(it, state, actions.answer) } }
    // Its open currency requests, as the newest page read has them (ahead of what Home last said): loading older pages updates them
    // without dropping history. Their refused calls are folded under them by tafs cloud, and every one stays in the Record.
    val latest = cache?.pages?.lastOrNull()
    items(latest?.cardUnitExceptions.orEmpty(), key = { "cu|" + it.id }) { ask -> CardUnitExceptionPanel(ask, state) }
    latest?.cardUnitExceptionsNotice?.let { notice -> item { Text(notice, style = scaled(13, color = Theme.dim)) } }
    // Where its work stands, as tafs cloud says it: its health with its fixes, its oldest waiting case, Answer within, its requests.
    item(key = "work") { WorkPanel(taf, state, actions) }
    // When it runs, with Run now and Pause, ahead of what it did.
    item(key = "runs") { RunsPanel(taf, taf.id in state.runsInFlight, actions, state.runNowQuiet[taf.id]) }
    item { RetainedRuns(taf, state, actions) }
    if (selection.isNotBlank()) item { TextButton(clearSelection) { Text(Words.Taf.Activity.allApps, color = Theme.amberText) } }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { cache?.pages?.firstOrNull()?.filters?.forEach { choice ->
        FilterChip(selected = filter == choice.id, onClick = { filter = choice.id }, label = { Text(choice.title) })
      } }
    }
    cache?.pages?.forEachIndexed { page, value -> value.groups.forEach { group ->
      if (group.title.isNotBlank()) item(key = "heading-$page-${group.id}") { Eyebrow(group.title) }
      items(group.entries, key = { it.id }) { entry -> SourceActivityRow(entry, taf, state, actions, onConnectApps) }
    } }
    cache?.error?.let { error -> item { Text(error, color = Theme.no); TextButton({ scope.launch { actions.loadActivity(taf.id, filter, false, selection) } }) { Text(Words.Common.tryAgain, color = Theme.amberText) } } }
    if (cache == null || cache.loading) item { CircularProgressIndicator(Modifier.size(24.dp), color = Theme.amber) }
    if (cache != null && cache.pages.isNotEmpty() && cache.pages.all { it.entries.isEmpty() } && next == null) item { Text(Words.Common.nothingYet, color = Theme.dim) }
    else if (next == null && !loading) cache?.pages?.lastOrNull()?.endWords?.let { words -> item { Text(words, style = scaled(13, color = Theme.dim)) } }
  }
}

/** The adapter copies signed evidence; it does not derive a decision or a display sentence. */
@Composable
internal fun SourceActivityRow(source: TafActivityEntry, taf: Taf, state: AppState, actions: TafActions, onConnectApps: () -> Unit) {
  var open by remember { mutableStateOf(false) }
  val kind = runCatching { RecordEntry.Kind.valueOf(if (source.kind == "not_done") "notDone" else source.kind) }.getOrDefault(RecordEntry.Kind.unknown)
  val call = source.call
  val entry = RecordEntry(id = source.id, tafId = taf.id, kind = kind, title = source.title, detail = source.detail, at = call.createdAt,
    readOnly = call.readOnly, amountText = source.amountText, amountValue = call.amount?.value, amountUnit = call.amount?.unit,
    entityId = call.entityId, onHash = call.onHash, onArg = call.onArg, answer = call.answer, receipt = call.receipt, why = call.reasons, madeAt = call.madeAt, reason = call.reason)
  Panel(Modifier.clickable { open = true }) {
    Row(Modifier.fillMaxWidth()) { Text(source.title, style = scaled(15, FontWeight.SemiBold, Theme.ink), modifier = Modifier.weight(1f)); Text(source.time, style = scaled(12, color = Theme.dim)) }
    Text(source.detail, style = scaled(13, color = Theme.soft))
    source.amountText?.let { Text(it, style = scaled(14, FontWeight.Medium, Theme.ink)) }
    if (call.receipt?.status == "signed") Text(Words.Receipt.kiffReceipt, style = scaled(12, color = Theme.ok))
    source.next?.let { NextStepRow(it, source, taf, state, actions, onConnectApps) }
  }
  if (open) ReceiptSheet(entry, state) { open = false }
}

/** One line of Its runs: a run, which opens its steps, or a firing that started no run, in tafs cloud's words. */
@Composable
private fun RunRow(row: bot.tafs.app.model.Chat.WorkRow, taf: Taf, actions: TafActions) {
  val scope = rememberCoroutineScope()
  when (row) {
    is bot.tafs.app.model.Chat.WorkRow.Request -> TextButton({ scope.launch { actions.openRequest(taf.id, row.request.id) } }) { Text(row.request.line, color = Theme.ink) }
    is bot.tafs.app.model.Chat.WorkRow.Skipped -> Text(row.firing.words, style = scaled(13, color = Theme.dim), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetainedRuns(taf: Taf, state: AppState, actions: TafActions) {
  var all by rememberSaveable(taf.id) { mutableStateOf(false) }
  LaunchedEffect(taf.id) { actions.loadRuns(taf.id, false, false) }
  val source = state.runPages["${taf.id}:false"]
  val scope = rememberCoroutineScope()
  Panel {
    Eyebrow(source?.pages?.firstOrNull()?.title ?: Words.Taf.itsWork)
    // Its runs, with the firings that started no run in their place (a failed Run now's check), as tafs cloud words them.
    source?.pages?.forEach { page -> bot.tafs.app.model.Chat.runRows(page).forEach { row -> RunRow(row, taf, actions) } }
    source?.error?.let { Text(it, color = Theme.no) }
    TextButton({ all = true }) { Text(source?.pages?.firstOrNull()?.moreTitle ?: Words.Taf.showAllRuns, color = Theme.amberText) }
  }
  if (all) DriveSheet(onDismissRequest = { all = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background) {
    val cache = state.runPages["${taf.id}:true"]; val list = rememberLazyListState()
    LaunchedEffect(taf.id) { actions.loadRuns(taf.id, false, true) }
    val next = cache?.pages?.lastOrNull()?.nextCursor
    val end by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 3 } }
    val loading = cache?.loading == true; val failed = cache?.error != null
    LaunchedEffect(end, next, loading, failed) { if (end && next != null && !loading && !failed) scope.launch { actions.loadRuns(taf.id, true, true) } }
    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 600.dp), state = list, contentPadding = PaddingValues(16.dp)) {
      item { Text(Words.Taf.itsWork, style = scaled(24, FontWeight.Bold, Theme.ink)) }
      cache?.pages?.forEach { page ->
        items(bot.tafs.app.model.Chat.runRows(page), key = { row -> when (row) { is bot.tafs.app.model.Chat.WorkRow.Request -> row.request.id; is bot.tafs.app.model.Chat.WorkRow.Skipped -> "skipped|" + row.firing.due } }) { row -> RunRow(row, taf, actions) }
      }
      if (cache?.loading != false) item { CircularProgressIndicator(color = Theme.amber) }
      else if (next == null) cache.pages.lastOrNull()?.endWords?.let { words -> item { Text(words, style = scaled(13, color = Theme.dim)) } }
      cache?.error?.let { item { Text(it, color = Theme.no); TextButton({ scope.launch { actions.loadRuns(taf.id, false, true) } }) { Text(Words.Common.tryAgain, color = Theme.amberText) } } }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemorySummaryPanel(taf: Taf, state: AppState, actions: TafActions) {
  var all by rememberSaveable(taf.id) { mutableStateOf(false) }
  val summary = state.tafSetups[taf.id]?.memory
  val scope = rememberCoroutineScope()
  Panel {
    Eyebrow(Words.Taf.Memory.title)
    summary?.let { Text(it.title, style = scaled(15, FontWeight.SemiBold, Theme.ink)); it.latest?.let { note -> Text(note, style = scaled(14, color = Theme.soft), maxLines = 2, overflow = TextOverflow.Ellipsis) } }
    TextButton({ all = true }) { Text(Words.Taf.Memory.seeAll, color = Theme.amberText) }
  }
  if (all) DriveSheet(
    onDismissRequest = { all = false; scope.launch { actions.loadSetup(taf.id) } },
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Theme.background,
  ) {
    LaunchedEffect(taf.id) { actions.loadAllMemory(taf.id) }
    Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) {
      MemoryPanel(taf.id, state.memories[taf.id], state.memoryErrors[taf.id], actions, load = false)
    }
  }
}

/** A workflow step in an app's sheet: "2 · List your disputes", with its words below only when tafs cloud sends any. */
internal fun workflowLine(step: WorkflowStep): String = "${step.number} · ${step.label}" + if (step.words.isEmpty()) "" else "\n${step.words}"

/**
 * A not-done call's next step, as tafs cloud says it (kiff/tafs#476, gap 10): Connect it again (signing in again where its vendor
 * signed it out, else Connect apps), Ask Coach (Coach on this taf, its first message written in, nothing sent), or a line. Only
 * someone who manages the taf gets a button; anyone else, and a kind this app doesn't know, reads the words alone.
 */
@Composable
private fun NextStepRow(next: bot.tafs.app.kiff.NextStep, source: TafActivityEntry, taf: Taf, state: AppState, actions: TafActions, onConnectApps: () -> Unit) {
  val action = bot.tafs.app.model.NextStepWords.action(next, taf.id, state.grants, "${source.title}: ${source.detail}")
  val may = state.manages(taf.id) && when (action) {
    is bot.tafs.app.model.NextStepWords.Action.AskCoach -> state.mayUseCoach
    is bot.tafs.app.model.NextStepWords.Action.SignInAgain -> !state.approvalKeyLost
    else -> true
  }
  if (action == bot.tafs.app.model.NextStepWords.Action.Line || !may) {
    Text(next.words, style = scaled(13, color = Theme.soft))
    return
  }
  val busy = action is bot.tafs.app.model.NextStepWords.Action.SignInAgain && state.connecting != null
  bot.tafs.app.design.OutlineButton(
    if (action is bot.tafs.app.model.NextStepWords.Action.SignInAgain && state.connecting == action.grant.id) state.connectStep ?: next.words else next.words,
    {
      when (action) {
        is bot.tafs.app.model.NextStepWords.Action.SignInAgain -> actions.connections.reconnect(action.grant)
        is bot.tafs.app.model.NextStepWords.Action.AskCoach -> actions.askCoachAbout(taf.id, action.words)
        else -> onConnectApps()
      }
    },
    Modifier.fillMaxWidth(),
    enabled = !busy,
  )
}
