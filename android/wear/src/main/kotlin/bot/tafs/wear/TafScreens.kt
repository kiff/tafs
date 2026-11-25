package bot.tafs.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.SwitchButtonDefaults
import androidx.wear.compose.material3.Text
import bot.tafs.watchlink.CardLine
import kotlinx.coroutines.launch

/*
 * Wear v2, step (v) (docs/watch-wear.md, Version 2): a taf's page, its settings one swipe left, and Choose which to halt from the
 * menu. Everything here is the phone's words (`taf`, `tafs`) or app/strings'; what's halted, and whether it went through, is the
 * phone's answer, never the watch's guess.
 */

/** Where Open on phone goes from a taf (row 28): that taf on the phone, by the phone's own id (`Alerts.openedFromWatch`). */
internal fun onPhone(open: WatchModel.OpenTaf): String = "taf/" + open.tafId

/**
 * A taf's page, read from the phone (`taf`): its name and state, what it does, its Card with each rule on a line, what of it waits
 * for you (a tap goes to the Inbox), and, halted, that resuming is the phone's. Its settings are the next page, one swipe left (or
 * Its settings), with Open on phone and Halt this taf, apart at the end, asked once more. [pager] is its own unless a test opens it
 * on the settings.
 */
@Composable
internal fun TafScreen(
  open: WatchModel.OpenTaf,
  outcome: WatchModel.Outcome?,
  act: Actions,
  openInbox: () -> Unit,
  chat: Boolean = WATCH_TAF_CHAT && WATCH_V2,
  pager: PagerState = rememberPagerState { if (chat) 3 else 2 },
  /** tafs cloud said chatting with a taf isn't on for this account (row 13): the chat says so in place of its send bar. */
  chatOff: Boolean = false,
) {
  var confirming by remember(open.tafId) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  // Back: from the question to its settings, from its settings to its page, and from its page to the pages.
  BackHandler {
    when {
      confirming -> confirming = false
      pager.currentPage > 0 -> scope.launch { pager.animateScrollToPage(0) }
      else -> act.closeTaf()
    }
  }
  if (confirming) {
    return HaltQuestion(Words.Watch.haltThis, open.name, Words.Halt.Explain.runningOne, halt = {
      confirming = false
      act.halt(open.tafId)
    }, cancel = { confirming = false })
  }
  // While its chat is answering, on screen: the answer followed through the phone, bounded, and stopped when the screen goes.
  val answering = chat && open.chat.answering
  androidx.compose.runtime.LaunchedEffect(open.tafId, answering) { if (answering) act.followAnswer(open.tafId) }
  HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
    // 1A: its chat first, then its page, then its settings; with the switch off, its page and settings as before.
    val page = if (chat) i - 1 else i
    // The chat opens on its newest message, with the send bar under it, as a chat app does; older ones are above (the owner's call).
    val latest = if (page < 0) tafChatFocus(open) else 0
    val list = rememberScalingLazyListState(initialCenterItemIndex = latest)
    if (page < 0) androidx.compose.runtime.LaunchedEffect(latest) { list.scrollToItem(latest) }
    ScalingLazyColumn(
      Modifier.fillMaxWidth(),
      state = list,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      when {
        page < 0 -> tafChat(open, act, chatOff)
        page == 0 -> tafPage(open, outcome, act, openInbox, settings = { scope.launch { pager.animateScrollToPage(pager.pageCount - 1) } })
        else -> tafSettings(open, outcome, act, halt = { confirming = true })
      }
    }
  }
}

private fun ScalingLazyListScope.tafPage(
  open: WatchModel.OpenTaf,
  outcome: WatchModel.Outcome?,
  act: Actions,
  openInbox: () -> Unit,
  settings: () -> Unit,
) {
  item { ListHeader { Text(open.name, textAlign = TextAlign.Center) } }
  val page = (open.read as? WatchModel.TafRead.Read)?.page
  page?.let {
    item {
      Text(it.state, style = MaterialTheme.typography.bodySmall, color = if (it.halted) Look.danger else Look.dim, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth())
    }
  }
  outcome?.let { item { OutcomeCard(it, act) } }
  if (page == null) {
    notReadTaf(open, act)
  } else {
    label(Words.Watch.whatItDoes)
    line(page.job)
    if (page.cards.isNotEmpty()) {
      label(Words.Card.itsCard)
      for (card in page.cards) item { CardFace(card) }
    }
    label(Words.Chat.waitingForYou)
    if (page.waiting > 0) {
      // Its held calls are in the Inbox, with Refuse and Approve on your phone.
      item { Button(onClick = openInbox, modifier = Modifier.fillMaxWidth(), colors = heldButton()) { Text(Words.Watch.needsYouCount(page.waiting)) } }
    } else {
      line(Words.Watch.nothingWaits)
    }
    if (page.halted) resumeOnPhone(open, act)
  }
  item { Spacer(Modifier.height(8.dp)) }
  item { Button(onClick = settings, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text("${Words.Watch.itsSettings} ›") } }
  item {
    Text(Words.Watch.swipeSettings, style = MaterialTheme.typography.bodySmall, color = Look.dim, textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth())
  }
  item { Button(onClick = act.closeTaf, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.close) } }
}

/** The page before the phone has answered, or when it can't, as v1 says it. */
private fun ScalingLazyListScope.notReadTaf(open: WatchModel.OpenTaf, act: Actions) {
  when (val read = open.read) {
    WatchModel.TafRead.Asking -> line(Words.Watch.askingPhone)
    WatchModel.TafRead.SignedOut -> line(Words.Watch.signInOnPhone)
    // The phone out of reach or slow: the calm linking line, and the page read again by itself while it shows (row 30, call 6).
    is WatchModel.TafRead.PhoneUnreachable -> if (read.why == LinkFailure.NoApp) line(read.why.line) else item {
      androidx.compose.runtime.LaunchedEffect(open.tafId) {
        for (pause in RETRY_BACKOFF_MS + List(LINKING_TRIES) { LINKING_RETRY_MS }) {
          kotlinx.coroutines.delay(pause)
          act.rereadTaf()
        }
      }
      Text(Words.Watch.linking, style = MaterialTheme.typography.bodySmall, color = Look.dim, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
    }
    WatchModel.TafRead.UpdatePhone -> line(Words.Watch.updatePhone)
    is WatchModel.TafRead.Failed -> {
      read.words?.let { line(it) }
      item { Button(onClick = { act.openOnPhone(onPhone(open)) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.openOnPhone) } }
    }
    is WatchModel.TafRead.Read -> Unit
  }
}

/**
 * A taf's chat (the owner's 1A): its state, the last turns as the phone shows them, this visit's turns with its answers, then
 * Message {taf}, Type and Quick reply. Halted, nothing is sent: Resume on your phone. Paused, the message still goes, and it
 * answers now (2A). A long answer shows its first part with Read more, read whole only on the tap. Nothing here is kept.
 */
private fun ScalingLazyListScope.tafChat(open: WatchModel.OpenTaf, act: Actions, chatOff: Boolean = false) {
  item { ListHeader { Text(open.name, textAlign = TextAlign.Center) } }
  when {
    open.halted -> Unit
    open.paused -> item { Centred(Words.Watch.pausedStillAnswers, Look.held) }
    else -> (open.read as? WatchModel.TafRead.Read)?.page?.state?.let { item { Centred(it, Look.dim) } }
  }
  // Each message's time under it, and a header where a day starts (row 44, C). A line tafs cloud sent no time for has none.
  var previous: java.time.LocalDate? = null
  fun header(at: java.time.Instant) {
    val day = WatchChatTime.dayOf(at)
    if (bot.tafs.watchlink.ChatTime.startsDay(previous, day)) item { DayHeader(WatchChatTime.day(day)) }
    previous = day
  }
  shownLines(open).forEach { line ->
    val at = WatchChatTime.instantOf(line.at)
    at?.let(::header)
    item { ChatBubble(line.text, mine = line.mine, foot = if (line.more) Words.Watch.moreOnPhone else null, time = at?.let { WatchChatTime.time(it) }) }
    // A cut answer the watch can't read whole (no request of its own here): the phone opens on this chat, and the watch stays.
    if (line.more && !line.mine) item { OpenChatOnPhone(open, act) }
  }
  for (turn in open.chat.turns) {
    val at = java.time.Instant.ofEpochMilli(turn.at)
    header(at)
    // Its own words, with "HH:mm ✓" under them: in the thread only once tafs cloud took it.
    item { ChatBubble(turn.said, mine = true, time = WatchChatTime.time(at), sent = true) }
    item { TafAnswer(turn, open.name, open.chat.whole[turn.ask.id], act, waitedOut = open.chat.waitedOut, onPhone = onPhone(open)) }
  }
  if (open.halted) {
    item { Centred(Words.Halt.Explain.halted, Look.danger) }
    item { Button(onClick = { act.openOnPhone(onPhone(open)) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text("↗ ${Words.Watch.resumeOnPhone}") } }
    return
  }
  // The account's switch off (row 13): said where the send bar would be, before anything is recorded or typed, and Open on phone
  // as an extra, never as what Send ends in.
  if (chatOff) {
    item { Centred(Words.Watch.askNotOn, Look.held) }
    item { Button(onClick = { act.openOnPhone(onPhone(open)) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.openOnPhone) } }
    return
  }
  // The send bar, as in a phone's chat: a round keyboard and a round microphone, named for TalkBack ("Message {taf}"); the quick
  // reply under it is its own words, so it stays a line of text.
  item {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.CenterHorizontally),
      verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      RoundIcon(Words.Watch.type, { act.typeTaf(open.tafId) }, TAF_TYPE, size = 44.dp) { keyboardGlyph(it) }
      RoundIcon(Words.Chat.messageTaf(open.name), { act.messageTaf(open.tafId) }, TAF_TALK, size = 52.dp, container = Look.send, content = Look.onSend) { micGlyph(it) }
    }
  }
  for (line in quickReplies().take(1)) item {
    Button(onClick = { act.quickTaf(open.tafId, line) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(line, maxLines = 2) }
  }
}

/**
 * Where a taf's chat opens: the index of what comes after its newest message (the send bar, or why nothing is sent), so that
 * message sits just above it, as [tafChat] lays the items out.
 */
internal fun tafChatLatest(open: WatchModel.OpenTaf): Int {
  var n = 1
  if (!open.halted && (open.paused || (open.read as? WatchModel.TafRead.Read)?.page?.state != null)) n++
  var previous: java.time.LocalDate? = null
  fun header(at: java.time.Instant) {
    val day = WatchChatTime.dayOf(at)
    if (bot.tafs.watchlink.ChatTime.startsDay(previous, day)) n++
    previous = day
  }
  shownLines(open).forEach { line ->
    WatchChatTime.instantOf(line.at)?.let(::header)
    n++
    if (line.more && !line.mine) n++
  }
  for (turn in open.chat.turns) {
    header(java.time.Instant.ofEpochMilli(turn.at))
    n += 2
  }
  return n
}

/**
 * Where a taf's chat is centred: with a message sent from the watch, the line under its newest one ("{taf} is answering", or its
 * answer), so the person's words just sent sit whole above it, never cut by the top edge (the owner's Galaxy Watch 5 re-test);
 * else what comes after the newest message ([tafChatLatest]).
 */
internal fun tafChatFocus(open: WatchModel.OpenTaf): Int = tafChatLatest(open).let { if (open.chat.turns.isNotEmpty()) it - 1 else it }

/** The phone's lines of a taf's chat, less those of the messages sent from this watch, which their turns show ([KeptThreads.shownLines]). */
internal fun shownLines(open: WatchModel.OpenTaf): List<bot.tafs.watchlink.ChatLine> =
  (open.chat.history as? WatchModel.TafChat.History.Read)?.lines?.let { lines ->
    KeptThreads.shownLines(lines, open.chat.turns.map { KeptTurn(WatchModel.Said(it.said, it.at), it.ask) })
  }.orEmpty()

/** Open on phone, under a cut answer: the phone opens this taf's chat, where the whole answer is; the watch stays where it is. */
@Composable
private fun OpenChatOnPhone(open: WatchModel.OpenTaf, act: Actions) {
  Button(onClick = { act.openOnPhone(onPhone(open)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp), colors = neutralButton()) { Text(Words.Watch.openOnPhone) }
}

/** A line under a taf's name in its chat: its state, the paused line, or why nothing is sent. */
@Composable
private fun Centred(words: String, color: androidx.compose.ui.graphics.Color) {
  Text(words, style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
}

/** One turn's words: the person's on the right in their own colours, the taf's on the left, and what follows under them. */
@Composable
internal fun ChatBubble(text: String, mine: Boolean, foot: String? = null, time: String? = null, sent: Boolean = false) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalAlignment = if (mine) androidx.compose.ui.Alignment.End else androidx.compose.ui.Alignment.Start) {
    Column(
      Modifier.background(if (mine) Look.mineGround else Look.neutral, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      // The person's own words as they wrote them; a taf's, plain (no markdown on the watch).
      Text(if (mine) text else Plain.of(text), style = MaterialTheme.typography.bodySmall, color = if (mine) Look.onMineGround else Look.text)
      foot?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Look.dim) }
    }
    time?.let { if (sent) SentFoot(it, sent = true, Modifier.padding(horizontal = 8.dp)) else MessageTime(it) }
  }
}

/**
 * The taf's side of a turn: Sent, then "{taf} is answering", then its answer: its first part with Read more while there's more,
 * the whole once read, "The rest is on your phone" when it can't be; held, Approve on your phone (the watch never approves).
 */
@Composable
internal fun TafAnswer(turn: WatchModel.TafTurn, taf: String, whole: WatchModel.Whole?, act: Actions, waitedOut: Boolean = false, onPhone: String? = null) {
  val ask = turn.ask
  if (!ask.ended && !ask.held) {
    // Taken: the ✓ under the person's words says it; then "{taf} is answering" until the answer.
    if (ask.state != bot.tafs.watchlink.AskLine.ACCEPTED) {
      Text(Words.Chat.tafIsAnswering(taf), style = MaterialTheme.typography.labelSmall, color = Look.done, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp))
    }
    // Followed for two minutes with no answer yet: it still lands here when it comes; meanwhile, the phone has the chat.
    if (waitedOut && onPhone != null) {
      Button(onClick = { act.openOnPhone(onPhone) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.openOnPhone) }
    }
    return
  }
  val shown = Plain.of((whole as? WatchModel.Whole.Read)?.text ?: ask.line ?: ask.words)
  Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
    Column(
      Modifier.background(Look.neutral, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Text(taf, style = MaterialTheme.typography.labelSmall, color = Look.done)
      Text(shown, style = MaterialTheme.typography.bodySmall, color = Look.text)
      if (ask.held) Text(Words.Watch.approveOnPhone, style = MaterialTheme.typography.labelSmall, color = Look.held)
      when (footOf(ask, whole)) {
        WatchModel.Foot.ReadMore -> Button(onClick = { act.readMore(ask.id) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.readMore) }
        WatchModel.Foot.Reading -> Text(Words.Common.loading, style = MaterialTheme.typography.labelSmall, color = Look.dim)
        WatchModel.Foot.OnPhone -> Text(Words.Watch.moreOnPhone, style = MaterialTheme.typography.labelSmall, color = Look.dim)
        null -> Unit
      }
    }
    // The whole couldn't be read on the watch: the phone opens on this chat, where it is.
    if (footOf(ask, whole) == WatchModel.Foot.OnPhone && onPhone != null) {
      Button(onClick = { act.openOnPhone(onPhone) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.openOnPhone) }
    }
    // When the answer came, under it (row 44, C).
    WatchChatTime.instantOf(ask.updated)?.let { MessageTime(WatchChatTime.time(it)) }
  }
}

/** A taf's settings: Open on phone, then, apart at the end, Halt this taf; halted, that resuming is the phone's. */
private fun ScalingLazyListScope.tafSettings(open: WatchModel.OpenTaf, outcome: WatchModel.Outcome?, act: Actions, halt: () -> Unit) {
  item { ListHeader { Text(Words.Watch.settingsOf(open.name), textAlign = TextAlign.Center) } }
  outcome?.let { item { OutcomeCard(it, act) } }
  item { Button(onClick = { act.openOnPhone(onPhone(open)) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.openOnPhone) } }
  item { Spacer(Modifier.height(16.dp)) }
  if (open.halted) {
    resumeOnPhone(open, act)
  } else {
    item { Button(onClick = halt, colors = dangerButton(), modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.haltThis) } }
  }
}

/** Halted: resuming starts only new work, and it's the phone's (it asks for a fingerprint or face). */
private fun ScalingLazyListScope.resumeOnPhone(open: WatchModel.OpenTaf, act: Actions) {
  item {
    Text(Words.Halt.Explain.halted, color = Look.danger, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
  }
  item { Button(onClick = { act.openOnPhone(onPhone(open)) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text("↗ ${Words.Watch.resumeOnPhone}") } }
}

private fun ScalingLazyListScope.label(words: String) = item {
  Text(words, style = MaterialTheme.typography.labelMedium, color = Look.dim, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
}

/** One of its Cards as the phone's Card tab draws it: its title, its face's figure and line, then each rule on a line. */
@Composable
private fun CardFace(card: CardLine) {
  Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(card.title, style = MaterialTheme.typography.titleSmall)
      val face = listOfNotNull(card.figure, card.words).filter { it.isNotBlank() }
      if (face.isNotEmpty()) Text(face.joinToString(" · "))
      for (rule in card.rules) Text(rule, style = MaterialTheme.typography.bodySmall)
    }
  }
}

/** Asked once more on the wrist, before anything goes to the phone: what's halted, what halting does, Halt now and Cancel. */
@Composable
private fun HaltQuestion(title: String, what: String, explain: String, halt: () -> Unit, cancel: () -> Unit) {
  ScalingLazyColumn(Modifier.fillMaxWidth(), state = rememberScalingLazyListState(initialCenterItemIndex = 0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { ListHeader { Text(title, textAlign = TextAlign.Center) } }
    item { Text(what, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    item {
      Text(explain, style = MaterialTheme.typography.bodySmall, color = Look.dim, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
    }
    item { Button(onClick = halt, colors = dangerButton(), modifier = Modifier.fillMaxWidth()) { Text(Words.Halt.now) } }
    item { Button(onClick = cancel, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.cancel) } }
  }
}

/**
 * Choose which to halt, from the menu's Halt: All your tafs, then each taf not halted, each a switch; Halt now · N, then asked once
 * more. Every taf picked is Halt all ("Halt all your tafs?"); one or a few, each by name.
 */
@Composable
internal fun HaltPickScreen(pick: WatchModel.HaltPick, act: Actions) {
  if (pick.confirming) {
    val chosen = pick.chosen
    return HaltQuestion(
      title = if (pick.all) Words.Watch.haltAllQ else Words.Watch.haltPickTitle,
      what = if (pick.all) Words.Watch.allYourTafs else chosen.joinToString(", ") { it.name },
      explain = if (!pick.all && chosen.size == 1) Words.Halt.Explain.runningOne else Words.Halt.Explain.runningAll,
      halt = act.haltPicked,
      cancel = act.cancelPick,
    )
  }
  ScalingLazyColumn(Modifier.fillMaxWidth(), state = rememberScalingLazyListState(initialCenterItemIndex = 0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { ListHeader { Text(Words.Watch.haltPickTitle, textAlign = TextAlign.Center) } }
    item {
      SwitchButton(checked = pick.all, onCheckedChange = { act.togglePickAll() }, modifier = Modifier.fillMaxWidth(), colors = haltPickColors(),
        label = { Text(Words.Watch.allYourTafs) })
    }
    for (taf in pick.offered) item {
      SwitchButton(checked = taf.tafId in pick.picked, onCheckedChange = { act.togglePick(taf.tafId) }, modifier = Modifier.fillMaxWidth(), colors = haltPickColors(),
        label = { Text(taf.name) })
    }
    item { Spacer(Modifier.height(8.dp)) }
    item {
      val count = pick.chosen.size
      Button(onClick = act.askPicked, enabled = count > 0, colors = dangerButton(), modifier = Modifier.fillMaxWidth()) {
        Text(Words.Watch.haltNowCount(count))
      }
    }
    item { Button(onClick = act.cancelPick, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.cancel) } }
  }
}

/** A taf picked to halt: its switch in the red of halting, on the grey of a button that means nothing else. */
@Composable
private fun haltPickColors() = SwitchButtonDefaults.switchButtonColors(
  checkedContainerColor = Look.neutral,
  checkedContentColor = Look.onNeutral,
  checkedIconColor = Look.danger,
  checkedThumbColor = Look.onDanger,
  checkedThumbIconColor = Look.danger,
  checkedTrackColor = Look.danger,
  checkedTrackBorderColor = Look.danger,
)

/** What came of one taf's Halt, in the phone's words, for the line beside its name; null where there's nothing to add. */
internal fun wordsOf(outcome: WatchModel.Outcome): String? = when (outcome) {
  is WatchModel.Outcome.Done -> outcome.words
  is WatchModel.Outcome.NotDone -> outcome.words
  WatchModel.Outcome.AlreadyAnswered -> Words.Watch.alreadyAnswered
  WatchModel.Outcome.NeedsLock -> Words.Watch.needsLock
  is WatchModel.Outcome.PhoneUnreachable -> outcome.why.line
  is WatchModel.Outcome.Halting, is WatchModel.Outcome.Several -> null
}

/** Read more while there's more and it hasn't been read; nothing once it's whole; the rest on the phone if it can't be. */
internal fun footOf(ask: bot.tafs.watchlink.AskLine, whole: WatchModel.Whole?): WatchModel.Foot? = when (whole) {
  is WatchModel.Whole.Read -> null
  WatchModel.Whole.Reading -> WatchModel.Foot.Reading
  WatchModel.Whole.OnPhone -> WatchModel.Foot.OnPhone
  null -> if (ask.more) WatchModel.Foot.ReadMore else null
}
