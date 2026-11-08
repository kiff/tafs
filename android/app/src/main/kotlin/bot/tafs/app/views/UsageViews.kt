package bot.tafs.app.views

import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.DriveSheet
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
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.LocalBiometry
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.PurchaseNote
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.Meter
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.BalanceNow
import bot.tafs.app.kiff.Usage
import bot.tafs.app.model.UsageWords
import bot.tafs.app.model.shortTime

/**
 * Plan & usage, from Settings (docs/accounts.md#in-the-apps): the balance in tafs cloud's words, with Raise the limit where it's
 * offered; Solo or packs through Google Play where the balance offers them (AppState.offersPurchases); who bills the plan; then
 * the credits the runs used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageSheet(state: AppState, actions: AccountActions, onClose: () -> Unit) {
  // Read on this screen: an earlier read isn't shown as now.
  var read by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { actions.refreshUsage(); read = true }

  UsageFrame(UsageWords.TITLE, onClose) {
    if (!read) {
      Loading(UsageWords.TITLE)
      return@UsageFrame
    }
    BalancePanel(state, actions)
    val usage = state.usage
    if (usage != null && state.offersPurchases) BuyPanel(usage, state, actions)
    if (state.mode == Mode.kiff) usage?.let { UsageWords.billingLine(it) }?.let { line ->
      Panel {
        Text(line, style = scaled(15, color = Theme.ink))
        // Billed by Google Play: Play's own page to manage or cancel it. The app never points anywhere else to buy.
        if (usage.billing?.source == "google") {
          val uri = LocalUriHandler.current
          Text("Manage", style = scaled(15, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.clickable { uri.openUri(Purchases.MANAGE_SUBSCRIPTIONS) }.padding(vertical = 6.dp))
        }
      }
    }
    // Members ask an admin; an admin answers their requests (as iOS's Usage).
    if (state.mode == Mode.kiff && usage != null) UsageWords.asks(usage)?.let { kind -> TaskRequests(usage, kind, state, actions) }
    CreditsPanel(state, actions)
    // KIFF's usage read failed: buying and requests wait on it, said once. The balance and credits are tafs cloud's.
    state.usageError?.let { Panel { Text(it, style = scaled(14, color = Theme.no)) } }
  }
}

/**
 * The balance (docs/accounts.md#the-balance-read), every line in tafs cloud's words: the balance and its second line, a bar where
 * something bounds it, the warning, and Raise the limit for the person it's offered to. After a raise whose read failed, Read it
 * again in its place, which signs nothing. Nothing from a tafs cloud from before the balance.
 */
@Composable
internal fun BalancePanel(state: AppState, actions: AccountActions) {
  val b = state.balance?.balance
  val why = state.balanceError
  if (b == null && why == null) return
  val scope = rememberCoroutineScope()
  Panel {
    Eyebrow(UsageWords.BALANCE)
    b?.let { now ->
      Text(now.words, style = scaled(20, FontWeight.Bold, Theme.ink, display = true))
      now.detail?.let { Text(it, style = scaled(14, color = Theme.soft)) }
      now.meterPct?.let { pct -> Meter(pct.coerceIn(0, 100) / 100.0, when (now.state) { BalanceNow.WAITING -> Theme.no; BalanceNow.WARNING -> Theme.amber; else -> Theme.ok }) }
      now.warning?.let { Text(it, style = scaled(15, color = Theme.ink)) }
    }
    why?.let { Text(it, style = scaled(14, color = Theme.no)) }
    if (state.balanceStale) {
      // It went through; what's above is from before. Reading signs nothing, and no raise until it's read.
      Text(UsageWords.RAISED_UNREAD, style = scaled(15, color = Theme.ink))
      OutlineButton(if (state.readingBalance) "Reading…" else "Read it again", { scope.launch { actions.readBalance() } }, Modifier.fillMaxWidth(), enabled = !state.readingBalance)
    } else b?.raise?.let { raise ->
      // Raising lets the tafs spend more, so it waits on the phone being able to approve, as Go on did. KIFF takes it signed by
      // the device key, once the person confirms.
      ApprovalGate {
        PrimaryButton(if (state.raising) "Raising…" else raise.words, { scope.launch { actions.raiseLimit() } }, enabled = !state.raising)
      }
      state.raiseError?.let { Text(it, style = scaled(13, color = Theme.no)) }
    }
  }
}

/**
 * Buying through Google Play, as the balance offers it to this person (`buy`): Solo, with every term, and packs of credits, which
 * never expire, each at Play's price. Each purchase goes to KIFF before Play is told it's done; Restore sends what wasn't.
 */
@Composable
private fun BuyPanel(usage: Usage, state: AppState, actions: AccountActions) {
  val scope = rememberCoroutineScope()
  val uri = LocalUriHandler.current
  val store = usage.store ?: return
  val offer = state.balance?.balance ?: return
  Panel {
    Eyebrow(UsageWords.BUY)
    if (offer.offers("solo")) {
      store.googleSoloProduct?.let { id ->
        state.storeProducts[id]?.let { solo ->
          Text("Solo", style = scaled(16, FontWeight.SemiBold, Theme.ink))
          Text(UsageWords.soloTerms(solo.price, solo.period, solo.freeTrial), style = scaled(13, color = Theme.soft))
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Terms", style = scaled(13, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.clickable { uri.openUri("https://kiff.dev/terms#tafs") }.padding(vertical = 6.dp))
            Text("Privacy policy", style = scaled(13, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.clickable { uri.openUri("https://kiff.dev/privacy#tafs") }.padding(vertical = 6.dp))
          }
          PrimaryButton(if (state.buying == id) "Buying…" else "Start Solo", { scope.launch { actions.buy(id) } }, enabled = state.buying == null, modifier = Modifier.confirms())
          PurchaseNoteLine(state, id, actions)
        }
      }
    }
    if (offer.offers("packs")) store.packs.forEach { pack ->
      val id = pack.googleProduct ?: return@forEach
      val product = state.storeProducts[id] ?: return@forEach
      Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(pack.line.ifEmpty { pack.name }, style = scaled(15, FontWeight.SemiBold, Theme.ink))
          Text(UsageWords.PACKS_KEEP, style = scaled(12, color = Theme.dim))
        }
        Text(
          if (state.buying == id) "Buying…" else product.price,
          style = scaled(15, FontWeight.SemiBold, if (state.buying == null) Theme.amberText else Theme.dim),
          modifier = Modifier.confirms().clickable(enabled = state.buying == null) { scope.launch { actions.buy(id) } }.padding(8.dp),
        )
      }
      PurchaseNoteLine(state, id, actions)
    }
    // Play lists none of what's offered: said, rather than an empty panel (Play billing off for this app on this phone, or unreachable).
    val offered = buildList {
      if (offer.offers("solo")) store.googleSoloProduct?.let { add(it) }
      if (offer.offers("packs")) store.packs.forEach { p -> p.googleProduct?.let { add(it) } }
    }
    bot.tafs.app.billing.Purchases.buyNote(offered, state.storeProducts.keys, state.storeLoaded, state.storeFailed)?.let {
      Text(it, style = scaled(13, color = Theme.soft))
    }
    Text(
      if (state.buying == PurchaseNote.RESTORE) "Restoring…" else "Restore purchases",
      style = scaled(14, FontWeight.SemiBold, Theme.amberText),
      modifier = Modifier.clickable(enabled = state.buying == null) { scope.launch { actions.restorePurchases() } }.padding(vertical = 6.dp),
    )
    PurchaseNoteLine(state, PurchaseNote.RESTORE, actions)
    state.tasksMessage?.let { Text(it, style = scaled(14, color = Theme.ink)) }
  }
}

/**
 * How a purchase or restore went, under what was tapped, read out by TalkBack as it appears. Bought but not recorded by KIFF
 * yet: Restore purchases, right there.
 */
@Composable
private fun PurchaseNoteLine(state: AppState, at: String, actions: AccountActions) {
  val note = state.purchaseNote?.takeIf { it.at == at } ?: return
  val scope = rememberCoroutineScope()
  Text(note.words, style = scaled(14, color = Theme.ink), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
  if (note.offersRestore && at != PurchaseNote.RESTORE) {
    Text(
      "Restore purchases",
      style = scaled(14, FontWeight.SemiBold, Theme.amberText),
      modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = state.buying == null) { scope.launch { actions.restorePurchases() } }.padding(vertical = 10.dp),
    )
  }
}

/**
 * The credits the runs used (docs/accounts.md#credits), every line in tafs cloud's words as they are: the period, each taf's row,
 * which opens its runs, Coach's past its fair use, what wasn't counted, and the note. Nothing from a tafs cloud from before credits.
 */
@Composable
private fun CreditsPanel(state: AppState, actions: AccountActions) {
  val credits = state.credits
  val why = state.creditsError
  if (state.mode == Mode.signedOut || (credits == null && why == null)) return
  val scope = rememberCoroutineScope()
  Panel {
    Eyebrow(UsageWords.CREDITS)
    credits?.let { c ->
      Text(c.words, style = scaled(17, FontWeight.SemiBold, Theme.ink))
      c.tafs.forEach { row ->
        // The row for tafs removed since has no taf, and doesn't open.
        val taf = row.taf?.takeIf { state.mode == Mode.kiff }
        Row(
          Modifier.fillMaxWidth().heightIn(min = 44.dp).then(if (taf != null) Modifier.clickable { scope.launch { actions.openTafCredits(taf) } } else Modifier),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(row.words, style = scaled(15, color = Theme.ink), modifier = Modifier.weight(1f))
          if (taf != null) Text("›", style = scaled(20, color = Theme.dim))
        }
      }
      c.coach?.let { Text(it.words, style = scaled(15, color = Theme.ink)) }
      c.notCounted?.let { n ->
        Text(n.words, style = scaled(14, FontWeight.SemiBold, Theme.soft))
        n.lines.forEach { Text(it.words, style = scaled(14, color = Theme.soft)) }
      }
      c.note?.let { Text(it, style = scaled(13, color = Theme.soft)) }
    }
    why?.let { Text(it, style = scaled(14, color = Theme.no)) }
    // The other period: the one before, or back to this one.
    val previous = state.creditsPrevious
    Text(
      if (previous) UsageWords.THIS_PERIOD else UsageWords.LAST_PERIOD,
      style = scaled(15, FontWeight.SemiBold, if (state.readingCredits) Theme.dim else Theme.amberText),
      modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = !state.readingCredits) { scope.launch { actions.showCreditsPeriod(!previous) } }.padding(vertical = 10.dp),
    )
  }
}

/** One taf's runs and their credits, newest first, from its Credits row: each opens its request while tafs cloud says it can. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsRunsSheet(open: bot.tafs.app.kiff.TafCredits, state: AppState, actions: AccountActions, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  UsageFrame(state.tafs.firstOrNull { it.cloudId == open.taf }?.name ?: UsageWords.CREDITS, onClose) {
    Panel { Text(open.words, style = scaled(17, FontWeight.SemiBold, Theme.ink)) }
    if (open.items.isNotEmpty()) Panel {
      open.items.forEach { run ->
        Row(
          Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (run.opens) Modifier.clickable { scope.launch { actions.openCreditsRun(open.taf, run.run) } } else Modifier),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(run.words, style = scaled(15, color = if (run.counted) Theme.ink else Theme.soft))
            Text(shortTime(run.at), style = scaled(12, color = Theme.dim))
          }
          if (run.opens) Text("›", style = scaled(20, color = Theme.dim))
        }
      }
    }
    if (open.next != null) {
      OutlineButton(if (state.readingCredits) "Reading…" else UsageWords.MORE_RUNS, { scope.launch { actions.moreTafCredits() } }, Modifier.fillMaxWidth(), enabled = !state.readingCredits)
    }
    state.tafCreditsError?.let { Text(it, style = scaled(14, color = Theme.no)) }
    state.requestError?.let { Text(it, style = scaled(14, color = Theme.no)) }
  }
}

/** The row on a taf's page that opens its credits this period: its runs. */
@Composable
fun UsageRow(onClick: () -> Unit) {
  Panel(Modifier.clickable(onClick = onClick)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Usage", style = scaled(15, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.weight(1f))
      Text("›", style = scaled(20, color = Theme.dim))
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
  DriveSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    // Its height is fixed: what loads in doesn't resize a sheet that was measured while it was empty.
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = scaled(28, FontWeight.Bold, Theme.ink, display = true), modifier = Modifier.weight(1f))
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(start = 8.dp, top = 8.dp))
      }
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) { content() }
    }
  }
}

/** The plan line's colour by tafs cloud's tone: waiting in red, a warning in amber text, otherwise [calm]. A tone this app doesn't know is calm. */
fun planColor(tone: String, calm: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color = when (tone) {
  BalanceNow.WAITING -> Theme.no
  BalanceNow.WARNING -> Theme.amberText
  else -> calm
}

/** Loading draws the screen at its full width, never a narrow strip. */
@Composable
private fun Loading(whose: String) {
  Panel {
    Eyebrow(whose)
    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Theme.dim)
  }
}
