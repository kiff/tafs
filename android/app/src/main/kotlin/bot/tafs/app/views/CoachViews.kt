package bot.tafs.app.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
 * chat, under a compact header: back, Coach, and a menu with its conversations; under it, the plan in one line, which folds away
 * while the owner scrolls down the thread and comes back on the way up, so a card has the screen. Before its first message,
 * Coach is started once per account.
 */
@Composable
fun CoachScreen(state: AppState, actions: TafActions, loadCoach: suspend () -> Unit, onBack: () -> Unit, chats: CoachChatActions = CoachChatActions()) {
  LaunchedEffect(Unit) { loadCoach() }
  // Folded while the thread scrolls toward its end, shown again as soon as it scrolls back.
  var folded by remember { mutableStateOf(false) }
  val folding = remember {
    object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
      override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
        if (available.y < -4f) folded = true else if (available.y > 4f) folded = false
        return androidx.compose.ui.geometry.Offset.Zero
      }
    }
  }
  Column(Modifier.fillMaxSize().statusBarsPadding()) {
    val started = state.mayUseCoach && state.coachChecked && state.coachCloudId != null
    // New conversation always does something: on a conversation with nothing in it yet, it stays there and shows its top.
    val newChat = { if (CoachWords.isEmptyChat(state)) folded = false else chats.newChat() }
    if (started) CoachHeader(state, chats, newChat, onBack) else BackBar(onBack)
    when {
      !state.mayUseCoach -> Note(CoachWords.NOT_ALLOWED)
      !state.coachChecked -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp, color = Theme.dim)
      }
      state.coachCloudId == null -> CoachIntro(state, actions.coach.start)
      else -> {
        androidx.compose.animation.AnimatedVisibility(visible = !folded) {
          Column {
            CoachToolsPanel(state, chats)
            CoachWords.plan(state.coachComponents)?.let { PlanPanel(it.component, Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) }
          }
        }
        TafChatView(COACH_CHAT, state, actions, Modifier.weight(1f).nestedScroll(folding))
      }
    }
  }
}

/** Coach's header, one row: back, its name, and a menu with a new conversation and the earlier ones. */
@Composable
private fun CoachHeader(state: AppState, chats: CoachChatActions, onNewChat: () -> Unit, onBack: () -> Unit) {
  var menu by remember { mutableStateOf(false) }
  var listing by remember { mutableStateOf(false) }
  Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(
      "‹",
      style = scaled(26, color = Theme.amber),
      modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(onClickLabel = "Back", onClick = onBack).padding(start = 10.dp, top = 4.dp),
    )
    Text("Coach", style = scaled(18, FontWeight.SemiBold, Theme.ink), modifier = Modifier.weight(1f))
    Box {
      Text(
        "⋯",
        style = scaled(22, FontWeight.Bold, Theme.amberText),
        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(onClickLabel = "Coach's conversations") { menu = true }.padding(top = 6.dp, start = 12.dp),
      )
      androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Theme.panel) {
        // Never greyed out, as in other chat apps: an empty conversation is kept, any other gives way to a new one.
        androidx.compose.material3.DropdownMenuItem(
          text = { Text("New conversation", style = scaled(15, color = Theme.ink)) },
          onClick = { menu = false; onNewChat() },
        )
        androidx.compose.material3.DropdownMenuItem(
          text = { Text("Previous conversations", style = scaled(15, color = Theme.ink)) },
          onClick = { menu = false; listing = true },
        )
      }
    }
  }
  if (listing) PreviousConversations(state, chats) { listing = false }
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
 * The plan, pinned above the thread in one line, tafs cloud's ("Step 3 of 6 · Your rules"); tapped, it opens to the steps, each
 * pending, active, done, failed or skipped. The phone never counts the steps: the line says where the plan is.
 */
@Composable
fun PlanPanel(plan: SetupComponent, modifier: Modifier = Modifier) {
  var open by remember { mutableStateOf(false) }
  val steps = plan.steps.orEmpty()
  Column(modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(onClickLabel = if (open) "Fold the plan" else "Show the whole plan") { open = !open },
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(CoachWords.planLine(plan), style = scaled(13, FontWeight.SemiBold, Theme.soft), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      Text(if (open) "⌃" else "⌄", style = scaled(13, FontWeight.Bold, Theme.dim))
    }
    if (open) {
      Panel {
        steps.forEach { step ->
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepMark(step.status)
            Text(step.label, style = scaled(14, if (step.status == "active") FontWeight.SemiBold else FontWeight.Normal, if (step.status == "pending" || step.status == "skipped") Theme.dim else Theme.ink))
          }
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
  val approve: (SetupComponent, String?, String?) -> Unit,
  /** The owner gave up waiting on the vendor's sign-in page. */
  val cancelSignIn: () -> Unit,
  /** Starts Coach on this account, behind one confirmation. */
  val start: suspend () -> Unit = {},
  /** Signs in again at the vendor, for a sign-in card: a new approval, one confirmation. */
  val signInAgain: (SetupComponent) -> Unit = {},
  /** A new conversation about improving one taf, by its id: Coach starts from what it did and what its Card allows. */
  val improve: (String) -> Unit = {},
  /** Tries again to make the version an improvement names. */
  /** Opens a taf, by its KIFF agent; and a taf's versions. */
  val openTaf: (String) -> Unit = {},
  val openVersions: (String) -> Unit = {},
)

/** One of Coach's cards in its thread. What grants authority is read from planque and KIFF, and signed there. */
@Composable
fun CoachCard(shown: ShownComponent, state: AppState, actions: CoachActions) {
  val c = shown.component
  // Answered once the thread says so, in kiff/agents' words; on its way while tafs cloud takes it. By this conversation's card,
  // never another's that had the same id.
  val key = shown.ref
  val answered = shown.answer?.let(CoachWords::answered)
    ?: if (key in state.cardsAnswering) CoachWords.ANSWERING else if (key in state.cardsTaken) CoachWords.TAKEN else null
  val error = state.cardErrors[key]
  when (c.type) {
    "notice" -> NoticeCard(c)
    "confirm" -> ConfirmCard(c, answered, error, actions.answer)
    "questions" -> QuestionsCard(c, answered, error, actions.answer)
    "approval" -> ApprovalCard(c, state, answered, error, actions)
    "sign_in" -> SignInCard(c, state, answered, error, actions)
    "result" -> if (c.taf?.improves != null) ImprovementCard(shown, state, actions) else HandOverCard(shown, state, actions)
  }
}

/**
 * A connection or a Card for the owner to approve, with planque's own words and KIFF's draft: nothing here is Coach's. The owner
 * reads it, and the one confirmation signs exactly what is shown. Coach's own description of it is never what is approved.
 */
@Composable
private fun ApprovalCard(c: SetupComponent, state: AppState, answered: String?, error: String?, actions: CoachActions) {
  val grant = state.grants.firstOrNull { it.id == c.grant }
  val draft = c.draft?.let { state.setupDrafts[it] }
  val busy = state.connecting != null
  var details by remember { mutableStateOf(false) }
  // Answered, it keeps one line saying what it was: several in a row each say what was approved. One line, whatever the words'
  // length: with no title, planque's text can run to paragraphs.
  if (answered != null) {
    Text(CoachWords.answeredApproval(answered, c, grant?.text, draft?.label), style = scaled(14, FontWeight.Medium, Theme.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
    return
  }
  Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
    Eyebrow("Coach asks you to approve", Theme.amberText)
    // A grant its vendor signed out reads as planque says it ("Signed out by Stripe · sign in again"), not as Coach titled it.
    (grant?.takeIf { it.needsReconnect }?.statusWords ?: c.title)?.let { Text(it, style = scaled(15, FontWeight.SemiBold, Theme.ink)) }
    when {
      state.approvalKeyLost -> ApprovalKeyLine()
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
              if (state.connecting == grant.id) state.connectStep ?: "Approving…" else if (grant.needsReconnect) "Sign in again" else "Approve with $biometry",
              { actions.approve(c, grant.digest, draft?.version) },
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
    error?.let { Text(it, style = scaled(13, color = Theme.no)) }
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
private fun ConfirmCard(c: SetupComponent, answered: String?, error: String?, onAnswer: (String, String, String) -> Unit) {
  Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
    c.title?.let { Text(it, style = scaled(15, FontWeight.SemiBold, Theme.ink)) }
    c.text?.let { Text(it, style = scaled(14, color = Theme.soft)) }
    val options = c.options.orEmpty()
    error?.let { Text(it, style = scaled(13, color = Theme.no)) }
    if (answered != null) {
      Text(answered, style = scaled(13, FontWeight.Medium, Theme.dim))
    } else {
      options.forEach { option -> OutlineButton(option.label, { onAnswer("confirm", c.id, option.value) }, Modifier.fillMaxWidth()) }
    }
  }
}

/** Several questions at once: every word is Coach's, and the owner may answer any in their own words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionsCard(c: SetupComponent, answered: String?, error: String?, onAnswer: (String, String, String) -> Unit) {
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
      Text(answered, style = scaled(13, FontWeight.Medium, Theme.dim))
      return@Panel
    }
    error?.let { Text(it, style = scaled(13, color = Theme.no)) }
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

/**
 * The vendor's sign-in, for a connection the owner approved and whose sign-in was left: one confirmation opens the vendor's own
 * page. Where it goes is always the app's own words (the card's service), whatever Coach's button says.
 */
@Composable
private fun SignInCard(c: SetupComponent, state: AppState, answered: String?, error: String?, actions: CoachActions) {
  val vendor = c.service ?: "the vendor"
  val grant = state.grants.firstOrNull { it.id == c.grant }
  val again = grant?.needsReconnect == true
  when {
    answered != null -> Text("Sign in at $vendor · $answered", style = scaled(14, FontWeight.Medium, Theme.dim))
    // A sign-in starts with an approval, which this phone can't give until a new sign-in with KIFF.
    state.approvalKeyLost -> Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
      Text("Sign in at $vendor", style = scaled(15, FontWeight.SemiBold, Theme.ink))
      ApprovalKeyLine()
    }
    else -> {
      val busy = state.connecting != null
      val waiting = c.grant != null && state.connecting == c.grant
      Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
        Eyebrow("Needs you · sign in", Theme.amberText)
        Text(grant?.takeIf { again }?.statusWords ?: "Sign in at $vendor", style = scaled(20, FontWeight.Bold, Theme.ink, display = true))
        Text(c.note ?: CoachWords.SIGN_IN_NOTE, style = scaled(14, color = Theme.soft))
        PrimaryButton(
          when {
            waiting -> state.connectStep ?: "Opening the sign-in…"
            again -> "Sign in again"
            else -> c.yes ?: "Open ${c.service ?: "the sign-in"}"
          },
          { actions.signInAgain(c) },
          enabled = !busy && c.grant != null,
        )
        Text("Opens ${c.destination}", style = scaled(12, color = Theme.dim, mono = true))
        if (waiting) {
          Text(CoachWords.WAITING_FOR_VENDOR, style = scaled(12, color = Theme.dim))
          Text("Cancel", style = scaled(13, FontWeight.Medium, Theme.soft), modifier = Modifier.heightIn(min = 36.dp).clickable { actions.cancelSignIn() })
        } else {
          Text(
            c.no ?: "Not now",
            style = scaled(14, color = Theme.dim),
            modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = !busy) { actions.answer("sign_in", c.id, "not now") },
          )
        }
        state.coachNotes[c.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
        error?.let { Text(it, style = scaled(13, color = Theme.no)) }
      }
    }
  }
}

/**
 * Coach's hand-over of a new taf. tafs cloud sets it up and answers the card; the card says what came of it: set up, with Open;
 * why it wasn't, with no Try again, since Coach hands it over again itself; or, until the answer comes, that it's being set up.
 */
@Composable
private fun HandOverCard(shown: ShownComponent, state: AppState, actions: CoachActions) {
  val c = shown.component
  val taf = c.taf ?: return
  // The name the taf got, once listed: tafs cloud gives a taken name the first free "<name> 2", so Coach's may name another taf.
  val id = shown.answer?.outcome?.tafId
  val listed = state.tafs.firstOrNull { it.id == taf.agent || (id != null && it.cloudId == id) }
  val name = listed?.name ?: taf.name
  Panel(stroke = Theme.ok.copy(alpha = 0.45f)) {
    Eyebrow("Set up · $name", Theme.ok)
    c.text?.let { Text(it, style = scaled(15, color = Theme.ink)) }
    // Coach's list of the taf's tools names them by id only: the taf's page has its Card in KIFF's words.
    when (val h = CoachWords.handOver(shown.answer, listed != null)) {
      CoachWords.HandOver.SetUp ->
        if (listed != null) PrimaryButton(if (listed.name != taf.name) "Open $name" else c.yes ?: "See it on $name", { actions.openTaf(listed.id) })
        else Text(CoachWords.settingUp(name), style = scaled(13, color = Theme.dim))
      is CoachWords.HandOver.Failed -> {
        Text(CoachWords.notSetUp(taf.name, h.why), style = scaled(13, color = Theme.no))
        Text(CoachWords.COACH_KNOWS, style = scaled(13, color = Theme.dim))
      }
      CoachWords.HandOver.SettingUp -> Text(CoachWords.settingUp(name), style = scaled(13, color = Theme.dim))
    }
  }
}

/**
 * An improvement's result: the taf's next version, which tafs cloud makes and answers the card with, to try and switch to from
 * its Versions. Nothing goes live. Its button says what it opens: the version, never the taf.
 */
@Composable
private fun ImprovementCard(shown: ShownComponent, state: AppState, actions: CoachActions) {
  val c = shown.component
  val taf = c.taf ?: return
  val improved = state.tafs.firstOrNull { it.cloudId == taf.improves || it.id == taf.improves || it.id == taf.agent }
  Panel(stroke = Theme.ok.copy(alpha = 0.45f)) {
    Eyebrow("New version · ${improved?.name ?: taf.name}", Theme.ok)
    c.text?.let { Text(it, style = scaled(15, color = Theme.ink)) }
    taf.note?.takeIf { it.isNotEmpty() }?.let { Text(it, style = scaled(13, color = Theme.soft)) }
    when (val made = CoachWords.improvement(shown.answer)) {
      is CoachWords.Improvement.Made ->
        if (improved != null) PrimaryButton("See version ${made.version}", { actions.openVersions(improved.id) })
        else Text("Version ${made.version} is made.", style = scaled(13, color = Theme.dim))
      is CoachWords.Improvement.Failed -> {
        Text(CoachWords.notImproved(improved?.name ?: taf.name, made.why), style = scaled(13, color = Theme.no))
        Text(CoachWords.COACH_KNOWS, style = scaled(13, color = Theme.dim))
      }
      CoachWords.Improvement.Preparing -> PrimaryButton("Preparing the new version…", {}, enabled = false)
    }
  }
}
