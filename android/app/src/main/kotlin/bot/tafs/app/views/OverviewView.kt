package bot.tafs.app.views

import bot.tafs.app.design.tapping
import bot.tafs.app.model.Words
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Dot
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.TafAvatar
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.OverviewWords
import bot.tafs.app.model.shortTime

/**
 * Overview, on a tablet (as iOS's `OverviewView`): today across the tafs the person uses or oversees. What needs them, then a
 * line per taf with what it does now, its Card as its page says it, and its last result. Each line opens the taf.
 */
@Composable
fun OverviewView(state: AppState, loadReport: suspend (String) -> Unit, onOpenCalendar: () -> Unit = {}, onOpenTaf: (String) -> Unit) {
  LaunchedEffect(state.tafs.map { it.id }) {
    state.tafs.filter { it.cloudId != null && state.reports[it.id] == null }.forEach { loadReport(it.id) }
  }
  Column(
    Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 980.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Words.Home.Overview.title, style = scaled(30, FontWeight.Bold, Theme.ink, display = true))
        Text(Words.Home.Overview.subtitle, style = scaled(14, color = Theme.dim))
      }
      // The tafs' scheduled work to come (kiff/tafs#534), as iOS's Overview has it.
      androidx.compose.foundation.layout.Box(
        Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Theme.raised).tapping(onClick = onOpenCalendar)
          .semantics { contentDescription = Words.Calendar.heading }.testTag("overview.calendar"),
        contentAlignment = Alignment.Center,
      ) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(bot.tafs.app.R.drawable.ic_calendar), contentDescription = null, tint = Theme.ink, modifier = Modifier.size(22.dp))
      }
    }
    if (state.held.isNotEmpty()) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(Words.NeedsYou.title, color = Theme.amber)
        state.held.forEach { call ->
          val taf = state.taf(call.tafId) ?: return@forEach
          Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Theme.panel).tapping { onOpenTaf(taf.id) }.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Dot(Theme.amber, 8.dp)
            Text("${taf.name} · ${call.proposed.summary}", style = scaled(14, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(Words.NeedsYou.answerBy(shortTime(call.expiresAt)), style = scaled(12, color = Theme.soft))
          }
        }
      }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(Words.Home.Overview.columnTaf to 1.3f, Words.Home.Overview.columnNow to 1f, Words.Home.Overview.columnCard to 1.2f, Words.Home.Overview.columnLastResult to 1.5f).forEach { (title, weight) ->
          Text(title, style = scaled(11, FontWeight.Bold, Theme.dim), modifier = Modifier.weight(weight))
        }
      }
      if (state.tafs.isEmpty()) Text(Words.Home.noTafs, style = scaled(14, color = Theme.dim))
      state.tafs.forEach { taf ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.line))
        val card = OverviewWords.cardLine(taf)
        val last = if (taf.cloudId != null) OverviewWords.lastResult(state.reports[taf.id]) else "–"
        Row(
          Modifier.fillMaxWidth().tapping { onOpenTaf(taf.id) }.semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(taf.name, taf.now, card, last).joinToString(", ")
          },
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Row(Modifier.weight(1.3f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TafAvatar(taf, 30.dp)
            Text(taf.name, style = scaled(15, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          Text(taf.now, style = scaled(13, color = Theme.ink), modifier = Modifier.weight(1f))
          Text(card ?: "–", style = scaled(13, color = Theme.ink), modifier = Modifier.weight(1.2f))
          Text(last, style = scaled(13, color = Theme.soft), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.5f))
        }
      }
      Text(Words.Home.Overview.eachLineOpens, style = scaled(12, color = Theme.dim))
    }
  }
}
