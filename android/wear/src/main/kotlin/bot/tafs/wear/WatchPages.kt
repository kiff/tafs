package bot.tafs.wear

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
 * Wear OS v2 (docs/watch-wear.md, Version 2): off. It's built one step at a time behind this switch, so a watch shows v1 whole
 * until every step is in; it turns on, as this one line, when the last of them lands and the owner has tried the build.
 */
internal const val WATCH_V2 = false

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
 * What waits in the Inbox, for the count on Coach's home: the calls Needs you holds and the results not opened yet. The inbox kept
 * on the watch, with read and dismissed, is step (iv); until then it's what the phone answered now, as v1 shows it.
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
  /** The Inbox kept on the watch (step iv); null draws what the phone answered now, as before it. */
  inbox: List<InboxItem>? = null,
) {
  when {
    voice != null -> return VoiceScreenV2(voice, act, probe = probe)
    result != null -> return ResultScreen(result, act, timing)
    outcome is WatchModel.Outcome.Halting -> return HaltingScreen(outcome.taf)
  }
  var menu by remember { mutableStateOf(false) }
  var quick by remember { mutableStateOf(false) }
  if (quick) {
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
  if (menu) {
    return MenuScreen(shown, pages, confirmingHaltAll, act, open = ::goTo, confirm = {
      menu = false
      act.confirmHaltAll()
    }, close = {
      menu = false
      act.cancelHaltAll()
    })
  }
  Box(Modifier.fillMaxSize()) {
    HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
      ScalingLazyColumn(
        Modifier.fillMaxWidth(),
        state = rememberScalingLazyListState(initialCenterItemIndex = 0),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        val page = pages[i]
        if (page == Page.Coach) {
          coachHome(shown, working, ready, act, kept, inbox, openInbox = { goTo(Page.Inbox) }, openMenu = { menu = true }, openQuick = { quick = true })
        } else {
          item { ListHeader { Text(page.title) } }
        }
        outcome?.let { item { OutcomeCard(it, act) } }
        when (page) {
          Page.Coach -> Unit
          Page.Account -> accountPage(act)
          else -> if (notRead(shown, act)) {
            when (page) {
              Page.Inbox -> if (inbox != null) keptInboxPage(inbox, shown as WatchModel.Shown.Read, act) else inboxPage(shown as WatchModel.Shown.Read, ready, act)
              Page.Tafs -> tafsPage(shown as WatchModel.Shown.Read)
              Page.Brief -> briefPage(shown as WatchModel.Shown.Read, act)
              else -> Unit
            }
          }
        }
      }
    }
    HorizontalPageIndicator(pager, Modifier.align(Alignment.BottomCenter))
  }
}

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
  item { AskHome(working.map { stateLine(it) to Look.brand[2] }, act.listen, label = Words.Coach.ask) }
  // Typed words and quick replies go to the same review as words heard.
  item {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Button(onClick = act.type, modifier = Modifier.weight(1f)) { Text(Words.Watch.type, maxLines = 1) }
      Button(onClick = openQuick, modifier = Modifier.weight(1f)) { Text(Words.Watch.quickReply, maxLines = 2) }
    }
  }
  // A message kept for later: its words, that it's sent once the phone is in reach, and Delete.
  kept?.let { draft ->
    item {
      Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(draft.text, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
          Text(Words.Watch.keptToSend, style = MaterialTheme.typography.bodySmall)
          Button(onClick = act.deleteKept, modifier = Modifier.fillMaxWidth()) { Text(Words.Chat.delete) }
        }
      }
    }
  }
  if (shown !is WatchModel.Shown.Read) {
    notRead(shown, act)
  } else {
    // Read means opened, on this watch (step iv); before it, what waits now.
    val count = inbox?.count { !it.read } ?: unread(shown, ready)
    if (count > 0) item { Button(onClick = openInbox, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.inboxUnread(count)) } }
  }
  item {
    Text(Words.Watch.voiceTextOnly, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
  }
  item { Button(onClick = openMenu, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.menu) } }
}

/** A page's lines before the phone has answered, or when it can't, as v1 says them. True once [shown] is the phone's read. */
private fun ScalingLazyListScope.notRead(shown: WatchModel.Shown, act: Actions): Boolean {
  when (shown) {
    WatchModel.Shown.Asking -> line(Words.Watch.askingPhone)
    WatchModel.Shown.SignedOut -> line(Words.Watch.signInOnPhone)
    WatchModel.Shown.PhoneUnreachable -> {
      line(Words.Watch.phoneNotReachable)
      item { Button(onClick = act.tryAgain, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.tryAgain) } }
    }
    WatchModel.Shown.OpenOnPhone -> item {
      Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.openOnPhone) }
    }
    is WatchModel.Shown.Read -> return true
  }
  return false
}

/**
 * The Inbox, until it's kept on the watch (step iv): Needs you's held calls, Refuse where the phone allows it and approving on the
 * phone, then each result ready to open. With nothing, the Inbox says what lands there.
 */
private fun ScalingLazyListScope.inboxPage(shown: WatchModel.Shown.Read, ready: List<AskLine>, act: Actions) {
  for (call in shown.needsYou.calls) item { HeldCallCard(call, act) }
  for (ask in ready) item {
    Card(onClick = { act.open(ask) }, modifier = Modifier.fillMaxWidth()) { Text(stateLine(ask)) }
  }
  if (shown.needsYou.calls.isEmpty() && ready.isEmpty()) line(Words.Watch.inboxEmpty)
}

/**
 * The Inbox kept on the watch (step iv): newest first, each line with a dot while unread, its taf and its words. An answer opens
 * its result card; a held call shows what it wants, Refuse where the phone allows it and Approve on your phone, while Needs you
 * still holds it, else that it's already answered. Each has Dismiss; Clear read takes every read line away.
 */
private fun ScalingLazyListScope.keptInboxPage(inbox: List<InboxItem>, shown: WatchModel.Shown.Read, act: Actions) {
  if (inbox.isEmpty()) {
    line(Words.Watch.inboxEmpty)
    return
  }
  for (entry in inbox) item {
    val call = if (entry.kind == InboxItem.Kind.HELD) shown.needsYou.calls.firstOrNull { it.holdId == entry.ref } else null
    Card(onClick = { act.openItem(entry) }, modifier = Modifier.fillMaxWidth().testTag(INBOX_ITEM + entry.id)) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          if (!entry.read) {
            androidx.compose.foundation.Canvas(Modifier.size(6.dp).testTag(UNREAD_DOT + entry.id)) { drawCircle(Look.unread) }
          }
          entry.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1) }
        }
        Text(entry.line, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (entry.kind == InboxItem.Kind.HELD) {
          if (call == null) {
            Text(Words.Watch.alreadyAnswered, style = MaterialTheme.typography.bodySmall)
          } else {
            call.amount?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            if (call.refusable) Button(onClick = { act.refuse(call.holdId) }, modifier = Modifier.fillMaxWidth()) { Text(Words.NeedsYou.refuse) }
            Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.approveOnPhone) }
          }
        }
        Button(onClick = { act.dismissItem(entry.id) }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.dismiss) }
      }
    }
  }
  if (inbox.any { it.read }) item { Button(onClick = act.clearRead, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.clearRead) } }
}

/** Test tags for an Inbox line and its unread dot, by the line's id. */
internal const val INBOX_ITEM = "inbox-item:"
internal const val UNREAD_DOT = "unread-dot:"

/** Each taf with its state, and nothing else: halting is in the menu and, with step (v), in a taf's settings. */
private fun ScalingLazyListScope.tafsPage(shown: WatchModel.Shown.Read) {
  val tafs = shown.tafs?.tafs.orEmpty()
  for (taf in tafs) item {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(taf.name, style = MaterialTheme.typography.titleSmall)
      Text(taf.state, style = MaterialTheme.typography.bodySmall)
    }
  }
}

/** Your brief, as v1 draws it in Needs you's place; with none from the phone, its way there. */
private fun ScalingLazyListScope.briefPage(shown: WatchModel.Shown.Read, act: Actions) {
  val brief = shown.brief
  if (brief != null) {
    briefItems(brief, shown, act)
  } else {
    item { Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.openOnPhone) } }
  }
}

/** Account, until the account read is drawn (step vi): what this watch holds and can't do, and the way to the phone. */
private fun ScalingLazyListScope.accountPage(act: Actions) {
  line(Words.Watch.trust)
  item { Button(onClick = { act.openOnPhone("needs-you") }, modifier = Modifier.fillMaxWidth()) { Text(Words.Watch.openOnPhone) } }
}

/**
 * The menu, from the foot of Coach's home: every page by name, and Halt at the very bottom, apart from the rest. Halt asks once
 * more, for all the tafs, until choosing which to halt comes with step (v).
 */
@Composable
private fun MenuScreen(
  shown: WatchModel.Shown,
  pages: List<Page>,
  confirmingHaltAll: Boolean,
  act: Actions,
  open: (Page) -> Unit,
  /** Halt all, confirmed: the menu closes on the halting screen. */
  confirm: () -> Unit,
  close: () -> Unit,
) {
  val tafs = (shown as? WatchModel.Shown.Read)?.tafs
  ScalingLazyColumn(Modifier.fillMaxWidth(), state = rememberScalingLazyListState(initialCenterItemIndex = 0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { ListHeader { Text(Words.Watch.menu) } }
    for (page in pages) item { Button(onClick = { open(page) }, modifier = Modifier.fillMaxWidth()) { Text(page.title) } }
    if (tafs != null && tafs.tafs.isNotEmpty() && !tafs.allHalted) {
      item { Spacer(Modifier.height(16.dp)) }
      item {
        if (confirmingHaltAll) {
          Card(onClick = act.cancelHaltAll, modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(Words.HaltAll.title, style = MaterialTheme.typography.titleSmall)
              Button(onClick = confirm, modifier = Modifier.fillMaxWidth()) { Text(Words.Halt.allNow) }
              Button(onClick = act.cancelHaltAll, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.cancel) }
            }
          }
        } else {
          Button(onClick = act.askHaltAll, modifier = Modifier.fillMaxWidth()) { Text(Words.Taf.Halt.title) }
        }
      }
    }
    item { Button(onClick = close, modifier = Modifier.fillMaxWidth()) { Text(Words.Common.close) } }
  }
}
