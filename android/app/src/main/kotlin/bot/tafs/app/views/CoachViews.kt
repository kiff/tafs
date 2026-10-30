package bot.tafs.app.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bot.tafs.app.app.AppState
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.model.CoachAnswer
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import bot.tafs.app.design.Eyebrow
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.PrimaryButton
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.SetupQuestion
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.CoachWords
import bot.tafs.app.model.QuestionAnswers
import bot.tafs.app.model.QuestionSelection

/**
 * Coach: where tafs are made, connected and changed (docs/coach.md). Its conversation is the taf's chat screen on Coach's own
 * chat, with its plan pinned above it and its cards in the thread. Before its first message, Coach is started once per account.
 */
@Composable
fun CoachScreen(state: AppState, actions: TafActions, loadCoach: suspend () -> Unit, onBack: () -> Unit, chats: CoachChatActions = CoachChatActions()) {
  LaunchedEffect(Unit) { loadCoach() }
  Column(Modifier.fillMaxSize().statusBarsPadding()) {
    BackBar(onBack)
    when {
      !state.mayUseCoach -> Note(CoachWords.NOT_ALLOWED)
      !state.coachChecked -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp, color = Theme.dim)
      }
      state.coachCloudId == null -> CoachIntro(state, actions.coach.start)
      else -> {
        Text("Coach", style = scaled(30, FontWeight.Bold, Theme.ink, display = true), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        CoachChatBar(state, chats)
        CoachToolsPanel(state, chats)
        CoachWords.plan(state.coachComponents)?.let { PlanPanel(it.component, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        TafChatView(COACH_CHAT, state, actions, Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun Note(text: String) {
  Panel(Modifier.padding(12.dp)) { Text(text, style = scaled(15, color = Theme.soft)) }
}

/** Before Coach is started on this account: what it is for, and the one confirmation that starts it. */
@Composable
private fun CoachIntro(state: AppState, start: suspend () -> Unit) {
  val scope = rememberCoroutineScope()
  val biometry = LocalBiometry.current
  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("Coach", style = scaled(30, FontWeight.Bold, Theme.ink, display = true))
    Text("${CoachWords.INTRO} Each connection and each Card waits for $biometry.", style = scaled(16, color = Theme.soft))
    (state.coachStartError ?: state.chatErrors[COACH_CHAT])?.let { Text(it, style = scaled(13, color = Theme.no)) }
    PrimaryButton(if (state.startingCoach) "Starting…" else "Start", { scope.launch { start() } }, enabled = !state.startingCoach)
    Text("Starting asks for $biometry once.", style = scaled(12, color = Theme.dim))
  }
}

/**
 * The plan, pinned above the thread: the work ahead, each step pending, active, done, failed or skipped. Folded to the step in
 * progress until tapped, so the chat comes first (as iOS's SetupPlan).
 */
@Composable
fun PlanPanel(plan: SetupComponent, modifier: Modifier = Modifier) {
  var open by remember { mutableStateOf(false) }
  val steps = plan.steps.orEmpty()
  val done = steps.count { it.status == "done" }
  val now = CoachWords.currentStep(steps)
  Panel(modifier) {
    Row(
      Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Fold the plan" else "Show the whole plan") { open = !open },
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (!open && now != null) StepMark(now.status)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow("Plan · $done of ${steps.size}")
        if (!open) {
          Text(now?.label ?: plan.title ?: "Plan", style = scaled(14, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      Text(if (open) "⌃" else "⌄", style = scaled(14, FontWeight.Bold, Theme.dim))
    }
    if (open) {
      steps.forEach { step ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          StepMark(step.status)
          Text(step.label, style = scaled(14, if (step.status == "active") FontWeight.SemiBold else FontWeight.Normal, if (step.status == "pending" || step.status == "skipped") Theme.dim else Theme.ink))
        }
      }
    }
  }
}

@Composable
private fun StepMark(status: String) {
  Text(
    CoachWords.stepMark(status),
    style = scaled(14, FontWeight.SemiBold, when (status) {
      "done" -> Theme.ok
      "failed" -> Theme.no
      "active" -> Theme.amberText
      else -> Theme.dim
    }),
  )
}

/** What Coach's cards ask of the model. */
class CoachActions(
  /** The owner's answer to a card: its type, its id and the answer. */
  val answer: (String, String, String) -> Unit,
  /** Approves a card's connection or Cards, as shown: its grant's digest and its draft's version. */
  val approve: suspend (SetupComponent, String?, String?) -> Unit,
  /** The owner gave up waiting on the vendor's sign-in page. */
  val cancelSignIn: () -> Unit,
  /** Starts Coach on this account, behind one confirmation. */
  val start: suspend () -> Unit = {},
  /** Signs in again at the vendor, for a sign-in card: a new approval, one confirmation. */
  val signInAgain: suspend (SetupComponent) -> Unit = {},
  /** Tries again to start the taf a hand-over names. */
  val startTaf: suspend (SetupComponent) -> Unit = {},
  /** Tries again to make the version an improvement names. */
  val makeVersion: suspend (SetupComponent) -> Unit = {},
  /** Opens a taf, by its KIFF agent; and a taf's versions. */
  val openTaf: (String) -> Unit = {},
  val openVersions: (String) -> Unit = {},
)

/** One of Coach's cards in its thread. What grants authority is read from planque and KIFF, and signed there. */
@Composable
fun CoachCard(shown: ShownComponent, state: AppState, actions: CoachActions) {
  val c = shown.component
  val messages = state.chats[COACH_CHAT].orEmpty()
  val answer = CoachWords.answerTo(c.id, messages)
  when (c.type) {
    "notice" -> NoticeCard(c)
    "confirm" -> ConfirmCard(c, answered = answer?.text, actions.answer)
    "questions" -> QuestionsCard(c, answered = answer?.text, actions.answer)
    "approval" -> ApprovalCard(c, state, answer, actions)
    "sign_in" -> SignInCard(c, state, answer, actions)
    "result" -> if (c.taf?.improves != null) ImprovementCard(c, state, actions) else HandOverCard(c, state, answer, actions)
  }
}

/**
 * A connection or a Card for the owner to approve, with planque's own words and KIFF's draft: nothing here is Coach's. The owner
 * reads it, and the one confirmation signs exactly what is shown. Coach's own description of it is never what is approved.
 */
@Composable
private fun ApprovalCard(c: SetupComponent, state: AppState, answer: CoachAnswer?, actions: CoachActions) {
  val scope = rememberCoroutineScope()
  val grant = state.grants.firstOrNull { it.id == c.grant }
  val draft = c.draft?.let { state.setupDrafts[it] }
  val busy = state.connecting != null
  var details by remember { mutableStateOf(false) }
  Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
    Eyebrow("Coach asks you to approve", Theme.amberText)
    c.title?.let { Text(it, style = scaled(15, FontWeight.SemiBold, Theme.ink)) }
    when {
      answer != null -> Text(CoachWords.approvalResult(answer), style = scaled(13, FontWeight.Medium, if (answer.failed) Theme.no else Theme.dim))
      !state.planqueReady -> Text(CoachWords.NOT_CONNECTABLE, style = scaled(14, color = Theme.soft))
      grant == null -> Text("Reading what it asks for…", style = scaled(14, color = Theme.soft))
      else -> {
        // planque's own words for what it will do, never Coach's.
        Text(grant.text, style = scaled(14, color = Theme.ink))
        Text(grant.vendor, style = scaled(13, color = Theme.dim, mono = true))
        grant.terms?.takeIf { it.isNotEmpty() }?.let { terms ->
          Text(if (details) "▾ Details" else "▸ Details", style = scaled(13, FontWeight.Medium, Theme.dim), modifier = Modifier.heightIn(min = 36.dp).clickable { details = !details })
          if (details) terms.forEach { Text(it, style = scaled(12, color = Theme.soft, mono = true)) }
        }
        if (grant.connected && draft != null && !draft.confirmed) {
          Eyebrow("Its Card")
          DraftCardsView(draft)
        }
        if (!grant.digestMatches) {
          Text(CoachWords.DIGEST_MISMATCH, style = scaled(13, color = Theme.no))
        } else {
          val biometry = LocalBiometry.current
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlineButton(
              if (state.connecting == grant.id) "Approving…" else "Approve with $biometry",
              { scope.launch { actions.approve(c, grant.digest, draft?.version) } },
              Modifier.weight(1f),
              enabled = !busy,
            )
            OutlineButton("Decline", { actions.answer("approval", c.id, "declined") }, Modifier.weight(1f), color = Theme.no, enabled = !busy)
          }
          if (state.connecting == grant.id) {
            Text(CoachWords.WAITING_FOR_VENDOR, style = scaled(12, color = Theme.dim))
            Text("Cancel", style = scaled(13, FontWeight.Medium, Theme.soft), modifier = Modifier.heightIn(min = 36.dp).clickable { actions.cancelSignIn() })
          }
        }
      }
    }
    state.coachNotes[c.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
}

private fun toneColor(tone: String?): Color = when (tone) {
  "success" -> Theme.ok
  "warning" -> Theme.amberText
  "error" -> Theme.no
  else -> Theme.dim
}

@Composable
private fun NoticeCard(c: SetupComponent) {
  Panel(stroke = toneColor(c.tone).copy(alpha = 0.4f)) {
    c.title?.let { Text(it, style = scaled(15, FontWeight.SemiBold, Theme.ink)) }
    c.text?.let { Text(it, style = scaled(14, color = Theme.soft)) }
  }
}

/** A choice between a few answers: once chosen, its buttons go quiet and say what was picked. */
@Composable
private fun ConfirmCard(c: SetupComponent, answered: String?, onAnswer: (String, String, String) -> Unit) {
  Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
    c.title?.let { Text(it, style = scaled(15, FontWeight.SemiBold, Theme.ink)) }
    c.text?.let { Text(it, style = scaled(14, color = Theme.soft)) }
    val options = c.options.orEmpty()
    if (answered != null) {
      Text("You chose: " + (options.firstOrNull { it.value == answered }?.label ?: answered), style = scaled(13, FontWeight.Medium, Theme.dim))
    } else {
      options.forEach { option -> OutlineButton(option.label, { onAnswer("confirm", c.id, option.value) }, Modifier.fillMaxWidth()) }
    }
  }
}

/** Several questions at once: every word is Coach's, and the owner may answer any in their own words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionsCard(c: SetupComponent, answered: String?, onAnswer: (String, String, String) -> Unit) {
  val questions = c.questions.orEmpty()
  var selection by remember(c.id) { mutableStateOf(QuestionSelection()) }
  // "Chat about this": the card folds away, and the owner talks the questions through in the chat instead (docs/coach.md).
  var chatting by remember(c.id) { mutableStateOf(false) }
  if (answered == null && chatting) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("You're talking these questions through with Coach.", style = scaled(13, color = Theme.soft), modifier = Modifier.weight(1f, fill = false))
      Text("Show them again", style = scaled(13, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.heightIn(min = 44.dp).clickable { chatting = false }.padding(top = 12.dp))
    }
    return
  }
  Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
    c.title?.let { Text(it, style = scaled(15, FontWeight.SemiBold, Theme.ink)) }
    c.text?.let { Text(it, style = scaled(14, color = Theme.soft)) }
    if (answered != null) {
      Text(QuestionAnswers.words(answered, questions) ?: answered, style = scaled(13, FontWeight.Medium, Theme.dim))
      return@Panel
    }
    questions.forEach { q ->
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(q.text, style = scaled(15, FontWeight.SemiBold, Theme.ink))
        q.note?.let { Text(it, style = scaled(12, color = Theme.dim)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          q.options.forEach { option ->
            Choice(option.label, selected = option.value in selection.picked[q.id].orEmpty()) { selection = selection.pick(q, option.value) }
          }
          Choice("Something else…", selected = q.id in selection.ownOpen) { selection = selection.toggleOwn(q) }
        }
        q.options.firstOrNull { it.value in selection.picked[q.id].orEmpty() }?.description?.let { Text(it, style = scaled(12, color = Theme.soft)) }
        if (q.id in selection.ownOpen) OwnAnswer(selection.own[q.id].orEmpty()) { selection = selection.write(q, it) }
      }
    }
    PrimaryButton("Send answers", { onAnswer("questions", c.id, selection.message(questions)) }, enabled = selection.complete(questions))
    Text(
      "Chat about this",
      style = scaled(15, FontWeight.SemiBold, Theme.amberText),
      modifier = Modifier.heightIn(min = 44.dp).clickable { chatting = true }.padding(top = 12.dp),
    )
  }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(50)
  Text(
    label,
    style = scaled(14, if (selected) FontWeight.SemiBold else FontWeight.Normal, if (selected) Theme.ink else Theme.soft),
    modifier = Modifier
      .heightIn(min = 44.dp)
      .clip(shape)
      .background(if (selected) Theme.amber.copy(alpha = 0.18f) else Color.Transparent)
      .border(BorderStroke(1.dp, if (selected) Theme.amber else Theme.line), shape)
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 8.dp),
  )
}

@Composable
private fun OwnAnswer(value: String, onValue: (String) -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  BasicTextField(
    value = value,
    onValueChange = onValue,
    textStyle = scaled(15, color = Theme.ink),
    cursorBrush = SolidColor(Theme.amber),
    modifier = Modifier.fillMaxWidth().clip(shape).background(Theme.background).border(BorderStroke(1.dp, Theme.line), shape),
    decorationBox = { field ->
      androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).heightIn(min = 40.dp)) {
        if (value.isEmpty()) Text("In your words", style = scaled(15, color = Theme.dim))
        field()
      }
    },
  )
}

/** Why an answer that didn't go through didn't, as the app wrote it after "failed: ". */
private fun why(answer: CoachAnswer): String? = answer.text.removePrefix("failed").trimStart(':', ' ').ifEmpty { null }

/**
 * The vendor's sign-in, for a connection the owner approved and whose sign-in was left: one confirmation opens the vendor's own
 * page. Where it goes is always the app's own words (the card's service), whatever Coach's button says.
 */
@Composable
private fun SignInCard(c: SetupComponent, state: AppState, answer: CoachAnswer?, actions: CoachActions) {
  val scope = rememberCoroutineScope()
  val vendor = c.service ?: "the vendor"
  when (CoachWords.signInOutcome(answer)) {
    "done" -> Text("✓ Signed in at $vendor", style = scaled(14, FontWeight.Medium, Theme.ok))
    "declined" -> Text("– Not now: no sign-in at $vendor", style = scaled(14, color = Theme.dim))
    else -> {
      val failed = answer?.failed == true
      val busy = state.connecting != null
      val waiting = c.grant != null && state.connecting == c.grant
      Panel(stroke = (if (failed) Theme.no else Theme.amber).copy(alpha = 0.45f)) {
        Eyebrow(if (failed) "Didn't go through · sign in" else "Needs you · sign in", if (failed) Theme.no else Theme.amberText)
        Text("Sign in at $vendor", style = scaled(20, FontWeight.Bold, Theme.ink, display = true))
        if (failed) Text(answer?.let(::why)?.let { "It didn't go through: $it" } ?: "It didn't go through.", style = scaled(14, color = Theme.no))
        Text(c.note ?: CoachWords.SIGN_IN_NOTE, style = scaled(14, color = Theme.soft))
        PrimaryButton(
          when {
            waiting -> "Opening the sign-in…"
            failed -> "Try again"
            else -> c.yes ?: "Open ${c.service ?: "the sign-in"}"
          },
          { scope.launch { actions.signInAgain(c) } },
          enabled = !busy && c.grant != null,
        )
        Text("Opens ${c.destination}", style = scaled(12, color = Theme.dim, mono = true))
        if (waiting) {
          Text(CoachWords.WAITING_FOR_VENDOR, style = scaled(12, color = Theme.dim))
          Text("Cancel", style = scaled(13, FontWeight.Medium, Theme.soft), modifier = Modifier.heightIn(min = 36.dp).clickable { actions.cancelSignIn() })
        } else if (!failed) {
          Text(
            c.no ?: "Not now",
            style = scaled(14, color = Theme.dim),
            modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = !busy) { actions.answer("sign_in", c.id, "not now") },
          )
        }
        state.coachNotes[c.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
      }
    }
  }
}

/**
 * Coach's hand-over of a new taf. The app starts it once by itself, wherever the owner is; the card says how that went: open it,
 * starting, didn't start with Try again, or Coach was told and hands it over again.
 */
@Composable
private fun HandOverCard(c: SetupComponent, state: AppState, answer: CoachAnswer?, actions: CoachActions) {
  val scope = rememberCoroutineScope()
  val taf = c.taf ?: return
  // The name the taf got, once started: tafs cloud gives a taken name the first free "<name> 2", so Coach's may name another taf.
  val started = state.tafs.firstOrNull { it.id == taf.agent }
  val name = started?.name ?: taf.name
  val renamed = started != null && started.name != taf.name
  Panel(stroke = Theme.ok.copy(alpha = 0.45f)) {
    Eyebrow("Set up · $name", Theme.ok)
    c.text?.let { Text(it, style = scaled(15, color = Theme.ink)) }
    c.tools?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = scaled(13, color = Theme.soft)) }
    val error = state.tafStartErrors[c.id]
    when {
      started != null -> PrimaryButton(if (renamed) "Open $name" else c.yes ?: "See it on $name", { actions.openTaf(taf.agent) })
      answer?.failed == true || c.id in state.toldStartFailures -> {
        Text(error ?: "${taf.name} wasn't created: ${answer?.let(::why) ?: "it didn't start"}.", style = scaled(13, color = Theme.no))
        Text(CoachWords.COACH_KNOWS, style = scaled(13, color = Theme.dim))
      }
      error != null -> {
        Text(error, style = scaled(13, color = Theme.no))
        OutlineButton("Try again", { scope.launch { actions.startTaf(c) } }, Modifier.fillMaxWidth(), enabled = c.id !in state.startingTafs)
      }
      taf.job != null -> Text("Starting ${taf.name}…", style = scaled(13, color = Theme.dim))
    }
  }
}

/**
 * An improvement's result: the taf's next version, made once, to try and switch to from its Versions. Nothing goes live. Its button
 * says what it opens: the version, never the taf.
 */
@Composable
private fun ImprovementCard(c: SetupComponent, state: AppState, actions: CoachActions) {
  val scope = rememberCoroutineScope()
  val key = CoachWords.improvementKey(c)
  val taf = c.taf ?: return
  val improved = state.tafs.firstOrNull { it.cloudId == taf.improves || it.id == taf.improves || it.id == taf.agent }
  Panel(stroke = Theme.ok.copy(alpha = 0.45f)) {
    Eyebrow("New version · ${improved?.name ?: taf.name}", Theme.ok)
    c.text?.let { Text(it, style = scaled(15, color = Theme.ink)) }
    taf.note?.takeIf { it.isNotEmpty() }?.let { Text(it, style = scaled(13, color = Theme.soft)) }
    val n = state.improvedVersions[key]
    val error = state.improveErrors[key]
    when {
      n != null && improved != null -> PrimaryButton("See version $n", { actions.openVersions(improved.id) })
      error != null -> {
        Text(error, style = scaled(13, color = Theme.no))
        OutlineButton("Try again", { scope.launch { actions.makeVersion(c) } }, Modifier.fillMaxWidth(), enabled = key !in state.makingVersions)
      }
      else -> PrimaryButton("Preparing the new version…", {}, enabled = false)
    }
  }
}
