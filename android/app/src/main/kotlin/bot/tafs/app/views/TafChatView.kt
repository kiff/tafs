package bot.tafs.app.views

import bot.tafs.app.design.tapping
import bot.tafs.app.model.Words
import bot.tafs.app.drive.driveTyped
import bot.tafs.app.drive.DriveMenu
import bot.tafs.app.drive.confirms
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
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
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
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.MarkdownText
import bot.tafs.app.design.Mascot
import bot.tafs.app.design.TafAvatar
import bot.tafs.app.design.Theme
import bot.tafs.app.design.OutlineButton
import bot.tafs.app.design.Panel
import bot.tafs.app.design.brandBackground
import bot.tafs.app.design.scaled
import bot.tafs.app.app.AppModel
import androidx.compose.material3.TextButton
import bot.tafs.app.model.Chat
import bot.tafs.app.model.ChatEnd
import bot.tafs.app.model.ChatWords
import bot.tafs.app.model.ReportResult
import bot.tafs.app.model.ChatItem
import bot.tafs.app.model.LiveAnswer
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.Refused
import bot.tafs.app.model.Work
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.Taf
import bot.tafs.app.model.chatDay
import bot.tafs.app.model.localDate
import bot.tafs.app.model.messageTime
import bot.tafs.app.model.shortTime
import java.time.Duration
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.launch

/** The chat's widest column: about 75 characters of text. */
private val Readable = bot.tafs.app.design.PageWidth.Reading

/** The row a run at work is drawn in: the dots, then its answer as it's written. */
private const val WORKING = "working"

/** The room under a sent message at the top, so even a short one gets there (docs/chat.md, "The sent message stays on top"). */
private const val ROOM = "room"

/** The thread's rows are this far apart: the room's size counts it. */
private val RowGap = 8.dp

/**
 * A taf's thread: what it reports and asks, and what the owner tells it. A message steers the
 * taf within its job; it can't widen its Card, and every action it takes is still checked.
 */
@Composable
fun TafChatView(tafId: String, state: AppState, actions: TafActions, modifier: Modifier = Modifier) {
  // One "Open this link?" for the whole chat, so it stays up when the answer it was asked from finishes and is drawn anew.
  LinkConfirm { TafChatScreen(tafId, state, actions, modifier) }
}

/** Coach's box locked (docs/coach.md, "Coach's tools step"): the line that stands in its place, and what tapping it opens. */
data class ComposerLock(val words: String, val open: (() -> Unit)? = null)

/** Set by Coach's screen while its tools aren't approved yet; null everywhere else, where the box is drawn as ever. */
val LocalComposerLock = androidx.compose.runtime.compositionLocalOf<ComposerLock?> { null }

/** The locked box's line, for the screen tests. */
const val COMPOSER_LOCKED = "composer_locked"

@Composable
private fun TafChatScreen(tafId: String, state: AppState, actions: TafActions, modifier: Modifier) {
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
  // The sent message stays on top (docs/chat.md), as on the iPad: sending puts it at the top of the chat with room under it,
  // and the taf's work and answer grow below it without moving it. `pinned` is its row's key; `pinText` its words until its row
  // is in the thread; `pinnedWords` its words after, so tafs cloud's copy taking the row's place takes the pin with it.
  var pinned by remember(tafId) { mutableStateOf<String?>(null) }
  var pinText by remember(tafId) { mutableStateOf<String?>(null) }
  var pinnedWords by remember(tafId) { mutableStateOf<String?>(null) }
  // The rows there when it was sent: the pin goes only to a new one, never an older message with the same words.
  var sentBefore by remember(tafId) { mutableStateOf(emptySet<String>()) }
  // The owner has scrolled since: the pin isn't put back over what they're reading.
  var touched by remember(tafId) { mutableStateOf(false) }
  // The room under the pinned message's turn, in pixels: the screen less the turn, so the answer grows into it and nothing moves.
  var room by remember(tafId) { mutableIntStateOf(0) }
  var pinning by remember(tafId) { mutableStateOf(false) }
  val listState = rememberLazyListState()
  // In the order already on screen: nothing moves while a turn streams or the thread is read again (Chat.steady).
  val shown = remember(tafId) { Chat.Shown() }
  val read = state.chatItems(tafId)
  val items = remember(read) { shown.order(read) }
  // Files, behind its switch: a chat's files go under its last answer, by the chat's session.
  val filesOn = LocalFiles.current != null
  val lastAnswer = remember(items, filesOn) {
    if (!filesOn) emptyMap() else items.withIndex().filter { (it.value as? ChatItem.Message)?.message?.from == bot.tafs.app.model.ChatMessage.From.taf }
      .associate { (it.value as ChatItem.Message).message.session to it.index }
  }
  val thinking = state.thinking(tafId)
  // Someone without a key wrote through a door (docs/doors.md): the owner decides here.
  val showKnocks = state.mode == Mode.kiff && state.doorsOffered.isNotEmpty() && !isTrialChat(tafId) && tafId != COACH_CHAT && taf?.cloudId != null
  val knocks = if (showKnocks) state.doors[tafId]?.openKnocks.orEmpty() else emptyList()
  // Each knock at its time among the messages, so the thread's newest is last, whoever wrote it.
  val rows = remember(items, knocks) { Chat.withKnocks(items, knocks) }
  val live = state.chatLive[tafId]
  // Coach's lines and a drive's "What I did" card sit under the thread: rows too, so the thread's end is after them.
  val extras = if (tafId == COACH_CHAT && state.coachLines.isNotEmpty()) 1 else 0
  val total = rows.size + extras + (if (thinking || live != null) 1 else 0)
  // Read again when the thread changes.
  LaunchedEffect(items.size, showKnocks) { if (showKnocks) actions.doors.load(tafId) }
  val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
  val scope = rememberCoroutineScope()
  // The list's rows by key, in its order, so a pinned row is found by its index even when it's off the screen.
  val heldWords = state.chatSteering[tafId]?.held?.text
  val ended = !thinking && state.chatEnded[tafId] != null
  val keys = buildList {
    if (items.isEmpty()) add("empty")
    rows.forEach { add(it.key) }
    if (extras == 1) add("coach-lines")
    if (live != null || thinking) add(Chat.liveKey(items, live, WORKING))
    if (heldWords != null) add("held")
    if (ended) add("ended")
  }

  // The thread's own scrolling to its end isn't the owner reading back: only the owner's stops it following. Counted, not a
  // flag: a new item and the answer growing can both scroll at once, and the first to end mustn't make the other the owner's.
  var autoScrolls by remember { mutableIntStateOf(0) }
  LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, more) ->
      if (scrolling && autoScrolls == 0) touched = true
      if (scrolling && more && autoScrolls == 0) following = false
      // A pinned message stays at the top: seeing the end under it doesn't make the thread follow what arrives.
      if (!more && pinned == null && pinText == null) following = true
    }
  }
  // It opens at its newest, the end of the thread, once the thread is there: not at the top of whatever is last.
  var opened by remember(tafId) { mutableStateOf(false) }
  // The end of the last item, not its top: a tall one shows its last lines.
  suspend fun toEnd(animated: Boolean) {
    if (total == 0 || !(following || !opened) || (opened && (pinned != null || pinText != null))) return
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
  // A drive that just ended: its end comes into view in the thread, whatever was read back before (docs/drive.md).
  val lastDrive = if (tafId == COACH_CHAT) state.coachDrives.lastOrNull()?.id else null
  LaunchedEffect(lastDrive) { if (lastDrive != null && opened) { pinned = null; pinText = null; room = 0; following = true; toEnd(animated = true) } }
  // The answer growing in its own row, a few words at a time: kept at its end without an animation. An animation started on
  // every word was cut off by the next before it finished, and the thread jumped about while Coach wrote.
  LaunchedEffect(thinking, live?.text?.length, live?.steps?.size, live?.state) { toEnd(animated = false) }
  // And when the last row's height changes after it's drawn (its words laid out again, a card opening), at once, while it follows.
  // This effect lives as long as the list: it calls the latest frame's toEnd, never the first's, whose thread may not have loaded.
  val toEndNow by rememberUpdatedState<suspend () -> Unit> { toEnd(animated = false) }
  LaunchedEffect(listState) {
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.let { it.key to it.size } }
      .collect { if (opened && following) toEndNow() }
  }

  // The pin: the sent message's row at the top of the list, glided to the first time, put back at once after (unless the owner
  // has scrolled since). Run in the screen's scope, so a change to the thread doesn't cut the glide short.
  fun pinTo(key: String, animated: Boolean) {
    val index = keys.indexOf(key)
    if (index < 0 || (!animated && (touched || pinning))) return
    scope.launch {
      autoScrolls += 1
      if (animated) pinning = true
      try {
        if (animated) listState.animateScrollToItem(index) else listState.scrollToItem(index)
      } finally {
        autoScrolls -= 1
        if (animated) pinning = false
      }
    }
  }
  fun unpin() {
    pinned = null
    pinText = null
    pinnedWords = null
    room = 0
  }
  // Sending: the message goes to the top once its row is in the thread. Until the turn is measured, its room is a whole screen.
  fun pinOnSend(text: String) {
    pinText = text.trim()
    pinned = null
    touched = false
    following = false
    sentBefore = keys.toSet()
    room = listState.layoutInfo.viewportEndOffset.coerceAtLeast(0)
  }
  // The owner's message with these words, as the thread keeps them (with files, its words come after them), in a row [allowed].
  fun ownerRow(words: String, allowed: (String) -> Boolean): String? = rows.lastOrNull { row ->
    val m = ((row as? Chat.Row.Item)?.let { items[it.index] } as? ChatItem.Message)?.message
    m != null && m.from == ChatMessage.From.owner && sentWords(m.text.trim(), words) && allowed(row.key)
  }?.key
  LaunchedEffect(keys) {
    pinned?.let { key ->
      if (key in keys) return@let
      // tafs cloud's copy took its row's place, or the run took the words held for it: the pin goes with them, nothing moves.
      val copy = pinnedWords?.let { words -> ownerRow(words) { true } }
      if (copy != null) pinned = copy
      else {
        // The pinned message is gone and nothing took its place: the chat goes to its end rather than stay past it, blank.
        unpin()
        following = true
        toEnd(animated = true)
        return@LaunchedEffect
      }
    }
    pinText?.let { text ->
      // The message just sent, matched by its words among the rows new since: its own row, or the words held for the run at work.
      val sent = ownerRow(text) { it !in sentBefore }
        ?: "held".takeIf { "held" in keys && "held" !in sentBefore && heldWords?.let { sentWords(it.trim(), text) } == true }
      if (sent != null) {
        pinText = null
        pinnedWords = text
        pinned = sent
        following = false
        touched = false
        pinTo(sent, animated = true)
        return@LaunchedEffect
      }
    }
    // The thread changed under it (the answer landed, a row came or went above it): it's put back at the top.
    pinned?.let { pinTo(it, animated = false) }
  }
  // Coach's card just answered takes the pin, as on iOS (docs/chat.md): its answer starts Coach's next answer, so the card goes to
  // the top with the work and the answer under it, as a sent message does.
  // Only when it changes, as iOS's `.onChange`: the chat drawn afresh (back in Coach, the phone turned) opens at its newest, never
  // at a card answered before. `pinnedCard` is the one already pinned, or there when the view was drawn.
  val answeredCard = if (tafId == COACH_CHAT) state.coachAnsweredCard?.let { "c|$it" } else null
  var pinnedCard by remember(tafId) { mutableStateOf(answeredCard) }
  LaunchedEffect(answeredCard, answeredCard in keys) {
    if (answeredCard == pinnedCard) return@LaunchedEffect
    val key = answeredCard?.takeIf { it in keys } ?: return@LaunchedEffect
    pinnedCard = key
    pinText = null
    pinnedWords = null
    pinned = key
    following = false
    touched = false
    room = listState.layoutInfo.viewportEndOffset.coerceAtLeast(0)
    pinTo(key, animated = true)
  }
  // The screen grows as the keyboard goes down on sending: the pinned message is put back at the top once it has.
  val repin by rememberUpdatedState({ pinned?.let { pinTo(it, animated = false) }; Unit })
  LaunchedEffect(listState) {
    snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { repin() }
  }
  // The room is the screen less the turn (from the pinned message to the thread's last row): as the answer grows, the room shrinks
  // by as much, and nothing moves. Measured while both are on screen; a turn taller than the screen leaves no room.
  val gap = with(LocalDensity.current) { RowGap.roundToPx() }
  LaunchedEffect(listState) {
    snapshotFlow {
      val key = pinned ?: return@snapshotFlow null
      val info = listState.layoutInfo
      val top = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@snapshotFlow null
      val under = info.visibleItemsInfo.firstOrNull { it.key == ROOM }
        ?: return@snapshotFlow if (top.offset <= 0) 0 else null
      val last = info.visibleItemsInfo.firstOrNull { it.index == under.index - 1 } ?: return@snapshotFlow null
      max(0, info.viewportEndOffset - info.afterContentPadding - (last.offset + last.size - top.offset) - gap)
    }.collect { measured -> if (measured != null) room = measured }
  }

  Column(modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        Modifier.widthIn(max = Readable).fillMaxSize().testTag("chat-thread"),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (items.isEmpty() && tafId == COACH_CHAT && !thinking) {
          // Coach's empty chat: what it's about, and ways to start (kiff/tafs#138, #107).
          item(key = "empty") { CoachStarts(state, actions.coach.improve, fill = setDraft) { example -> pinOnSend(example); actions.sendChat(example, tafId, false) { unpin(); following = true } } }
        } else if (items.isEmpty()) {
          item(key = "empty") {
            Text(
              taf?.name?.let { Words.Chat.emptyNamed(it) } ?: Words.Chat.emptyUnnamed,
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
          item(key = row.key) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              // A header where a day starts (row 44, C): Today, Yesterday, then the date; each message has its time under it.
              if (bot.tafs.watchlink.ChatTime.startsDay(if (i == 0) null else localDate(items[i - 1].at), localDate(item.at))) {
                Text(
                  chatDay(localDate(item.at)),
                  style = scaled(12, FontWeight.Medium, Theme.dim),
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(DAY_HEADER),
                )
              }
              when (item) {
                is ChatItem.Message -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  MessageRow(
                    item.message,
                    tafId = tafId,
                    files = actions.files,
                    onEdit = { text -> setDraft(text) },
                    onResend = { text -> pinOnSend(text); actions.sendChat(text, tafId, false) { unpin(); following = true } },
                    // An answer of Coach's or the taf's, reported by tafs cloud's id for it; none in the sample.
                    onReport = if (state.mode == Mode.kiff) ({ reason, note -> actions.report(tafId, item.message, reason, note) }) else null,
                  )
                  if (lastAnswer[item.message.session] == i) ChatFiles(item.message.session)
                  // A failed run's line: Try again, only when tafs cloud says it's safe (its `retry`), in its words. Once.
                  state.failedRetry(tafId)?.takeIf { bot.tafs.app.app.FAILED_LINE + it.run == item.message.serverId }?.let { again ->
                    Text(
                      again.label ?: Words.Chat.retry,
                      style = scaled(14, FontWeight.SemiBold, Theme.amberText),
                      modifier = Modifier.testTag(FAILED_RETRY).tapping {
                        pinOnSend(again.text)
                        actions.retryFailed(tafId) { text -> unpin(); following = true; setDraft(text) }
                      }.padding(vertical = 6.dp),
                    )
                  }
                  // Not sent: why, and Retry or Edit, under the words as written (kiff/tafs#476).
                  state.chatUnsent[tafId]?.firstOrNull { it.message == item.message }?.let { unsent ->
                    // Refused for a halt that still holds: Resume, behind the owner's confirmation, in Retry's place.
                    val halted = state.unsentWaitsOnHalt(tafId, unsent)
                    NotSentLine(
                      unsent.why,
                      canRetry = !state.holdsRetry(tafId, unsent),
                      onRetry = { unpin(); following = true; actions.retryUnsent(tafId, unsent.message) },
                      onEdit = { actions.editUnsent(tafId, unsent.message)?.let(setDraft) },
                      onDelete = { actions.deleteUnsent(tafId, unsent.message) },
                      resume = if (halted) ({ actions.resumeHalt(if (unsent.halted == bot.tafs.app.model.Unsent.HALTED_TAF) tafId else null) }) else null,
                      resumeError = if (halted) state.haltError else null,
                    )
                  }
                }
                is ChatItem.Held -> HeldCallCard(item.call, state, actions.answer)
                is ChatItem.Outcome -> OutcomeLine(Chat.outcomeText(item.entry), outcomeTone(item.entry.kind), outcomeMark(item.entry.kind))
                is ChatItem.Card -> if (item.shown.component.type == bot.tafs.app.app.ASK_COACH && tafId != COACH_CHAT) {
                  AskCoachCard(item.shown.component.text.orEmpty(), if (state.manages(tafId) && state.mayUseCoach) ({ words -> actions.askCoachAbout(tafId, words) }) else null)
                } else if (tafId != COACH_CHAT) {
                  // The loop card (Read on / Stop here), in tafs cloud's words: answered for this taf's run, never Coach's.
                  CoachCard(item.shown, state, CoachActions(answer = { type, id, answer -> actions.answerCard(tafId, type, id, answer) }, approve = { _, _, _ -> }, cancelSignIn = {}))
                } else CoachCard(item.shown, state, actions.coach)
                is ChatItem.Drive -> DriveRecapCard(item.recap)
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
        if (live != null || thinking) item(key = Chat.liveKey(items, live, WORKING)) {
          Box(Modifier.fillMaxWidth().heightIn(min = 28.dp), contentAlignment = Alignment.CenterStart) {
            if (live != null) LiveWork(live) else TypingDots()
          }
        }
        // What tafs cloud holds of the owner's in this chat (docs/chat.md): it goes by itself once the taf is done.
        state.chatSteering[tafId]?.held?.let { held -> item(key = "held") { SteeredWords(held.text, ChatWords.HELD) } }
        // How the latest run ended, when it says so here: stopped part-way (with Retry), stopped by the owner, or at a cap.
        if (!thinking) state.chatEnded[tafId]?.let { end ->
          item(key = "ended") { EndedLine(end, taf?.name ?: Words.Chat.itFallback, onRetry = { text -> pinOnSend(text); actions.sendChat(text, tafId, false) { unpin(); following = true } }) }
        }
        // Room under a sent message at the top, so a short one reaches the top at once; it stays once the run ends, until the next.
        if (pinned != null || pinText != null) item(key = ROOM) {
          Box(Modifier.fillMaxWidth().height(with(LocalDensity.current) { room.toDp() }))
        }
      }

      // The held call waiting for the owner, when it's out of view; and the way back down.
      Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val waiting = items.lastOrNull { it is ChatItem.Held }
        if (waiting is ChatItem.Held) {
          val index = items.indexOf(waiting)
          val visible = listState.layoutInfo.visibleItemsInfo.any { it.key == waiting.id }
          if (!visible) {
            NeedsYouPill(Words.NeedsYou.title + " · " + waiting.call.proposed.summary) {
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
              .tapping {
                // The way to the end lets the pinned message go: the thread follows what arrives again.
                unpin()
                following = true
                scope.launch { toEnd(animated = true) }
              }
              .semantics { contentDescription = Words.Chat.jumpToLatest },
            contentAlignment = Alignment.Center,
          ) {
            Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = null, tint = Theme.ink, modifier = Modifier.size(16.dp))
          }
        }
      }
    }

    // Refused for no plan: tafs cloud's words stay as a prompt, with Plan & usage one tap away, until the person taps either
    // button or sends again (kiff/tafs#2190). Choose a plan puts it away too, so it's gone when they come back with a plan.
    // Coach's goes there from Coach's own sheet, which closes on the way.
    val planPrompt = state.planPrompt(tafId)
    if (planPrompt != null) {
      val openPlan = if (tafId == COACH_CHAT) actions.coach.openPlan else actions.openPlan
      PlanPrompt(planPrompt, onPlan = { actions.dismissPlanPrompt(tafId); openPlan() }, onDismiss = { actions.dismissPlanPrompt(tafId) })
    } else state.chatErrors[tafId]?.let { error ->
      Text(error, style = scaled(13, color = Theme.no), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp))
    }
    // Coach takes no typing until its tools are approved (docs/coach.md, "Coach's tools step"): the box gives way to one line
    // in tafs cloud's words, which opens the step where there is one to open.
    // A lock never opens the box: with no words, it says the app's own line.
    val lock = LocalComposerLock.current?.takeIf { tafId == COACH_CHAT }
    if (lock != null) {
      val words = lock.words.ifBlank { Words.Coach.Tools.writeAfter }
      Text(
        words,
        style = scaled(14, color = if (lock.open != null) Theme.amberText else Theme.soft),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
          .let { m -> lock.open?.let { open -> m.tapping(onClickLabel = words, onClick = open) } ?: m }
          .padding(horizontal = 16.dp, vertical = 12.dp).testTag(COMPOSER_LOCKED),
      )
      return@Column
    }
    val canAttach = state.canAttach(tafId)
    if (canAttach) FileTray(tafId, state, actions.files)
    Composer(
      name = taf?.name,
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
      // It goes to the top and stays while the taf works and answers below it (docs/chat.md, "The sent message stays on top").
      pinOnSend(text)
      // As sent: if it didn't go (too long), it comes back to the box, and the chat follows its end again.
      actions.sendChat(text, tafId, true) { notSent ->
        unpin()
        following = true
        if (state.drafts[tafId].isNullOrEmpty()) setDraft(notSent)
      }
    }
  }
}

@Composable
private fun Composer(
  name: String?,
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
    Column(
      Modifier.widthIn(max = Readable).fillMaxWidth().padding(8.dp)
        .clip(RoundedCornerShape(20.dp)).background(Theme.panel)
        .border(BorderStroke(1.dp, Theme.line), RoundedCornerShape(20.dp)).padding(8.dp),
    ) {
      BasicTextField(
        value = draft,
        onValueChange = driveTyped(onDraft),
        textStyle = scaled(16, color = Theme.ink),
        cursorBrush = SolidColor(Theme.amber),
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
          Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).heightIn(min = 20.dp)) {
            if (draft.isEmpty()) Text(if (thinking) Words.Chat.writeNext else name?.let { Words.Chat.messageTaf(it) } ?: Words.Chat.messageYourTaf, style = scaled(16, color = Theme.dim))
            field()
          }
        },
      )
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      attach?.invoke()
      androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
      dictation?.let { MicButton(it, draft, onDraft) }
      if (onStop != null && empty) {
        // While the taf works and the box is empty, Send is Stop: its run is cancelled.
        Box(
          Modifier.requiredSize(40.dp).clip(CircleShape).background(Theme.ink).tapping(onClick = onStop).semantics { contentDescription = Words.Chat.stop },
          contentAlignment = Alignment.Center,
        ) {
          Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(Theme.background))
        }
      } else Box(
        Modifier
          .requiredSize(40.dp)
          // The whole button dims while it can't send, as iOS's does: circle and arrow together, in one layer.
          .alpha(if (empty || sending || holdsSend) 0.4f else 1f)
          .clip(CircleShape)
          .brandBackground()
          .tapping(enabled = !empty && !sending && !holdsSend) {
            dictation?.stop()
            focus.clearFocus()
            onSend()
          }
          .semantics { contentDescription = Words.Common.send },
        contentAlignment = Alignment.Center,
      ) {
        Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
      }
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
private fun MessageRow(
  message: ChatMessage,
  tafId: String,
  files: FileActions,
  onEdit: (String) -> Unit,
  onResend: (String) -> Unit,
  onReport: (suspend (String, String?) -> ReportResult)? = null,
) {
  // Where a job run's result went, tafs cloud's one line, above the taf's own words (docs/chat.md).
  message.delivered?.takeIf { message.from == ChatMessage.From.taf }?.let { line ->
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(line, style = scaled(13, color = Theme.soft))
      MessageRow(message.copy(delivered = null), tafId, files, onEdit, onResend, onReport)
    }
    return
  }
  // A connection its work asked for that was refused: tafs cloud's lines, under the taf's own words and never folded, so the
  // person sees why and what to do next without opening the steps (docs/chat.md). The step's words in the failure colour.
  message.refused?.takeIf { message.from == ChatMessage.From.taf }?.let { refused ->
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      MessageRow(message.copy(refused = null), tafId, files, onEdit, onResend, onReport)
      RefusedLines(refused)
    }
    return
  }
  // What the run did, counted from the record: tafs cloud's one line, under the taf's own words (docs/chat.md).
  message.counted?.takeIf { message.from == ChatMessage.From.taf }?.let { line ->
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      MessageRow(message.copy(counted = null), tafId, files, onEdit, onResend, onReport)
      Text(line, style = scaled(13, color = Theme.soft))
    }
    return
  }
  val door = message.door
  if (door != null && message.kind != ChatMessage.Kind.kickoff) {
    // A door's message, or its answer: marked with where it came from and how it went back.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.from == ChatMessage.From.owner) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (message.from == ChatMessage.From.owner) DoorStamp(door, fromOwner = true)
      MessageBody(message, tafId, files, onEdit, onResend, onReport)
      if (message.from == ChatMessage.From.taf) DoorStamp(door, fromOwner = false)
    }
  } else {
    MessageBody(message, tafId, files, onEdit, onResend, onReport)
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBody(
  message: ChatMessage,
  tafId: String,
  files: FileActions,
  onEdit: (String) -> Unit,
  onResend: (String) -> Unit,
  onReport: (suspend (String, String?) -> ReportResult)?,
) {
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
            // Its menu on a long press, and TalkBack's long press (double-tap and hold) too. A tap does nothing, so none is offered:
            // TalkBack would announce a button that does nothing.
            .pointerInput(Unit) { detectTapGestures(onLongPress = { menu = true }) }
            .semantics { onLongClick { menu = true; true } }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        // A sent message's menu (kiff/tafs#285): send it again (its words only), change it first, or copy it.
        DriveMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Theme.panel) {
          DropdownMenuItem(text = { Text(Words.Chat.sendAgain) }, onClick = { menu = false; onResend(message.text) })
          DropdownMenuItem(text = { Text(Words.Chat.editAndSend) }, onClick = { menu = false; onEdit(message.text) })
          DropdownMenuItem(text = { Text(Words.Common.copy) }, onClick = {
            menu = false
            scope.launch { clipboard.setClipEntry(ClipEntry(bot.tafs.app.model.Res.clip("message", message.text))) }
          })
        }
      }
      // The files sent with it, each opened full size.
      if (message.attachments.isNotEmpty()) MessageFiles(message.attachments, tafId, files)
      // Written while a card waited: these words were the card's answer (`answers`).
      if (message.answers != null) Text(Chat.ANSWERED_CARD, style = scaled(12, color = Theme.dim))
      MessageTime(message)
    }
  } else if (message.kind == ChatMessage.Kind.kickoff) {
    // A run's kickoff shows as a small note, not a bubble.
    Text(
      Words.Chat.runStarted(shortTime(message.at)) + " · ${message.text}",
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
      // Full width, with no bubble, as the taf writes (#58). Copy under it; Report under More actions, once tafs cloud has an id for it.
      AnswerWithActions(message.text, onReport?.takeIf { message.serverId != null }) {
        PageTurnText(message.text, asks = message.pageTurn)
      }
      MessageTime(message)
    }
  }
}

/** When a message was sent, small, under it (row 44, C): in the device's 12- or 24-hour setting, the app's language. */
@Composable
private fun MessageTime(message: ChatMessage) {
  val is24 = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
  Text(messageTime(message.at, is24), style = scaled(11, color = Theme.dim), modifier = Modifier.testTag(MESSAGE_TIME))
}

internal const val DAY_HEADER = "day-header"
/** Try again under a failed run's line, for tests. */
internal const val FAILED_RETRY = "failed-retry"
internal const val MESSAGE_TIME = "message-time"

/** A refused connection's lines, each as tafs cloud wrote it: the step's in the failure colour, then why and what to do next. */
@Composable
internal fun RefusedLines(refused: Refused) {
  Column(
    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("refused"),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(refused.words, style = scaled(13, FontWeight.Medium, Theme.no))
    refused.why?.let { Text(it, style = scaled(13, color = Theme.soft)) }
    Text(refused.next, style = scaled(13, color = Theme.soft))
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
      modifier = Modifier.heightIn(min = 28.dp).tapping(enabled = steps.isNotEmpty()) { open = !open }.padding(vertical = 4.dp),
    )
    // A failed step in the failure colour, with tafs cloud's reason under it when it sent one; nothing of the app's own.
    if (open) steps.forEach { step ->
      Text("• " + step.words + if (step.notMade) " · " + Words.Chat.notMade else "", style = scaled(12, color = if (step.failed) Theme.no else Theme.soft))
      step.why?.let { Text(it, style = scaled(12, color = Theme.dim), modifier = Modifier.padding(start = 10.dp)) }
    }
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
    // An answer still being written can't yet say whether its turn read a page (a resumed stream carries no flag): its links
    // ask first, until the thread read says.
    if (writing) PageTurnText(live.text, asks = true, live = true)
  }
}

/** How the chat's latest run ended, said under it: part-way with what went through and Retry, stopped by the owner, or at a cap. */
@Composable
private fun EndedLine(end: ChatEnd, taf: String, onRetry: (String) -> Unit) {
  val words = when (end) {
    is ChatEnd.Failed -> AppModel.failedWords(taf, end.done, end.words)
    ChatEnd.Stopped -> Words.Chat.youStopped(taf)
    is ChatEnd.Capped -> AppModel.capWords(end.stop, taf) ?: return
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(words, style = scaled(14, color = Theme.ink))
    if (end is ChatEnd.Failed && end.retryable) {
      Text(Words.Chat.retry, style = scaled(14, FontWeight.SemiBold, Theme.amberText), modifier = Modifier.tapping { onRetry(end.retry) }.padding(vertical = 6.dp))
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
    Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { contentDescription = Words.Chat.answering },
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
      Text(mark, style = scaled(10, FontWeight.Bold, Theme.panel))
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
      .tapping(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(Theme.amber))
    Text(text, style = scaled(14, FontWeight.SemiBold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
  }
}

/**
 * Under an owner's message that wasn't sent: why, then Delete (gone for good), Edit (back to the box) and Retry (sends it once). Retry waits, greyed,
 * while it can't go yet ([AppState.holdsRetry]).
 */
@Composable
private fun NotSentLine(
  why: String,
  canRetry: Boolean,
  onRetry: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  /** Refused for a halt that holds: Resume takes Retry's place, and Retry comes back once it's resumed. */
  resume: (suspend () -> Unit)? = null,
  resumeError: String? = null,
) {
  val scope = rememberCoroutineScope()
  var resuming by remember { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(listOf(ChatWords.NOT_SENT, why).filter { it.isNotBlank() }.joinToString(" · "), style = scaled(13, color = Theme.no), textAlign = TextAlign.End)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      // Delete takes the owner's words away for good: Coach never taps it (docs/drive.md).
      TextButton(onClick = onDelete, modifier = Modifier.confirms()) { Text(ChatWords.DELETE, style = scaled(14, color = Theme.soft)) }
      TextButton(onClick = onEdit) { Text(ChatWords.EDIT, style = scaled(14, color = Theme.soft)) }
      if (resume != null) ApprovalGate {
        // Resuming confirms behind the fingerprint, as the halt row does: Coach never taps it.
        TextButton(onClick = { scope.launch { resuming = true; resume(); resuming = false } }, enabled = !resuming, modifier = Modifier.confirms()) {
          Text(if (resuming) Words.Taf.Halt.resuming else Words.Taf.Halt.resumeWith(LocalBiometry.current), style = scaled(14, color = Theme.ink))
        }
      } else {
        TextButton(onClick = onRetry, enabled = canRetry) { Text(ChatWords.RETRY, style = scaled(14, color = if (canRetry) Theme.ink else Theme.soft)) }
      }
    }
    resumeError?.let { Text(it, style = scaled(13, color = Theme.no), textAlign = TextAlign.End) }
  }
}

/** The owner's words tafs cloud holds, as their own message, dimmed, with [line] under them. */
@Composable
private fun SteeredWords(text: String, line: String?) {
  Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      text,
      style = scaled(15, color = Color.White),
      modifier = Modifier.alpha(0.7f).clip(RoundedCornerShape(18.dp)).brandBackground().padding(horizontal = 10.dp, vertical = 8.dp),
    )
    line?.let { Text(it, style = scaled(13, color = Theme.soft), textAlign = TextAlign.End) }
  }
}

/**
 * A taf's card handing a setting to Coach (kiff/tafs#476, gap 15): the owner's request in the taf's words, and Ask Coach, which opens
 * Coach on this taf with them in the box to send, as written or changed. Nothing is sent. Someone who can't change the taf reads it alone.
 */
@Composable
private fun AskCoachCard(text: String, ask: ((String) -> Unit)?) {
  if (text.isBlank()) return
  bot.tafs.app.design.Panel {
    Text(text, style = scaled(15, color = Theme.ink))
    ask?.let { bot.tafs.app.design.OutlineButton(bot.tafs.app.model.ChatWords.ASK_COACH, { it(text) }, Modifier.fillMaxWidth()) }
  }
}

/** Whether a message in the thread is the one the owner sent with [sent]: the same words, or them after its files' lines. */
internal fun sentWords(stored: String, sent: String): Boolean = stored == sent || stored.endsWith("\n\n" + sent)

/** Where a link that asks first goes: the chat's one [LinkConfirm]. */
internal val LocalAskLink = androidx.compose.runtime.staticCompositionLocalOf<((url: String, live: Boolean) -> Unit)?> { null }

/**
 * Holds the chat's "Open this link?" (docs/chat.md): the whole address, Open and Cancel. It's kept here, above the messages,
 * so a live answer that finishes while it's up (drawn again as a message) doesn't take it away under the person's finger.
 */
@Composable
internal fun LinkConfirm(content: @Composable () -> Unit) {
  var confirming by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
  var fromLive by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
  val uri = androidx.compose.ui.platform.LocalUriHandler.current
  androidx.compose.runtime.CompositionLocalProvider(LocalAskLink provides { url: String, live: Boolean -> fromLive = live; confirming = url }) { content() }
  confirming?.let { url ->
    bot.tafs.app.drive.DriveAlert(
      onDismissRequest = { confirming = null },
      // A link that won't open says so; one that will says where it came from: a page Coach read, or an answer still coming.
      title = { Text(if (linkOpens(url)) Words.Chat.linkConfirmTitle else Words.Chat.linkBlockedTitle) },
      text = { Text(linkQuestionBody(url, fromLive), modifier = Modifier.testTag("link-confirm-body")) },
      // Open only for an address that opens a web page over https; anything else (another app's scheme, tel:, http:) gets the
      // address and Cancel alone, so a page can't steer Coach into starting another app's action.
      confirmButton = {
        if (linkOpens(url)) {
          androidx.compose.material3.TextButton({
            confirming = null
            runCatching { uri.openUri(url) }
          }, Modifier.confirms()) { Text(Words.Chat.linkConfirmOpen) }
        } else {
          androidx.compose.material3.TextButton({ confirming = null }) { Text(Words.Common.cancel, color = Theme.soft) }
        }
      },
      dismissButton = if (linkOpens(url)) ({ androidx.compose.material3.TextButton({ confirming = null }) { Text(Words.Common.cancel, color = Theme.soft) } }) else null,
      containerColor = Theme.panel,
    )
  }
}

/**
 * The question's body with the whole address set apart left to right (U+2066 … U+2069) in every language, its own direction
 * characters taken out, so a right-to-left sentence around it can't reorder its `/ ? . 0-9` parts: what's shown is what opens.
 * It's put in after formatting, since `Res.s` sets a value apart by its first letter (U+2068) and takes out any direction
 * character.
 */
internal fun linkConfirmBody(url: String): String = withAddress(url, Words.Chat::linkConfirmBody)

/** The body for [url]: one that won't open says why; one that will says it's from a page Coach read, or an answer still coming. */
internal fun linkQuestionBody(url: String, live: Boolean): String = when {
  !linkOpens(url) -> withAddress(url, Words.Chat::linkBlockedBody)
  live -> withAddress(url, Words.Chat::linkConfirmBodyLive)
  else -> linkConfirmBody(url)
}

private fun withAddress(url: String, words: (String) -> String): String {
  val shown = "\u2066${bot.tafs.app.model.Res.withoutDirection(url)}\u2069"
  return words("\uE000").replace("\u2068\uE000\u2069", shown).replace("\uE000", shown)
}

/**
 * Whether "Open this link?" offers Open for [url]: an `https` address with a host, and nothing in it that could make what's
 * shown differ from what opens (direction characters, other controls, spaces).
 */
internal fun linkOpens(url: String): Boolean {
  if (url.any { it.isWhitespace() || it.isISOControl() } || bot.tafs.app.model.Res.withoutDirection(url) != url) return false
  val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
  return u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrEmpty()
}

/**
 * A taf's words, as the chat draws them. When [asks] (an answer from a turn that read a page in the person's Chrome, or one still
 * being written), a link doesn't open on the tap: it goes to the chat's [LinkConfirm]. Otherwise a plain https link opens on the
 * tap, and any other goes there too ([linkAsks]).
 */
@Composable
internal fun PageTurnText(text: String, asks: Boolean, live: Boolean = false) {
  val ask = LocalAskLink.current ?: { _, _ -> }
  val uri = androidx.compose.ui.platform.LocalUriHandler.current
  MarkdownText(text, size = 16, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), onLink = { url ->
    if (linkAsks(url, asks)) ask(url, live) else runCatching { uri.openUri(url) }
  })
}

/**
 * Whether a link in a taf's words asks before it opens: always in an answer from a page turn or one still being written, and in
 * any answer when it isn't a plain https address ([linkOpens]), so no chat link starts another app's action (a `tel:`, a bank's
 * scheme) or goes over plain http on a tap. That question then has no Open.
 */
internal fun linkAsks(url: String, pageTurn: Boolean): Boolean = pageTurn || !linkOpens(url)

/** A chat refused for no plan: tafs cloud's words, Choose a plan (Plan & usage), and Not now. */
@Composable
private fun PlanPrompt(words: String, onPlan: () -> Unit, onDismiss: () -> Unit) {
  Panel(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp).testTag(PLAN_PROMPT), stroke = Theme.amberText.copy(alpha = 0.45f)) {
    Text(words, style = scaled(14, FontWeight.SemiBold, Theme.amberText))
    OutlineButton(Words.Usage.choosePlan, onPlan, Modifier.fillMaxWidth(), color = Theme.amberText)
    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(Words.Common.notNow, style = scaled(14, color = Theme.soft)) }
  }
}

/** The prompt a chat refused for no plan shows, for tests. */
internal const val PLAN_PROMPT = "plan-prompt"
