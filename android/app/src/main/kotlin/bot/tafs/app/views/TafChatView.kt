package bot.tafs.app.views

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bot.tafs.app.R
import bot.tafs.app.app.AppState
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.app.Mode
import bot.tafs.app.app.isTrialChat
import bot.tafs.app.design.MarkdownText
import bot.tafs.app.design.Mascot
import bot.tafs.app.design.TafAvatar
import bot.tafs.app.design.Theme
import bot.tafs.app.design.brandBackground
import bot.tafs.app.design.scaled
import bot.tafs.app.app.AppModel
import bot.tafs.app.model.Chat
import bot.tafs.app.model.ChatEnd
import bot.tafs.app.model.ChatItem
import bot.tafs.app.model.LiveAnswer
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.Work
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.Taf
import bot.tafs.app.model.endsWords
import bot.tafs.app.model.shortTime
import java.time.Duration
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.launch

/** The chat's widest column: about 75 characters of text. */
private val Readable = 720.dp

/** The row a run at work is drawn in: the dots, then its answer as it's written. */
private const val WORKING = "working"

/**
 * A taf's thread: what it reports and asks, and what the owner tells it. A message steers the
 * taf within its job; it can't widen its Card, and every action it takes is still checked.
 */
@Composable
fun TafChatView(tafId: String, state: AppState, actions: TafActions, modifier: Modifier = Modifier) {
  val taf = state.taf(tafId)
  LaunchedEffect(tafId) { actions.loadChat(tafId) }
  // What the owner wrote and hasn't sent, kept by the app, not the screen: leaving the chat and coming back keeps it, as iOS does.
  val draft = state.drafts[tafId].orEmpty()
  val setDraft = { text: String -> actions.setDraft(tafId, text) }
  // Dictation into the box, recognised on the phone; it stops when the chat closes.
  val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
  val dictation = remember(tafId) { bot.tafs.app.voice.Dictation(bot.tafs.app.voice.DeviceSpeech(appContext), bot.tafs.app.voice.SavedLanguage(appContext)) }
  androidx.compose.runtime.DisposableEffect(dictation) { onDispose { dictation.stop() } }
  // Whether the thread follows what arrives: it stops when the owner reads back, and starts again
  // at the bottom.
  var following by remember { mutableStateOf(true) }
  val listState = rememberLazyListState()
  val items = state.chatItems(tafId)
  val thinking = state.thinking(tafId)
  // Someone without a key wrote through a door (docs/doors.md): the owner decides here.
  val showKnocks = state.mode == Mode.kiff && state.doorsOffered.isNotEmpty() && !isTrialChat(tafId) && tafId != COACH_CHAT && taf?.cloudId != null
  val knocks = if (showKnocks) state.doors[tafId]?.openKnocks.orEmpty() else emptyList()
  // Each knock at its time among the messages, so the thread's newest is last, whoever wrote it.
  val rows = remember(items, knocks) { Chat.withKnocks(items, knocks) }
  val live = state.chatLive[tafId]
  val total = rows.size + (if (thinking || live != null) 1 else 0)
  // Read again when the thread changes.
  LaunchedEffect(items.size, showKnocks) { if (showKnocks) actions.doors.load(tafId) }
  val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
  val scope = rememberCoroutineScope()

  // The thread's own scrolling to its end isn't the owner reading back: only the owner's stops it following. Counted, not a
  // flag: a new item and the answer growing can both scroll at once, and the first to end mustn't make the other the owner's.
  var autoScrolls by remember { mutableIntStateOf(0) }
  LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, more) ->
      if (scrolling && more && autoScrolls == 0) following = false
      if (!more) following = true
    }
  }
  // It opens at its newest, the end of the thread, once the thread is there: not at the top of whatever is last.
  var opened by remember(tafId) { mutableStateOf(false) }
  // The end of the last item, not its top: a tall one shows its last lines.
  suspend fun toEnd(animated: Boolean) {
    if (total == 0 || !(following || !opened)) return
    val last = listState.layoutInfo.totalItemsCount.coerceAtLeast(total) - 1
    autoScrolls += 1
    try {
      if (animated && opened) listState.animateScrollToItem(last, Int.MAX_VALUE) else listState.scrollToItem(last, Int.MAX_VALUE)
    } finally {
      autoScrolls -= 1
    }
    opened = true
  }
  // A new item at the end: the thread glides to it.
  LaunchedEffect(total, rows.lastOrNull()?.key) { toEnd(animated = true) }
  // The answer growing in its own row, a few words at a time: kept at its end without an animation. An animation started on
  // every word was cut off by the next before it finished, and the thread jumped about while Coach wrote.
  LaunchedEffect(thinking, live?.text?.length, live?.steps?.size, live?.state) { toEnd(animated = false) }

  Column(modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        Modifier.widthIn(max = Readable).fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (items.isEmpty() && tafId == COACH_CHAT && !thinking) {
          // Coach's empty chat: what it's about, and ways to start (kiff/tafs#138, #107).
          item(key = "empty") { CoachStarts(state, actions.coach.improve) { example -> following = true; actions.sendChat(example, tafId, false) { } } }
        } else if (items.isEmpty()) {
          item(key = "empty") {
            Text(
              "Ask ${taf?.name ?: "it"} what it did, or tell it what to do differently. It stays inside its Card. A call it needs you to approve, from a chat or a scheduled run, shows here too.",
              style = scaled(14, color = Theme.dim),
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
            )
          }
        }
        rows.forEachIndexed { r, row ->
          if (row is Chat.Row.Knock) {
            item(key = row.key) { ChatKnock(tafId, taf?.name, row.knock, state, actions.doors) }
            return@forEachIndexed
          }
          val i = (row as Chat.Row.Item).index
          val item = items[i]
          item(key = item.id) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              // A time between parts of the thread that are far apart, not on every line.
              if (i == 0 || Duration.between(items[i - 1].at, item.at) >= Duration.ofHours(1)) {
                Text(
                  endsWords(item.at),
                  style = scaled(12, color = Theme.dim),
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
              }
              when (item) {
                is ChatItem.Message -> MessageRow(
                  item.message,
                  tafId = tafId,
                  files = actions.files,
                  onEdit = { text -> setDraft(text) },
                  onResend = { text -> following = true; actions.sendChat(text, tafId, false) { } },
                )
                is ChatItem.Held -> HeldCallCard(item.call, state, actions.answer)
                is ChatItem.Outcome -> OutcomeLine(Chat.outcomeText(item.entry), outcomeTone(item.entry.kind), outcomeMark(item.entry.kind))
                is ChatItem.Card -> CoachCard(item.shown, state, actions.coach)
              }
            }
          }
        }
        // News of the tasks Coach handed tafs, until Coach reads it: what came while the owner was away, together.
        if (tafId == COACH_CHAT && state.coachLines.isNotEmpty()) item(key = "coach-lines") { CoachLines(state.coachLines) }
        // The answer as it's written, under the work that led to it (docs/chat.md, the work block); the dots until it begins.
        // One row with one key: when the answer takes the dots' place, the list keeps it where it is rather than dropping one
        // row and adding another at its end, which moved the thread back.
        // At least as tall as the dots and the live line, so neither taking the other's place shortens the thread's end.
        if (live != null || thinking) item(key = WORKING) {
          Box(Modifier.fillMaxWidth().heightIn(min = 28.dp), contentAlignment = Alignment.CenterStart) {
            if (live != null) LiveWork(live) else TypingDots()
          }
        }
        // How the latest run ended, when it says so here: stopped part-way (with Retry), stopped by the owner, or at a cap.
        if (!thinking) state.chatEnded[tafId]?.let { end ->
          item(key = "ended") { EndedLine(end, taf?.name ?: "It", onRetry = { text -> following = true; actions.sendChat(text, tafId, false) { } }) }
        }
      }

      // The held call waiting for the owner, when it's out of view; and the way back down.
      Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val waiting = items.lastOrNull { it is ChatItem.Held }
        if (waiting is ChatItem.Held) {
          val index = items.indexOf(waiting)
          val visible = listState.layoutInfo.visibleItemsInfo.any { it.key == waiting.id }
          if (!visible) {
            NeedsYouPill("Needs you · " + waiting.call.proposed.summary) {
              following = false
              scope.launch { listState.animateScrollToItem(index) }
            }
          }
        }
        if (!atBottom && !following) {
          Box(
            Modifier
              .size(38.dp)
              .shadow(6.dp, CircleShape, ambientColor = Theme.ink.copy(alpha = 0.12f))
              .clip(CircleShape)
              .background(Theme.panel)
              .border(BorderStroke(1.dp, Theme.line), CircleShape)
              .clickable {
                following = true
                scope.launch { listState.animateScrollToItem(max(0, total - 1)) }
              }
              .semantics { contentDescription = "Jump to the latest" },
            contentAlignment = Alignment.Center,
          ) {
            Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = null, tint = Theme.ink, modifier = Modifier.size(16.dp))
          }
        }
      }
    }

    state.chatErrors[tafId]?.let { error ->
      Text(error, style = scaled(13, color = Theme.no), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp))
    }
    val canAttach = state.canAttach(tafId)
    if (canAttach) FileTray(tafId, state, actions.files)
    Composer(
      name = taf?.name ?: "your taf",
      draft = draft,
      onDraft = { setDraft(it) },
      // A message is on its way: one at a time. While the taf works, what's written goes to its run.
      sending = tafId in state.chatSending,
      thinking = thinking,
      // Files ready to go make a message of their own, words or not; Send waits while one is going up, or while the taf works.
      hasFiles = canAttach && state.files(tafId).any { it.ref != null },
      holdsSend = canAttach && state.holdsSend(tafId),
      attach = if (canAttach) ({ AttachButton(tafId, state, actions.files) }) else null,
      dictation = dictation,
      // Stop, in Send's place, while a run is at work in this chat (as iOS): in a taf's chat for whoever manages Cards; in
      // Coach's, for whoever is talking to it.
      onStop = (live?.session ?: state.chatWorking[tafId]?.session)
        ?.takeIf { thinking && state.mode == Mode.kiff && (state.canManageCards || tafId == COACH_CHAT) }
        ?.let { { scope.launch { actions.stopChat(tafId) } } },
    ) {
      val text = draft
      setDraft("")
      following = true
      // As sent: if it didn't go (too long), it comes back to the box.
      actions.sendChat(text, tafId, true) { notSent -> if (state.drafts[tafId].isNullOrEmpty()) setDraft(notSent) }
    }
  }
}

@Composable
private fun Composer(
  name: String,
  draft: String,
  onDraft: (String) -> Unit,
  sending: Boolean,
  thinking: Boolean,
  hasFiles: Boolean = false,
  holdsSend: Boolean = false,
  /** The paperclip, where the chat takes files. */
  attach: (@Composable () -> Unit)? = null,
  /** Dictation into the box, on the phone only (docs/chat.md); sending stops it. */
  dictation: bot.tafs.app.voice.Dictation? = null,
  onStop: (() -> Unit)?,
  onSend: () -> Unit,
) {
  val empty = draft.isBlank() && !hasFiles
  val focus = LocalFocusManager.current
  Box(
    Modifier
      .fillMaxWidth()
      .shadow(6.dp, ambientColor = Theme.ink.copy(alpha = 0.06f), spotColor = Theme.ink.copy(alpha = 0.06f))
      .background(Theme.background),
    contentAlignment = Alignment.TopCenter,
  ) {
    Row(
      Modifier.widthIn(max = Readable).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.Bottom,
    ) {
      attach?.invoke()
      val shape = RoundedCornerShape(20.dp)
      BasicTextField(
        value = draft,
        onValueChange = onDraft,
        textStyle = scaled(16, color = Theme.ink),
        cursorBrush = SolidColor(Theme.amber),
        maxLines = 6,
        modifier = Modifier.weight(1f).clip(shape).background(Theme.panel).border(BorderStroke(1.dp, Theme.line), shape),
        decorationBox = { field ->
          Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).heightIn(min = 20.dp)) {
            if (draft.isEmpty()) Text(if (thinking) "Write the next message" else "Message $name", style = scaled(16, color = Theme.dim))
            field()
          }
        },
      )
      dictation?.let { MicButton(it, draft, onDraft) }
      if (onStop != null && empty) {
        // While the taf works and the box is empty, Send is Stop: its run is cancelled.
        Box(
          Modifier.size(40.dp).clip(CircleShape).background(Theme.ink).clickable(onClick = onStop).semantics { contentDescription = "Stop" },
          contentAlignment = Alignment.Center,
        ) {
          Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
        }
      } else Box(
        Modifier
          .size(40.dp)
          // The whole button dims while it can't send, as iOS's does: circle and arrow together, in one layer.
          .alpha(if (empty || sending || holdsSend) 0.4f else 1f)
          .clip(CircleShape)
          .brandBackground()
          .clickable(enabled = !empty && !sending && !holdsSend) {
            dictation?.stop()
            focus.clearFocus()
            onSend()
          }
          .semantics { contentDescription = "Send" },
        contentAlignment = Alignment.Center,
      ) {
        Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
      }
    }
  }
}

/**
 * One message. The taf's goes full width with no bubble, as Claude and ChatGPT write; the owner's
 * on the right in the mascot's colours.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(message: ChatMessage, tafId: String, files: FileActions, onEdit: (String) -> Unit, onResend: (String) -> Unit) {
  val door = message.door
  if (door != null && message.kind != ChatMessage.Kind.kickoff) {
    // A door's message, or its answer: marked with where it came from and how it went back.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.from == ChatMessage.From.owner) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (message.from == ChatMessage.From.owner) DoorStamp(door, fromOwner = true)
      MessageBody(message, tafId, files, onEdit, onResend)
      if (message.from == ChatMessage.From.taf) DoorStamp(door, fromOwner = false)
    }
  } else {
    MessageBody(message, tafId, files, onEdit, onResend)
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBody(message: ChatMessage, tafId: String, files: FileActions, onEdit: (String) -> Unit, onResend: (String) -> Unit) {
  if (message.from == ChatMessage.From.owner) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      // A message may be files alone: then there are no words to show, send again or copy.
      if (message.text.isNotBlank()) Box {
        Text(
          message.text,
          style = scaled(15, color = Color.White),
          modifier = Modifier
            .alpha(if (message.session == "pending") 0.7f else 1f)
            .clip(RoundedCornerShape(18.dp))
            .brandBackground()
            .combinedClickable(onClick = {}, onLongClick = { menu = true })
            .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        // A sent message's menu (kiff/tafs#285): send it again (its words only), change it first, or copy it.
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Theme.panel) {
          DropdownMenuItem(text = { Text("Send again") }, onClick = { menu = false; onResend(message.text) })
          DropdownMenuItem(text = { Text("Edit and send") }, onClick = { menu = false; onEdit(message.text) })
          DropdownMenuItem(text = { Text("Copy") }, onClick = {
            menu = false
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.text))) }
          })
        }
      }
      // The files sent with it, each opened full size.
      if (message.attachments.isNotEmpty()) MessageFiles(message.attachments, tafId, files)
      // Written while a card waited: these words were the card's answer (`answers`).
      if (message.answers != null) Text(Chat.ANSWERED_CARD, style = scaled(12, color = Theme.dim))
    }
  } else if (message.kind == ChatMessage.Kind.kickoff) {
    // A run's kickoff shows as a small note, not a bubble.
    Text(
      "Run started ${shortTime(message.at)} · ${message.text}",
      style = scaled(12, color = Theme.dim),
      textAlign = TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
  } else {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      // What it did before writing it, folded (kiff/tafs#285).
      message.work?.let { WorkSummary(it) }
      // Full width, with no bubble, as the taf writes (#58).
      MarkdownText(message.text, size = 16, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
    }
  }
}

/**
 * How long the taf worked and how many steps it took, folded: "Worked 4 s · 3 steps, 1 failed". Open, the steps tafs cloud has
 * words for, in order; a step without words is left out, and a tool's id is never shown.
 */
@Composable
private fun WorkSummary(work: Work) {
  var open by remember { mutableStateOf(false) }
  val steps = Chat.steps(work)
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      (if (steps.isEmpty()) "" else if (open) "▾ " else "▸ ") + Chat.workLine(work),
      style = scaled(12, FontWeight.Medium, Theme.dim),
      modifier = Modifier.heightIn(min = 28.dp).clickable(enabled = steps.isNotEmpty()) { open = !open }.padding(vertical = 4.dp),
    )
    if (open) steps.forEach { Text("• $it", style = scaled(12, color = if (it.endsWith(" · failed")) Theme.no else Theme.soft)) }
  }
}

/**
 * The work of the answer streaming now: one calm line of what it's at, tafs cloud's (docs/chat.md, "The live line"), then the
 * words as they're written. No thinking text and no list of steps: when the run ends, the line folds into the message's
 * "Worked … · N steps". Before tafs cloud sends the line, the step at work stands in for it.
 */
@Composable
private fun LiveWork(live: LiveAnswer) {
  val writing = live.text.isNotBlank()
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    val line = Chat.liveLine(live)
    if (line != null) {
      // Waiting for the owner, nothing is at work: the line says so in amber, with no spinner.
      val waiting = live.state?.state == "waiting_for_you"
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!waiting) androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Theme.dim)
        Text(line, style = scaled(13, color = if (waiting) Theme.amberText else Theme.dim))
      }
    }
    if (writing) MarkdownText(live.text, size = 16, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
  }
}

/** How the chat's latest run ended, said under it: part-way with what went through and Retry, stopped by the owner, or at a cap. */
@Composable
private fun EndedLine(end: ChatEnd, taf: String, onRetry: (String) -> Unit) {
  val words = when (end) {
    is ChatEnd.Failed -> AppModel.failedWords(taf, end.done)
    ChatEnd.Stopped -> "You stopped $taf."
    is ChatEnd.Capped -> AppModel.capWords(end.stop, taf) ?: return
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(words, style = scaled(14, color = Theme.ink))
    if (end is ChatEnd.Failed) {
      Text("Retry", style = scaled(14, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.clickable { onRetry(end.retry) }.padding(vertical = 6.dp))
    }
  }
}

/** The taf is working on an answer: dots where its words will be. */
@Composable
private fun TypingDots() {
  val transition = rememberInfiniteTransition(label = "typing")
  val phase by transition.animateFloat(
    initialValue = 0f,
    targetValue = (2 * PI).toFloat(),
    animationSpec = infiniteRepeatable(tween((2 * PI / 4 * 1000).toInt(), easing = LinearEasing), RepeatMode.Restart),
    label = "phase",
  )
  Row(
    Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { contentDescription = "Answering" },
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    repeat(3) { i ->
      Box(Modifier.size(7.dp).alpha(0.35f + 0.65f * max(0f, sin(phase - i * 0.9f))).clip(CircleShape).background(Theme.dim))
    }
  }
}

/** What became of a call the owner answered, in the chat. */
@Composable
private fun OutcomeLine(text: String, tone: Color, mark: String) {
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Theme.raised.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(Modifier.padding(top = 2.dp).size(16.dp).clip(CircleShape).background(tone), contentAlignment = Alignment.Center) {
      Text(mark, style = scaled(10, FontWeight.Bold, Color.White))
    }
    Text(text, style = scaled(14, color = Theme.soft), modifier = Modifier.weight(1f))
  }
}

private fun outcomeTone(kind: RecordEntry.Kind): Color = when (kind) {
  RecordEntry.Kind.approved -> Theme.ok
  RecordEntry.Kind.notDone -> Theme.no
  RecordEntry.Kind.sending, RecordEntry.Kind.unknown -> Theme.amber
  else -> Theme.dim
}

private fun outcomeMark(kind: RecordEntry.Kind): String = when (kind) {
  RecordEntry.Kind.approved -> "✓"
  RecordEntry.Kind.sending -> "…"
  RecordEntry.Kind.unknown -> "?"
  else -> "✕"
}

/** The held call waiting for the owner, as a bar over the thread that takes them to it. */
@Composable
private fun NeedsYouPill(text: String, onClick: () -> Unit) {
  Row(
    Modifier
      .padding(horizontal = 12.dp)
      .shadow(6.dp, CircleShape, ambientColor = Theme.ink.copy(alpha = 0.12f))
      .clip(CircleShape)
      .background(Theme.panel)
      .border(BorderStroke(1.dp, Theme.amber.copy(alpha = 0.6f)), CircleShape)
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(Theme.amber))
    Text(text, style = scaled(14, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
  }
}
