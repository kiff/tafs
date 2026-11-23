package bot.tafs.app.views

import bot.tafs.app.design.tapping
import bot.tafs.app.model.Words
import bot.tafs.app.drive.confirms
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
import androidx.compose.runtime.setValue
import bot.tafs.app.drive.DriveAlert
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import bot.tafs.app.app.Relink
import bot.tafs.app.design.Eyebrow
import androidx.compose.ui.platform.LocalUriHandler
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.kiff.AnswerRefusals
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
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
val NOTHING_SENT get() = Words.NeedsYou.nothingSent

/** Where a held call is answered in KIFF today, for a call KIFF holds; null for the sample's, which this phone answers. */
val LocalAnswerPage = staticCompositionLocalOf<(HeldCall) -> String?> { { null } }

/** Opens Coach on a new conversation about a taf, by its id: for a hold over a total, to change the limit. Null where Coach isn't there. */
val LocalImproveInCoach = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Signs in again with KIFF, which registers a new approval key: where the phone can't approve, it's offered in place of a button that would fail. */
val LocalSignInAgain = staticCompositionLocalOf<(() -> Unit)?> { null }

/** KIFF keeps this session view-only: the key line says to sign in again on this phone, or approve from the other one. */
val LocalViewOnly = compositionLocalOf { false }

/** This phone holds no approval key (`AppState.noApprovalKeyHere`): its view-only line asks for a fingerprint, not a new sign-in. */
val LocalNoApprovalKeyHere = compositionLocalOf { false }

/**
 * Approve again in your browser, where KIFF can re-link this phone: what it's waiting for, and its actions. Null where it isn't
 * offered (the sample, previews, a KIFF without it), which leaves Sign in again.
 */
class ApproveAgain(
  val relink: Relink?,
  val problem: String?,
  val start: () -> Unit,
  val cancel: () -> Unit,
  /** The app came back from the browser: KIFF is asked again at once. */
  val nudge: () -> Unit,
)

val LocalApproveAgain = compositionLocalOf<ApproveAgain?> { null }

/**
 * The phone can't approve (its approval key is gone, or KIFF holds another): said where the owner would approve, before any
 * tap, with Approve again in your browser where KIFF can re-link it, else Sign in again. Refusing still works.
 */
@Composable
fun ApprovalKeyLine(modifier: Modifier = Modifier) {
  val signInAgain = LocalSignInAgain.current
  val again = LocalApproveAgain.current
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (again == null) {
      val noKey = LocalNoApprovalKeyHere.current
      Text(viewOnlyLine(LocalViewOnly.current, noKey), style = scaled(14, color = Theme.amberText))
      // With no fingerprint the line asks for one first; Sign in again stays, since KIFF learns the new key at the next sign-in.
      signInAgain?.let { OutlineButton(Words.Connect.signInAgain, it, Modifier.fillMaxWidth(), color = Theme.amberText) }
      return@Column
    }
    Text(bot.tafs.app.signing.ApprovalKeyRenewed.APPROVE_AGAIN_WORDS, style = scaled(14, color = Theme.amberText))
    val relink = again.relink
    if (relink == null) {
      OutlineButton(APPROVE_AGAIN, again.start, Modifier.fillMaxWidth(), color = Theme.amberText)
      // The way that always works: a new sign-in, which ends this session first.
      signInAgain?.let {
        Text(
          Words.NeedsYou.signInAgainInstead,
          style = scaled(13, FontWeight.SemiBold, Theme.dim),
          modifier = Modifier.heightIn(min = 44.dp).tapping(onClick = it).padding(vertical = 12.dp),
        )
      }
    } else {
      val uri = LocalUriHandler.current
      // Back from approving in the browser: ask KIFF at once.
      LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { again.nudge() }
      Panel(stroke = Theme.amber.copy(alpha = 0.4f)) {
        Eyebrow(Words.SignIn.yourCode)
        SelectionContainer { Text(relink.userCode, style = scaled(28, FontWeight.SemiBold, Theme.ink, mono = true)) }
        Text(Words.NeedsYou.checkCode, style = scaled(13, color = Theme.dim))
      }
      PrimaryButton(Words.SignIn.openKiff, { uri.openUri(relink.link) })
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Theme.amber, strokeWidth = 2.dp)
        Text(WAITING_FOR_BROWSER, style = scaled(14, color = Theme.soft), modifier = Modifier.weight(1f))
        // A 44 dp target, its word level with the line beside it.
        Text(Words.Common.cancel, style = scaled(14, FontWeight.SemiBold, Theme.amber), modifier = Modifier.tapping(onClick = again.cancel).padding(horizontal = 4.dp, vertical = 12.dp))
      }
    }
    again.problem?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
}

val APPROVE_AGAIN get() = Words.NeedsYou.approveAgain
val WAITING_FOR_BROWSER get() = Words.NeedsYou.waitingForBrowser

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

/** "Allow this from now on" on a held call (docs/allow-from-now-on.md): opens its sheet; null where it isn't offered. */
val LocalAllowHold = staticCompositionLocalOf<((HeldCall) -> Unit)?> { null }

/**
 * The phone's own checks on a sealed fill's release (docs/chrome.md, "The phone's release card"): why it won't sign, or null. Only
 * read while [bot.tafs.app.kiff.SealedRelease.ENABLED]; the card says why and offers Refuse only.
 */
val LocalFillRefusal = staticCompositionLocalOf<((HeldCall) -> bot.tafs.app.kiff.SealedRelease.Refusal?)?> { null }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NeedsYouView(
  state: AppState,
  onAnswer: suspend (HeldCall, Answer) -> Unit,
  onRefresh: suspend () -> Unit = {},
  modifier: Modifier = Modifier,
  onAnswerUnsure: (suspend (bot.tafs.app.kiff.UnsureCall, Boolean) -> Unit)? = null,
  onCheckUnsure: (suspend (bot.tafs.app.kiff.UnsureCall) -> Unit)? = null,
  onOpenCoach: ((bot.tafs.app.kiff.CoachWaiting) -> Unit)? = null,
  /** Sets a Coach chat aside, or all with null: tafs cloud's "not now". Null where it can't be done (sample mode). */
  onSetAside: ((String?) -> Unit)? = null,
) {
  var confirmingClear by remember { mutableStateOf(false) }
  val clearAsk = state.coachWaitingClear?.takeIf { confirmingClear }
  if (clearAsk != null) {
    val clear = clearAsk
    DriveAlert(
      onDismissRequest = { confirmingClear = false },
      containerColor = Theme.panel,
      title = { Text(clear.words.title) },
      text = { if (clear.words.text.isNotBlank()) Text(clear.words.text) },
      confirmButton = { androidx.compose.material3.TextButton({ confirmingClear = false; onSetAside?.invoke(null) }, modifier = Modifier.confirms()) { Text(clear.words.yes, color = Theme.no) } },
      dismissButton = { androidx.compose.material3.TextButton({ confirmingClear = false }) { Text(clear.words.no, color = Theme.soft) } },
    )
  }
  val unsureScope = rememberCoroutineScope()
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
    item { ScreenTitle(Words.NeedsYou.title) }
    // After a set-aside, where the chat went, in tafs cloud's words: the owner went looking for it in Coach.
    state.setAsideNote?.let { note -> item(key = "cw|done") { Text(note, style = scaled(14, color = Theme.soft)) } }
    // Steps a taf handed its owner, oldest first, as tafs cloud lists them: before the held calls, as each is the owner's to do.
    items(state.yourSteps, key = { "ys|" + it.handoff }) { step -> YourStepPanel(step, state) }
    // Coach's chats waiting on this person, in tafs cloud's words: the button opens that chat, where the card is answered.
    // "Set all aside", tafs cloud's, above them: one ask, naming the count.
    state.coachWaitingClear?.takeIf { onSetAside != null && state.coachWaiting.size > 1 }?.let { clear ->
      item(key = "cw|clear") {
        Text(clear.words.button, style = scaled(14, FontWeight.SemiBold, Theme.amberText),
          modifier = Modifier.heightIn(min = 44.dp).confirms().tapping(enabled = state.settingAside.isEmpty()) { confirmingClear = true }.padding(vertical = 10.dp))
      }
    }
    // A row being set aside goes at once, so one full swipe takes on the first try (the owner's sprang back and looked
    // untouched until tafs cloud answered); a refusal brings it back, with why.
    items(state.coachWaitingShown, key = { "cw|" + it.session }) { waiting -> CoachWaitingPanel(waiting, onOpenCoach, onSetAside?.takeIf { waiting.words.setAside.isNotBlank() }) }
    // Cards asked to take another currency: tafs cloud's rows, apart from the held calls, with no Approve once.
    items(state.cardUnitExceptions, key = { "cu|" + it.id }) { ask -> CardUnitExceptionPanel(ask, state) }
    if (state.held.isEmpty() && state.unsure.isEmpty() && state.cardUnitExceptions.isEmpty() && state.yourSteps.isEmpty() && state.coachWaiting.isEmpty()) {
      // Nothing waiting: the owner's board 6, above the screen's own line.
      item { bot.tafs.app.design.Illustrated(Modifier.padding(bottom = 12.dp)) { bot.tafs.app.design.NeedsYouClearArt(it) } }
      item {
        Panel {
          Text(Words.NeedsYou.nothingWaiting, style = scaled(15, FontWeight.SemiBold, Theme.ink))
          Text(
            Words.NeedsYou.emptyHint,
            style = scaled(14, color = Theme.soft),
          )
        }
      }
    } else {
      items(state.held, key = { it.id }) { call -> HeldCallCard(call, state, onAnswer) }
    }
    // Money calls KIFF can't prove: Did this happen?, answered by someone who answers held calls.
    items(state.unsure, key = { "u|" + it.proposal }) { call ->
      UnsureCallCard(
        call,
        answering = state.answeringUnsure == call.proposal,
        error = state.unsureErrors[call.proposal],
        onAnswer = onAnswerUnsure?.takeIf { state.mayAnswerHolds }?.let { answer -> { c, happened -> unsureScope.launch { answer(c, happened) } } },
        onCheck = onCheckUnsure?.takeIf { state.mayAnswerHolds }?.let { check -> { c -> unsureScope.launch { check(c) } } },
      )
    }
  }
  }
}

@Composable
fun HeldCallCard(call: HeldCall, state: AppState, onAnswer: suspend (HeldCall, Answer) -> Unit) {
  val taf = state.holder(call.tafId)
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
        Text(taf?.name ?: Words.NeedsYou.aTaf, style = scaled(14, FontWeight.SemiBold, Theme.ink), modifier = Modifier.weight(1f))
        Text(remaining(call, now), style = scaled(12, color = Theme.amberText, mono = true))
      }
      call.proposed.amount?.let { Text(it.formatted, style = scaled(44, FontWeight.Bold, Theme.ink, display = true)) }
      Text(call.proposed.summary, style = scaled(16, FontWeight.SemiBold, Theme.ink))
      if (call.proposed.context.isNotEmpty()) Text(call.proposed.context, style = scaled(14, color = Theme.soft))
      // KIFF's reason, as a badge; none where there's none to say (Coach's install).
      if (call.reason.isNotBlank()) Text(
        call.reason,
        style = scaled(12, FontWeight.Medium, Theme.amberText),
        modifier = Modifier.clip(CircleShape).background(Theme.amber.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 5.dp),
      )
    }
    // A step in the owner's Chrome: the site large, the origin small, planque's action words, what the page showed, a checkout's
    // lines, and which Chrome. Under KIFF's amount and reason, never in their place (docs/chrome.md, "The phone approval card").
    val fill = call.browser?.takeIf { bot.tafs.app.kiff.SealedRelease.ENABLED && it.fill != null }
    val fillRefused = fill?.let { LocalFillRefusal.current?.invoke(call) }
    // A sealed fill while the card is off: drawn as any step, and only Refuse, since it can't be allowed without its release.
    val fillOff = fill == null && call.browser?.fill != null
    if (fill != null) SealedFillBlock(fill, fillRefused) else call.browser?.let { BrowserStepBlock(it) }
    if (fillOff) Text(Words.Chrome.releaseUnreadable, style = scaled(13, color = Theme.soft))
    // The taf's line on why, under its own heading: the taf's words, from text a customer wrote, drawn as plain text and never in
    // place of KIFF's amount, rule or action above.
    if (call.tafAbout != null || call.tafSays != null) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow(Words.NeedsYou.tafSays)
        call.tafAbout?.let { Text(it, style = scaled(14, FontWeight.SemiBold, Theme.ink)) }
        call.tafSays?.let { Text(it, style = scaled(14, color = Theme.ink)) }
      }
    }
    // What it's on, as KIFF keeps the call (kiff/tafs#102), under Details: ids and a vendor's own values aren't
    // words for the card's face (kiff/tafs#236, item 7).
    call.details?.takeIf { it.isNotEmpty() }?.let { details -> Details(details) }

    val lasting = state.unanswerable[call.id]
    if (expired) {
      Text(Words.NeedsYou.expiredLine, style = scaled(14, color = Theme.soft))
    } else if (lasting != null && !AnswerRefusals.refusesOnly(lasting)) {
      // KIFF says this person may never answer it: words, not buttons that can't work.
      Text(AnswerRefusals.refusalWords(lasting) ?: Words.NeedsYou.answerNotTaken, style = scaled(14, color = Theme.soft))
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Said here, where the owner answers, so the taf's words needn't say it.
        Text(NOTHING_SENT, style = scaled(14, color = Theme.soft))
        // They asked for it: someone else approves, and they can still refuse it.
        lasting?.let { code -> AnswerRefusals.refusalWords(code)?.let { Text(it, style = scaled(14, color = Theme.soft)) } }
        // No approval key KIFF holds: it says so before a tap, and only Refuse is offered.
        val keyLost = state.mode == Mode.kiff && state.approvalKeyLost
        if (keyLost) ApprovalKeyLine()
        Answers(
          call, state, refuseOnly = lasting != null || keyLost || fillRefused != null || fillOff, sending = sending, send = ::send,
          install = if (installs != null) ::install else null, fill = fill != null,
        )
        // A third answer: change the Card so calls like this go on their own. Shown by the app's own rule, from the call as KIFF
        // sent it; the offer is made on the tap, never to draw the button.
        LocalAllowHold.current?.takeIf { lasting == null && !keyLost && state.offersAllow(call) }?.let { allow ->
          OutlineButton(Words.NeedsYou.allowFromNowOn, { allow(call) }, Modifier.fillMaxWidth(), enabled = !sending)
        }
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
private fun Answers(call: HeldCall, state: AppState, refuseOnly: Boolean, sending: Boolean, send: (Answer) -> Unit, install: (() -> Unit)?, fill: Boolean = false) {
  val biometry = LocalBiometry.current
  // Changing a limit is changing the taf's Card: offered only to whoever may use Coach and manages the taf, as on the iPad.
  val improveInCoach = LocalImproveInCoach.current?.takeIf { state.mayAskCoachAbout(call.tafId) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (!state.mayAnswerHolds) {
      // A viewer uses the taf but doesn't answer for the account.
      Text(Words.NeedsYou.someoneAnswers, style = scaled(13, color = Theme.soft))
    } else if (state.canAnswer(call)) {
      if (refuseOnly) {
        // KIFF takes this person's refusal but not their approval (they asked for the call): Refuse and the next
        // steps stay, approving and extra room go. Over a total, the limit itself can still be changed with Coach.
        if (!call.approvableOnce) improveInCoach?.let { improve ->
          OutlineButton(Words.NeedsYou.changeLimit, { improve(call.tafId) }, Modifier.fillMaxWidth(), enabled = !sending)
        }
      } else if (call.install != null) {
        // Installing is approving: the version goes live, then Coach hears. Not offered where it can't be done.
        if (install != null) ApprovalGate { PrimaryButton(if (sending) Words.Version.installing else Words.Version.installWith(biometry), install, enabled = !sending) }
      } else if (call.approvableOnce) {
        // Back after extra room: the first step went through, and this is the second, said plainly with KIFF's reason.
        if (call.kiffId != null && call.kiffId in state.roomGiven) {
          Text(SecondStep.words(call.reason), style = scaled(13, color = Theme.amberText))
        }
        // A sealed fill's Allow: the release's fingerprint, then the answer's, each prompt naming what it signs.
        ApprovalGate {
          PrimaryButton(
            if (fill) Words.Chrome.releaseAllow else Words.NeedsYou.approveOnceWith(biometry), { send(Answer.ApproveOnce) },
            if (fill) Modifier.confirms() else Modifier, enabled = !sending,
          )
        }
      } else {
        // Over a total the Card keeps across calls: approving once can't pass it (kiff/tafs#138, item 49). Extra room
        // can, for this call, until that total resets (kiff/tafs#211, 6.13); a lasting change is Coach's.
        Text(
          if (state.canManageCards) {
            Words.NeedsYou.overTotalManager
          } else {
            Words.NeedsYou.overTotal
          },
          style = scaled(13, color = Theme.soft),
        )
        // How much of its totals is from calls the tool refused, in KIFF's words: they count until KIFF confirms nothing moved.
        state.taf(call.tafId)?.let(::pendingLines)?.forEach { Text(it, style = scaled(13, color = Theme.soft)) }
        if (state.canManageCards) ApprovalGate { PrimaryButton(Words.NeedsYou.extraRoomWith(biometry), { send(Answer.ExtraRoom) }, enabled = !sending) }
        // A lasting change is Coach's: a new conversation about this taf (kiff/tafs#107).
        improveInCoach?.let { improve ->
          OutlineButton(Words.NeedsYou.changeLimit, { improve(call.tafId) }, Modifier.fillMaxWidth(), enabled = !sending)
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        call.nextSteps.forEach { step -> OutlineButton(step, { send(Answer.NextStep(step)) }, Modifier.weight(1f).confirms(), enabled = !sending) }
        OutlineButton(Words.NeedsYou.refuse, { send(Answer.Refuse) }, Modifier.weight(1f).confirms(), color = Theme.no, enabled = !sending)
      }
    } else {
      Text(
        Words.NeedsYou.answerInKiffUntil,
        style = scaled(13, color = Theme.soft),
      )
      LocalAnswerPage.current(call)?.let { page ->
        val uri = LocalUriHandler.current
        PrimaryButton(Words.NeedsYou.answerInKiffButton, { uri.openUri(page) })
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
      if (open) Words.NeedsYou.detailsOpen else Words.NeedsYou.detailsClosed,
      style = scaled(13, FontWeight.SemiBold, Theme.blue),
      modifier = Modifier.tapping { open = !open }.padding(vertical = 4.dp),
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
 * KIFF's words for how much of a taf's totals is from calls the tool refused, each once, from its live and active Cards only: a
 * test Card's totals aren't what the taf may do for real. None for none.
 */
fun pendingLines(taf: bot.tafs.app.model.Taf): List<String> =
  taf.shownCards.filter(::countsForReal).flatMap(::pendingOf).distinct()

/** A Card whose totals are what the taf may do for real: live, and active. */
fun countsForReal(card: bot.tafs.app.model.TafCard): Boolean = card.live && card.active

/** A Card's own pending lines, its money total's and its count's. */
fun pendingOf(card: bot.tafs.app.model.TafCard): List<String> = listOfNotNull(card.total?.pending, card.calls?.pending)

private fun remaining(call: HeldCall, now: Instant): String {
  val seconds = Duration.between(now, call.expiresAt).seconds
  if (seconds < 1) return Words.NeedsYou.expired
  return Words.NeedsYou.left("%d:%02d".format(seconds / 60, seconds % 60))
}

/** The line where an approval can't be given here: a fingerprint to add, KIFF's view-only session, or the approval key renewed. */
internal fun viewOnlyLine(viewOnly: Boolean, noApprovalKeyHere: Boolean): String = when {
  noApprovalKeyHere -> Words.Approval.noFingerprint
  viewOnly -> Words.Approval.viewOnly
  else -> bot.tafs.app.signing.ApprovalKeyRenewed.WORDS
}

/** A held browser step's page, as the card draws it: nothing here decides, every word is planque's or KIFF's. */
@Composable
internal fun BrowserStepBlock(step: bot.tafs.app.model.BrowserStep) {
  // As iOS's BrowserStepBlock (docs/chrome.md, "The approval card"): the site large, its exact origin small, which Chrome; then
  // what the page showed, its own label for the element first; then the basket. The card's summary stays KIFF's, never the label.
  Column(Modifier.fillMaxWidth().testTag(BROWSER_STEP), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(step.site, style = scaled(20, FontWeight.Bold, Theme.ink))
      if (step.origin.isNotEmpty()) Text(step.origin, style = scaled(12, color = Theme.soft, mono = true))
      step.chrome?.let { Text(Words.Chrome.heldIn(name = it), style = scaled(13, color = Theme.soft)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(Words.Chrome.heldPage, style = scaled(12, FontWeight.SemiBold, Theme.soft))
      Text(step.action, style = scaled(14, FontWeight.SemiBold, Theme.ink), modifier = Modifier.testTag(BROWSER_STEP_ACTION))
      step.pageTitle?.let { Text(it, style = scaled(14, FontWeight.SemiBold, Theme.ink)) }
      step.excerpt?.let { Text(it, style = scaled(13, color = Theme.ink)) }
    }
    if (step.lines.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(Words.Chrome.heldBasket, style = scaled(12, FontWeight.SemiBold, Theme.soft))
      step.lines.forEach { Text(it, style = scaled(13, color = Theme.ink)) }
    }
  }
}

/**
 * A sealed fill's card (docs/chrome.md, "The phone's release card"), in the design's order: what it asks, the site large and the
 * origin small, which Chrome, which part (read from the handle, never planque's words), the field's label and the page's title as
 * page words, and the fixed line that the value never leaves Chrome. [refused]: the phone's own checks won't sign it, said here.
 */
@Composable
internal fun SealedFillBlock(step: bot.tafs.app.model.BrowserStep, refused: bot.tafs.app.kiff.SealedRelease.Refusal?) {
  val fill = step.fill ?: return
  val part = bot.tafs.app.kiff.SealedRelease.part(fill.sealed)
  Column(Modifier.fillMaxWidth().testTag(SEALED_FILL), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(Words.Chrome.releaseTitle, style = scaled(16, FontWeight.SemiBold, Theme.ink))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(step.site, style = scaled(20, FontWeight.Bold, Theme.ink))
      if (step.origin.isNotEmpty()) Text(step.origin, style = scaled(12, color = Theme.soft, mono = true))
      step.chrome?.let { Text(Words.Chrome.heldIn(name = it), style = scaled(13, color = Theme.soft)) }
    }
    // An unreadable handle names no part: nothing is said about one, and the line below says why it can't be allowed.
    part?.let {
      Text(
        if (it == bot.tafs.app.kiff.SealedRelease.Part.PASSWORD) Words.Chrome.releasePassword else Words.Chrome.releaseUsername,
        style = scaled(15, FontWeight.SemiBold, Theme.ink), modifier = Modifier.testTag(SEALED_FILL_PART),
      )
    }
    if (step.fieldLabel != null || step.pageTitle != null) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(Words.Chrome.heldPage, style = scaled(12, FontWeight.SemiBold, Theme.soft))
      step.fieldLabel?.let { Text(it, style = scaled(14, color = Theme.ink)) }
      step.pageTitle?.let { Text(it, style = scaled(14, color = Theme.ink)) }
    }
    Text(Words.Chrome.releaseNeverLeaves, style = scaled(13, color = Theme.soft))
    refused?.let(bot.tafs.app.kiff.SealedRelease::refusalWords)?.let {
      Text(it, style = scaled(13, color = Theme.no), modifier = Modifier.testTag(SEALED_FILL_REFUSED))
    }
  }
}

const val SEALED_FILL = "needs-you.sealed-fill"
const val SEALED_FILL_PART = "needs-you.sealed-fill.part"
const val SEALED_FILL_REFUSED = "needs-you.sealed-fill.refused"
const val BROWSER_STEP_ACTION = "needs-you.browser-step.action"
const val BROWSER_STEP = "needs-you.browser-step"
