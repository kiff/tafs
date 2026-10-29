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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import bot.tafs.app.model.LiveStep
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

/**
 * A taf's thread: what it reports and asks, and what the owner tells it. A message steers the
 * taf within its job; it can't widen its Card, and every action it takes is still checked.
 */
@Composable
fun TafChatView(tafId: String, state: AppState, actions: TafActions, modifier: Modifier = Modifier) {
  val taf = state.taf(tafId)
  LaunchedEffect(tafId) { actions.loadChat(tafId) }
  var draft by rememberSaveable { mutableStateOf("") }
  // Whether the thread follows what arrives: it stops when the owner reads back, and starts again
  // at the bottom.
  var following by remember { mutableStateOf(true) }
  val listState = rememberLazyListState()
  val items = state.chatItems(tafId)
  val thinking = state.thinking(tafId)
  // Someone without a key wrote through a door (docs/doors.md): the owner decides here.
  val showKnocks = state.mode == Mode.kiff && state.doorsOffered.isNotEmpty() && !isTrialChat(tafId) && tafId != COACH_CHAT && taf?.cloudId != null
  val knocking = showKnocks && state.doors[tafId]?.openKnocks.orEmpty().isNotEmpty()
  val live = state.chatLive[tafId]
  val total = items.size + (if (knocking) 1 else 0) + (if (thinking || live != null) 1 else 0)
  // Read again when the thread changes.
  LaunchedEffect(items.size, showKnocks) { if (showKnocks) actions.doors.load(tafId) }
  val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
  val scope = rememberCoroutineScope()

  LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, more) ->
      if (scrolling && more) following = false
      if (!more) following = true
    }
  }
  // It opens at the latest, and follows what arrives while the owner isn't reading back.
  LaunchedEffect(tafId) { if (total > 0) listState.scrollToItem(total - 1) }
  // Keyed by the last item and the dots too, not only the count: the answer takes the dots' place
  // at the same count, and is taller.
  LaunchedEffect(total, items.lastOrNull()?.id, thinking, live?.text?.length, live?.steps?.size) { if (following && total > 0) listState.animateScrollToItem(total - 1) }

  Column(modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        Modifier.widthIn(max = Readable).fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (items.isEmpty()) {
          item(key = "empty") {
            Text(
              if (tafId == COACH_CHAT) "Tell Coach what you want to make or change. It asks what it needs, shows its plan, and nothing it proposes counts until you approve it."
              else "Ask ${taf?.name ?: "it"} what it did, or tell it what to do differently. It stays inside its Card. A call it needs you to approve, from a chat or a scheduled run, shows here too.",
              style = scaled(14, color = Theme.dim),
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
            )
          }
        }
        items.forEachIndexed { i, item ->
          item(key = item.id) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                  onEdit = { text -> draft = text },
                  onResend = { text -> following = true; actions.sendChat(text, tafId) { } },
                )
                is ChatItem.Held -> HeldCallCard(item.call, state, actions.answer)
                is ChatItem.Outcome -> OutcomeLine(Chat.outcomeText(item.entry), outcomeTone(item.entry.kind), outcomeMark(item.entry.kind))
                is ChatItem.Card -> CoachCard(item.shown, state, actions.coach)
              }
            }
          }
        }
        if (knocking) item(key = "knocks") { ChatKnocks(tafId, taf?.name, state, actions.doors) }
        when {
          // The answer as it's written, under the work that led to it (docs/chat.md, the work block).
          live != null -> item(key = "live") { LiveWork(live) }
          thinking -> item(key = "thinking") { TypingDots() }
        }
        // Stop: for whoever manages Cards, while a run is at work in this chat.
        val session = live?.session ?: state.chatWorking[tafId]?.session
        if (thinking && session != null && state.mode == Mode.kiff && state.canManageCards) {
          item(key = "stop") {
            Text(
              "Stop",
              style = scaled(14, FontWeight.SemiBold, Theme.no),
              modifier = Modifier.clip(RoundedCornerShape(50)).border(BorderStroke(1.dp, Theme.line), RoundedCornerShape(50))
                .clickable { scope.launch { actions.stopChat(tafId) } }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
        }
        // How the latest run ended, when it says so here: stopped part-way (with Retry), stopped by the owner, or at a cap.
        if (!thinking) state.chatEnded[tafId]?.let { end ->
          item(key = "ended") { EndedLine(end, taf?.name ?: "It", onRetry = { text -> following = true; actions.sendChat(text, tafId) { } }) }
        }
      }

      // The held call waiting for the owner, when it's out of view; and the way back down.
      Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
      Text(error, style = scaled(13, color = Theme.no), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp))
    }
    Composer(
      name = taf?.name ?: "your taf",
      draft = draft,
      onDraft = { draft = it },
      // A message is on its way: one at a time. While the taf works, what's written goes to its run.
      sending = tafId in state.chatSending,
      thinking = thinking,
    ) {
      val text = draft
      draft = ""
      following = true
      // As sent: if it didn't go (too long), it comes back to the box.
      actions.sendChat(text, tafId) { notSent -> if (draft.isEmpty()) draft = notSent }
    }
  }
}

@Composable
private fun Composer(name: String, draft: String, onDraft: (String) -> Unit, sending: Boolean, thinking: Boolean, onSend: () -> Unit) {
  val empty = draft.isBlank()
  val focus = LocalFocusManager.current
  Box(
    Modifier
      .fillMaxWidth()
      .shadow(6.dp, ambientColor = Theme.ink.copy(alpha = 0.06f), spotColor = Theme.ink.copy(alpha = 0.06f))
      .background(Theme.background),
    contentAlignment = Alignment.TopCenter,
  ) {
    Row(
      Modifier.widthIn(max = Readable).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.Bottom,
    ) {
      val shape = RoundedCornerShape(20.dp)
      BasicTextField(
        value = draft,
        onValueChange = onDraft,
        textStyle = scaled(16, color = Theme.ink),
        cursorBrush = SolidColor(Theme.amber),
        maxLines = 6,
        modifier = Modifier.weight(1f).clip(shape).background(Theme.panel).border(BorderStroke(1.dp, Theme.line), shape),
        decorationBox = { field ->
          Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 20.dp)) {
            if (draft.isEmpty()) Text(if (thinking) "Write the next message" else "Message $name", style = scaled(16, color = Theme.dim))
            field()
          }
        },
      )
      Box(
        Modifier
          .size(40.dp)
          .clip(CircleShape)
          .brandBackground()
          .alpha(if (empty || sending) 0.4f else 1f)
          .clickable(enabled = !empty && !sending) {
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
private fun MessageRow(message: ChatMessage, onEdit: (String) -> Unit, onResend: (String) -> Unit) {
  val door = message.door
  if (door != null && message.kind != ChatMessage.Kind.kickoff) {
    // A door's message, or its answer: marked with where it came from and how it went back.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.from == ChatMessage.From.owner) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (message.from == ChatMessage.From.owner) DoorStamp(door, fromOwner = true)
      MessageBody(message, onEdit, onResend)
      if (message.from == ChatMessage.From.taf) DoorStamp(door, fromOwner = false)
    }
  } else {
    MessageBody(message, onEdit, onResend)
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBody(message: ChatMessage, onEdit: (String) -> Unit, onResend: (String) -> Unit) {
  if (message.from == ChatMessage.From.owner) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      Box(Modifier.padding(start = 48.dp)) {
        Text(
          message.text,
          style = scaled(15, color = Color.White),
          modifier = Modifier
            .alpha(if (message.session == "pending") 0.7f else 1f)
            .clip(RoundedCornerShape(18.dp))
            .brandBackground()
            .combinedClickable(onClick = {}, onLongClick = { menu = true })
            .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        // A sent message's menu (kiff/tafs#285): send it again, change it first, or copy it.
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Theme.panel) {
          DropdownMenuItem(text = { Text("Send again") }, onClick = { menu = false; onResend(message.text) })
          DropdownMenuItem(text = { Text("Edit and send") }, onClick = { menu = false; onEdit(message.text) })
          DropdownMenuItem(text = { Text("Copy") }, onClick = {
            menu = false
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.text))) }
          })
        }
      }
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

/** How long the taf worked and the tools it called, in order, folded: "Worked 4 s · list payments, look up one payment". */
@Composable
private fun WorkSummary(work: Work) {
  var open by remember { mutableStateOf(false) }
  val seconds = work.ms / 1000.0
  val took = if (seconds < 10) "%.1f s".format(java.util.Locale.ROOT, seconds) else "${Math.round(seconds)} s"
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      (if (open) "▾ " else "▸ ") + "Worked $took" + if (work.steps.isEmpty()) "" else " · ${work.steps.size} ${if (work.steps.size == 1) "step" else "steps"}",
      style = scaled(12, FontWeight.Medium, Theme.dim),
      modifier = Modifier.heightIn(min = 28.dp).clickable(enabled = work.steps.isNotEmpty()) { open = !open }.padding(vertical = 4.dp),
    )
    if (open) {
      work.steps.forEach { step ->
        val note = if (step.error) " · failed" else if (step.notMade) " · not made" else ""
        Text("• " + KiffMapping.toolWords(step.tool) + note, style = scaled(12, color = if (step.error) Theme.no else Theme.soft))
      }
    }
  }
}

/**
 * The work of the answer streaming now: the step in progress, or what it's at ("Thinking…", "Writing…"); the line of what it's
 * thinking while it hasn't started writing; the steps done so far; then the words as they're written.
 */
@Composable
private fun LiveWork(live: LiveAnswer) {
  val writing = live.text.isNotBlank()
  // A quick answer's thinking is noise: the line shows once it has thought for a few seconds.
  var thoughtAWhile by remember(live.started) { mutableStateOf(false) }
  LaunchedEffect(live.started) { kotlinx.coroutines.delay(3_000); thoughtAWhile = true }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    val running = live.steps.lastOrNull { it.status == LiveStep.Status.running }
    if (running != null || live.activity != null || !writing) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Theme.dim)
        Text(live.activity ?: running?.words ?: if (writing) "Writing…" else "Thinking…", style = scaled(13, color = Theme.dim))
      }
    }
    if (!writing && thoughtAWhile) {
      Chat.thinkingLine(live.thinking).takeIf { it.isNotEmpty() }?.let { Text(it, style = scaled(13, color = Theme.soft), maxLines = 2) }
    }
    live.steps.filter { it.status != LiveStep.Status.running }.forEach { step ->
      val (mark, note, tone) = when (step.status) {
        LiveStep.Status.error -> Triple("✗", " · failed", Theme.no)
        LiveStep.Status.notMade -> Triple("↩", " · not made · read first", Theme.dim)
        LiveStep.Status.held -> Triple("⏸", " · held for you", Theme.amberText)
        else -> Triple("✓", "", Theme.soft)
      }
      Text("$mark ${step.words}$note", style = scaled(13, color = tone))
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
      Text("Retry", style = scaled(14, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.clickable { onRetry(end.retry) }.padding(vertical = 8.dp))
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
    Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = "Answering" },
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
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Theme.raised.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
      .padding(horizontal = 16.dp)
      .shadow(6.dp, CircleShape, ambientColor = Theme.ink.copy(alpha = 0.12f))
      .clip(CircleShape)
      .background(Theme.panel)
      .border(BorderStroke(1.dp, Theme.amber.copy(alpha = 0.6f)), CircleShape)
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(Theme.amber))
    Text(text, style = scaled(14, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
  }
}
