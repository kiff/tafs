package bot.tafs.app.views

import bot.tafs.app.model.Chat
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.R
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.MarkdownText
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Segmented
import bot.tafs.app.design.StateChip
import bot.tafs.app.design.TafAvatar
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.Answer
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.MemoryNote
import bot.tafs.app.model.RunReport
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.ReportResult
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafState
import bot.tafs.app.model.shortTime
import kotlinx.coroutines.launch

/** Chat first: talking to a taf is the main thing. */
private enum class Section(val title: String) { Chat("Chat"), Activity("Activity"), Card("Card"), Doors("Doors") }

/** What the screen asks of the model: its changes, so the screen holds no state of the app's. */
class TafActions(
  val answer: suspend (HeldCall, Answer) -> Unit,
  val togglePause: (String) -> Unit,
  val revoke: (String) -> Unit,
  val loadMemory: (String) -> Unit,
  val forget: (MemoryNote, String) -> Unit,
  val loadChat: suspend (String) -> Unit,
  /** Sends a message: its words, the chat, whether the tray's files go with it (Send), and what to do with words that didn't go. */
  val sendChat: (String, String, Boolean, (String) -> Unit) -> Unit,
  val issuer: String,
  val doors: DoorActions,
  /** What Coach's cards ask of the model: answers, and approvals. */
  val coach: CoachActions,
  /** The taf's connections, read from planque, and ending one. */
  val connections: ConnectionActions,
  /** Stops what the taf is doing in its chat. */
  val stopChat: suspend (String) -> Unit = {},
  /** Photos and files on a message (docs/attachments.md). */
  val files: FileActions = FileActions(),
  /** Runs it now, outside its schedule (tafs cloud). */
  val runNow: suspend (String) -> Unit = {},
  /** Reads its latest run and report. */
  val loadReport: suspend (String) -> Unit = {},
  /** What the owner wrote in a chat and hasn't sent, kept by the app: the chat, and its words. */
  val setDraft: (String, String) -> Unit = { _, _ -> },
  /** Reads where its work stands (docs/reliability.md), and sends a door reply again by its run. */
  val loadWork: suspend (String) -> Unit = {},
  val sendAgain: suspend (String, String) -> Unit = { _, _ -> },
  /** Pauses or resumes its schedule. */
  val setPaused: suspend (String, Boolean) -> Unit = { _, _ -> },
  /** Removes it for good, after one confirmation: what to tell the owner, null when nothing was done. */
  val remove: suspend (String) -> String? = { null },
  /** Reports an answer Coach or the taf wrote: the chat, the answer, a reason and an optional note. */
  val report: suspend (String, ChatMessage, String, String?) -> ReportResult = { _, _, _, _ -> ReportResult.NotSent("") },
)

@Composable
fun TafDetailView(
  tafId: String,
  state: AppState,
  actions: TafActions,
  onBack: () -> Unit,
  onHalt: (String) -> Unit,
  onUsage: (String) -> Unit,
  onVersions: (String) -> Unit,
  people: PeopleActions? = null,
  /** Back to the list; not shown beside it, on a tablet. */
  showBack: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val taf = state.taf(tafId)
  // What sits above the tabs folds away while the page scrolls down, and comes back on the way up, so the chat has the screen.
  var folded by remember { mutableStateOf(false) }
  val folding = remember {
    object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
      override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
        if (available.y < -4f) folded = true else if (available.y > 4f) folded = false
        return androidx.compose.ui.geometry.Offset.Zero
      }
    }
  }
  Column(modifier.fillMaxSize().statusBarsPadding()) {
    if (taf == null) {
      if (showBack) BackBar(onBack)
      Text("This taf is gone.", style = scaled(15, color = Theme.dim), modifier = Modifier.padding(16.dp))
      return@Column
    }
    val canChat = state.canChat(taf.id)
    val hasDoors = state.hasDoors(taf.id)
    val sections = Section.entries.filter { (it != Section.Chat || canChat) && (it != Section.Doors || hasDoors) }
    var section by rememberSaveable { mutableStateOf(sections.first()) }
    var frontCard by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRevoke by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    // What removing it did, shown once it's done.
    var removal by rememberSaveable { mutableStateOf<String?>(null) }
    val removeScope = rememberCoroutineScope()
    val cards = taf.shownCards
    // A wide screen in landscape (as iOS on an iPad): beside the chat or the activity, a second column with the call waiting for
    // the owner and the Cards, when the chat keeps at least a phone's width beside it.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val pageWidth = config.screenWidthDp - (if (showBack) 0 else 361)
    val side = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
      section != Section.Card && section != Section.Doors && pageWidth - SIDE_WIDTH - 32 >= CHAT_AT_LEAST

    Header(taf, if (showBack) onBack else null)
    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      // Who it's shared with, and Share: the taf's manager's to see. It folds with the page.
      if (people != null && taf.cloudId != null) {
        androidx.compose.animation.AnimatedVisibility(visible = !folded) { ShareLine(taf.id, state, people) }
      }
      Segmented(sections, section, { it.title }) { section = it }
      // The Cards are one line above the chat and the activity; it opens the Card tab (kiff/tafs#211), and folds with the page.
      if (section != Section.Card && !side) {
        (cards.firstOrNull { it.id == frontCard } ?: cards.firstOrNull())?.let {
          androidx.compose.animation.AnimatedVisibility(visible = !folded) {
            CardBar(it, cards.size, Theme.tafColor(taf.colorIndex), taf.card.revoked) { section = Section.Card }
          }
        }
      }
    }

    Row(Modifier.weight(1f).nestedScroll(folding)) {
    Column(Modifier.weight(1f).fillMaxHeight()) {
    if (section == Section.Chat) {
      // The thread scrolls on its own, with the field under it.
      TafChatView(taf.id, state, actions, Modifier.weight(1f))
    } else {
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        when (section) {
          Section.Activity -> Activity(taf, state, actions)
          Section.Card -> {
            val managesHere = state.mode == Mode.kiff && taf.cloudId != null && state.manages(taf.id)
            CardTab(taf, actions, frontCard, { frontCard = it }, controls = state.mode == Mode.sample, manages = managesHere, onRevoke = { confirmRevoke = true })
            // Which tools it is connected to, and whose sign-ins: read from planque, for the people who manage it.
            if (state.canHalt(taf.id) && state.manages(taf.id)) ConnectionsPanel(taf, state, actions.connections)
            // Halting is a job of its own: its row opens its screen. Its usage is the account's Usage, with this taf's count.
            // Only what this person manages, as KIFF says: a Viewer sees no Halt or Versions, each refused only once tapped.
            if (state.canHalt(taf.id) && state.manages(taf.id) && taf.state != TafState.revoked) HaltRow(taf.id, state.haltOf(taf.id), { onHalt(taf.id) })
            if (state.mode == Mode.sample || state.canHalt(taf.id)) UsageRow(state.tafUsage(taf.id)?.counted) { onUsage(taf.id) }
            // Its versions are a job of their own: their own screen, not another section here.
            if (state.canHalt(taf.id) && state.manages(taf.id)) VersionsRow { onVersions(taf.id) }
            // For good: its Cards revoked, its schedule, chats, run reports, memory and key deleted, its name freed.
            if (managesHere) OutlineButton("Remove for good", { confirmRemove = true }, Modifier.fillMaxWidth(), color = Theme.no)
          }
          Section.Doors -> DoorsTab(taf, state, actions.doors, state.kiffAccount?.email)
          Section.Chat -> Unit
        }
      }
    }
    }
    if (side) {
      Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.line))
      SideColumn(taf, state, actions, frontCard, { frontCard = it }) { section = Section.Card }
    }
    }

    if (confirmRevoke) {
      AlertDialog(
        onDismissRequest = { confirmRevoke = false },
        title = { Text("Revoke ${taf.name}'s Card?") },
        text = { Text("No new action goes through. Anything already done stays done.") },
        confirmButton = {
          TextButton({ confirmRevoke = false; actions.revoke(taf.id) }) { Text("Revoke and stop", color = Theme.no) }
        },
        dismissButton = { TextButton({ confirmRevoke = false }) { Text("Cancel", color = Theme.soft) } },
        containerColor = Theme.panel,
      )
    }
    if (confirmRemove) {
      AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove ${taf.name} for good?") },
        text = { Text("Its Cards are revoked, its schedule stopped, its chats, run reports, memory and key deleted, and its name freed. KIFF's receipts stay.") },
        confirmButton = {
          TextButton({
            confirmRemove = false
            removeScope.launch { actions.remove(taf.id)?.let { removal = it } }
          }) { Text("Remove", color = Theme.no) }
        },
        dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel", color = Theme.soft) } },
        containerColor = Theme.panel,
      )
    }
    removal?.let { words ->
      AlertDialog(
        onDismissRequest = { removal = null },
        title = { Text("Remove ${taf.name}") },
        text = { Text(words) },
        confirmButton = { TextButton({ removal = null }) { Text("OK", color = Theme.amberText) } },
        containerColor = Theme.panel,
      )
    }
  }
}

/** The way back to the list: the screens inside a tab keep the tab bar, as a navigation stack does. */
@Composable
internal fun BackBar(onBack: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onBack).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = null, tint = Theme.amber, modifier = Modifier.size(20.dp))
    Text("Back", style = scaled(17, color = Theme.amber))
  }
}

/**
 * The taf's header, one row: back (where the page isn't beside the list), its avatar and name, and its state. Its tools aren't
 * listed here: the Card tab says what it may do, in KIFF's words.
 */
@Composable
private fun Header(taf: Taf, onBack: (() -> Unit)?) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = if (onBack == null) 12.dp else 0.dp, end = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onBack != null) {
      Box(Modifier.size(44.dp).clickable(onClickLabel = "Back", onClick = onBack), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = "Back", tint = Theme.amber, modifier = Modifier.size(20.dp))
      }
    }
    TafAvatar(taf, 30.dp)
    Text(taf.name, style = scaled(18, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    StateChip(taf.state)
  }
}

@Composable
private fun Activity(taf: Taf, state: AppState, actions: TafActions) {
  Panel {
    Eyebrow("The job")
    Text(taf.job, style = scaled(15, color = Theme.ink))
  }
  // How it does the job, beside the job: not part of its Card (kiff/tafs#96). Signed in, read from tafs cloud.
  if (state.mode == Mode.sample || taf.cloudId != null) MemoryPanel(taf.id, state.memories[taf.id], state.memoryErrors[taf.id], actions)
  if (state.mode == Mode.kiff && taf.cloudId != null) {
    RunsPanel(taf, taf.id in state.runsInFlight, actions)
    // Change how it works: Coach opens on a new conversation about it, for whoever manages it.
    LocalImproveInCoach.current?.takeIf { state.manages(taf.id) }?.let { improve ->
      OutlineButton("Improve in Coach", { improve(taf.id) }, Modifier.fillMaxWidth())
    }
    WorkPanel(taf, state, actions)
    RunReportPanel(taf.id, state.reports[taf.id], actions)
  }
  state.heldCalls(taf.id).forEach { HeldCallCard(it, state, actions.answer) }
  val entries = state.entries(taf.id)
  if (entries.isEmpty()) Text("Nothing yet.", style = scaled(15, color = Theme.dim))
  entries.forEach { RecordRow(it, state, showTaf = false) }
}

@Composable
private fun CardTab(taf: Taf, actions: TafActions, front: String?, onFront: (String) -> Unit, controls: Boolean, manages: Boolean, onRevoke: () -> Unit) {
  val cards = taf.shownCards
  val shown = cards.firstOrNull { it.id == front } ?: cards.firstOrNull()
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    CardStack(cards, actions.issuer, Theme.tafColor(taf.colorIndex), front, onFront, revoked = taf.card.revoked)
  }
  // All of the front Card's limits: the face shows the main one.
  CardLines(shown?.rules ?: emptyList())
  // How much of its totals is from calls the tool refused, in KIFF's words: for a live, active Card only.
  shown?.takeIf(::countsForReal)?.let(::pendingOf)?.forEach { Text(it, style = scaled(13, color = Theme.soft)) }
  // In the sample, Pause and Revoke are its own; signed in, Revoke is KIFF's, for whoever manages it, and pausing is under
  // Runs, on the Activity tab.
  if (taf.state != TafState.revoked && (controls || manages)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      if (controls) OutlineButton(if (taf.state == TafState.paused) "Resume" else "Pause", { actions.togglePause(taf.id) }, Modifier.weight(1f))
      OutlineButton("Revoke", onRevoke, Modifier.weight(1f), color = Theme.no)
    }
  }
}

/** When it runs, with Run now and Pause or Resume; a taf that works from its chat has no schedule to run or pause. */
@Composable
private fun RunsPanel(taf: Taf, inFlight: Boolean, actions: TafActions) {
  val scope = rememberCoroutineScope()
  // Set on the tap itself, before the request starts, so a second tap can't send one.
  var tapped by remember { mutableStateOf(false) }
  Panel {
    Eyebrow("Runs")
    if (taf.worksFromChat) {
      Text("It works when you tell it, in its chat: no schedule runs it.", style = scaled(15, color = Theme.ink))
    } else {
      Text(if (taf.paused) "Paused" else taf.nextRun?.let { "Next run at ${shortTime(it)}" } ?: "On its schedule", style = scaled(15, color = Theme.ink))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val busy = inFlight || tapped
        OutlineButton(if (busy) "Starting a run…" else "Run now", {
          if (!tapped) {
            tapped = true
            scope.launch {
              actions.runNow(taf.id)
              tapped = false
            }
          }
        }, Modifier.weight(1f), enabled = !busy)
        OutlineButton(if (taf.paused) "Resume" else "Pause", { scope.launch { actions.setPaused(taf.id, !taf.paused) } }, Modifier.weight(1f))
      }
    }
  }
}

/**
 * Where the taf's work stands, as tafs cloud says it: its line, then its recent requests and skipped firings, newest first, each in
 * tafs cloud's words. A request needing the owner is amber, a failed one red; a skipped firing isn't a failure, so it's dim. A door
 * reply given up on offers Send again to whoever manages the taf.
 */
@Composable
private fun WorkPanel(taf: Taf, state: AppState, actions: TafActions) {
  val scope = rememberCoroutineScope()
  LaunchedEffect(taf.id) { actions.loadWork(taf.id) }
  val work = state.works[taf.id]
  val error = state.workErrors[taf.id]
  if (work == null && error == null) return
  Panel {
    Eyebrow("Its work")
    work?.summary?.line?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(15, color = Theme.ink)) }
    work?.let { Chat.workRows(it) }?.forEach { row ->
      when (row) {
        is Chat.WorkRow.Skipped -> Text(row.firing.words, style = scaled(13, color = Theme.dim))
        is Chat.WorkRow.Request -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          val r = row.request
          Text(r.line, style = scaled(14, color = when (r.state) {
            "needs_you" -> Theme.amberText
            "failed" -> Theme.no
            else -> Theme.soft
          }))
          r.deliveries.forEach { d ->
            Text(d.line, style = scaled(12, color = if (d.sendAgain) Theme.amberText else Theme.dim))
            if (d.sendAgain && state.manages(taf.id)) {
              OutlineButton(
                if (r.id in state.sendingAgain) "Sending again…" else "Send again",
                { scope.launch { actions.sendAgain(taf.id, r.id) } },
                Modifier.fillMaxWidth(),
                enabled = r.id !in state.sendingAgain,
              )
            }
          }
        }
      }
    }
    error?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
}

/** The taf's latest run on tafs cloud and, once it stops, what it reported. */
@Composable
private fun RunReportPanel(tafId: String, report: RunReport?, actions: TafActions) {
  val scope = rememberCoroutineScope()
  LaunchedEffect(tafId) { actions.loadReport(tafId) }
  Panel {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Eyebrow("Last run")
      Spacer(Modifier.weight(1f))
      Text(
        "Refresh",
        style = scaled(13, color = Theme.amberText),
        modifier = Modifier.heightIn(min = 44.dp).clickable { scope.launch { actions.loadReport(tafId) } }.padding(top = 12.dp),
      )
    }
    if (report == null) {
      CircularProgressIndicator(Modifier.size(18.dp), color = Theme.amber, strokeWidth = 2.dp)
    } else {
      report.at?.let { at ->
        val how = if (report.finished) "finished" else if (report.status == "running") "still running" else "no session"
        Text("${shortTime(at)} · $how", style = scaled(12, color = Theme.dim, mono = true))
      }
      when {
        // A taf writes markdown: shown as the chat shows it, never raw (kiff/tafs#96).
        report.text.isNotEmpty() -> MarkdownText(report.text, size = 14, modifier = Modifier.fillMaxWidth())
        report.session == null && report.error == null -> Text("No run yet.", style = scaled(14, color = Theme.dim))
        !report.finished && report.error == null -> Text("Its report shows here when the run ends.", style = scaled(14, color = Theme.dim))
      }
      report.error?.let { Text(it, style = scaled(13, color = Theme.no)) }
    }
  }
}

/** What the taf has written down about how the owner likes the job done. */
@Composable
private fun MemoryPanel(tafId: String, notes: List<MemoryNote>?, error: String?, actions: TafActions) {
  LaunchedEffect(tafId) { actions.loadMemory(tafId) }
  // The note waiting for the owner's yes: forgetting can't be undone (the 755 retest).
  var forgetting by remember { mutableStateOf<MemoryNote?>(null) }
  Panel {
    Eyebrow("What it remembers")
    if (notes != null) {
      if (notes.isEmpty()) {
        Text("Nothing yet. It writes down what it learns about how you like the job done.", style = scaled(14, color = Theme.soft))
      }
      notes.forEach { note ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(note.content, style = scaled(15, color = Theme.ink))
            Text("${note.title} · ${shortTime(note.updatedAt)}", style = scaled(12, color = Theme.soft))
          }
          Text(
            "Forget",
            style = scaled(14, FontWeight.SemiBold, Theme.soft),
            modifier = Modifier.heightIn(min = 44.dp).clickable { forgetting = note },
          )
        }
      }
      Text(
        "Every run and chat starts with these. They shape how it works, never what its Card allows. Forget removes that one note and its earlier versions; past chats and run reports stay until the taf is removed.",
        style = scaled(12, color = Theme.soft),
      )
    } else if (error == null) {
      CircularProgressIndicator(Modifier.size(18.dp), color = Theme.amber, strokeWidth = 2.dp)
    }
    error?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
  forgetting?.let { note ->
    AlertDialog(
      onDismissRequest = { forgetting = null },
      title = { Text("Forget this note?") },
      text = { Text("“${note.content}”\n\nIt can't be undone: the taf stops working with it from its next run or chat. Other notes stay.") },
      confirmButton = { TextButton({ forgetting = null; actions.forget(note, tafId) }) { Text("Forget", color = Theme.no) } },
      dismissButton = { TextButton({ forgetting = null }) { Text("Cancel", color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
}

/** The second column's width, and the least the chat keeps beside it: as iOS's, the largest iPhone's width. */
private const val SIDE_WIDTH = 360
private const val CHAT_AT_LEAST = 430

/** Beside the chat or the activity on a wide screen: what waits for the owner, then the Cards, and a way to all of them. */
@Composable
private fun SideColumn(taf: Taf, state: AppState, actions: TafActions, front: String?, onFront: (String) -> Unit, onAll: () -> Unit) {
  Column(
    Modifier.width(SIDE_WIDTH.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    val held = state.heldCalls(taf.id)
    if (held.isNotEmpty()) {
      Eyebrow("Needs you", color = Theme.amber)
      held.forEach { HeldCallCard(it, state, actions.answer) }
    }
    val cards = taf.shownCards
    Eyebrow(if (cards.size == 1) "Card" else "Cards")
    CardStack(cards, actions.issuer, Theme.tafColor(taf.colorIndex), front, onFront, revoked = taf.card.revoked)
    OutlineButton(if (cards.size == 1) "All of the Card" else "All of the Cards", onAll, Modifier.fillMaxWidth())
  }
}
