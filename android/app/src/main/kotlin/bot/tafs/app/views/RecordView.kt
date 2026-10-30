package bot.tafs.app.views

import bot.tafs.app.model.shortTime
import bot.tafs.app.model.dayTitle
import bot.tafs.app.model.localDate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

/** The Record's filters (kiff/tafs#167). */
enum class RecordFilter(val title: String) {
  All("All"), MoneyMoved("Money moved"), Stopped("Stopped"), Waiting("Waiting");

  fun keeps(e: RecordEntry): Boolean = when (this) {
    All -> true
    MoneyMoved -> e.amountText != null && (e.kind == Kind.done || e.kind == Kind.approved)
    Stopped -> e.kind == Kind.refused || e.kind == Kind.notDone || e.kind == Kind.revoked
    Waiting -> e.kind == Kind.held || e.kind == Kind.sending || e.kind == Kind.unknown
  }
}

private data class Day(val date: LocalDate, val lines: MutableList<RecordEntry> = mutableListOf(), val reads: MutableList<RecordEntry> = mutableListOf())

/** The Record by day, newest first; a day's reads apart, to fold. */
private fun days(record: List<RecordEntry>, filter: RecordFilter): List<Day> {
  val out = mutableListOf<Day>()
  for (entry in record.filter(filter::keeps).sortedByDescending { it.at }) {
    val date = localDate(entry.at)
    if (out.lastOrNull()?.date != date) out += Day(date)
    if (entry.readOnly == true && filter == RecordFilter.All) out.last().reads += entry else out.last().lines += entry
  }
  return out
}

/**
 * The Record (kiff/tafs#167): every call each taf made, with how much, who decided and what the
 * tool did. Reads fold into one line a day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordView(state: AppState, modifier: Modifier = Modifier) {
  var filter by rememberSaveable { mutableStateOf(RecordFilter.All) }
  // The days whose reads are shown, not folded.
  var openReads by rememberSaveable { mutableStateOf(setOf<String>()) }
  val days = days(state.record, filter)

  LazyColumn(
    modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item { ScreenTitle("Record") }
    item { Text("Everything your tafs did, were refused, or asked you. Newest first.", style = scaled(14, color = Theme.soft)) }
    item {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RecordFilter.entries.forEach { f -> Chip(f.title, selected = filter == f) { filter = f } }
      }
    }
    for (day in days) {
      item(key = "day-${day.date}") {
        Text(dayTitle(day.date).uppercase(), style = scaled(11, FontWeight.Medium, Theme.dim, mono = true), modifier = Modifier.padding(top = 6.dp))
      }
      day.lines.forEach { e -> item(key = e.id) { RecordRow(e, state) } }
      if (day.reads.isNotEmpty()) {
        val key = day.date.toString()
        val open = key in openReads
        if (open) day.reads.forEach { e -> item(key = e.id) { RecordRow(e, state) } }
        item(key = "reads-$key") {
          val n = day.reads.size
          Text(
            "· ${if (n == 1) "1 read" else "$n reads"} of data, within its Card · ${if (open) "Hide" else "Show"}",
            style = scaled(13, color = Theme.dim),
            modifier = Modifier.clickable { openReads = if (open) openReads - key else openReads + key }.padding(horizontal = 4.dp),
          )
        }
      }
    }
    if (days.isEmpty()) {
      item { Text(if (filter == RecordFilter.All) "Nothing yet." else "Nothing here.", style = scaled(14, color = Theme.dim), modifier = Modifier.padding(top = 6.dp)) }
    }
  }
}

@Composable
private fun Chip(title: String, selected: Boolean, onClick: () -> Unit) {
  Text(
    title,
    style = scaled(13, color = if (selected) Color.White else Theme.soft),
    modifier = Modifier
      .clip(CircleShape)
      .background(if (selected) Theme.ink else Theme.panel)
      .border(BorderStroke(1.dp, if (selected) Theme.ink else Theme.line), CircleShape)
      .clickable(onClick = onClick)
      .padding(horizontal = 11.dp, vertical = 5.dp),
  )
}

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
      .clickable(enabled = entry.kind != Kind.inTafs) { showing = true }
      .padding(10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Dot(entry.kind.color, 9.dp, Modifier.padding(top = 6.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(entry.titleWithAmount, style = scaled(14, FontWeight.SemiBold, Theme.ink), modifier = Modifier.weight(1f, fill = false))
        Text(shortTime(entry.at), style = scaled(12, color = Theme.dim, mono = true), modifier = Modifier.padding(start = 6.dp))
      }
      // "<taf> · <what it acted on> · <who decided> · <result at the tool>"; a call from before KIFF said who
      // decided reads as it did.
      val parts = buildList {
        if (showTaf) state.taf(entry.tafId)?.let { add(it.name) }
        entry.onText?.let { add(it) }
        when {
          // Held, and KIFF no longer holds it: it may have expired, or been withdrawn when its run stopped.
          entry.kind == Kind.held && !state.isWaiting(entry) -> { add("no longer waiting"); add("nothing sent") }
          entry.receipt != null || entry.answer != null -> { add(entry.decidedLine); add(entry.resultLine) }
          else -> add(entry.detail)
        }
      }
      Text(parts.joinToString(" · "), style = scaled(13, color = Theme.soft))
      // KIFF says it's signed; the seal is for the receipt's own check.
      if (entry.receipt?.status == "signed") Text("KIFF receipt", style = scaled(12, color = Theme.ok))
      else if (entry.signature != null) Text("Signed on this phone", style = scaled(12, color = Theme.ok))
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
