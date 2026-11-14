package bot.tafs.app.views

import bot.tafs.app.model.Words
import bot.tafs.app.model.HomeWords
import bot.tafs.app.model.TafState
import bot.tafs.app.model.ListOrder
import androidx.compose.runtime.SideEffect
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
import bot.tafs.app.design.tapping
import bot.tafs.app.design.Dot
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.Mascot
import bot.tafs.app.design.Meter
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.drive.confirms
import bot.tafs.app.design.StateChip
import bot.tafs.app.design.TafAvatar
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.Money
import bot.tafs.app.model.Taf
import bot.tafs.app.model.UsageWords

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeView(
  state: AppState,
  onNeedsYou: () -> Unit,
  onPlayNext: suspend () -> Unit,
  onOpenTaf: (String) -> Unit,
  /** Opens the calendar of the tafs' scheduled work (kiff/tafs#534); null when there's none to open (the sample, or no tafs). */
  onCalendar: (() -> Unit)? = null,
  onHaltAll: () -> Unit,
  /** A halted taf's chip: Halt, where it's resumed ("Halted · resume it from Halt"). */
  onHalted: (String) -> Unit = {},
  /** "Finish setting up" on another agent whose setup stopped partway: Coach's chat where it stopped. */
  onFinishSetup: ((bot.tafs.app.kiff.HomeFinish) -> Unit)? = null,
  /** Discard on a taf being set up, for the person who started it: asked first in tafs cloud's words; null in the sample. */
  onDiscardSetup: ((String) -> Unit)? = null,
  /** Opens Plan & usage, from the plan's line when it needs the owner. */
  onUsage: () -> Unit = {},
  /** Opens a recipe's sheet, from Home's list with no taf, or the strip under the tafs list. */
  onRecipe: (bot.tafs.app.kiff.Recipe) -> Unit = {},
  /** + New: every job a taf can take (docs/android-app.md); null where there's no gallery to open. */
  onNewTaf: (() -> Unit)? = null,
  /** Explore jobs and See all jobs: every job, to browse, with nothing created; null where there's no gallery to open. */
  onExplore: (() -> Unit)? = null,
  /** "Say it in your own words", first on Home: Coach, empty, to build the taf with the person. */
  onOwnWords: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  // Pulling to refresh plays the sample's next round; signed in, it reads KIFF again.
  val sample = state.mode == Mode.sample
  var refreshing by remember { mutableStateOf(false) }
  val pullScope = rememberCoroutineScope()
  // The order the list last showed, held while it's on screen (ListOrder): starting afresh on a pull, and when the app comes back.
  val shownOrder = remember { mutableStateOf<ListOrder.Shown?>(null) }
  val waiting = state.waitingOnOwner
  val order = ListOrder.hold(shownOrder.value, state.listedTafs.map { it.id }, waiting)
  SideEffect { shownOrder.value = ListOrder.Shown(order, waiting) }
  androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { shownOrder.value = null }
  PullToRefreshBox(
    isRefreshing = refreshing,
    onRefresh = {
      pullScope.launch {
        refreshing = true
        try { onPlayNext() } finally {
          refreshing = false
          shownOrder.value = null
        }
      }
    },
    modifier = modifier.fillMaxSize(),
  ) {
    LazyColumn(
      Modifier.fillMaxSize().statusBarsPadding(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Header(state.accountLabel, onCalendar)
          // KIFF can't be reached just now: said quietly, under the account's name.
          state.unreachable?.let { UnreachableLine(it, state.retryingRefresh) }
          // The phone can't approve until a new sign-in: one calm line, said at launch rather than after a tap.
          if (state.mode == Mode.kiff && state.approvalKeyLost) ApprovalKeyLine(Modifier.padding(top = 4.dp))
          // The plan, only when it needs the person (tafs cloud's `home_line`). Home stays about the tafs.
          if (state.mode == Mode.kiff) state.balance?.let { b -> b.homeLine?.let { PlanLine(it, b.planTone, onUsage) } }
          // A newer tafs on the track: one tap to update, then one to restart into it.
          UpdateLine()
        }
      }
      if (sample) {
        item {
          // The welcome's line isn't repeated here: Home says what's running (the tagline is in Settings, About).
          Headline(state)
        }
        state.held.firstOrNull()?.let { first ->
          state.taf(first.tafId)?.let { taf -> item { NeedsYouStrip(first, taf, onNeedsYou) } }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { state.tafs.forEach { taf -> TafRow(taf, onHalted = { onHalted(taf.id) }) { onOpenTaf(taf.id) } } } }
        item { OutlineButton(Words.Sample.playNext, { pullScope.launch { onPlayNext() } }, Modifier.fillMaxWidth(), color = Theme.soft) }
        item {
          Text(
            HomeWords.SAMPLE_FOOT,
            style = scaled(12, color = Theme.dim),
          )
        }
      } else {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Spacer(Modifier.weight(1f))
              // + New: the gallery of every job, also once a taf runs. tafs cloud's words, else the app's own.
              if (onNewTaf != null && state.mayUseCoach) {
                Text(
                  "+ " + HomeWords.newTaf(state.gallery),
                  style = scaled(15, FontWeight.SemiBold, Theme.amberText),
                  modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onNewTaf).padding(horizontal = 4.dp, vertical = 10.dp),
                )
              }
            }
            // A new account's Home leads with the jobs: "0 tafs working" and "Nothing needs you" say nothing yet.
            if (!state.homeLeadsWithRecipes) Headline(state)
            // Explore jobs: every job to read, with nothing created, with or without a taf, and with no Coach needed. Only when tafs
            // cloud sends its words: it leaves them out while Home already lists every job.
            if (onExplore != null) HomeWords.exploreRow(state.gallery)?.let { ExploreRow(it, onExplore) }
          }
        }
        state.held.firstOrNull()?.let { first ->
          state.taf(first.tafId)?.let { taf -> item { NeedsYouStrip(first, taf, onNeedsYou) } }
        }
        item {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val byId = state.listedTafs.associateBy { it.id }
            order.forEach { id ->
              byId[id]?.let { taf -> TafRow(taf, state.signedOut(taf.id)?.let(bot.tafs.app.model.ConnectionWords::signedOut), onHalted = { onHalted(taf.id) }) { onOpenTaf(taf.id) } }
            }
          }
        }
        if (state.settingUp.isNotEmpty()) {
          // Tafs whose setup stopped partway, among the tafs: by name, tafs cloud's "Finish setting up", and no state, since it
          // doesn't run yet. A tap opens Coach where it stopped, for someone who may use Coach.
          item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              state.settingUp.forEach { taf ->
                val finish = taf.finish?.takeIf { state.mayUseCoach && it.taf == state.coachCloudId }
                TafRow(taf, showState = false) { if (finish != null && onFinishSetup != null) onFinishSetup(finish) }
                // Discard beside it, only when tafs cloud sends it (to the person who started it), with every word its own.
                val discard = taf.discard
                if (discard != null && onDiscardSetup != null) DiscardSetup(taf.id, discard.words, onDiscardSetup)
              }
            }
          }
        }
        // A few jobs the account doesn't run yet, under its tafs, as tafs cloud picks them (docs/android-app.md).
        val gallery = state.gallery
        if (gallery != null && state.tafs.isNotEmpty() && state.mayUseCoach && gallery.stripRecipes.isNotEmpty()) item { JobStrip(gallery, onRecipe, onExplore) }
        if (state.otherAgents.isNotEmpty()) {
          // Agents made through KIFF's API, not run by tafs: their Cards, apart.
          item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Eyebrow(Words.Home.otherAgents)
              state.otherAgents.forEach { taf ->
                // Only Coach's own chat, for someone who may use Coach (as on iPhone and iPad): anything else opens as before.
                val finish = taf.finish?.takeIf { state.mayUseCoach && it.taf == state.coachCloudId }
                TafRow(taf, onHalted = { onHalted(taf.id) }) { if (finish != null && onFinishSetup != null) onFinishSetup(finish) else onOpenTaf(taf.id) }
              }
            }
          }
        }
        if (state.listLoaded && !state.listUnread && state.tafs.isEmpty()) {
          // tafs cloud says what Home shows with no taf: the recipes under its line, or its empty line (docs/android-app.md).
          val recipes = state.recipes
          val labels = recipes?.labels
          item {
            if (state.homeLeadsWithRecipes && recipes != null && labels != null) {
              HomeRecipes(recipes, labels, onRecipe, onOwnWords.takeIf { state.mayUseCoach })
            } else {
              Panel {
                val words = recipes?.home?.words?.takeIf { it.isNotBlank() }
                if (words != null) {
                  // tafs cloud's empty line, whoever reads it: it has no words from the app.
                  Text(words, style = scaled(15, FontWeight.SemiBold, Theme.ink))
                } else {
                  // A tafs cloud from before Home's line: the app's own. Setting one up is Coach's, so someone a taf is shared
                  // with who can't use Coach waits for one instead.
                  Text(Words.Home.noTafs, style = scaled(15, FontWeight.SemiBold, Theme.ink))
                  Text(HomeWords.empty(state.mayUseCoach), style = scaled(14, color = Theme.soft))
                }
              }
              // With no job listed, "Say it in your own words" under the line: there's always a way to Coach.
              onOwnWords?.takeIf { state.mayUseCoach }?.let { open ->
                Box(Modifier.padding(top = 8.dp)) { OwnWordsRow(recipes?.home, open) }
              }
            }
          }
        }
        // Stopping every taf at once is one row away, with where it stands; and resuming an account halted with no taf left.
        if (state.showsAccountHalt) item { HaltRow(null, state.haltOf(null), onHaltAll) }
        item { Text(Words.Home.footer, style = scaled(12, color = Theme.dim)) }
      }
    }
  }
}

/** A row that opens every job to browse: Explore jobs on Tafs, See all jobs under the strip. */
@Composable
internal fun ExploreRow(words: String, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(words, style = scaled(15, FontWeight.SemiBold, Theme.ink), modifier = Modifier.weight(1f))
    Text("›", style = scaled(17, FontWeight.SemiBold, Theme.dim))
  }
}

/** KIFF's status page, served apart from KIFF, so it answers when KIFF doesn't. */
private const val STATUS_PAGE = "https://status.kiff.dev"

/** The plan's line on Home, in its tone, opening Plan & usage. */
@Composable
fun PlanLine(words: String, tone: String, onUsage: () -> Unit) {
  val color = planColor(tone, calm = Theme.amberText)
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onUsage).padding(vertical = 6.dp).semantics { contentDescription = Words.Home.planLineDescription(words) },
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
    Text(words, style = scaled(13, color = color), modifier = Modifier.weight(1f))
    Text(UsageWords.TITLE, style = scaled(12, FontWeight.SemiBold, Theme.amberText))
  }
}

/** While a refresh can't get through: one quiet line under the account's name, with the status page beside it. */
@Composable
fun UnreachableLine(words: String, retrying: Boolean) {
  val uri = androidx.compose.ui.platform.LocalUriHandler.current
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(Theme.amber))
    Text(if (retrying) Words.Home.statusRetrying(words) else words, style = scaled(12, color = Theme.dim))
    Text(Words.Home.status, style = scaled(12, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.clickable { uri.openUri(STATUS_PAGE) }.padding(vertical = 6.dp))
  }
}

/** The bar above the list: the mascot and the account it is in. Account and Coach are in the tab bar; a new taf starts in Coach. */
@Composable
private fun Header(accountLabel: String?, onCalendar: (() -> Unit)? = null) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Mascot(34.dp)
      // The account this is, always in sight.
      accountLabel?.let { Text(it, style = scaled(13, FontWeight.SemiBold, Theme.soft), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    // The calendar of the tafs' scheduled work (kiff/tafs#534): the phone has no Overview, so it opens from here.
    if (onCalendar != null) {
      Box(
        Modifier.size(36.dp).clip(CircleShape).tapping(onClick = onCalendar).semantics { contentDescription = Words.Calendar.heading },
        contentAlignment = Alignment.Center,
      ) {
        Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, tint = Theme.ink, modifier = Modifier.size(22.dp))
      }
    }
  }
}

/**
 * Whether Home leads with the jobs a taf can take: an account read, with no taf, where tafs cloud lists them (`home.shown`) and
 * sends the sheet's words. Home then shows no count above them.
 */
internal val AppState.homeLeadsWithRecipes: Boolean
  get() = mode == Mode.kiff && listLoaded && !listUnread && tafs.isEmpty() && recipes?.home?.shown == true && recipes.labels != null

@Composable
private fun Headline(state: AppState) {
  Column {
    // Never "0 tafs working" before the tafs are read (#91).
    if (state.mode == Mode.kiff && !state.listLoaded) {
      Text(Words.Home.loading, style = scaled(36, FontWeight.Bold, Theme.dim, display = true))
      return@Column
    }
    if (state.mode == Mode.kiff && state.listUnread) {
      Text(Words.Home.notLoaded, style = scaled(36, FontWeight.Bold, Theme.dim, display = true))
      return@Column
    }
    // tafs cloud's line, counted from the tafs it lists: a new taf with no schedule reads "1 taf ready.", never "0 tafs working."
    Text(state.headlineWords, style = scaled(36, FontWeight.Bold, Theme.ink, display = true))
    // tafs cloud's words for what Needs you holds, else the app's for its own count.
    Text(state.needsYouWords, style = scaled(36, FontWeight.Bold, Theme.dim, display = true))
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
      .padding(10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Dot(Theme.amber, 10.dp)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text("${taf.name}: ${call.proposed.summary}", style = scaled(14, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("${call.reason} · " + Words.NeedsYou.answerBy(shortTime(call.expiresAt)), style = scaled(13, color = Theme.soft), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text(Words.NeedsYou.answer, style = scaled(14, FontWeight.Medium, Theme.amberText))
  }
}

@Composable
/** [onHalted]: a halted taf's chip opens Halt, where it's resumed; the rest of the row opens the taf. */
fun TafRow(taf: Taf, signedOut: String? = null, onHalted: (() -> Unit)? = null, showState: Boolean = true, onClick: () -> Unit) {
  val shape = RoundedCornerShape(18.dp)
  Column(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(Theme.panel)
      .border(BorderStroke(1.dp, Theme.line), shape)
      .clickable(onClick = onClick)
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      TafAvatar(taf)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(taf.name, style = scaled(15, FontWeight.SemiBold, Theme.ink))
          taf.badge?.let {
            Text(
              it,
              style = scaled(11, FontWeight.Bold, Theme.amberInk),
              modifier = Modifier.clip(CircleShape).background(Theme.amber).padding(horizontal = 7.dp, vertical = 2.dp),
            )
          }
        }
        // A connection its vendor signed out says so, in planque's words, in place of what it's doing: it waits on the owner.
        // At risk or failing, tafs cloud's first reason, amber or red.
        val tone = when {
          // Its setup stopped partway: it needs the owner.
          signedOut != null || taf.finish != null -> Theme.amberText
          taf.nowHealth == "failing" -> Theme.no
          taf.nowHealth == "at_risk" -> Theme.amberText
          else -> Theme.dim
        }
        (signedOut ?: taf.now).takeIf { it.isNotEmpty() }?.let { Text(it, style = scaled(13, color = tone), maxLines = 1, overflow = TextOverflow.Ellipsis) }
      }
      if (showState) StateChip(taf.state, onClick = onHalted?.takeIf { taf.state == TafState.halted })
    }
    val rule = taf.card.rules.firstOrNull { it.perWindow != null }
    val limit = rule?.perWindow
    if (rule != null && limit != null) {
      val used = taf.usedOf(rule)
      // KIFF couldn't read the use: say so, never show an empty meter.
      val unknown = taf.usageUnknown?.contains(rule.action) == true
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The label takes what the amount leaves: a long one never squeezes the amount to a digit a line.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("${rule.label} · ${limit.formatted} ${rule.window.label}", style = scaled(12, color = Theme.dim), modifier = Modifier.weight(1f))
          Text(if (unknown) Words.Usage.useUnavailable else used.formatted, style = scaled(12, color = Theme.dim, mono = true), maxLines = 1, softWrap = false)
        }
        if (!unknown) Meter(used.minor.toDouble() / maxOf(limit.minor, 1L), Theme.tafColor(taf.colorIndex))
      }
    }
  }
}

/** Play's newer version, in one line with one button: Update while it's offered, Restart once it's downloaded. */
@Composable
private fun UpdateLine() {
  val row = bot.tafs.app.push.LocalAppUpdate.current ?: return
  val (words, button, action) = when (row.stage) {
    bot.tafs.app.push.UpdateStage.None -> return
    bot.tafs.app.push.UpdateStage.Available -> Triple(Words.Update.available, Words.Update.update, row.update)
    bot.tafs.app.push.UpdateStage.Downloading -> Triple(Words.Update.downloading, null, null)
    bot.tafs.app.push.UpdateStage.Ready -> Triple(Words.Update.ready, Words.Update.restart, row.restart)
  }
  Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(words, style = scaled(14, color = Theme.soft), modifier = Modifier.weight(1f))
    if (button != null && action != null) androidx.compose.material3.TextButton(action) { Text(button, style = scaled(14, FontWeight.SemiBold, Theme.amberText)) }
  }
}

/** Discard for a taf being set up: the button, then tafs cloud's question before anything is undone (docs/android-app.md). */
@Composable
fun DiscardSetup(agent: String, words: bot.tafs.app.kiff.HomeDiscardWords, onDiscard: (String) -> Unit) {
  var asking by remember { mutableStateOf(false) }
  OutlineButton(words.button, { asking = true }, Modifier.fillMaxWidth(), color = Theme.no)
  if (asking) {
    bot.tafs.app.drive.DriveAlert(
      onDismissRequest = { asking = false },
      title = { Text(words.title) },
      text = { Text(words.text) },
      confirmButton = {
        androidx.compose.material3.TextButton(onClick = { asking = false; onDiscard(agent) }, modifier = Modifier.confirms()) { Text(words.yes, color = Theme.no) }
      },
      dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text(words.no) } },
    )
  }
}
