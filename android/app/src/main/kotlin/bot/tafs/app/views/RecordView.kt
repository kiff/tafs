package bot.tafs.app.views

import bot.tafs.app.design.tapping
import bot.tafs.app.model.Words
import bot.tafs.app.model.shortTime
import bot.tafs.app.model.dayTitle
import bot.tafs.app.model.localDate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Dot
import bot.tafs.app.design.ScreenTitle
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.RecordEntry.Kind
import java.time.LocalDate
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected

/** The Record's filters (kiff/tafs#167). */
enum class RecordFilter {
  All, MoneyMoved, Stopped, Waiting;

  val title: String get() = when (this) {
    All -> Words.Record.Filter.all
    MoneyMoved -> Words.Record.Filter.moneyMoved
    Stopped -> Words.Record.Filter.stopped
    Waiting -> Words.Record.Filter.waiting
  }

  fun keeps(e: RecordEntry): Boolean = when (this) {
    All -> true
    MoneyMoved -> e.amountText != null && (e.kind == Kind.done || e.kind == Kind.approved)
    Stopped -> e.kind == Kind.refused || e.kind == Kind.notDone || e.kind == Kind.revoked
    Waiting -> e.kind == Kind.held || e.kind == Kind.sending || e.kind == Kind.unknown
  }
}

private data class Day(val date: LocalDate, val lines: MutableList<RecordEntry> = mutableListOf(), val reads: MutableList<RecordEntry> = mutableListOf())

/** The Record by day, newest first; a day's reads apart, to fold. [taf], one taf's alone. */
private fun days(record: List<RecordEntry>, filter: RecordFilter, taf: String? = null): List<Day> {
  val out = mutableListOf<Day>()
  for (entry in record.filter { filter.keeps(it) && (taf == null || it.tafId == taf) }.sortedByDescending { it.at }) {
    val date = localDate(entry.at)
    if (out.lastOrNull()?.date != date) out += Day(date)
    if (entry.readOnly == true && filter == RecordFilter.All) out.last().reads += entry else out.last().lines += entry
  }
  return out
}

/**
 * What a day's money moved came to, by unit, as each amount was recorded: "€120", or "€120 · $30" across units. Null when no
 * money moved that day.
 */
internal fun spentOn(entries: List<RecordEntry>): String? {
  val sums = entries.filter { RecordFilter.MoneyMoved.keeps(it) }
    .mapNotNull { e -> e.amountValue?.toBigDecimalOrNull()?.let { e.amountUnit to it } }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, values) -> values.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add) }
  if (sums.isEmpty()) return null
  return sums.entries.joinToString(" · ") { (unit, sum) -> bot.tafs.app.model.Money.of(sum.unscaledValue().toLong(), unit, sum.scale()).formatted }
}

/**
 * The Record (kiff/tafs#167): every call each taf made, with how much, who decided and what the
 * tool did. Reads fold into one line a day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordView(
  state: AppState,
  modifier: Modifier = Modifier,
  record: RecordActions? = null,
  tafActions: TafActions? = null,
  onConnectApps: () -> Unit = {},
) {
  var filter by rememberSaveable { mutableStateOf(RecordFilter.All) }
  // One taf's Record, or every taf's (null).
  var taf by rememberSaveable { mutableStateOf<String?>(null) }
  // The days whose reads are shown, not folded.
  var openReads by rememberSaveable { mutableStateOf(setOf<String>()) }
  val days = days(state.record, filter, taf)
  // One case, open from a search: drawn over the Record.
  state.recordCase?.let { case -> RecordCaseSheet(case, state.recordCaseError, state, tafActions, onClose = { record?.closeCase?.invoke() }, onConnectApps = onConnectApps) }

  LazyColumn(
    modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item { ScreenTitle(Words.Record.title) }
    item { Text(Words.Record.lead, style = scaled(14, color = Theme.soft)) }
    // Signed in: search across the person's tafs, and export receipts for whoever sees every taf's Record.
    if (record != null && state.mode == bot.tafs.app.app.Mode.kiff) {
      item(key = "search") {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        androidx.compose.runtime.LaunchedEffect(Unit) { record.loadHint() }
        RecordSearchBox(state.recordHint) { q -> scope.launch { record.search(q, false) } }
      }
      if (state.seesEveryTaf) item(key = "export") { ExportReceiptsRow(record.export) }
      state.recordSearch?.let { search ->
        item(key = "results") {
          val scope = androidx.compose.runtime.rememberCoroutineScope()
          RecordResults(
            search, state, tafActions,
            onCase = { about -> scope.launch { record.openCase(about) } },
            onMore = { scope.launch { record.search(search.q, true) } },
            onConnectApps = onConnectApps,
          )
        }
      }
    }
    // While a search is shown, it stands in for the list below.
    if (record != null && state.recordSearch != null) {
      return@LazyColumn
    }
    // Which taf, above what kind: the two combine.
    if (state.tafs.size > 1) {
      item {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Chip(ALL_TAFS, selected = taf == null) { taf = null }
          state.tafs.forEach { t -> Chip(t.name, selected = taf == t.id, dot = Theme.tafColor(t.colorIndex)) { taf = t.id } }
        }
      }
    }
    item {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RecordFilter.entries.forEach { f -> Chip(f.title, selected = filter == f) { filter = f } }
      }
    }
    for (day in days) {
      item(key = "day-${day.date}") {
        // The day, and what its money moved came to.
        val spent = spentOn(day.lines)
        Text(
          (dayTitle(day.date) + (spent?.let { " · $it" } ?: "")).uppercase(),
          style = scaled(11, FontWeight.Medium, Theme.dim, mono = true),
          modifier = Modifier.padding(top = 6.dp),
        )
      }
      day.lines.forEach { e -> item(key = e.id) { RecordRow(e, state) } }
      if (day.reads.isNotEmpty()) {
        val key = day.date.toString()
        val open = key in openReads
        if (open) day.reads.forEach { e -> item(key = e.id) { RecordRow(e, state) } }
        item(key = "reads-$key") {
          val n = day.reads.size
          Text(
            "· " + Words.Record.reads(count = n) + " · " + (if (open) Words.Record.hide else Words.Record.show),
            style = scaled(13, color = Theme.ok),
            modifier = Modifier.tapping { openReads = if (open) openReads - key else openReads + key }.padding(horizontal = 4.dp),
          )
        }
      }
    }
    if (days.isEmpty()) {
      item { Text(if (filter == RecordFilter.All) Words.Common.nothingYet else Words.Record.nothingHere, style = scaled(14, color = Theme.dim), modifier = Modifier.padding(top = 6.dp)) }
    }
  }
}

@Composable
private fun Chip(title: String, selected: Boolean, dot: Color? = null, onClick: () -> Unit) {
  Row(
    Modifier
      .clip(CircleShape)
      .background(if (selected) Theme.ink else Theme.panel)
      .border(BorderStroke(1.dp, if (selected) Theme.ink else Theme.line), CircleShape)
      .tapping(onClick = onClick)
      .semantics { this.selected = selected }
      .padding(horizontal = 11.dp, vertical = 5.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // A taf's chip carries its colour.
    dot?.let { Dot(it, 8.dp) }
    Text(title, style = scaled(13, color = if (selected) Theme.background else Theme.soft))
  }
}

/** The taf chips' first: every taf's Record. */
val ALL_TAFS get() = Words.Record.allTafs

@Composable
fun RecordRow(entry: RecordEntry, state: AppState, showTaf: Boolean = true) {
  val shape = RoundedCornerShape(14.dp)
  // Done in tafs, not a call: there's no receipt to show (kiff/tafs#236). Anything else opens its sheet.
  var showing by rememberSaveable { mutableStateOf(false) }
  if (showing) ReceiptSheet(entry, state) { showing = false }
  Row(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(Theme.panel)
      .border(BorderStroke(1.dp, Theme.line), shape)
      .tapping(enabled = entry.kind != Kind.inTafs) { showing = true }
      .padding(10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Its taf first, by its colour and name; what happened to it, by the status dot beside the time.
    val taf = if (showTaf) state.taf(entry.tafId) else null
    Dot(taf?.let { Theme.tafColor(it.colorIndex) } ?: entry.kind.color, 9.dp, Modifier.padding(top = 6.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(
          listOfNotNull(taf?.name, entry.titleWithAmount).joinToString(" · "),
          style = scaled(14, FontWeight.SemiBold, Theme.ink),
          modifier = Modifier.weight(1f, fill = false),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
          if (taf != null) Dot(entry.kind.color, 7.dp, Modifier.padding(start = 6.dp))
          Text(shortTime(entry.at), style = scaled(12, color = Theme.dim, mono = true), modifier = Modifier.padding(start = 6.dp))
        }
      }
      // "<what it acted on> · <who decided> · <result at the tool>"; a call from before KIFF said who decided reads as it did.
      val parts = buildList {
        entry.onText?.let { add(it) }
        when {
          // Held, and KIFF no longer holds it: it may have expired, or been withdrawn when its run stopped.
          entry.kind == Kind.held && !state.isWaiting(entry) -> { add(Words.Record.noLongerWaiting); add(Words.Record.nothingSent) }
          entry.receipt != null || entry.answer != null -> { add(entry.decidedLine); add(entry.resultLine) }
          else -> add(entry.detail)
        }
      }
      Text(parts.joinToString(" · "), style = scaled(13, color = Theme.soft))
      // KIFF says it's signed; the seal is for the receipt's own check.
      if (entry.receipt?.status == "signed") Text(Words.Receipt.kiffReceipt, style = scaled(12, color = Theme.ok))
      else if (entry.signature != null) Text(Words.Record.signedOnDevice(bot.tafs.app.model.ThisDevice.word), style = scaled(12, color = Theme.ok))
    }
  }
}

val Kind.color: Color
  get() = when (this) {
    Kind.done, Kind.approved, Kind.started -> Theme.ok
    Kind.held, Kind.unknown, Kind.sending -> Theme.amber
    Kind.refused, Kind.revoked, Kind.notDone -> Theme.no
    Kind.inTafs -> Theme.dim
  }
