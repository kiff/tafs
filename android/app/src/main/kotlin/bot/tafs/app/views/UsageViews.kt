package bot.tafs.app.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import bot.tafs.app.billing.Purchases
import bot.tafs.app.design.PrimaryButton
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.Meter
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.CountedTask
import bot.tafs.app.kiff.Usage
import bot.tafs.app.model.UsageWords
import bot.tafs.app.model.shortTime

/**
 * The account's usage this period, from Settings: the tasks the period has and what is left, who bills the plan,
 * what the tafs did that counted, and what wasn't counted and why. Titled as the account's, with each number
 * saying whose it is, so it reads at a glance with no text explaining it. Adding tasks through Google Play shows only where it's
 * offered (AppState.offersPurchases).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageSheet(state: AppState, actions: AccountActions, onClose: () -> Unit) {
  // Read on this screen: an earlier read isn't shown as now.
  var read by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { actions.refreshUsage(); read = true }

  UsageFrame(UsageWords.TITLE, onClose) {
    val usage = state.usage
    val why = state.usageError
    if (read && usage != null) {
      TasksPanel(usage)
      UsageWords.notice(usage)?.let { Panel { Text(it, style = scaled(15, color = Theme.ink)) } }
      if (state.mode == Mode.kiff) UsageWords.billingLine(usage)?.let { line ->
        Panel {
          Text(line, style = scaled(15, color = Theme.ink))
          // Billed by Google Play: Play's own page to manage or cancel it. The app never points anywhere else to buy.
          if (usage.billing?.source == "google") {
            val uri = LocalUriHandler.current
            Text("Manage", style = scaled(15, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.clickable { uri.openUri(Purchases.MANAGE_SUBSCRIPTIONS) }.padding(vertical = 8.dp))
          }
        }
      }
      if (state.offersPurchases) AddTasksPanel(usage, state, actions)
      usage.packList?.takeIf { it.isNotEmpty() }?.let { packs ->
        Panel {
          Eyebrow("Packs")
          packs.forEach { p -> CountRow(p.name, UsageWords.packLeft(p)) }
        }
      }
      state.aiUse?.takeIf { it.usedPct != null }?.let { ai ->
        Panel {
          Eyebrow("AI use this month")
          Text("${ai.usedPct}%", style = scaled(26, FontWeight.Bold, Theme.ink, display = true))
          Meter((ai.usedPct ?: 0).toDouble() / (ai.asksAtPct ?: 100).coerceAtLeast(1), if (ai.paused) Theme.amber else Theme.ok)
          Text(UsageWords.aiExplain(ai.asksAtPct), style = scaled(13, color = Theme.soft))
          if (ai.paused) Text(UsageWords.AI_ASKING, style = scaled(15, color = Theme.ink))
        }
      }
      // Every taf is listed by KIFF, most at 0 on a busy account: only those that counted.
      usage.tasks.byAgent?.filter { it.counted > 0 }?.takeIf { it.isNotEmpty() }?.let { by ->
        Panel {
          Eyebrow("By taf")
          by.forEach { CountRow(state.tafName(it.agent), "${it.counted}") }
        }
      }
      NotCounted(usage.tasks.free?.lines)
      // Agents that aren't tafs, named as the home list names them; only when they counted something.
      UsageWords.othersLine(usage)?.let { Panel { CountRow("Other agents with Cards", it) } }
      if (state.countedTasks.isNotEmpty()) CountedTasks(state.countedTasks, state)
    } else if (why != null) {
      Panel { Text(why, style = scaled(14, color = Theme.no)) }
    } else {
      Loading(UsageWords.ALL_TAFS)
    }
  }
}

/**
 * Adding tasks through Google Play, for someone who may: Solo on an account with no plan billed, with every term, and the packs,
 * each at Play's price. Each purchase goes to KIFF before Play is told it's done; Restore sends what wasn't.
 */
@Composable
private fun AddTasksPanel(usage: Usage, state: AppState, actions: AccountActions) {
  val scope = rememberCoroutineScope()
  val uri = LocalUriHandler.current
  val store = usage.store ?: return
  Panel {
    Eyebrow("Add tasks")
    if (UsageWords.offersSolo(usage)) {
      store.googleSoloProduct?.let { id ->
        state.storeProducts[id]?.let { solo ->
          Text("Solo", style = scaled(16, FontWeight.SemiBold, Theme.ink))
          Text(UsageWords.soloTerms(solo.price, solo.period, solo.freeTrial), style = scaled(13, color = Theme.soft))
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Terms", style = scaled(13, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.clickable { uri.openUri("https://kiff.dev/terms#tafs") }.padding(vertical = 6.dp))
            Text("Privacy policy", style = scaled(13, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.clickable { uri.openUri("https://kiff.dev/privacy#tafs") }.padding(vertical = 6.dp))
          }
          PrimaryButton(if (state.buying == id) "Buying…" else "Start Solo", { scope.launch { actions.buy(id) } }, enabled = state.buying == null)
        }
      }
    }
    store.packs.forEach { pack ->
      val id = pack.googleProduct ?: return@forEach
      val product = state.storeProducts[id] ?: return@forEach
      Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(pack.line.ifEmpty { pack.name }, style = scaled(15, FontWeight.SemiBold, Theme.ink))
          Text("Tasks last ${pack.months} months", style = scaled(12, color = Theme.dim))
        }
        Text(
          if (state.buying == id) "Buying…" else product.price,
          style = scaled(15, FontWeight.SemiBold, if (state.buying == null) Theme.amberText else Theme.dim),
          modifier = Modifier.clickable(enabled = state.buying == null) { scope.launch { actions.buy(id) } }.padding(12.dp),
        )
      }
    }
    Text("Restore purchases", style = scaled(14, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.clickable(enabled = state.buying == null) { scope.launch { actions.restorePurchases() } }.padding(vertical = 8.dp))
    state.tasksMessage?.let { Text(it, style = scaled(14, color = Theme.ink)) }
  }
}

/** One taf's usage this period, from a row on its page: what it counted, what it didn't and why, and its counted tasks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TafUsageSheet(tafId: String, state: AppState, actions: AccountActions, onClose: () -> Unit) {
  var read by remember { mutableStateOf(false) }
  LaunchedEffect(tafId) { actions.refreshUsage(); actions.refreshTafUsage(tafId); read = true }
  val name = state.tafName(tafId)

  UsageFrame(name, onClose) {
    val mine = state.tafUsage(tafId)
    val why = state.usageError
    if (read && mine != null) {
      Panel {
        Eyebrow(name)
        Text(UsageWords.tasks(mine.counted), style = scaled(26, FontWeight.Bold, Theme.ink, display = true))
        state.usage?.period?.end?.let { Text(UsageWords.periodEnds(it), style = scaled(13, color = Theme.soft)) }
      }
      NotCounted(mine.free?.lines)
      state.tafTasks[tafId]?.takeIf { it.isNotEmpty() }?.let { CountedTasks(it, state) }
      // The account's count is its own, said with whose it is: this taf's never reads as the account's.
      state.usage?.tasks?.counted?.let { Panel { CountRow(UsageWords.ALL_TAFS, UsageWords.tasks(it)) } }
    } else if (why != null) {
      Panel { Text(why, style = scaled(14, color = Theme.no)) }
    } else {
      Loading(name)
    }
  }
}

/** The row on a taf's page that opens its usage, with its count this period. */
@Composable
fun UsageRow(count: Int?, onClick: () -> Unit) {
  Panel(Modifier.clickable(onClick = onClick)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Usage", style = scaled(15, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.weight(1f))
      count?.let { Text(UsageWords.tafCount(it), style = scaled(14, color = Theme.soft)) }
      Text("›", style = scaled(20, color = Theme.dim))
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    // Its height is fixed: what loads in doesn't resize a sheet that was measured while it was empty.
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = scaled(28, FontWeight.Bold, Theme.ink, display = true), modifier = Modifier.weight(1f))
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(start = 12.dp, top = 10.dp))
      }
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) { content() }
    }
  }
}

@Composable
private fun TasksPanel(u: Usage) {
  Panel {
    Eyebrow(UsageWords.whose(u))
    Text(
      UsageWords.count(u.tasks.counted, if (u.tasks.left == null) 0 else u.tasks.total, "tasks"),
      style = scaled(26, FontWeight.Bold, Theme.ink, display = true),
    )
    if (u.tasks.left != null && u.tasks.total > 0) {
      Meter(u.tasks.counted.toDouble() / u.tasks.total, if (u.tasks.usedUp) Theme.no else Theme.ok)
    }
    UsageWords.leftLine(u)?.let { Text(it, style = scaled(15, FontWeight.SemiBold, if (u.tasks.usedUp) Theme.no else Theme.ink)) }
    Text(UsageWords.periodEnds(u.period.end), style = scaled(13, color = Theme.soft))
  }
}

@Composable
private fun NotCounted(lines: List<Pair<String, Int>>?) {
  if (lines.isNullOrEmpty()) return
  Panel {
    Eyebrow("Not counted")
    lines.forEach { (label, n) -> CountRow(label, "$n") }
  }
}

@Composable
private fun CountedTasks(tasks: List<CountedTask>, state: AppState) {
  Panel {
    Eyebrow("Counted tasks")
    tasks.forEach { task ->
      val entry = state.recordEntry(task.record)
      if (entry != null) {
        RecordRow(entry, state, showTaf = true)
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(state.tafName(task.agent), style = scaled(14, FontWeight.SemiBold, Theme.ink))
          Text("${UsageWords.toolName(task)} · ${shortTime(task.at)}", style = scaled(13, color = Theme.dim))
        }
      }
    }
  }
}

@Composable
private fun CountRow(label: String, value: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = scaled(14, color = Theme.ink), modifier = Modifier.weight(1f))
    Text(value, style = scaled(14, color = Theme.soft), modifier = Modifier.padding(start = 12.dp))
  }
}

/** Loading draws the screen at its full width, never a narrow strip. */
@Composable
private fun Loading(whose: String) {
  Panel {
    Eyebrow(whose)
    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Theme.dim)
  }
}
