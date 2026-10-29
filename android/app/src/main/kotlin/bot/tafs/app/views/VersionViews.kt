package bot.tafs.app.views

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import bot.tafs.app.app.trialChatId
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.kiff.AgentDraft
import bot.tafs.app.kiff.DraftCard
import bot.tafs.app.kiff.DraftRetired
import bot.tafs.app.kiff.Switching
import bot.tafs.app.kiff.Version
import bot.tafs.app.model.VersionWords
import kotlinx.coroutines.launch

/** What the Versions screens ask of the model. */
class VersionActions(
  val load: suspend (String) -> Unit,
  val prepare: suspend (Version, String, Boolean, Boolean) -> Switching?,
  val card: suspend (Version, String) -> AgentDraft?,
  val use: suspend (Version, String, String?) -> Boolean,
  val cancel: suspend (String) -> Unit,
  val loadTrial: suspend (String) -> Unit,
  val trialCard: suspend (Version, String) -> AgentDraft?,
  val startTrying: suspend (Version, String, String) -> String?,
  val installTrial: suspend (String) -> Boolean,
  val goBack: suspend (String) -> Boolean,
)

/**
 * The taf's versions, like app builds: newest first, the live one marked. Install makes one live, forward or back,
 * with one confirmation; a version that brings a Card has its Card read first, now → after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsSheet(tafId: String, state: AppState, actions: VersionActions, onOpenTrial: () -> Unit, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  /** The version whose Card the owner reads before confirming. */
  var reading by remember { mutableStateOf<Version?>(null) }
  /** The version whose trial the owner is about to start. */
  var starting by remember { mutableStateOf<Version?>(null) }
  val list = state.versions[tafId]
  val switchingNow = state.switchingVersion[tafId]
  val under = state.switches[tafId]
  val error = state.versionErrors[tafId]
  val trial = state.openTrials[tafId]
  val trialBusy = tafId in state.trialBusy

  LaunchedEffect(tafId) {
    actions.load(tafId)
    actions.loadTrial(tafId)
  }
  // A Card that changed between the screen and the tap opens again, to read anew.
  val changed = state.changedVersionCards[tafId]
  LaunchedEffect(changed?.id) {
    if (changed != null && reading == null) reading = state.versions[tafId]?.firstOrNull { state.switchDraft(it, tafId) == changed.id }
  }

  /** Install this version: the switch begins with the Card draft tafs cloud prepared, which is read first; with none, one confirmation makes it live. */
  fun use(v: Version, redraft: Boolean = false, stopBlocking: Boolean = false) {
    scope.launch {
      val sw = actions.prepare(v, tafId, redraft, stopBlocking) ?: return@launch
      if (sw.draft == null) actions.use(v, tafId, null) else reading = v
    }
  }

  VersionsFrame("Versions", onClose) {
    Panel {
      Eyebrow("Versions")
      Text(VersionWords.INTRO, style = scaled(13, color = Theme.soft))
      Text(VersionWords.TRY_FIRST, style = scaled(13, color = Theme.soft))
      if (under != null) SwitchUnderWay(under, state.versions[tafId]?.firstOrNull { it.n == under.n }, switchingNow != null, onFinish = { use(it) }, onCancel = { scope.launch { actions.cancel(tafId) } })
      state.staleVersionCards[tafId]?.let { n ->
        list?.firstOrNull { it.n == n }?.let { v -> OutlineButton("Draft its Card again", { use(v, redraft = true) }, Modifier.fillMaxWidth()) }
      }
      if (list != null) {
        if (list.size <= 1) Text(VersionWords.FIRST_ONLY, style = scaled(14, color = Theme.soft))
        list.asReversed().forEach { v ->
          VersionRow(
            v,
            installing = switchingNow == v.n,
            // One switch at a time, and none while a trial is open: Install ends the trial.
            canInstall = switchingNow == null && trial == null && (under == null || under.n == v.n),
            trying = trial?.n == v.n,
            // One trial per taf: another waits for the open one to end.
            canTry = trial == null && !trialBusy,
            onTry = { starting = v },
            onResume = onOpenTrial,
            onInstall = { use(v) },
          )
        }
      } else if (error == null) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Theme.amber)
      }
      if (error != null) Text(error, style = scaled(13, color = Theme.no))
      state.trialErrors[tafId]?.let { Text(it, style = scaled(13, color = Theme.no)) }
      state.blockedInstalls[tafId]?.let { n ->
        // One step: what is in the way stops (a held call withdrawn, nothing sent), then the install begins.
        list?.firstOrNull { it.n == n }?.let { v -> OutlineButton("Stop it and install", { use(v, stopBlocking = true) }, Modifier.fillMaxWidth()) }
      }
    }
  }

  starting?.let { v ->
    TrialStartSheet(v, tafId, state, actions, onClose = { starting = null }, onStarted = { starting = null; onOpenTrial() })
  }

  reading?.let { v ->
    VersionCardSheet(
      v, tafId, state, actions.card,
      onCancel = { reading = null; scope.launch { actions.cancel(tafId) } },
      onInstall = { shown -> reading = null; scope.launch { actions.use(v, tafId, shown) } },
    )
  }
}

@Composable
private fun VersionRow(
  v: Version,
  installing: Boolean,
  canInstall: Boolean,
  trying: Boolean,
  canTry: Boolean,
  onTry: () -> Unit,
  onResume: () -> Unit,
  onInstall: () -> Unit,
) {
  Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Version ${v.n}", style = scaled(15, FontWeight.SemiBold, Theme.ink))
      if (v.live) Chip("Live now", Theme.ok) else if (trying) Chip("Trying", Theme.amber)
    }
    v.note?.takeIf { it.isNotEmpty() }?.let { Text(it, style = scaled(14, color = Theme.ink)) }
    Text(VersionWords.byline(v), style = scaled(12, color = Theme.dim))
    // What each ended trial came to, the one line kept once its chat is gone.
    v.trials.orEmpty().forEach { Text(VersionWords.tried(it), style = scaled(12, color = Theme.soft)) }
    if (!v.live) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (trying) OutlineButton("Resume the trial", onResume, Modifier.weight(1f))
        else OutlineButton("Try it", onTry, Modifier.weight(1f), enabled = canTry)
        OutlineButton(if (installing) "Installing…" else "Install this version", onInstall, Modifier.weight(1f), enabled = canInstall)
      }
    }
  }
}

/**
 * Before a trial starts: what the version changes, the Card it runs under, and what trying it means for the live taf.
 * One confirmation starts it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrialStartSheet(version: Version, tafId: String, state: AppState, actions: VersionActions, onClose: () -> Unit, onStarted: () -> Unit) {
  val scope = rememberCoroutineScope()
  var draft by remember { mutableStateOf<AgentDraft?>(null) }
  var loaded by remember { mutableStateOf(false) }
  val busy = tafId in state.trialBusy
  val changed = state.changedVersionCards[tafId]
  LaunchedEffect(version.n, changed?.version) {
    draft = changed?.takeIf { it.id == draft?.id } ?: actions.trialCard(version, tafId)
    loaded = true
  }

  VersionsFrame("Try it", onClose) {
    Text("Try version ${version.n}${version.note?.let { ": $it" } ?: ""}", style = scaled(17, FontWeight.SemiBold, Theme.ink))
    Panel {
      Eyebrow("While you try it")
      VersionWords.whileYouTry(version.n).forEach { Text(it, style = scaled(14, color = Theme.ink)) }
    }
    val shown = draft
    if (shown?.cards != null) {
      Eyebrow("Its Card")
      DraftCardsView(shown)
    } else if (loaded) {
      Text(state.trialErrors[tafId] ?: "This trial's Card couldn't be read.", style = scaled(13, color = Theme.no))
    } else {
      CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Theme.amber)
    }
    if (shown != null) state.trialErrors[tafId]?.let { Text(it, style = scaled(13, color = Theme.no)) }
    OutlineButton(
      if (busy) "Starting…" else "Start trying",
      {
        val read = draft?.version ?: return@OutlineButton
        scope.launch {
          if (actions.startTrying(version, tafId, read) != null) onStarted()
          // Changed since it was shown: the new terms, to read again.
          else state.changedVersionCards[tafId]?.takeIf { it.id == draft?.id }?.let { draft = it }
        }
      },
      Modifier.fillMaxWidth(),
      enabled = draft?.cards != null && !busy,
    )
    OutlineButton("Cancel", onClose, Modifier.fillMaxWidth(), color = Theme.soft)
  }
}

/**
 * A trial, open like a taf: the version's chat under a bar that says it isn't live, with Go back and Install. The chat
 * is the taf's chat view, on the trial's own chat id.
 */
@Composable
fun TrialScreen(tafId: String, state: AppState, actions: VersionActions, tafActions: TafActions, onBack: () -> Unit) {
  val scope = rememberCoroutineScope()
  var confirmBack by remember { mutableStateOf(false) }
  var confirmInstall by remember { mutableStateOf(false) }
  var read by remember { mutableStateOf(false) }
  val trial = state.openTrials[tafId]
  val taf = state.taf(tafId)
  val chat = trialChatId(tafId)

  LaunchedEffect(tafId) { actions.loadTrial(tafId); read = true }
  // The trial ended (Install, Go back, a week unused): back to its taf, never a screen saying it's gone.
  LaunchedEffect(trial == null, read) { if (trial == null && read) onBack() }

  Column(Modifier.fillMaxSize().statusBarsPadding()) {
    BackBar(onBack)
    if (trial != null && taf != null) {
      val busy = tafId in state.trialBusy
      Column(
        Modifier.fillMaxWidth().background(Theme.amber.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Trying version ${trial.n}", style = scaled(15, FontWeight.SemiBold, Theme.ink))
          Chip("not live", Theme.amber)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          OutlineButton("Go back", { confirmBack = true }, Modifier.weight(1f), color = Theme.no, enabled = !busy)
          OutlineButton(if (busy) "Working…" else "Install", { confirmInstall = true }, Modifier.weight(1f), enabled = !busy)
        }
        state.trialErrors[tafId]?.let { Text(it, style = scaled(13, color = Theme.no)) }
      }
      // Before its first message, what this trial is and what to try.
      if (state.chatItems(chat).isEmpty()) {
        val name = taf.name.substringBefore(" · v")
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          VersionWords.trialGuide(trial.changed, trial.tryWith, name, trial.ends).forEachIndexed { i, line ->
            Text(line, style = if (i == 0 || i == 1) scaled(14, color = Theme.ink) else scaled(13, color = Theme.soft))
          }
        }
      }
      TafChatView(chat, state, tafActions, Modifier.weight(1f))
    }
  }

  if (confirmBack && trial != null && taf != null) {
    AlertDialog(
      onDismissRequest = { confirmBack = false },
      title = { Text("Go back to the live version?") },
      text = { Text("Version ${trial.n}'s trial Card stops at once, and this chat is deleted. ${taf.name.substringBefore(" · v")} keeps working as it is.") },
      confirmButton = { TextButton({ confirmBack = false; scope.launch { actions.goBack(tafId) } }) { Text("Go back", color = Theme.no) } },
      dismissButton = { TextButton({ confirmBack = false }) { Text("Cancel", color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
  if (confirmInstall && trial != null) {
    val biometry = LocalBiometry.current
    AlertDialog(
      onDismissRequest = { confirmInstall = false },
      title = { Text("Install version ${trial.n}?") },
      text = { Text("Version ${trial.n} becomes the live version, with its Card. This chat is deleted.") },
      confirmButton = { TextButton({ confirmInstall = false; scope.launch { actions.installTrial(tafId) } }) { Text("Install with $biometry", color = Theme.amber) } },
      dismissButton = { TextButton({ confirmInstall = false }) { Text("Cancel", color = Theme.soft) } },
      containerColor = Theme.panel,
    )
  }
}

@Composable
private fun Chip(text: String, color: Color) {
  Text(
    text,
    style = scaled(11, FontWeight.Bold, Color.White),
    modifier = Modifier.clip(CircleShape).background(color).padding(horizontal = 7.dp, vertical = 2.dp),
  )
}

/** A switch that didn't finish: the taf doesn't run until it is finished or cancelled. */
@Composable
private fun SwitchUnderWay(sw: Switching, version: Version?, working: Boolean, onFinish: (Version) -> Unit, onCancel: () -> Unit) {
  Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Installing version ${sw.n}", style = scaled(15, FontWeight.SemiBold, Theme.amberText))
    Text("Your taf doesn't run until it's installed or you cancel.", style = scaled(13, color = Theme.soft))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      if (version != null) OutlineButton(if (working) "Installing…" else "Finish", { onFinish(version) }, Modifier.weight(1f), enabled = !working)
      OutlineButton("Cancel", onCancel, Modifier.weight(1f), color = Theme.no, enabled = !working)
    }
  }
}

/**
 * The Card a version brings, read before the confirmation: each Card it revises as it is now and as it will be, and
 * each new one. Install signs exactly what this sheet shows. The switch is begun while this is open: Cancel or Install
 * ends it, never a swipe that would leave the taf not running with nothing on screen to finish it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionCardSheet(
  version: Version,
  tafId: String,
  state: AppState,
  /** Reads the Card the version brings. */
  card: suspend (Version, String) -> AgentDraft?,
  onCancel: () -> Unit,
  onInstall: (String?) -> Unit,
) {
  var draft by remember { mutableStateOf<AgentDraft?>(null) }
  var loaded by remember { mutableStateOf(false) }
  val changed = state.changedVersionCards[tafId]?.takeIf { it.id == state.switchDraft(version, tafId) }
  // The terms that changed since last shown, when that is why this opened.
  LaunchedEffect(version.n, changed?.version) {
    draft = changed ?: card(version, tafId)
    loaded = true
  }

  ModalBottomSheet(
    onDismissRequest = {},
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { false }),
    containerColor = Theme.background,
    sheetGesturesEnabled = false,
  ) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Its Card", style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        // Not now: the switch it began is dropped, and the taf runs as it was.
        Text("Cancel", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onCancel).padding(top = 8.dp))
      }
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text("Version ${version.n}${version.note?.let { ": $it" } ?: ""}", style = scaled(17, FontWeight.SemiBold, Theme.ink))
        val shown = draft
        if (shown?.cards != null) {
          DraftCardsView(shown)
          Text(VersionWords.cardNote(shown.retires?.isNotEmpty() == true), style = scaled(12, color = Theme.soft))
        } else if (loaded) {
          Text(state.versionErrors[tafId] ?: "This version's Card couldn't be read.", style = scaled(13, color = Theme.no))
        } else {
          CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Theme.amber)
        }
        val readable = draft?.cards != null
        OutlineButton("Install this version", { onInstall(draft?.version) }, Modifier.fillMaxWidth(), enabled = readable)
      }
    }
  }
}

/** The Cards a draft brings, as the owner reads them before confirming: each Card it revises as it is now and as it will be, each new one, and each it removes. */
@Composable
fun DraftCardsView(draft: AgentDraft) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    val lines = draft.cardLines
    draft.cards.orEmpty().forEachIndexed { i, card -> CardView(card, lines[i]) }
    draft.retires.orEmpty().forEach { Retired(it) }
  }
}

/** A Card this version doesn't have: confirming removes it, so what it allows stops. */
@Composable
private fun Retired(card: DraftRetired) {
  Panel {
    Eyebrow("Removes a Card")
    card.current?.lines.orEmpty().forEach { Text(it, style = scaled(14, color = Theme.soft).copy(textDecoration = TextDecoration.LineThrough)) }
    Text("Your taf can't do this any more.", style = scaled(12, color = Theme.dim))
  }
}

/** One Card of the version, with its lines as the draft reads them. */
@Composable
private fun CardView(card: DraftCard, lines: List<String>) {
  Panel {
    when {
      // Re-issued as it is: its terms once, not a now and after.
      card.isUnchanged -> {
        Eyebrow("Unchanged")
        lines.forEach { Text(it, style = scaled(14, color = Theme.soft)) }
      }
      card.current != null -> {
        Eyebrow("Changes a Card")
        Text("Now", style = scaled(12, FontWeight.SemiBold, Theme.dim))
        card.current.lines.forEach { Text(it, style = scaled(14, color = Theme.soft)) }
        Text("After", style = scaled(12, FontWeight.SemiBold, Theme.dim))
      }
      else -> Eyebrow("A new Card")
    }
    if (!card.isUnchanged) lines.forEach { Text(it, style = scaled(14, FontWeight.SemiBold, Theme.ink)) }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionsFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Theme.background,
  ) {
    // Its height is fixed: what loads in doesn't resize a sheet that was measured while it was empty.
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = scaled(28, FontWeight.Bold, Theme.ink, display = true))
        Text("Done", style = scaled(17, FontWeight.SemiBold, Theme.amber), modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClose).padding(top = 8.dp))
      }
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) { content() }
    }
  }
}

/** The row on a taf's page that opens its versions. */
@Composable
fun VersionsRow(onClick: () -> Unit) {
  Panel(Modifier.clickable(onClick = onClick)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Versions", style = scaled(15, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.weight(1f))
      Text("›", style = scaled(20, color = Theme.dim))
    }
  }
}
