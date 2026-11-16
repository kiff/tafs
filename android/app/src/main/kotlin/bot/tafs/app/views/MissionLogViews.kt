package bot.tafs.app.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.R
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.design.tapping
import bot.tafs.app.kiff.HomeLabelMission
import bot.tafs.app.kiff.MissionLogItem
import bot.tafs.app.kiff.MissionLogPage
import bot.tafs.app.kiff.MissionLogs
import bot.tafs.app.model.Words
import kotlinx.coroutines.launch

/**
 * A mission's row on the tafs list filtered to its label (Missions v2.1, mockup §3): its goal in the person's own words, and
 * Log, which opens what its tafs and the lead did, read only.
 */
@Composable
internal fun MissionRow(mission: HomeLabelMission, onLog: () -> Unit) {
  Panel(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(mission.goal?.takeIf { it.isNotBlank() } ?: mission.name, style = scaled(15, FontWeight.SemiBold), modifier = Modifier.weight(1f))
      OutlineButton(Words.Mission.log, onClick = onLog)
    }
  }
}

/** The colour each kind of entry is told apart by (mockup §4): delivered green, handed blue, stuck amber, decided ink, a note grey. */
internal fun kindColor(kind: String): Color = when (kind) {
  "delivered" -> Theme.ok
  "handed" -> Theme.blue
  "stuck" -> Theme.amberText
  "decided" -> Theme.ink
  else -> Theme.dim
}

/**
 * A mission's log (Missions v2.1 §6): read only, newest first, a page at a time as the person scrolls. Each entry is drawn as
 * tafs cloud words it; a removed taf's entries stay, greyed and marked removed, so the history is whole.
 */
@Composable
fun MissionLogView(mission: HomeLabelMission, load: suspend (String, String?) -> MissionLogPage?, onBack: () -> Unit) {
  var pages by remember(mission.id) { mutableStateOf<List<MissionLogPage>>(emptyList()) }
  var failed by remember(mission.id) { mutableStateOf(false) }
  var loading by remember(mission.id) { mutableStateOf(true) }
  val scope = rememberCoroutineScope()
  suspend fun more() {
    if (pages.isNotEmpty() && pages.last().before == null) return
    loading = true
    val page = load(mission.id, pages.lastOrNull()?.before)
    if (page == null) failed = true else { failed = false; pages = pages + page }
    loading = false
  }
  LaunchedEffect(mission.id) { more() }
  val items = MissionLogs.drawn(pages)
  LazyColumn(
    Modifier.fillMaxSize().background(Theme.background).statusBarsPadding().padding(horizontal = 20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
        Row(Modifier.tapping(onClick = onBack).semantics { contentDescription = Words.Common.back }, verticalAlignment = Alignment.CenterVertically) {
          Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = null, tint = Theme.amber, modifier = Modifier.size(20.dp))
          Text(Words.Common.back, style = scaled(16, color = Theme.amber))
        }
        Text(mission.name, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        mission.goal?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(15, color = Theme.soft)) }
        Text(Words.Mission.readOnly, style = scaled(12, color = Theme.dim))
      }
    }
    items(items, key = { it.id.ifEmpty { it.at + it.words } }) { LogEntry(it) }
    item {
      when {
        failed -> OutlineButton(Words.Common.tryAgain, onClick = { scope.launch { more() } })
        loading -> Text(Words.Common.loading, style = scaled(13, color = Theme.dim))
        pages.lastOrNull()?.before != null -> OutlineButton(Words.Common.more, onClick = { scope.launch { more() } })
        items.isEmpty() -> Text(Words.Common.nothingYet, style = scaled(13, color = Theme.dim))
      }
    }
  }
}

@Composable
private fun LogEntry(item: MissionLogItem) {
  val dim = item.removed
  Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      MissionLogs.byName(item)?.let { Text(it, style = scaled(14, FontWeight.SemiBold, if (dim) Theme.dim else Theme.ink)) }
      if (item.removed) Text(Words.Mission.removed, style = scaled(12, color = Theme.dim))
      item.kindWords?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(12, FontWeight.SemiBold, if (dim) Theme.dim else kindColor(item.kind))) }
      Text(MissionLogs.shownAt(item.at), style = scaled(12, color = Theme.dim), modifier = Modifier.weight(1f, fill = false))
    }
    Text(item.words, style = scaled(15, color = if (dim) Theme.dim else Theme.ink))
    if (item.evidence.isNotEmpty() || item.value != null) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        item.evidence.forEach { e ->
          Text(
            MissionLogs.evidenceWords(e),
            style = scaled(12, color = Theme.soft),
            modifier = Modifier.background(Theme.raised, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
        item.value?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, FontWeight.SemiBold, if (dim) Theme.dim else Theme.ink)) }
      }
    }
  }
}
