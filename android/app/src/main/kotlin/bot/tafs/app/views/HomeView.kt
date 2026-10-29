package bot.tafs.app.views

import bot.tafs.app.model.shortTime
import bot.tafs.app.model.dayTitle
import bot.tafs.app.model.localDate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import bot.tafs.app.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Dot
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.Mascot
import bot.tafs.app.design.Meter
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.StateChip
import bot.tafs.app.design.TafAvatar
import bot.tafs.app.design.brandBackground
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.Money
import bot.tafs.app.model.Taf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeView(
  state: AppState,
  onNeedsYou: () -> Unit,
  onPlayNext: suspend () -> Unit,
  onOpenTaf: (String) -> Unit,
  onNewTaf: () -> Unit,
  onSettings: () -> Unit,
  onHaltAll: () -> Unit,
  onCoach: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Pulling to refresh plays the sample's next round; signed in, it reads KIFF again.
  val sample = state.mode == Mode.sample
  var refreshing by remember { mutableStateOf(false) }
  val pullScope = rememberCoroutineScope()
  PullToRefreshBox(
    isRefreshing = refreshing,
    onRefresh = {
      pullScope.launch {
        refreshing = true
        try { onPlayNext() } finally { refreshing = false }
      }
    },
    modifier = modifier.fillMaxSize(),
  ) {
    LazyColumn(
      Modifier.fillMaxSize().statusBarsPadding(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Header(state.accountLabel, showNew = sample, showCoach = state.mayUseCoach, onSettings, onNewTaf, onCoach)
          // KIFF can't be reached just now: said quietly, under the account's name.
          state.unreachable?.let { UnreachableLine(it, state.retryingRefresh) }
        }
      }
      if (sample) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Eyebrow("They taf. You kiff.")
            Headline(state)
          }
        }
        state.held.firstOrNull()?.let { first ->
          state.taf(first.tafId)?.let { taf -> item { NeedsYouStrip(first, taf, onNeedsYou) } }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { state.tafs.forEach { taf -> TafRow(taf) { onOpenTaf(taf.id) } } } }
        item { OutlineButton("Play the next sample actions", { pullScope.launch { onPlayNext() } }, Modifier.fillMaxWidth(), color = Theme.soft) }
        item {
          Text(
            "Sample data. The Card is checked on this phone; signed in with KIFF, KIFF decides.",
            style = scaled(12, color = Theme.dim),
          )
        }
      } else {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Eyebrow("They taf. You kiff.")
            Headline(state)
          }
        }
        state.held.firstOrNull()?.let { first ->
          state.taf(first.tafId)?.let { taf -> item { NeedsYouStrip(first, taf, onNeedsYou) } }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { state.listedTafs.forEach { taf -> TafRow(taf) { onOpenTaf(taf.id) } } } }
        if (state.otherAgents.isNotEmpty()) {
          // Agents made through KIFF's API, not run by tafs: their Cards, apart.
          item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Eyebrow("Other agents with Cards")
              state.otherAgents.forEach { taf -> TafRow(taf) { onOpenTaf(taf.id) } }
            }
          }
        }
        if (state.listLoaded && !state.listUnread && state.tafs.isEmpty()) {
          item {
            Panel {
              Text("No tafs yet.", style = scaled(15, FontWeight.SemiBold, Theme.ink))
              Text("Connect a tool to set up your first taf. Pull down to refresh.", style = scaled(14, color = Theme.soft))
            }
          }
        }
        // Stopping every taf at once is one row away, with where it stands.
        if (state.canHalt(null) && state.tafs.isNotEmpty()) item { HaltRow(null, state.haltOf(null), onHaltAll) }
        item { Text("Signed in with KIFF. KIFF decides every action. Pull down to refresh.", style = scaled(12, color = Theme.dim)) }
      }
    }
  }
}

/** KIFF's status page, served apart from KIFF, so it answers when KIFF doesn't. */
private const val STATUS_PAGE = "https://status.kiff.dev"

/** While a refresh can't get through: one quiet line under the account's name, with the status page beside it. */
@Composable
fun UnreachableLine(words: String, retrying: Boolean) {
  val uri = androidx.compose.ui.platform.LocalUriHandler.current
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(Theme.amber))
    Text(if (retrying) "$words · trying again" else words, style = scaled(12, color = Theme.dim))
    Text("Status", style = scaled(12, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.clickable { uri.openUri(STATUS_PAGE) }.padding(vertical = 8.dp))
  }
}

/** The bar above the list: the mascot and the account it is in, which open Settings, and a new taf. */
@Composable
private fun Header(accountLabel: String?, showNew: Boolean, showCoach: Boolean, onSettings: () -> Unit, onNewTaf: () -> Unit, onCoach: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
    Row(
      Modifier.clickable(onClick = onSettings).semantics { contentDescription = accountLabel?.let { "Settings, in $it" } ?: "Settings" },
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Mascot(34.dp)
      // The account this is, always in sight.
      accountLabel?.let { Text(it, style = scaled(13, FontWeight.SemiBold, Theme.soft), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    // Coach: where tafs are made, connected and changed, for the people KIFF lets use it.
    if (showCoach) {
      Text(
        "✦ Coach",
        style = scaled(14, FontWeight.SemiBold, Color.White),
        modifier = Modifier.heightIn(min = 36.dp).clip(CircleShape).brandBackground().clickable(onClick = onCoach).padding(horizontal = 14.dp, vertical = 8.dp),
      )
    }
    // A new taf starts in Coach mode once signed in; the sample starts from its form.
    if (showNew) {
      Box(
        Modifier.size(36.dp).clip(CircleShape).brandBackground().clickable(onClick = onNewTaf).semantics { contentDescription = "Start a new taf" },
        contentAlignment = Alignment.Center,
      ) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = Theme.amberInk, modifier = Modifier.size(18.dp))
      }
    }
  }
}

@Composable
private fun Headline(state: AppState) {
  val working = state.workingCount
  val waiting = state.held.size
  Column {
    // Never "0 tafs working" before the tafs are read (#91).
    if (state.mode == Mode.kiff && !state.listLoaded) {
      Text("Loading your tafs…", style = scaled(36, FontWeight.Bold, Theme.dim, display = true))
      return@Column
    }
    if (state.mode == Mode.kiff && state.listUnread) {
      Text("Couldn't load your tafs.", style = scaled(36, FontWeight.Bold, Theme.dim, display = true))
      return@Column
    }
    Text("$working ${if (working == 1) "taf" else "tafs"} working.", style = scaled(36, FontWeight.Bold, Theme.ink, display = true))
    Text(
      if (waiting == 0) "Nothing needs you." else "$waiting ${if (waiting == 1) "needs" else "need"} you.",
      style = scaled(36, FontWeight.Bold, Theme.dim, display = true),
    )
  }
}

@Composable
private fun NeedsYouStrip(call: HeldCall, taf: Taf, onClick: () -> Unit) {
  val shape = RoundedCornerShape(16.dp)
  Row(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(Theme.amber.copy(alpha = 0.1f))
      .border(BorderStroke(1.dp, Theme.amber.copy(alpha = 0.4f)), shape)
      .clickable(onClick = onClick)
      .padding(14.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Dot(Theme.amber, 10.dp)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text("${taf.name}: ${call.proposed.summary}", style = scaled(14, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("${call.reason} · answer by ${shortTime(call.expiresAt)}", style = scaled(13, color = Theme.soft), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text("Answer", style = scaled(14, FontWeight.Medium, Theme.amberText))
  }
}

@Composable
fun TafRow(taf: Taf, onClick: () -> Unit) {
  val shape = RoundedCornerShape(18.dp)
  Column(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(Theme.panel)
      .border(BorderStroke(1.dp, Theme.line), shape)
      .clickable(onClick = onClick)
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      TafAvatar(taf)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(taf.name, style = scaled(15, FontWeight.SemiBold, Theme.ink))
          taf.badge?.let {
            Text(
              it,
              style = scaled(11, FontWeight.Bold, androidx.compose.ui.graphics.Color.White),
              modifier = Modifier.clip(CircleShape).background(Theme.amber).padding(horizontal = 7.dp, vertical = 2.dp),
            )
          }
        }
        Text(taf.now, style = scaled(13, color = Theme.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      StateChip(taf.state)
    }
    val rule = taf.card.rules.firstOrNull { it.perWindow != null }
    val limit = rule?.perWindow
    if (rule != null && limit != null) {
      val used = taf.usedOf(rule)
      // KIFF couldn't read the use: say so, never show an empty meter.
      val unknown = taf.usageUnknown?.contains(rule.action) == true
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text("${rule.label} · ${limit.formatted} ${rule.window.label}", style = scaled(12, color = Theme.dim))
          Text(if (unknown) "use unavailable" else used.formatted, style = scaled(12, color = Theme.dim, mono = true))
        }
        if (!unknown) Meter(used.minor.toDouble() / maxOf(limit.minor, 1L), Theme.tafColor(taf.colorIndex))
      }
    }
  }
}
