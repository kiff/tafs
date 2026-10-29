package bot.tafs.app.views

import bot.tafs.app.model.SecondStep
import bot.tafs.app.model.shortTime
import bot.tafs.app.model.dayTitle
import bot.tafs.app.model.localDate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalUriHandler
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.kiff.AnswerRefusals
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.kiff.AgentDraft
import bot.tafs.app.kiff.Version
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.ScreenTitle
import bot.tafs.app.design.TafAvatar
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.model.Answer
import bot.tafs.app.model.HeldCall
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/** While a call waits: nothing the call asks for happens until someone approves it. */
const val NOTHING_SENT = "Nothing is sent until you approve."

/** Where a held call is answered in KIFF today, for a call KIFF holds; null for the sample's, which this phone answers. */
val LocalAnswerPage = staticCompositionLocalOf<(HeldCall) -> String?> { { null } }

/**
 * Coach's held installs (kiff/tafs#206): approving one is installing its version. The switch begins, what's in the way stopped;
 * a Card to change is read first; then one confirmation confirms it, makes the version live, and approves the held call.
 */
class CoachInstalls(
  val begin: suspend (HeldCall) -> AppModel.CoachInstall?,
  /** Installs it, with the Card draft's version as the owner read it (null when it brings none). */
  val use: suspend (AppModel.CoachInstall, String?) -> Boolean,
  val card: suspend (Version, String) -> AgentDraft?,
  /** Drops the switch begun, so the taf runs as it was. */
  val cancel: suspend (String) -> Unit,
)

/** Null where installing isn't offered: the sample, and previews. */
val LocalCoachInstalls = staticCompositionLocalOf<CoachInstalls?> { null }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NeedsYouView(state: AppState, onAnswer: suspend (HeldCall, Answer) -> Unit, onRefresh: suspend () -> Unit = {}, modifier: Modifier = Modifier) {
  // Pulling reads KIFF again, as on iOS: a hold made while this screen was open shows without leaving it.
  var refreshing by remember { mutableStateOf(false) }
  val pullScope = rememberCoroutineScope()
  androidx.compose.material3.pulltorefresh.PullToRefreshBox(
    isRefreshing = refreshing,
    onRefresh = {
      pullScope.launch {
        refreshing = true
        try { onRefresh() } finally { refreshing = false }
      }
    },
    modifier = modifier.fillMaxSize(),
  ) {
  LazyColumn(
    Modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item { ScreenTitle("Needs you") }
    if (state.held.isEmpty()) {
      item {
        Panel {
          Text("Nothing is waiting.", style = scaled(15, FontWeight.SemiBold, Theme.ink))
          Text(
            "When a taf asks for something outside its Card, it shows up here and on your lock screen.",
            style = scaled(14, color = Theme.soft),
          )
        }
      }
    } else {
      items(state.held, key = { it.id }) { call -> HeldCallCard(call, state, onAnswer) }
    }
  }
  }
}

@Composable
fun HeldCallCard(call: HeldCall, state: AppState, onAnswer: suspend (HeldCall, Answer) -> Unit) {
  val taf = state.taf(call.tafId)
  // Set on the tap itself, until the owner has confirmed or declined: a second tap sends nothing.
  val scope = rememberCoroutineScope()
  var sending by remember { mutableStateOf(false) }
  fun send(answer: Answer) {
    if (sending) return
    sending = true
    scope.launch {
      try {
        onAnswer(call, answer)
      } finally {
        sending = false
      }
    }
  }
  val installs = LocalCoachInstalls.current
  // Coach's install, its switch begun, while its Card is read.
  var installing by remember { mutableStateOf<AppModel.CoachInstall?>(null) }
  fun install() {
    val i = installs ?: return
    if (sending) return
    sending = true
    scope.launch {
      try {
        val ready = i.begin(call) ?: return@launch
        if (ready.hasCard) installing = ready else i.use(ready, null)
      } finally {
        sending = false
      }
    }
  }
  // Counted down every second wherever the card is; it turns to "expired" at the end.
  val now by produceState(Instant.now(), call.id) {
    while (true) {
      value = Instant.now()
      delay(1000)
    }
  }
  val expired = now >= call.expiresAt
  Panel(stroke = Theme.amber.copy(alpha = 0.4f)) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (taf != null) TafAvatar(taf, 32.dp)
        Text(taf?.name ?: "A taf", style = scaled(14, FontWeight.SemiBold, Theme.ink), modifier = Modifier.weight(1f))
        Text(remaining(call, now), style = scaled(12, color = Theme.amberText, mono = true))
      }
      call.proposed.amount?.let { Text(it.formatted, style = scaled(44, FontWeight.Bold, Theme.ink, display = true)) }
      Text(call.proposed.summary, style = scaled(16, FontWeight.SemiBold, Theme.ink))
      if (call.proposed.context.isNotEmpty()) Text(call.proposed.context, style = scaled(14, color = Theme.soft))
      Text(
        call.reason,
        style = scaled(12, FontWeight.Medium, Theme.amberText),
        modifier = Modifier.clip(CircleShape).background(Theme.amber.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 5.dp),
      )
    }
    // What it's on, as KIFF keeps the call (kiff/tafs#102), under Details: ids and a vendor's own values aren't
    // words for the card's face (kiff/tafs#236, item 7).
    call.details?.takeIf { it.isNotEmpty() }?.let { details -> Details(details) }

    val lasting = state.unanswerable[call.id]
    if (expired) {
      Text("Expired. Nobody answered in time, so KIFF refused it and nothing was sent.", style = scaled(14, color = Theme.soft))
    } else if (lasting != null && !AnswerRefusals.refusesOnly(lasting)) {
      // KIFF says this person may never answer it: words, not buttons that can't work.
      Text(AnswerRefusals.refusalWords(lasting) ?: "KIFF doesn't take your answer to this one.", style = scaled(14, color = Theme.soft))
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Said here, where the owner answers, so the taf's words needn't say it.
        Text(NOTHING_SENT, style = scaled(14, color = Theme.soft))
        // They asked for it: someone else approves, and they can still refuse it.
        lasting?.let { code -> AnswerRefusals.refusalWords(code)?.let { Text(it, style = scaled(14, color = Theme.soft)) } }
        Answers(call, state, refuseOnly = lasting != null, sending = sending, send = ::send, install = if (installs != null) ::install else null)
        // Why the install didn't go on: a run in the way, a Card that changed, a switch that didn't finish.
        if (call.install != null) installTaf(call, state)?.let { tafId -> state.versionErrors[tafId]?.let { Text(it, style = scaled(13, color = Theme.no)) } }
      }
    }
  }
  val ready = installing
  if (ready != null && installs != null) {
    VersionCardSheet(
      ready.version, ready.tafId, state, installs.card,
      onCancel = { installing = null; scope.launch { installs.cancel(ready.tafId) } },
      onInstall = { shown ->
        installing = null
        sending = true
        scope.launch {
          try {
            installs.use(ready, shown)
          } finally {
            sending = false
          }
        }
      },
    )
  }
}

/** The taf a held install of Coach's names, as this phone lists it: by its tafs id, else by its name. */
private fun installTaf(call: HeldCall, state: AppState): String? {
  val ask = call.install ?: return null
  return (state.tafs.firstOrNull { it.cloudId == ask.taf } ?: state.tafs.firstOrNull { it.name.equals(ask.taf, ignoreCase = true) })?.id
}

/** What the owner can answer a held call with, while it can still be answered. [install]: approving Coach's held install installs it. */
@Composable
private fun Answers(call: HeldCall, state: AppState, refuseOnly: Boolean, sending: Boolean, send: (Answer) -> Unit, install: (() -> Unit)?) {
  val biometry = LocalBiometry.current
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (!state.mayAnswerHolds) {
      // A viewer uses the taf but doesn't answer for the account.
      Text("Someone who approves on this account answers this. Their answer shows here.", style = scaled(13, color = Theme.soft))
    } else if (state.canAnswer(call)) {
      if (refuseOnly) {
        // KIFF takes this person's refusal but not their approval (they asked for the call): Refuse and the next
        // steps stay, approving and extra room go.
      } else if (call.install != null) {
        // Installing is approving: the version goes live, then Coach hears. Not offered where it can't be done.
        if (install != null) PrimaryButton(if (sending) "Installing…" else "Install with $biometry", install, enabled = !sending)
      } else if (call.approvableOnce) {
        // Back after extra room: the first step went through, and this is the second, said plainly with KIFF's reason.
        if (call.kiffId != null && call.kiffId in state.roomGiven) {
          Text(SecondStep.words(call.reason), style = scaled(13, color = Theme.amberText))
        }
        PrimaryButton("Approve once with $biometry", { send(Answer.ApproveOnce) }, enabled = !sending)
      } else {
        // Over a total the Card keeps across calls: approving once can't pass it (kiff/tafs#138, item 49). Extra room
        // can, for this call, until that total resets (kiff/tafs#211, 6.13); a lasting change is Coach's.
        Text(
          if (state.canManageCards) {
            "Approving it once can't pass this: it's over a total the Card allows across calls, such as a day's. Extra room adds just what this call needs, until that total resets. After that the Card is as it was."
          } else {
            "Approving it once can't pass this: it's over a total the Card allows across calls, such as a day's. Someone who manages the account's Cards can give it extra room, or you can refuse it."
          },
          style = scaled(13, color = Theme.soft),
        )
        refusedLine(state.refusedToday(call.tafId))?.let { Text(it, style = scaled(13, color = Theme.soft)) }
        if (state.canManageCards) PrimaryButton("Extra room with $biometry", { send(Answer.ExtraRoom) }, enabled = !sending)
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        call.nextSteps.forEach { step -> OutlineButton(step, { send(Answer.NextStep(step)) }, Modifier.weight(1f), enabled = !sending) }
        OutlineButton("Refuse", { send(Answer.Refuse) }, Modifier.weight(1f), color = Theme.no, enabled = !sending)
      }
    } else {
      Text(
        "Answering from the phone arrives once KIFF checks the phone's signature. Until then, answer in KIFF.",
        style = scaled(13, color = Theme.soft),
      )
      LocalAnswerPage.current(call)?.let { page ->
        val uri = LocalUriHandler.current
        PrimaryButton("Answer in KIFF", { uri.openUri(page) })
      }
    }
  }
}

/** What the call is on, one line each, as KIFF keeps its arguments: folded, since ids aren't words for the card's face. */
@Composable
private fun Details(lines: List<String>) {
  var open by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      if (open) "Details ▾" else "Details ▸",
      style = scaled(13, FontWeight.SemiBold, Theme.blue),
      modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
    )
    if (open) {
      SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          lines.forEach { Text(it, style = scaled(12, color = Theme.soft, mono = true)) }
        }
      }
    }
  }
}

/**
 * How many calls the vendor refused today that still count toward the Card's totals, in a line, naming the vendor
 * when every one is the same; null for none.
 */
fun refusedLine(refused: List<bot.tafs.app.model.RecordEntry>): String? {
  if (refused.isEmpty()) return null
  val vendors = refused.mapNotNull { it.refusedBy }.toSet()
  val who = if (vendors.size == 1 && refused.all { it.refusedBy != null }) vendors.first() else "the vendor"
  val calls = if (refused.size == 1) "1 call" else "${refused.size} calls"
  return "$calls $who refused today still counted toward it: nothing moved, but they used room."
}

private fun remaining(call: HeldCall, now: Instant): String {
  val seconds = Duration.between(now, call.expiresAt).seconds
  if (seconds < 1) return "expired"
  return "%d:%02d left".format(seconds / 60, seconds % 60)
}
