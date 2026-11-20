package bot.tafs.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import bot.tafs.watchlink.AskLine
import kotlinx.coroutines.launch

/**
 * Wear OS v2 (docs/watch-wear.md, Version 2), from the build: on only in a test version ("-test") for the owner's watch on Play's
 * internal track, off in debug builds, the unit tests and any production build. It turns on for everyone, as one line in
 * wear/build.gradle.kts, once the owner has tried it and the server pieces it waits on are in.
 */
internal val WATCH_V2: Boolean = BuildConfig.WATCH_V2

/**
 * Chatting with a taf from the watch (docs/watch-wear.md; the owner's picks 1A, 2A and Read more, 20 Nov): a taf opens its own
 * chat, its page and settings after it in its pager. On since Wear 0.4.2 (400,000,007), once the privacy paragraph was live
 * (kiff/cloud#19, 20 Nov). tafs cloud's `TAFS_WATCH_TAF_CHAT` still decides per account: where it's off, a message gets
 * Open on phone, never Coach. It needs v2's review first.
 */
internal val WATCH_TAF_CHAT: Boolean = true

/** v2's pages, side by side, moved between with a swipe: Coach is home, since talking to Coach is what the wrist is for. */
internal enum class Page { Coach, Inbox, Tafs, Brief, Account }

/** The pages in order; Brief only with Your brief on the watch ([BRIEF_ON_WATCH]), which waits for the owner's yes. */
internal fun pages(briefOn: Boolean = BRIEF_ON_WATCH): List<Page> = Page.entries.filter { it != Page.Brief || briefOn }

/** Each page's name, in the menu and on its header: the phone's own word where the phone has the page. */
internal val Page.title: String
  get() = when (this) {
    Page.Coach -> Words.Coach.title
    Page.Inbox -> Words.Watch.Page.inbox
    Page.Tafs -> Words.Home.title
    Page.Brief -> Words.Watch.Page.brief
    Page.Account -> Words.Settings.title
  }

/**
 * What waits in the Inbox, for the count on Coach's home, when the Inbox can't be kept on this watch (locked, or no screen lock):
 * the calls Needs you holds and the results not opened yet, as the phone answered now. When it's kept, its unread lines count.
 */
internal fun unread(shown: WatchModel.Shown, ready: List<AskLine>): Int =
  ((shown as? WatchModel.Shown.Read)?.needsYou?.count ?: 0) + ready.size

/**
 * The v2 watch: the microphone's screens and a result's card take the whole face, as in v1; otherwise the pages, with the menu
 * over them when it's open. [pager] is the pages' own unless a caller (a test, the store screenshots) opens it on another.
 */
@Composable
internal fun WatchPages(
  shown: WatchModel.Shown,
  outcome: WatchModel.Outcome?,
  confirmingHaltAll: Boolean,
  voice: WatchModel.Voice?,
  result: AskLine?,
  working: List<AskLine>,
  ready: List<AskLine>,
  act: Actions,
  pages: List<Page> = pages(),
  pager: PagerState = rememberPagerState { pages.size },
  /** The message kept for later, shown on Coach's home with Delete. */
  kept: WatchModel.Draft? = null,
  timing: String? = null,
  /** The internal build's probe line on the review: what the recognizer did with the app's audio. */
  probe: String? = null,
  /** The Inbox kept on the watch (step iv); null when it can't be kept now (locked, no screen lock): what the phone answered now. */
  inbox: List<InboxItem>? = null,
  /** Step (v): the taf whose page is open, and Choose which to halt, from the menu (TafScreens.kt). */
  taf: WatchModel.OpenTaf? = null,
  haltPick: WatchModel.HaltPick? = null,
  /** Signed out on this watch alone (step vi): only that, and Sign in. */
  signedOutHere: Boolean = false,
  /** The calendar day, while it's open (step vi). */
  calendar: WatchModel.Reading<WatchModel.DayRead>? = null,
  /** Account's read from the phone (step vi). */
  account: WatchModel.Reading<bot.tafs.watchlink.WatchReply.AccountPage>? = null,
  /** Today's conversation with Coach from this watch, oldest first (W4). */
  thread: List<Pair<WatchModel.Said, AskLine>> = emptyList(),
) {
  if (signedOutHere) return SignedOutHereScreen(act)
  // The system's back (a swipe from the left edge) closes what's drawn over the pages, as its own Cancel or Close does, and never
  // the app; on the pages themselves it's the system's own (backOf).
  when {
    voice != null -> {
      BackHandler(onBack = backOf(voice, act))
      return VoiceScreenV2(voice, act, probe = probe)
    }
    result != null -> {
      BackHandler(onBack = act.closeResult)
      return ResultScreen(result, act, timing)
    }
    outcome is WatchModel.Outcome.Halting -> {
      // Nothing to cancel: the phone's answer is shown when it comes.
      BackHandler {}
      return HaltingScreen(outcome.taf)
    }
  }
  var menu by remember { mutableStateOf(false) }
  var quick by remember { mutableStateOf(false) }
  // A held call, the hand-off, the calendar or Sign out's question (AccountScreens.kt), over the pages.
  var over by remember { mutableStateOf<Over?>(null) }
  over?.let { o -> return OverScreen(o, shown, calendar, act, go = { over = it }, close = { over = null }) }
  if (quick) {
    BackHandler { quick = false }
    return QuickReplyScreen(pick = {
      quick = false
      act.quick(it)
    }, close = { quick = false })
  }
  val scope = rememberCoroutineScope()
  fun goTo(page: Page) {
    menu = false
    pages.indexOf(page).takeIf { it >= 0 }?.let { scope.launch { pager.animateScrollToPage(it) } }
  }
  // Choosing which to halt, over the menu it came from; a taf's page and its settings, over the pages.
  haltPick?.let {
    // From the question back to the choice, then to the menu, as Cancel.
    BackHandler(onBack = act.cancelPick)
    return HaltPickScreen(it, act)
  }
  taf?.let {
    return TafScreen(it, outcome, act, openInbox = {
      act.closeTaf()
      goTo(Page.Inbox)
    })
  }
  // Account is read from the phone each time it's the page shown.
  androidx.compose.runtime.LaunchedEffect(pager.currentPage) { if (pages.getOrNull(pager.currentPage) == Page.Account) act.readAccount() }
  if (menu) {
    BackHandler { menu = false }
    return MenuScreen(shown, pages, act, outcome, open = ::goTo, close = { menu = false })
  }
  Box(Modifier.fillMaxSize()) {
    HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
      ScalingLazyColumn(
        Modifier.fillMaxWidth(),
        // Coach's page opens on the microphone, with the conversation's newest turn just above it (W4); others at the top.
        state = rememberScalingLazyListState(initialCenterItemIndex = if (pages[i] == Page.Coach) thread.size else 0).also { list ->
          // A new turn (a send) brings the page back to the microphone, with that turn just above it (mac12's W4 note).
          if (pages[i] == Page.Coach) LaunchedEffect(thread.size) { if (thread.isNotEmpty()) list.scrollToItem(thread.size) }
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        // Room under the last item, so it scrolls clear of the page dots at the foot (qa-kiff's device read, #1409).
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 36.dp),
      ) {
        val page = pages[i]
        if (page == Page.Coach) {
          coachThread(thread, act)
          coachHome(shown, working, ready, act, kept, inbox, openInbox = { goTo(Page.Inbox) }, openMenu = { menu = true }, openQuick = { quick = true })
        } else {
          item { ListHeader { Text(page.title) } }
        }
        outcome?.let { item { OutcomeCard(it, act) } }
        when (page) {
          Page.Coach -> Unit
          Page.Account -> accountItems(account, act, signOut = { over = Over.SignOut })
          else -> if (notRead(shown, act)) {
            when (page) {
              Page.Inbox -> if (inbox != null) keptInboxPage(inbox, shown as WatchModel.Shown.Read, act, openCall = { over = Over.HeldCall(it.holdId) })
                else inboxPage(shown as WatchModel.Shown.Read, ready, act, openCall = { over = Over.HeldCall(it.holdId) })
              Page.Tafs -> { tafsPage(shown as WatchModel.Shown.Read, act); calendarDoor { over = Over.Calendar } }
              Page.Brief -> { briefPage(shown as WatchModel.Shown.Read, act); calendarDoor { over = Over.Calendar } }
            }
          }
        }
      }
    }
    HorizontalPageIndicator(pager, Modifier.align(Alignment.BottomCenter))
  }
}

/**
 * Back on the microphone's screens: Cancel, through the model, so a review's recording is erased (`WatchModel.voiceNow`); while the
 * words are being sent, nothing, since what tafs cloud answers is shown when it comes.
 */
internal fun backOf(voice: WatchModel.Voice, act: Actions): () -> Unit = if (voice is WatchModel.Voice.Sending) ({}) else act.closeVoice

/**
 * Coach's home: the microphone first, named for Coach, with the asks at work under it; Type and Quick reply, which open the review
 * as words heard do (step iii); how many wait in the Inbox (a tap goes there); the line that Coach gets words and never a voice;
 * and the menu at the foot. The last chats come with tafs cloud's chats read.
 */
private fun ScalingLazyListScope.coachHome(
  shown: WatchModel.Shown,
  working: List<AskLine>,
  ready: List<AskLine>,
  act: Actions,
  kept: WatchModel.Draft?,
  inbox: List<InboxItem>?,
  openInbox: () -> Unit,
  openMenu: () -> Unit,
  openQuick: () -> Unit,
) {
  item { AskHome(working.map { stateLine(it) to Look.coach }, act.listen, label = Words.Coach.ask) }
  // Typed words and quick replies go to the same review as words heard.
  item {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Button(onClick = act.type, modifier = Modifier.weight(1f), colors = neutralButton()) { Text(Words.Watch.type, maxLines = 1) }
      Button(onClick = openQuick, modifier = Modifier.weight(1f), colors = neutralButton()) { Text(Words.Watch.quickReply, maxLines = 2) }
    }
  }
  // A message kept for later: its words, that it's sent once the phone is in reach, and Delete.
  kept?.let { draft ->
    item {
      Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(draft.text, color = Look.send, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
          Text(Words.Watch.keptToSend, style = MaterialTheme.typography.bodySmall)
          Button(onClick = act.deleteKept, modifier = Modifier.fillMaxWidth(), colors = dangerQuietButton(onCard = true)) { Text(Words.Chat.delete) }
        }
      }
    }
  }
  if (shown !is WatchModel.Shown.Read) {
    notRead(shown, act)
  } else {
    // Read means opened, on this watch (step iv); when the Inbox can't be kept, what waits now.
    val count = inbox?.count { !it.read } ?: unread(shown, ready)
    if (count > 0) item { Button(onClick = openInbox, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.inboxUnread(count)) } }
  }
  item {
    Text(Words.Watch.voiceTextOnly, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
  }
  item { Button(onClick = openMenu, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.menu) } }
}

/** A page's lines before the phone has answered, or when it can't, as v1 says them. True once [shown] is the phone's read. */
private fun ScalingLazyListScope.notRead(shown: WatchModel.Shown, act: Actions): Boolean {
  when (shown) {
    WatchModel.Shown.Asking -> line(Words.Watch.askingPhone)
    WatchModel.Shown.SignedOut -> line(Words.Watch.signInOnPhone)
    WatchModel.Shown.PhoneUnreachable -> {
      line(Words.Watch.phoneNotReachable)
      item { Button(onClick = act.tryAgain, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.tryAgain) } }
    }
    WatchModel.Shown.OpenOnPhone -> item {
      Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.openOnPhone) }
    }
    is WatchModel.Shown.Read -> return true
  }
  return false
}

/**
 * The Inbox when it can't be kept on this watch (locked, or no screen lock): what the phone answered now, stored nowhere. Needs
 * you's held calls, each opening on its own screen (step vi), then each result ready to open. With nothing, what lands there.
 */
private fun ScalingLazyListScope.inboxPage(shown: WatchModel.Shown.Read, ready: List<AskLine>, act: Actions, openCall: (bot.tafs.watchlink.HeldLine) -> Unit) {
  for (call in shown.needsYou.calls) item { HeldCallRow(call) { openCall(call) } }
  for (ask in ready) item {
    Card(onClick = { act.open(ask) }, modifier = Modifier.fillMaxWidth()) { Text(stateLine(ask)) }
  }
  if (shown.needsYou.calls.isEmpty() && ready.isEmpty()) line(Words.Watch.inboxEmpty)
}

/**
 * The Inbox kept on the watch (step iv): newest first, each line with a dot while unread, its taf and its words. An answer opens
 * its result card; a held call Needs you still holds opens whole (step vi), else it says it's already answered. Each has Dismiss;
 * Clear read takes every read line away.
 */
private fun ScalingLazyListScope.keptInboxPage(inbox: List<InboxItem>, shown: WatchModel.Shown.Read, act: Actions, openCall: (bot.tafs.watchlink.HeldLine) -> Unit) {
  if (inbox.isEmpty()) {
    line(Words.Watch.inboxEmpty)
    return
  }
  for (entry in inbox) item {
    val call = if (entry.kind == InboxItem.Kind.HELD) shown.needsYou.calls.firstOrNull { it.holdId == entry.ref } else null
    // A held call Needs you still holds opens whole (step vi), where Refuse and Approve on your phone are; tapping reads the line.
    Card(onClick = { act.openItem(entry); call?.let(openCall) }, modifier = Modifier.fillMaxWidth().testTag(INBOX_ITEM + entry.id)) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          if (!entry.read) {
            androidx.compose.foundation.Canvas(Modifier.size(6.dp).testTag(UNREAD_DOT + entry.id)) { drawCircle(Look.coach) }
          }
          // Its taf, by what it is: a held call amber, an answer in Coach's blue.
          entry.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = if (entry.kind == InboxItem.Kind.HELD) Look.held else Look.coach, maxLines = 1) }
        }
        // An answer that was only an amount is kept blank (no amount is kept on the watch): it says the rest is on the phone.
        Text(entry.line.ifBlank { Words.Watch.moreOnPhone }, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (entry.kind == InboxItem.Kind.HELD) {
          if (call == null) {
            Text(Words.Watch.alreadyAnswered, style = MaterialTheme.typography.bodySmall)
          } else {
            call.amount?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
          }
        }
        Button(onClick = { act.dismissItem(entry.id) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton(onCard = true)) { Text(Words.Watch.dismiss) }
      }
    }
  }
  if (inbox.any { it.read }) item { Button(onClick = act.clearRead, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.clearRead) } }
}

/** Test tags for an Inbox line and its unread dot, by the line's id. */
internal const val INBOX_ITEM = "inbox-item:"
internal const val UNREAD_DOT = "unread-dot:"

/** Each taf with its state, and nothing else: a tap opens its page; halting is in the menu and in a taf's settings. */
private fun ScalingLazyListScope.tafsPage(shown: WatchModel.Shown.Read, act: Actions) {
  val tafs = shown.tafs?.tafs.orEmpty()
  for (taf in tafs) item {
    Card(onClick = { act.openTaf(taf.tafId) }, modifier = Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(taf.name, style = MaterialTheme.typography.titleSmall)
        Text(taf.state, style = MaterialTheme.typography.bodySmall, color = if (taf.halted) Look.danger else Look.dim)
      }
    }
  }
}

/** Your brief, as v1 draws it in Needs you's place; with none from the phone, its way there. */
private fun ScalingLazyListScope.briefPage(shown: WatchModel.Shown.Read, act: Actions) {
  val brief = shown.brief
  if (brief != null) {
    briefItems(brief, shown, act)
  } else {
    item { Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Watch.openOnPhone) } }
  }
}

/**
 * The menu, from the foot of Coach's home: every page by name, and Halt at the very bottom, apart from the rest, while a taf runs.
 * Halt opens Choose which to halt (step v), which asks once more before anything is sent.
 */
@Composable
private fun MenuScreen(shown: WatchModel.Shown, pages: List<Page>, act: Actions, outcome: WatchModel.Outcome?, open: (Page) -> Unit, close: () -> Unit) {
  val tafs = (shown as? WatchModel.Shown.Read)?.tafs
  ScalingLazyColumn(Modifier.fillMaxWidth(), state = rememberScalingLazyListState(initialCenterItemIndex = 0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { ListHeader { Text(Words.Watch.menu) } }
    // A Halt confirmed from here comes back here: what came of it, "set a screen lock" included, is said where it was asked.
    outcome?.let { item { OutcomeCard(it, act) } }
    for (page in pages) item { Button(onClick = { open(page) }, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(page.title) } }
    if (tafs != null && !tafs.allHalted && tafs.tafs.any { !it.halted }) {
      item { Spacer(Modifier.height(16.dp)) }
      item { Button(onClick = act.pickToHalt, modifier = Modifier.fillMaxWidth(), colors = dangerQuietButton()) { Text(Words.Taf.Halt.title) } }
    }
    item { Button(onClick = close, modifier = Modifier.fillMaxWidth(), colors = neutralButton()) { Text(Words.Common.close) } }
  }
}
