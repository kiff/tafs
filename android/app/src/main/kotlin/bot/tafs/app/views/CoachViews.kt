package bot.tafs.app.views

import bot.tafs.app.model.Words
import bot.tafs.app.drive.driveTyped
import bot.tafs.app.drive.DriveMenu
import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.approvalTerms
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
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
  var toolsOpen by remember { mutableStateOf(false) }
  // With the keyboard open, what sits above the conversation steps aside: the box keeps its room.
  val keyboard = LocalKeyboardOpen.current ?: (androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0)
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
    // New conversation always does something: on a plain conversation with nothing in it yet, it stays there and shows its
    // top; on an empty one about a taf, it opens a plain new one.
    val newChat = { if (CoachWords.newChatStays(state)) folded = false else chats.newChat() }
    if (started) CoachHeader(state, chats, newChat, onBack) else BackBar(onBack)
    when {
      !state.mayUseCoach -> Note(CoachWords.NOT_ALLOWED)
      // The sample, for the store's screenshots: what Coach is, as a new account first sees it. Nothing to read from tafs cloud.
      state.mode != bot.tafs.app.app.Mode.kiff -> CoachIntro(state)
      !state.coachChecked -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp, color = Theme.dim)
      }
      // A phone that can't approve yet can't start Coach: it says why, as before.
      state.coachCloudId == null && LocalApprovalReadiness.current != bot.tafs.app.model.ApprovalReadiness.Ready -> CoachIntro(state)
      else -> {
        // Not started on this phone: the conversation shows anyway, with what the person brought (a job's words) in its box,
        // and the first Send starts Coach behind one fingerprint. What Coach is, and that it asks once, stay above it.
        if (state.coachCloudId == null) StartNote(state, chats)
        else androidx.compose.animation.AnimatedVisibility(visible = !folded && !keyboard) {
          Column {
            CoachToolsRow(state) { toolsOpen = true }
            CoachWords.plan(state.coachComponents)?.let { PlanPanel(it.component, Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) }
          }
        }
        TafChatView(COACH_CHAT, state, actions, Modifier.weight(1f).nestedScroll(folding))
      }
    }
  }
  // The tools step: asked at the end of a first Start, and from its row after.
  if (toolsOpen || state.coachToolsAsked) CoachToolsSheet(state, chats) {
    toolsOpen = false
    if (state.coachToolsAsked) chats.notNowTools()
  }
}

/** Whether the keyboard is open, where a screen test says so; else read from the keyboard's own inset. */
val LocalKeyboardOpen = androidx.compose.runtime.compositionLocalOf<Boolean?> { null }

/** Coach's header, one row: back, its name, and a menu with a new conversation and the earlier ones. */
@Composable
private fun CoachHeader(state: AppState, chats: CoachChatActions, onNewChat: () -> Unit, onBack: () -> Unit) {
  var menu by remember { mutableStateOf(false) }
  var listing by remember { mutableStateOf(false) }
  Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(
      "‹",
      style = scaled(26, color = Theme.amber),
      modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(onClickLabel = Words.Common.back, onClick = onBack).padding(start = 10.dp, top = 4.dp),
    )
    Text(Words.Coach.title, style = scaled(18, FontWeight.SemiBold, Theme.ink), modifier = Modifier.weight(1f))
    androidx.compose.material3.IconButton(onClick = onNewChat) {
      androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(bot.tafs.app.R.drawable.ic_new_conversation),
        contentDescription = Words.Coach.newConversation, tint = Theme.amberText)
    }
    Box {
      Text(
        "⋯",
        style = scaled(22, FontWeight.Bold, Theme.amberText),
        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(onClickLabel = Words.Coach.conversations) { menu = true }.padding(top = 6.dp, start = 12.dp),
      )
      DriveMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Theme.panel) {
        androidx.compose.material3.DropdownMenuItem(
          text = { Text(Words.Coach.Chats.previous, style = scaled(15, color = Theme.ink)) },
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

/** Above a conversation Coach isn't started for yet: what it is, that the first message asks for one fingerprint, and why a Start didn't go. */
@Composable
private fun StartNote(state: AppState, chats: CoachChatActions) {
  val start = state.coachStart
  if (start != null) {
    StartScreen(state, start, chats)
    return
  }
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    // That each connection and Card waits for approval is said where one does: the first approval card, not here too.
    Text(CoachWords.INTRO, style = scaled(14, color = Theme.soft))
    // No line of the app's own about what starting asks: tafs cloud's Start says it when it applies (StartScreen, `words.does`).
    if (state.startingCoach) Text(Words.Common.starting, style = scaled(12, color = Theme.dim))
    (state.coachStartError ?: state.chatErrors[COACH_CHAT])?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
}

/**
 * Start, with tafs cloud's first start (docs/coach.md): its line, then what Coach is given, the Cards in KIFF's own words and the
 * tools folded under their details, then what Start does and its button. One tap, or the first Send, signs it all under one
 * fingerprint: nothing is signed unread, and there's no second screen. tafs cloud's words; the app's own only before them.
 */
@Composable
private fun StartScreen(state: AppState, start: bot.tafs.app.kiff.CoachStart, chats: CoachChatActions) {
  val scope = rememberCoroutineScope()
  var folded by remember { mutableStateOf(true) }
  val words = start.words
  val tools = start.tools
  Column(
    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(words?.lead?.ifBlank { null } ?: CoachWords.INTRO, style = scaled(14, color = Theme.soft))
    // The Cards, in KIFF's own words: what the one fingerprint gives Coach.
    tools.card?.let { card ->
      card.cardTitle?.let { Text(it, style = scaled(14, FontWeight.SemiBold, Theme.ink), modifier = Modifier.padding(top = 4.dp)) }
      card.words.forEach { (title, lines) ->
        if (title.isNotEmpty()) Text(title, style = scaled(13, FontWeight.SemiBold, Theme.ink))
        lines.forEach { Text(it, style = scaled(13, color = Theme.soft)) }
      }
    }
    // Coach's tools, each with what it does and why, folded under the step's details.
    val step = tools.step
    if (step != null && step.details.isNotBlank() && step.groups.isNotEmpty()) {
      Text(
        step.details + if (folded) " ⌄" else " ⌃",
        style = scaled(13, FontWeight.SemiBold, Theme.amberText),
        modifier = Modifier.heightIn(min = 44.dp).clickable { folded = !folded }.padding(vertical = 10.dp),
      )
      if (!folded) step.groups.forEach { g ->
        Text(g.name, style = scaled(13, FontWeight.SemiBold, Theme.ink))
        g.tools.forEach { t ->
          Text(t.name, style = scaled(13, FontWeight.SemiBold, Theme.ink))
          if (t.does.isNotBlank()) Text(t.does, style = scaled(13, color = Theme.ink))
          if (t.why.isNotBlank()) Text(t.why, style = scaled(13, color = Theme.soft))
        }
      }
    } else tools.connect?.lines?.forEach { Text("· $it", style = scaled(13, color = Theme.ink)) }
    // tafs cloud's line about what starting asks, only when it sends one: the app's own ("asks for your fingerprint once") was
    // drawn after tafs cloud stopped sending it, and no longer held.
    words?.does?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(12, color = Theme.dim)) }
    bot.tafs.app.design.PrimaryButton(
      if (state.startingCoach) Words.Common.starting else words?.start?.ifBlank { null } ?: Words.Coach.start,
      { scope.launch { chats.giveTools() } },
      enabled = !state.startingCoach, modifier = Modifier.confirms(),
    )
    (state.coachToolsError ?: state.coachStartError ?: state.chatErrors[COACH_CHAT])?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
}

/** Before Coach is started on this account, on a phone that can't approve yet: what it is for, and why it can't start. */
@Composable
private fun CoachIntro(state: AppState) {
  val biometry = LocalBiometry.current
  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(Words.Coach.title, style = scaled(30, FontWeight.Bold, Theme.ink, display = true))
    Text(CoachWords.INTRO, style = scaled(16, color = Theme.soft))
    (state.coachStartError ?: state.chatErrors[COACH_CHAT])?.let { Text(it, style = scaled(13, color = Theme.no)) }
    // Starting issues Coach's own Cards, an approval: before the first taf, say so while this phone can't approve yet.
    Text(bot.tafs.app.model.ApprovalWords.FIRST_TAF, style = scaled(15, color = Theme.ink))
    ApprovalsUnavailable(LocalApprovalReadiness.current)
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
      Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(onClickLabel = if (open) Words.Coach.Plan.fold else Words.Coach.Plan.show) { open = !open },
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
  /** Signs in again at the vendor, for a sign-in card: a new approval, one confirmation. */
  val signInAgain: (SetupComponent) -> Unit = {},
  /** A new conversation about improving one taf, by its id: Coach starts from what it did and what its Card allows. */
  val improve: (String) -> Unit = {},
  /** Tries again to make the version an improvement names. */
  /** Opens a taf, by its KIFF agent; and a taf's versions. */
  val openTaf: (String) -> Unit = {},
  val openVersions: (String) -> Unit = {},
  /** Reads KIFF's words for a grant's profile, by grant id. */
  val loadProfileWords: suspend (String) -> Unit = {},
  /** A taf's dial, read again; and Coach's dial draft made live (the version switch), both by taf id. */
  val loadKnob: suspend (String) -> Unit = {},
  val confirmKnob: suspend (String) -> Boolean = { false },
  /** Approves a drive card: the approval key, behind the fingerprint, signs the drive's Start (docs/drive.md). */
  val approveDrive: (SetupComponent) -> Unit = {},
  /**
   * A card for an app connected with a key: opens the Connect sheet's key screen, full screen, outside the chat. The key is given
   * there, never in Coach's chat, and the card is answered once it went. With the card's draft version.
   */
  val openKeySheet: (SetupComponent, String?) -> Unit = { _, _ -> },
  /** Alerts, offered on a new taf's card: turned on as from Settings, or put off for good on this phone. */
  val turnOnAlerts: () -> Unit = {},
  val notNowAlerts: () -> Unit = {},
  /** A taf made with no plan: Plan & usage, where a plan is chosen. */
  val openPlan: () -> Unit = {},
  /** Closes the "What I did" card. */
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
    "knob" -> KnobDraftCard(c, state, answered, error, actions)
    "drive" -> DriveCard(shown, state, answered, error, actions)
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
  // length: with no title, planque's text can run to paragraphs. With a Card, a tap opens it again to read the Card that was
  // approved, KIFF's draft at the version signed, with nothing to answer; another tap folds it. planque's grant isn't shown
  // there: it reads as it is now, and a later approval can widen it past what this one approved.
  if (answered != null) {
    // A signed Card is named, and its lines, KIFF's own from the draft this phone read, stay under it: what the fingerprint signed.
    val signed = draft?.signedCard == true
    val line = CoachWords.answeredApproval(answered, c, grant?.text, draft?.label, cardSigned = signed)
    if (draft == null) {
      Text(line, style = scaled(14, FontWeight.Medium, Theme.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
      return
    }
    var open by remember(c.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = !open }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text((if (open) "▾ " else "▸ ") + line, style = scaled(14, FontWeight.Medium, Theme.dim), maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
      if (open) DraftCardsView(draft) else if (signed) draft.cardLines.flatten().forEach { Text(it, style = scaled(13, color = Theme.soft)) }
    }
    return
  }
  Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
    Eyebrow(Words.Coach.Approval.title, Theme.amberText)
    // A grant its vendor signed out reads as planque says it ("Signed out by Stripe · sign in again"), not as Coach titled it.
    (grant?.takeIf { it.needsReconnect }?.statusWords ?: c.title)?.let { Text(it, style = scaled(15, FontWeight.SemiBold, Theme.ink)) }
    when {
      state.approvalKeyLost -> ApprovalKeyLine()
      !state.planqueReady -> Text(CoachWords.NOT_CONNECTABLE, style = scaled(14, color = Theme.soft))
      grant == null -> Text(Words.Connect.readingAsks, style = scaled(14, color = Theme.soft))
      else -> {
        // Already connected, as tafs cloud marks it: its one line, then Its Card; the connection's text isn't asked again.
        val done = c.connection?.takeIf { it.done && it.words.isNotBlank() }
        if (done != null) Text(done.words, style = scaled(14, FontWeight.Medium, Theme.ink))
        // planque's own words for what it will do, never Coach's. An open approval's terms are the person's to read: blanked for
        // a drive, named only by its vendor (docs/drive.md).
        if (done == null) {
          Text(grant.text, style = scaled(14, color = Theme.ink), modifier = Modifier.approvalTerms(grant.vendor))
          Text(grant.vendor, style = scaled(13, color = Theme.dim, mono = true))
        }
        // KIFF's words for the connection's profile, verbatim and in order (KIFF's profile contract, `kiff.words.v1`): what the owner's approval signs.
        val profiled = grant.request.profile != null
        val words = state.profileWordsFor(grant)
        if (profiled) {
          LaunchedEffect(grant.id, state.profileDigest(grant)) { actions.loadProfileWords(grant.id) }
          when {
            done != null -> Unit
            words != null -> ProfileWordsLines(words)
            state.profileWordsErrors[grant.id] != null -> Text(state.profileWordsErrors.getValue(grant.id), style = scaled(13, color = Theme.no))
            else -> Text(CoachWords.READING_KIFF_WORDS, style = scaled(13, color = Theme.soft))
          }
        }
        grant.terms?.takeIf { it.isNotEmpty() && done == null }?.let { terms ->
          Text(if (details) Words.Coach.detailsOpen else Words.Coach.detailsClosed, style = scaled(13, FontWeight.Medium, Theme.dim), modifier = Modifier.heightIn(min = 36.dp).clickable { details = !details })
          if (details) Column(Modifier.approvalTerms(grant.vendor)) { terms.forEach { Text(it, style = scaled(12, color = Theme.soft, mono = true)) } }
        }
        // What this fingerprint covers is tafs cloud's to say (`signs`): the app learns the step only after the tap.
        // - It signs the Card: KIFF's own lines (its limits, what it never does) stay on screen until the fingerprint, connected
        //   or not, so the tools and their Card under one fingerprint (`tools_and_cards`) show what they sign. Until KIFF's
        //   draft is read, it says so, and Approve waits.
        // - The Card comes next: under tafs cloud's words, its lines dimmed, never under its own title as if signed now; with no
        //   words, not drawn (as on iPhone and iPad).
        // - No mark (a tafs cloud from before it): as before, the Card once the grant is connected.
        val signsCard = c.signs?.card ?: (grant.connected && draft != null)
        if (c.draft != null && draft?.confirmed != true) {
          when {
            signsCard -> {
              Eyebrow(Words.Card.itsCard)
              if (draft != null) DraftCardsView(draft) else Text(CoachWords.READING_KIFF_WORDS, style = scaled(13, color = Theme.soft))
            }
            c.signs?.words?.isNotBlank() == true -> {
              Text(c.signs!!.words!!, style = scaled(13, FontWeight.Medium, Theme.dim))
              draft?.cardLines?.flatten()?.forEach { Text(it, style = scaled(13, color = Theme.dim.copy(alpha = 0.7f))) }
            }
          }
        } else if (c.draft == null && c.signs?.card != true && done == null) {
          // An app's first sign-in has no draft yet (a Card is drafted over the tools it brings): tafs cloud's words still say a
          // Card comes next ("Its Card: you approve it next"), so the owner knows a second approval follows.
          c.signs?.words?.takeIf { it.isNotBlank() }?.let { Text(it, style = scaled(13, FontWeight.Medium, Theme.dim)) }
        }
        if (!grant.digestMatches) {
          Text(CoachWords.DIGEST_MISMATCH, style = scaled(13, color = Theme.no))
        } else {
          val biometry = LocalBiometry.current
          // Approving signs with the approval key: while this phone can't, its state in the button's place. Decline still works.
          val readiness = LocalApprovalReadiness.current
          if (readiness != bot.tafs.app.model.ApprovalReadiness.Ready && !grant.needsReconnect) ApprovalsUnavailable(readiness)
          // An app connected with a key, not connected yet: the chat never takes a secret. The card opens the key sheet, outside it.
          val needsKey = grant.takesKey && !grant.connected && !grant.needsReconnect
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (readiness == bot.tafs.app.model.ApprovalReadiness.Ready || grant.needsReconnect) OutlineButton(
              when {
                state.connecting == grant.id -> state.connectStep ?: Words.Approval.approving
                grant.needsReconnect -> Words.Connect.signInAgain
                needsKey -> Words.Connect.giveKeyOnSheet
                state.windowWords != null -> Words.Approval.approve
                else -> Words.Approval.approveWith(biometry)
              },
              { if (needsKey) actions.openKeySheet(c, draft?.version) else actions.approve(c, grant.digest, draft?.version) },
              Modifier.weight(1f).confirms(),
              // With a profile, nothing is approved before KIFF's words for it are read and shown; with a Card, nor before its draft.
              enabled = !busy && (!profiled || words != null) && (!signsCard || draft != null),
            )
            OutlineButton(Words.Coach.Approval.decline, { actions.answer("approval", c.id, "declined") }, Modifier.weight(1f), color = Theme.no, enabled = !busy)
          }
          // While this phone holds an approval window, KIFF's words for the next tap (kiff/tafs#494).
          state.windowWords?.takeIf { state.connecting == null }?.let { Text(it, style = scaled(12, color = Theme.dim)) }
          if (state.connecting == grant.id) {
            Text(CoachWords.WAITING_FOR_VENDOR, style = scaled(12, color = Theme.dim))
            Text(Words.Common.cancel, style = scaled(13, FontWeight.Medium, Theme.soft), modifier = Modifier.heightIn(min = 36.dp).clickable { actions.cancelSignIn() })
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
      Text(Words.Coach.Questions.talking, style = scaled(13, color = Theme.soft), modifier = Modifier.weight(1f, fill = false))
      Text(Words.Coach.Questions.showAgain, style = scaled(13, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.heightIn(min = 44.dp).clickable { chatting = false }.padding(top = 12.dp))
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
          Choice(Words.Coach.Questions.somethingElse, selected = q.id in selection.ownOpen) { selection = selection.toggleOwn(q) }
        }
        q.options.firstOrNull { it.value in selection.picked[q.id].orEmpty() }?.description?.let { Text(it, style = scaled(12, color = Theme.soft)) }
        if (q.id in selection.ownOpen) OwnAnswer(selection.own[q.id].orEmpty()) { selection = selection.write(q, it) }
      }
    }
    PrimaryButton(Words.Coach.Questions.send, { onAnswer("questions", c.id, selection.message(questions)) }, enabled = selection.complete(questions))
    Text(
      Words.Coach.Questions.chatAbout,
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
    onValueChange = driveTyped(onValue),
    textStyle = scaled(15, color = Theme.ink),
    cursorBrush = SolidColor(Theme.amber),
    modifier = Modifier.fillMaxWidth().clip(shape).background(Theme.background).border(BorderStroke(1.dp, Theme.line), shape),
    decorationBox = { field ->
      androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).heightIn(min = 40.dp)) {
        if (value.isEmpty()) Text(Words.Coach.Questions.inYourWords, style = scaled(15, color = Theme.dim))
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
  val signInAt = c.service?.let { Words.Coach.SignIn.at(it) } ?: Words.Coach.SignIn.atVendor
  val grant = state.grants.firstOrNull { it.id == c.grant }
  val again = grant?.needsReconnect == true
  when {
    answered != null -> Text("$signInAt · $answered", style = scaled(14, FontWeight.Medium, Theme.dim))
    // A sign-in starts with an approval, which this phone can't give until a new sign-in with KIFF.
    state.approvalKeyLost -> Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
      Text(signInAt, style = scaled(15, FontWeight.SemiBold, Theme.ink))
      ApprovalKeyLine()
    }
    else -> {
      val busy = state.connecting != null
      val waiting = c.grant != null && state.connecting == c.grant
      Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
        Eyebrow(Words.Coach.SignIn.eyebrow, Theme.amberText)
        Text(grant?.takeIf { again }?.statusWords ?: signInAt, style = scaled(20, FontWeight.Bold, Theme.ink, display = true))
        Text(c.note ?: CoachWords.SIGN_IN_NOTE, style = scaled(14, color = Theme.soft))
        PrimaryButton(
          when {
            waiting -> state.connectStep ?: Words.Connect.openingSignIn
            again -> Words.Connect.signInAgain
            else -> c.yes ?: c.service?.let { Words.Coach.SignIn.open(it) } ?: Words.Coach.SignIn.openSignIn
          },
          { actions.signInAgain(c) },
          enabled = !busy && c.grant != null,
        )
        Text(Words.Coach.SignIn.opens(c.destination.toString()), style = scaled(12, color = Theme.dim, mono = true))
        if (waiting) {
          Text(CoachWords.WAITING_FOR_VENDOR, style = scaled(12, color = Theme.dim))
          Text(Words.Common.cancel, style = scaled(13, FontWeight.Medium, Theme.soft), modifier = Modifier.heightIn(min = 36.dp).clickable { actions.cancelSignIn() })
        } else {
          Text(
            c.no ?: Words.Common.notNow,
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
    Eyebrow(Words.Coach.Result.setUp(name), Theme.ok)
    c.text?.let { Text(it, style = scaled(15, color = Theme.ink)) }
    // Coach's list of the taf's tools names them by id only: the taf's page has its Card in KIFF's words.
    when (val h = CoachWords.handOver(shown.answer, listed != null)) {
      CoachWords.HandOver.SetUp ->
        if (listed != null) {
          PrimaryButton(if (listed.name != taf.name) Words.Coach.Result.open(name) else c.yes ?: Words.Coach.Result.seeIt(name), { actions.openTaf(listed.id) })
          // Made, with no plan yet: nothing runs until there is one, in tafs cloud's words.
          shown.answer?.outcome?.planNeeded?.words?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = scaled(14, FontWeight.SemiBold, Theme.amberText))
            // Where to do it, one tap away: Plan & usage, where a plan is chosen.
            OutlineButton(Words.Usage.choosePlan, actions.openPlan, Modifier.fillMaxWidth())
          }
          // The first run's one offer of alerts, now that a taf can ask for something: a held call never reaches nobody.
          if (state.offersAlerts) AlertsOffer(actions)
        } else Text(CoachWords.settingUp(name), style = scaled(13, color = Theme.dim))
      is CoachWords.HandOver.Failed -> {
        Text(CoachWords.notSetUp(taf.name, h.why), style = scaled(13, color = Theme.no))
        Text(CoachWords.COACH_KNOWS, style = scaled(13, color = Theme.dim))
      }
      CoachWords.HandOver.SettingUp -> Text(CoachWords.settingUp(name), style = scaled(13, color = Theme.dim))
    }
  }
}

/** Alerts, offered once on a new taf's card: what they're for, Turn on alerts, and Not now. */
@Composable
private fun AlertsOffer(actions: CoachActions) {
  Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(Words.Alert.prompt, style = scaled(14, color = Theme.ink))
    OutlineButton(Words.Settings.AlertsOff.turnOn, actions.turnOnAlerts, Modifier.fillMaxWidth(), color = Theme.amberText)
    Text(
      Words.Common.notNow,
      style = scaled(14, color = Theme.dim),
      modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = actions.notNowAlerts),
    )
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
    Eyebrow(Words.Coach.Result.newVersion(improved?.name ?: taf.name), Theme.ok)
    c.text?.let { Text(it, style = scaled(15, color = Theme.ink)) }
    taf.note?.takeIf { it.isNotEmpty() }?.let { Text(it, style = scaled(13, color = Theme.soft)) }
    when (val made = CoachWords.improvement(shown.answer)) {
      is CoachWords.Improvement.Made ->
        if (improved != null) PrimaryButton(Words.Coach.Result.seeVersion(made.version.toString()), { actions.openVersions(improved.id) })
        else Text(Words.Coach.Result.versionMade(made.version.toString()), style = scaled(13, color = Theme.dim))
      is CoachWords.Improvement.Failed -> {
        Text(CoachWords.notImproved(improved?.name ?: taf.name, made.why), style = scaled(13, color = Theme.no))
        Text(CoachWords.COACH_KNOWS, style = scaled(13, color = Theme.dim))
      }
      CoachWords.Improvement.Preparing -> PrimaryButton(Words.Coach.Result.preparing, {}, enabled = false)
    }
  }
}

/**
 * KIFF's words for a connection's profile (KIFF's profile contract, `kiff.words.v1`), each line verbatim and in KIFF's order. Its kind only styles it: what
 * KIFF can't check, probation and an unpinned vendor are in amber, so they're seen before approving.
 */
@Composable
fun ProfileWordsLines(words: bot.tafs.app.kiff.ProfileWords) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    words.lines.forEach { line ->
      Text(line.text, style = scaled(13, color = if (line.caution) Theme.amberText else Theme.ink))
    }
  }
}

/**
 * Coach's dial card (K4): the dial Coach drafted for a taf, drawn from that taf's knob `draft` while it's this card's version.
 * Wakes it, When and Results go in tafs cloud's words; one confirmation makes it live (the version switch, with the fingerprint
 * where the Card changes); Change goes back to Coach. Once its version is live it says so; a newer draft replaces it.
 */
@Composable
private fun KnobDraftCard(c: SetupComponent, state: AppState, answered: String?, error: String?, actions: CoachActions) {
  val taf = c.knobTaf?.let { id -> state.tafs.firstOrNull { it.cloudId == id || it.id == id } }
  val scope = rememberCoroutineScope()
  if (taf != null) LaunchedEffect(taf.id, c.version) { actions.loadKnob(taf.id) }
  if (answered != null) {
    Text(answered, style = scaled(14, FontWeight.Medium, Theme.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
    return
  }
  val draft = taf?.let { state.knobs[it.id]?.draft }
  Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
    Eyebrow(taf?.let { bot.tafs.app.model.KnobWords.draftTitle(it.name) } ?: bot.tafs.app.model.KnobWords.DRAFT, Theme.amberText)
    when {
      taf == null || state.knobs[taf.id] == null -> Text(bot.tafs.app.model.KnobWords.READING_DRAFT, style = scaled(14, color = Theme.soft))
      // This card's version is the live one now.
      c.version != null && state.knobs[taf.id]?.version == c.version && draft?.version != c.version -> Text(bot.tafs.app.model.KnobWords.LIVE, style = scaled(14, color = Theme.soft))
      draft == null || (c.version != null && draft.version != c.version) -> Text(bot.tafs.app.model.KnobWords.REPLACED, style = scaled(14, color = Theme.soft))
      else -> {
        // By connection, as tafs cloud groups it (each app's lines, then This taf); by ring from one that doesn't group yet.
        val rings = listOf(bot.tafs.app.model.KnobRing.Kind.WakesIt, bot.tafs.app.model.KnobRing.Kind.What, bot.tafs.app.model.KnobRing.Kind.ResultsGo)
        if (draft.groups.isNotEmpty()) {
          draft.groups.forEach { group ->
            Text(group.name.ifBlank { bot.tafs.app.model.KnobWords.THIS_TAF }, style = scaled(14, FontWeight.Bold, Theme.ink))
            rings.zip(listOf(group.wakes, group.what, group.results)).filter { it.second.isNotEmpty() }.forEach { (ring, lines) ->
              Text(ring.title, style = scaled(12, FontWeight.SemiBold, KnobColors.of(ring)))
              lines.forEach { Text(it, style = scaled(14, color = Theme.ink)) }
            }
          }
        } else {
          rings.zip(listOf(draft.wakes, draft.what, draft.results)).filter { it.second.isNotEmpty() }.forEach { (ring, lines) ->
            Text(ring.title, style = scaled(13, FontWeight.SemiBold, KnobColors.of(ring)))
            lines.forEach { Text(it, style = scaled(14, color = Theme.ink)) }
          }
          if (draft.wakes.isEmpty() && draft.what.isEmpty() && draft.results.isEmpty()) draft.lines.forEach { Text(it, style = scaled(14, color = Theme.ink)) }
        }
        error?.let { Text(it, style = scaled(13, color = Theme.no)) }
        state.knobErrors[taf.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
        val busy = taf.id in state.switchingVersion
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          ApprovalGate {
            OutlineButton(
              if (busy) Words.Coach.makingLive else bot.tafs.app.model.KnobWords.confirmWith(LocalBiometry.current),
              { scope.launch { if (actions.confirmKnob(taf.id)) actions.answer("knob", c.id, "approved") } },
              Modifier.weight(1f).confirms(),
              enabled = !busy,
            )
          }
          OutlineButton(bot.tafs.app.model.KnobWords.CHANGE, { actions.answer("knob", c.id, "change") }, Modifier.weight(1f), color = Theme.soft, enabled = !busy)
        }
        Text(bot.tafs.app.model.KnobWords.ONE_CONFIRMATION, style = scaled(12, color = Theme.dim))
      }
    }
  }
}

/**
 * Coach asks to drive the app for one request (docs/drive.md): the request in the person's words, Approve (the approval key, behind
 * the fingerprint: a driven touch on it is refused, as on every confirming control) and Not now. There's no standing approval:
 * the next request asks again.
 */
@Composable
private fun DriveCard(shown: ShownComponent, state: AppState, answered: String?, error: String?, actions: CoachActions) {
  val c = shown.component
  val task = (c.text ?: c.title).orEmpty()
  if (answered != null) {
    Text("${bot.tafs.app.drive.DriveWords.DRIVE_CARD} · $answered · $task", style = scaled(14, FontWeight.Medium, Theme.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
    return
  }
  val starting = state.driveStarting == shown.ref
  Panel(stroke = Theme.amber.copy(alpha = 0.45f)) {
    Eyebrow(bot.tafs.app.drive.DriveWords.DRIVE_CARD, Theme.amberText)
    Text(task, style = scaled(15, FontWeight.SemiBold, Theme.ink))
    Text(bot.tafs.app.drive.DriveWords.DRIVE_NOTE, style = scaled(13, color = Theme.soft))
    val biometry = LocalBiometry.current
    val readiness = LocalApprovalReadiness.current
    if (readiness != bot.tafs.app.model.ApprovalReadiness.Ready) ApprovalsUnavailable(readiness)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      if (readiness == bot.tafs.app.model.ApprovalReadiness.Ready) OutlineButton(
        if (starting) Words.Approval.approving else Words.Approval.approveWith(biometry),
        { actions.approveDrive(c) },
        Modifier.weight(1f).confirms(),
        enabled = state.driveStarting == null,
      )
      OutlineButton(Words.Common.notNow, { actions.answer("drive", c.id, "declined") }, Modifier.weight(1f), color = Theme.soft, enabled = !starting)
    }
    state.coachNotes[c.id]?.let { Text(it, style = scaled(13, color = Theme.no)) }
    error?.let { Text(it, style = scaled(13, color = Theme.no)) }
  }
}

/**
 * A drive's end in Coach's chat, where it happened (kiff/tafs#568): the request, why it ended, and tafs cloud's record of each
 * step, in a line. It's the thread's, kept with it, so it never needs closing.
 */
@Composable
fun DriveRecapCard(recap: bot.tafs.app.drive.DriveRecap) {
  Panel {
    Eyebrow(bot.tafs.app.drive.DriveWords.WHAT_I_DID)
    Text(recap.task, style = scaled(15, FontWeight.SemiBold, Theme.ink))
    Text(recap.words, style = scaled(13, color = Theme.soft))
    if (recap.steps.isEmpty()) Text(bot.tafs.app.drive.DriveWords.NO_STEPS, style = scaled(13, color = Theme.dim))
    recap.steps.forEach { step ->
      val what = step.touched?.let { t -> listOfNotNull(t.words, t.taf).joinToString(" · ") }
      Text("• " + step.line + (what?.let { " · $it" } ?: ""), style = scaled(13, color = Theme.ink))
    }
  }
}
