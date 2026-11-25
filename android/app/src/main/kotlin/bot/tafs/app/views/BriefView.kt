package bot.tafs.app.views

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.BriefRoutes
import bot.tafs.app.app.Mode
import bot.tafs.app.design.Dot
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.ScreenTitle
import bot.tafs.app.design.TafAvatar
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.design.tapping
import bot.tafs.app.kiff.Brief
import bot.tafs.app.kiff.BriefWords
import bot.tafs.app.kiff.HomeLabelMission
import bot.tafs.app.kiff.MissionLogPage
import bot.tafs.app.model.Answer
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.Taf
import bot.tafs.app.model.Words
import kotlinx.coroutines.launch
import bot.tafs.app.design.PageWidth
import bot.tafs.app.design.pageColumn

/**
 * "Your brief" (Missions design v2.1 §7, docs/android-app.md, Your brief): decisions first, each with Coach's recommendation and what
 * happens meanwhile, then one next move, then what got done, case by case. Two columns on a wide screen, decisions beside Done. Every
 * word, count and order is tafs cloud's; a recommendation is shown, never acted on, and nothing is signed from here.
 */
class BriefActions(
  val filter: suspend (String?) -> Unit,
  /** Opens where the decision is answered ([BriefRoutes.destination]); never answers it. */
  val answer: (Brief.Decision, Brief.Answer) -> Unit,
  val later: suspend (Brief.Decision) -> Boolean,
  val startNextMove: (Brief.NextMove) -> Unit,
  val dismissNextMove: () -> Unit,
  val openNeedsYou: () -> Unit,
  val close: () -> Unit,
  /** A page of a mission's log ([MissionLogView]). */
  val missionLog: suspend (String, String?) -> MissionLogPage?,
  /** Ask Coach about a mission, from its log; null where Coach isn't there. */
  val askCoach: ((String) -> Unit)?,
  val refresh: suspend () -> Unit,
)

/**
 * The Needs you tab: the brief once there is one, else Needs you, unchanged ([needsYou]). What an answer opened shows over the brief:
 * a held call on its own card ([HeldCallCard], answered as in Needs you), a step, or Needs you's list.
 */
@Composable
fun BriefTab(state: AppState, actions: BriefActions, onAnswerHold: suspend (HeldCall, Answer) -> Unit, needsYou: @Composable () -> Unit) {
  val brief = state.brief
  if (brief == null) {
    needsYou()
    return
  }
  // A mission's log, open from Done's Evidence: its id and its name.
  var log by rememberSaveable { mutableStateOf<List<String>?>(null) }
  val open = state.briefOpen
  val shownLog = log
  when {
    open != null -> {
      BackHandler { actions.close() }
      BriefOpened(open, state, brief.words, actions, onAnswerHold, needsYou)
    }
    shownLog != null -> {
      BackHandler { log = null }
      // The mission's own log, as from its row on the tafs list: by the log id Done carries, under the mission's name.
      MissionLogView(HomeLabelMission(id = shownLog[0], name = shownLog[1]), load = actions.missionLog, onBack = { log = null }, askCoach = actions.askCoach)
    }
    else -> BriefScreen(state, brief, actions) { done -> done.log?.let { log = listOf(it, done.mission.orEmpty()) } }
  }
}

/** Back to the brief, by its name. */
@Composable
private fun BackToBrief(words: BriefWords, onBack: () -> Unit) {
  Text(
    "‹ " + words.titleShown,
    style = scaled(15, FontWeight.SemiBold, Theme.amberText),
    modifier = Modifier.statusBarsPadding().heightIn(min = 44.dp).tapping(onClick = onBack).padding(horizontal = 16.dp, vertical = 12.dp)
      .testTag("brief.back"),
  )
}

@Composable
private fun BriefOpened(
  open: String,
  state: AppState,
  words: BriefWords,
  actions: BriefActions,
  onAnswerHold: suspend (HeldCall, Answer) -> Unit,
  needsYou: @Composable () -> Unit,
) {
  val call = BriefRoutes.holdOf(open)?.let { id -> state.held.firstOrNull { it.kiffId == id } }
  val step = BriefRoutes.stepOf(open)?.let { id -> state.yourSteps.firstOrNull { it.handoff == id } }
  // Once the card it opened is answered and gone, back to the brief; one never found shows Needs you's list instead.
  var seen by remember(open) { mutableStateOf(false) }
  val found = call != null || step != null
  LaunchedEffect(open, found) { if (found) seen = true else if (seen) actions.close() }
  Column(Modifier.fillMaxSize()) {
    BackToBrief(words, actions.close)
    Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
      when {
        call != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp).pageColumn(PageWidth.Reading)) {
          // The held call's own card, as Needs you draws it: no answer chosen, its own answer flow and fingerprint.
          HeldCallCard(call, state, onAnswerHold)
        }
        step != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp).pageColumn(PageWidth.Reading)) {
          YourStepPanel(step, state)
        }
        else -> needsYou()
      }
    }
  }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BriefScreen(state: AppState, brief: Brief, actions: BriefActions, onOpenLog: (Brief.Done) -> Unit) {
  val scope = rememberCoroutineScope()
  var refreshing by remember { mutableStateOf(false) }
  androidx.compose.material3.pulltorefresh.PullToRefreshBox(
    isRefreshing = refreshing,
    onRefresh = { scope.launch { refreshing = true; try { actions.refresh() } finally { refreshing = false } } },
    modifier = Modifier.fillMaxSize().testTag("brief"),
  ) {
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp).pageColumn(PageWidth.Brief),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle(brief.words.titleShown)
        if (brief.words.`when`.isNotEmpty()) Text(brief.words.`when`, style = scaled(13, color = Theme.soft))
        // All, two labels, then "+N": the tafs list's chips, filtering the brief as tafs cloud filters it; a chosen one tapped again is All.
        LabelRow(brief.labels, brief.words.all.ifEmpty { allWords(state.labelsAll) }, null, state.briefLabel) { id ->
          scope.launch { actions.filter(if (id == state.briefLabel) null else id) }
        }
      }
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= WIDE_BRIEF) {
          Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
              Decisions(state, brief, actions)
              CoachWaiting(brief, actions)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
              DoneCases(brief, onOpenLog)
              // Under Done, so it stays in view beside the decisions.
              NextMove(state, brief, actions)
              Rest(state, brief, actions)
            }
          }
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Decisions(state, brief, actions)
            CoachWaiting(brief, actions)
            NextMove(state, brief, actions)
            DoneCases(brief, onOpenLog)
            Rest(state, brief, actions)
          }
        }
      }
      brief.credits?.let { Text(it, style = scaled(12, color = Theme.dim), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    }
  }
}

/** Decisions beside Done from this width. */
private val WIDE_BRIEF = 600.dp

@Composable
private fun ColumnScope.Decisions(state: AppState, brief: Brief, actions: BriefActions) {
  val open = state.briefDecisions
  // Nothing to decide: the owner's board 3, where the decisions would be; Done and the rest say the brief's own words.
  if (open.isEmpty()) {
    bot.tafs.app.design.Illustrated { bot.tafs.app.design.BriefClearArt(it) }
    return
  }
  Eyebrow(brief.words.decisionsShown)
  open.forEach { decision -> DecisionCard(decision, brief.words, state, actions) }
  brief.more?.let { Text(it.words, style = scaled(13, color = Theme.soft)) }
}

/** The taf a decision is about: by tafs cloud's id, or by name in the sample. */
private fun tafOf(decision: Brief.Decision, state: AppState): Taf? =
  state.tafs.firstOrNull { it.cloudId != null && it.cloudId == decision.ref.taf }
    ?: if (state.mode == Mode.sample) state.tafs.firstOrNull { it.name == decision.ref.taf } else null

/**
 * One decision: its taf, the question, Coach's recommendation with its why, what happens meanwhile, why it can't wait when it can't,
 * and its answers. The recommended answer is filled; the others, a decline in red, and Later beside them. Nothing is decided here.
 */
@Composable
private fun DecisionCard(decision: Brief.Decision, words: BriefWords, state: AppState, actions: BriefActions) {
  val scope = rememberCoroutineScope()
  var puttingOff by remember(decision.id) { mutableStateOf(false) }
  Panel(Modifier.testTag("brief.decision." + decision.id), stroke = if (decision.urgent) Theme.no.copy(alpha = 0.5f) else Theme.line) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      val taf = tafOf(decision, state)
      if (taf != null) {
        TafAvatar(taf, 26.dp)
        Text(taf.name, style = scaled(14, FontWeight.SemiBold, Theme.ink))
      }
      Box(Modifier.weight(1f))
      if (decision.urgent) {
        // tafs cloud's word for it ("Urgent").
        Text(
          words.urgentShown,
          style = scaled(11, FontWeight.SemiBold, Theme.panel),
          modifier = Modifier.clip(CircleShape).background(Theme.no).padding(horizontal = 8.dp, vertical = 4.dp).testTag("brief.urgent"),
        )
      } else {
        decision.deadline?.let { Text(it, style = scaled(12, color = Theme.amberText, mono = true)) }
      }
    }
    Text(decision.question, style = scaled(17, FontWeight.SemiBold, Theme.ink))
    val pick = decision.recommended
    if (pick != null && decision.why != null) {
      Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Theme.blue.copy(alpha = 0.08f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(words.recommendsShown + ": " + pick.words, style = scaled(13, FontWeight.SemiBold, Theme.blue))
        Text(decision.why, style = scaled(14, color = Theme.ink))
      }
    }
    if (decision.meanwhile.isNotEmpty()) {
      Text(
        buildAnnotatedString {
          withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(words.meanwhileShown) }
          append(" ")
          append(decision.meanwhile)
        },
        style = scaled(14, color = Theme.ink),
      )
    }
    decision.cantWait?.let {
      Text(
        it,
        style = scaled(13, color = Theme.no),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Theme.no.copy(alpha = 0.08f)).padding(10.dp),
      )
    }
    // The answers: each opens where it's answered (a held call on its own card, with no answer chosen).
    if (pick != null) PrimaryButton(pick.words, { actions.answer(decision, pick) }, Modifier.testTag("brief.answer." + pick.answer))
    val others = decision.answers.filter { it != pick }
    // An urgent decision can't be put off: no Later, whatever was sent.
    val later = decision.laterShown
    if (others.isNotEmpty() || later != null) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        others.forEach { a ->
          OutlineButton(a.words, { actions.answer(decision, a) }, Modifier.weight(1f).testTag("brief.answer." + a.answer), color = if (a.decline) Theme.no else Theme.ink)
        }
        if (later != null) {
          OutlineButton(
            later,
            {
              if (!puttingOff) {
                puttingOff = true
                scope.launch { try { actions.later(decision) } finally { puttingOff = false } }
              }
            },
            Modifier.weight(1f).testTag("brief.later"),
            color = Theme.soft,
            enabled = !puttingOff,
          )
        }
      }
    }
    decision.note?.let { Text(it, style = scaled(12, color = Theme.dim)) }
  }
}

@Composable
private fun NextMove(state: AppState, brief: Brief, actions: BriefActions) {
  val move = brief.nextMove ?: return
  if (state.nextMoveDismissed) return
  Panel(Modifier.testTag("brief.next-move"), stroke = Theme.amber.copy(alpha = 0.35f)) {
    Eyebrow(brief.words.nextMoveShown, color = Theme.amberText)
    Text(move.words, style = scaled(15, FontWeight.SemiBold, Theme.ink))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      OutlineButton(move.start, { actions.startNextMove(move) }, Modifier.weight(1f), color = Theme.amberText)
      OutlineButton(move.dismiss, actions.dismissNextMove, Modifier.weight(1f))
    }
  }
}

@Composable
private fun ColumnScope.DoneCases(brief: Brief, onOpenLog: (Brief.Done) -> Unit) {
  if (brief.done.isEmpty()) return
  Eyebrow(brief.words.doneShown)
  Panel(Modifier.testTag("brief.done")) {
    brief.done.forEachIndexed { i, group ->
      if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.line))
      group.cases.forEach { CaseRow(it) }
      if (group.value != null || group.log != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          group.value?.let { Text(it, style = scaled(15, FontWeight.SemiBold, Theme.ink)) }
          Box(Modifier.weight(1f))
          group.log?.let { mission ->
            Text(
              brief.words.evidenceShown + " ›",
              style = scaled(13, FontWeight.SemiBold, Theme.blue),
              modifier = Modifier.heightIn(min = 44.dp).tapping { onOpenLog(group) }.padding(vertical = 12.dp).testTag("brief.evidence.$mission"),
            )
          }
        }
      }
    }
  }
}

/** A case's line, as tafs cloud says it: green once the source says it's closed, amber while it waits on someone. */
@Composable
private fun CaseRow(item: Brief.Case) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
    Dot(if (item.closed) Theme.ok else if (item.waiting) Theme.amber else Theme.dim, 7.dp, Modifier.padding(top = 6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      if (item.name.isNotEmpty()) Text(item.name, style = scaled(14, FontWeight.SemiBold, Theme.ink))
      if (item.words.isNotEmpty()) Text(item.words, style = scaled(13, color = if (item.closed) Theme.ok else Theme.soft))
    }
  }
}

/**
 * Coach's chats that wait on the person's answer, in tafs cloud's words, under the decisions: Needs you's `coach` part, so the
 * brief accounts for the badge. It opens Needs you, where each waits with Open and Set aside. Only when tafs cloud sends it.
 */
@Composable
private fun CoachWaiting(brief: Brief, actions: BriefActions) {
  val waiting = brief.coachWaiting ?: return
  Panel(Modifier.tapping(onClick = actions.openNeedsYou).testTag("brief.coach_waiting")) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(waiting.words, style = scaled(14, color = Theme.ink), modifier = Modifier.weight(1f))
      Text("›", style = scaled(18, FontWeight.SemiBold, Theme.dim))
    }
  }
}

/**
 * The things that need nothing now, in one line, opening today's Needs you list; with Needs you's own count whenever that list
 * holds more than the brief's decisions, even when tafs cloud sent no line for it.
 */
@Composable
private fun Rest(state: AppState, brief: Brief, actions: BriefActions) {
  val count = state.briefNeedsYouLine
  val rest = brief.rest
  if (rest == null && count == null) return
  Panel(Modifier.tapping(onClick = actions.openNeedsYou).testTag("brief.rest")) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rest?.let { Text(it.words, style = scaled(14, color = Theme.ink)) }
        count?.let { Text(it, style = scaled(13, FontWeight.SemiBold, if (rest == null) Theme.ink else Theme.dim)) }
      }
      Text("›", style = scaled(18, FontWeight.SemiBold, Theme.dim))
    }
  }
}
