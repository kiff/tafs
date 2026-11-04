package bot.tafs.app.views

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
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
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
  var showJob by remember { mutableStateOf(false) }
  var workflowOpen by rememberSaveable(taf.id) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val improve = LocalImproveInCoach.current
  Panel {
    Eyebrow("Connected tools")
    (error ?: source?.connectionsError)?.let { Text(it, style = scaled(13, color = Theme.no)); TextButton({ scope.launch { actions.loadSetup(taf.id) } }) { Text("Try again", color = Theme.amberText) } }
    if (source == null && error == null) CircularProgressIndicator(Modifier.size(24.dp), color = Theme.amber)
    val apps = source?.connections.orEmpty()
    BoxWithConstraints(Modifier.fillMaxWidth().height(280.dp)) {
      val radius = maxWidth / 2 - 42.dp
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
          .clip(CircleShape).background(Theme.raised).clickable(onClick = connect).semantics { contentDescription = "Connect an app" }, contentAlignment = Alignment.Center) {
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
            Column { Text(step.label, style = scaled(15, FontWeight.SemiBold, Theme.ink)); Text(step.words, style = scaled(13, color = Theme.soft)); step.app?.let { Text(it, style = scaled(12, color = Theme.amberText)) } }
          }
        }
        improve?.let { TextButton({ it(taf.id) }) { Text("Change the workflow with Coach", color = Theme.amberText) } }
      }
    }
  }
  source?.connections?.firstOrNull { it.id == chosen }?.let { app ->
    ModalBottomSheet(onDismissRequest = { chosen = null }, sheetState = rememberModalBottomSheetState(), containerColor = Theme.background) {
      Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(app.name, style = scaled(26, FontWeight.Bold, Theme.ink, display = true))
        Eyebrow("What it may do")
        app.actions.forEach { action ->
          Text(action.title, style = scaled(16, FontWeight.SemiBold, Theme.ink))
          action.limits.forEach { Text(it, style = scaled(13, color = Theme.soft)) }
          Text(action.stepWords, style = scaled(13, color = Theme.dim))
        }
        app.workflowSteps.forEach { step -> Text(step.label + " · " + step.words, style = scaled(14, color = Theme.soft)) }
        TextButton({ chosen = null; onActivity(app.stats.activityQuery) }, enabled = app.actions.isNotEmpty()) { Text(app.stats.words + " ›", color = if (app.stats.complete) Theme.amberText else Theme.no) }
        if (state.manages(taf.id)) {
          improve?.let { TextButton({ chosen = null; it(taf.id) }) { Text("Add actions with Coach", color = Theme.amberText) } }
          var confirming by remember { mutableStateOf(false) }
          TextButton({ confirming = true }) { Text("Disconnect", color = Theme.no) }
          if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("Disconnect ${app.name}?") },
            text = { Text("Its actions stop working for this taf.") }, containerColor = Theme.panel,
            confirmButton = { TextButton({ confirming = false; scope.launch { if (actions.connections.disconnect(app.id)) { chosen = null; actions.loadSetup(taf.id) } } }) { Text("Disconnect", color = Theme.no) } },
            dismissButton = { TextButton({ confirming = false }) { Text("Cancel", color = Theme.soft) } })
        }
        Spacer(Modifier.height(20.dp))
      }
    }
  }
  if (showJob) AlertDialog(onDismissRequest = { showJob = false }, title = { Text(taf.name) }, text = { Text(taf.job) },
    confirmButton = { TextButton({ showJob = false }) { Text("Done", color = Theme.amberText) } }, containerColor = Theme.panel)
}

@Composable
internal fun TafActivityList(taf: Taf, state: AppState, actions: TafActions, selection: String, clearSelection: () -> Unit, modifier: Modifier = Modifier) {
  var filter by rememberSaveable(taf.id, selection) { mutableStateOf("all") }
  val key = "${taf.id}:$filter:$selection"; val cache = state.activityPages[key]
  val list = rememberLazyListState(); val scope = rememberCoroutineScope()
  LaunchedEffect(taf.id, filter, selection) { actions.loadActivity(taf.id, filter, false, selection) }
  val next = cache?.pages?.lastOrNull()?.nextCursor
  val atEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 3 } }
  LaunchedEffect(atEnd, next, filter, selection) { if (atEnd && next != null) actions.loadActivity(taf.id, filter, true, selection) }
  LazyColumn(modifier, state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    item { state.heldCalls(taf.id).forEach { HeldCallCard(it, state, actions.answer) } }
    item { RetainedRuns(taf, state, actions) }
    if (selection.isNotBlank()) item { TextButton(clearSelection) { Text("All apps", color = Theme.amberText) } }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { cache?.pages?.firstOrNull()?.filters?.forEach { choice ->
        FilterChip(selected = filter == choice.id, onClick = { filter = choice.id }, label = { Text(choice.title) })
      } }
    }
    cache?.pages?.forEachIndexed { page, value -> value.groups.forEach { group ->
      if (group.title.isNotBlank()) item(key = "heading-$page-${group.id}") { Eyebrow(group.title) }
      items(group.entries, key = { it.id }) { entry -> SourceActivityRow(entry, taf, state) }
    } }
    cache?.error?.let { error -> item { Text(error, color = Theme.no); TextButton({ scope.launch { actions.loadActivity(taf.id, filter, false, selection) } }) { Text("Try again", color = Theme.amberText) } } }
    if (cache == null || cache.loading) item { CircularProgressIndicator(Modifier.size(24.dp), color = Theme.amber) }
    if (cache != null && cache.pages.isNotEmpty() && cache.pages.all { it.entries.isEmpty() } && next == null) item { Text("Nothing yet.", color = Theme.dim) }
  }
}

/** The adapter copies signed evidence; it does not derive a decision or a display sentence. */
@Composable
private fun SourceActivityRow(source: TafActivityEntry, taf: Taf, state: AppState) {
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
    if (call.receipt?.status == "signed") Text("KIFF receipt", style = scaled(12, color = Theme.ok))
  }
  if (open) ReceiptSheet(entry, state) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetainedRuns(taf: Taf, state: AppState, actions: TafActions) {
  var all by rememberSaveable(taf.id) { mutableStateOf(false) }
  LaunchedEffect(taf.id) { actions.loadRuns(taf.id, false, false) }
  val source = state.runPages["${taf.id}:false"]
  val scope = rememberCoroutineScope()
  Panel {
    Eyebrow(source?.pages?.firstOrNull()?.title ?: "Its work")
    source?.pages?.forEach { page -> page.requests.forEach { run ->
      TextButton({ scope.launch { actions.openRequest(taf.id, run.id) } }) { Text(run.line, color = Theme.ink) }
    } }
    source?.error?.let { Text(it, color = Theme.no) }
    TextButton({ all = true }) { Text(source?.pages?.firstOrNull()?.moreTitle ?: "Show all runs", color = Theme.amberText) }
  }
  if (all) ModalBottomSheet(onDismissRequest = { all = false }, containerColor = Theme.background) {
    val cache = state.runPages["${taf.id}:true"]; val list = rememberLazyListState()
    LaunchedEffect(taf.id) { actions.loadRuns(taf.id, false, true) }
    val next = cache?.pages?.lastOrNull()?.nextCursor
    val end by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 3 } }
    LaunchedEffect(end, next) { if (end && next != null) actions.loadRuns(taf.id, true, true) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 600.dp), state = list, contentPadding = PaddingValues(16.dp)) {
      item { Text("Its work", style = scaled(24, FontWeight.Bold, Theme.ink)) }
      cache?.pages?.forEach { page -> items(page.requests, key = { it.id }) { run -> TextButton({ scope.launch { actions.openRequest(taf.id, run.id) } }) { Text(run.line, color = Theme.ink) } } }
      if (cache?.loading != false) item { CircularProgressIndicator(color = Theme.amber) }
      cache?.error?.let { item { Text(it, color = Theme.no); TextButton({ scope.launch { actions.loadRuns(taf.id, false, true) } }) { Text("Try again", color = Theme.amberText) } } }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemorySummaryPanel(taf: Taf, state: AppState, actions: TafActions) {
  var all by rememberSaveable(taf.id) { mutableStateOf(false) }
  val summary = state.tafSetups[taf.id]?.memory
  Panel {
    Eyebrow("What it remembers")
    summary?.let { Text(it.title, style = scaled(15, FontWeight.SemiBold, Theme.ink)); it.latest?.let { note -> Text(note, style = scaled(14, color = Theme.soft), maxLines = 2, overflow = TextOverflow.Ellipsis) } }
    TextButton({ all = true }) { Text("See all", color = Theme.amberText) }
  }
  if (all) ModalBottomSheet(onDismissRequest = { all = false }, containerColor = Theme.background) {
    LaunchedEffect(taf.id) { actions.loadAllMemory(taf.id) }
    Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) {
      MemoryPanel(taf.id, state.memories[taf.id], state.memoryErrors[taf.id], actions, load = false)
    }
  }
}
