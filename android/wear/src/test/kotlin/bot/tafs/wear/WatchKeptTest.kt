package bot.tafs.wear

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The watch keeps its data and opens on it at once; updates are pushed (docs/watch-wear.md, Delivery, "What the watch keeps"):
 * Coach's conversation and the Inbox outlive the screen going off and the app closing, a kept item from the phone lands in them
 * with the app closed, a slow phone is tried again by the watch itself, and a taf's chat never drops the person's words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WatchApplication::class)
class WatchKeptTest {
  private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val t = 1_763_244_000_000L
  private val call = HeldLine("hold_1", "Refunds", "Refund order 1042", "€64.00", "Over €50 each", refusable = true)
  private val needsYou = WatchReply.NeedsYou(1, "1 held call needs you", listOf(call))
  private val tafs = WatchReply.Tafs(listOf(TafLine("taf_1", "Refunds", "Waiting for you", halted = false)), allHalted = false)
  private val taken = AskLine("wa_01", "w_00000001", AskLine.ACCEPTED, words = "Sent · Coach has it", updated = "2026-11-16T19:00:00Z")
  private val done = taken.copy(state = AskLine.DONE, tafName = "Calendar", line = "Moved your call to 18:00.", words = "Done", updated = "2026-11-16T19:01:00Z")

  @Before fun words() = Res.install(context)

  private class Phone(val answers: (WatchRequest) -> Asked) : PhoneLink {
    val asked = mutableListOf<WatchRequest>()
    override suspend fun ask(request: WatchRequest): Asked = answers(request).also { asked += request }
  }

  private fun reads(request: WatchRequest): Asked = when (request) {
    is WatchRequest.NeedsYou -> Asked.Answered(needsYou)
    is WatchRequest.Tafs -> Asked.Answered(tafs)
    is WatchRequest.Ask -> Asked.Answered(WatchReply.AskTaken(taken))
    is WatchRequest.Asks -> Asked.Answered(WatchReply.AskList(emptyList()))
    else -> Asked.Unreachable()
  }

  private inner class Thread : ThreadStore {
    var threads: Map<String, KeptThread> = emptyMap()

    /** Coach's kept turns. */
    var turns: List<KeptTurn>
      get() = threads[COACH_THREAD]?.turns.orEmpty()
      set(value) { threads = threads + (COACH_THREAD to KeptThread(t, turns = value)) }

    override fun read() = threads
    override fun write(threads: Map<String, KeptThread>) { this.threads = threads }
    override fun clear() { threads = emptyMap() }
  }

  private class Inbox : InboxStore {
    var items: List<InboxItem> = emptyList()
    var gone: Set<String> = emptySet()
    override fun read() = items
    override fun write(items: List<InboxItem>) { this.items = items }
    override fun dismissed() = gone
    override fun writeDismissed(ids: Set<String>) { gone = ids }
    override fun clear() { items = emptyList(); gone = emptySet() }
  }

  private fun model(phone: PhoneLink, thread: ThreadStore = Thread(), inbox: InboxStore = Inbox(), secure: Boolean = true) =
    WatchModel(phone, now = { t }, unlocked = { secure }, secure = { secure }, newClientId = { "w_00000001" }, reviewFirst = true,
      inboxStore = inbox, thread = thread)

  private suspend fun WatchModel.say(words: String) {
    review(words)
    sendReviewed()
  }

  /** The conversation survives the screen going off and on, and the app opening again: no wipe on put-away. */
  @Test fun coachsConversationIsKeptAcrossPutAwayAndANewOpen() = runTest {
    val thread = Thread()
    val model = model(Phone(::reads), thread)
    model.say("Move my call to six")
    model.took(done)
    model.putAway()
    assertEquals(listOf(WatchModel.Said("Move my call to six", t) to done), model.thread())
    // The app opened again (a new model over the same store): the conversation is there before any read.
    val again = model(Phone { Asked.Unreachable() }, thread)
    assertEquals(listOf("wa_01"), again.thread().map { it.second.id })
    assertEquals("Moved your call to 18:00.", again.thread().single().second.line)
  }

  /** v2's Send goes back to the conversation: the words are its newest turn, Sent, and the answer comes under them. */
  @Test fun aSentMessageIsTheNewestTurnAndItsAnswerComesUnderIt() = runTest {
    val model = model(Phone(::reads))
    model.say("Move my call to six")
    assertNull("no Sent screen on its own: back to the conversation", model.voice.value)
    assertEquals(listOf(WatchModel.Said("Move my call to six", t) to taken), model.thread())
    assertFalse(model.thread().single().second.ended)
    model.took(done)
    assertEquals(done, model.thread().single().second)
    assertNull("the answer stays in the thread, no card opens by itself", model.result.value)
  }

  /** Signed out: the conversation goes, on the watch too. */
  @Test fun signingOutHereForgetsTheConversation() = runTest {
    val thread = Thread()
    val model = model(Phone(::reads), thread)
    model.say("Move my call to six")
    model.signOutHere()
    assertTrue(model.thread().isEmpty())
    assertTrue(thread.turns.isEmpty())
  }

  /** A watch with no screen lock keeps no conversation on disk; it stays in memory for the app's life. */
  @Test fun noScreenLockKeepsNothingOnDisk() {
    val thread = Thread().apply { turns = listOf(KeptTurn(WatchModel.Said("Move my call to six", t), done)) }
    val model = model(Phone { Asked.Unreachable() }, thread, secure = false)
    assertTrue(model.thread().isEmpty())
    assertTrue("what was kept goes once the screen lock is off", thread.turns.isEmpty())
  }

  /** A kept item from the phone with the app closed: into the conversation and the Inbox, and notified. */
  @Test fun aKeptAnswerWithTheAppClosedLandsInTheConversationAndTheInboxAndIsNotified() {
    AskResults.shown = null
    val thread = Thread().apply { turns = listOf(KeptTurn(WatchModel.Said("Move my call to six", t), taken)) }
    val inbox = Inbox()
    KeptResults.ask(context, done, KeptResults.Stores(thread, inbox, secure = true, signedOut = false), now = t)
    assertEquals(done, thread.turns.single().ask)
    assertEquals(listOf(InboxItem(InboxItem.askId("wa_01"), InboxItem.Kind.ASK, "Calendar", "Moved your call to 18:00.", t)), inbox.items)
    val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
    assertEquals(1, posted.size)
    // The app opens on it: the conversation and the Inbox, before any read.
    val opened = model(Phone { Asked.Unreachable() }, thread, inbox)
    assertEquals(done, opened.thread().single().second)
    assertEquals(1, opened.unreadCount())
  }

  /** ...but nothing is kept on a watch with no screen lock, or signed out here: only notified. */
  @Test fun aKeptAnswerIsNotKeptWithoutAScreenLock() {
    AskResults.shown = null
    val thread = Thread().apply { turns = listOf(KeptTurn(WatchModel.Said("Move my call to six", t), taken)) }
    val inbox = Inbox()
    KeptResults.ask(context, done, KeptResults.Stores(thread, inbox, secure = false, signedOut = false), now = t)
    assertEquals(taken, thread.turns.single().ask)
    assertTrue(inbox.items.isEmpty())
  }

  /** The Inbox's marks kept by the phone with the app closed: a line dismissed elsewhere goes, one read elsewhere is read. */
  @Test fun keptMarksWithTheAppClosedAreTakenIn() {
    KeptResults.marks = null
    val inbox = Inbox().apply {
      items = listOf(InboxItem("ask:wa_01", InboxItem.Kind.ASK, null, "a", t), InboxItem("ask:wa_02", InboxItem.Kind.ASK, null, "b", t))
    }
    KeptResults.marks(WatchReply.InboxMarks(read = listOf("wa_02"), dismissed = listOf("wa_01")), KeptResults.Stores(null, inbox, secure = true, signedOut = false))
    assertEquals(listOf(InboxItem("ask:wa_02", InboxItem.Kind.ASK, null, "b", t, read = true)), inbox.items)
    assertEquals(setOf("ask:wa_01"), inbox.gone)
  }

  /** A first slow answer is tried again by the watch itself, never by the person's Try again, and shows nothing meanwhile. */
  @Test fun aSlowPhoneIsTriedAgainByItselfBeforeAnyLine() = runTest {
    var tries = 0
    val waits = mutableListOf<Long>()
    val model = model(Phone { r -> if (r is WatchRequest.NeedsYou && ++tries < 3) Asked.Unreachable(LinkFailure.NoAnswer) else reads(r) })
    model.start(wait = { waits += it })
    assertEquals(3, tries)
    assertEquals(RETRY_BACKOFF_MS.take(2), waits)
    assertTrue(model.shown.value is WatchModel.Shown.Read)
    assertNull((model.shown.value as WatchModel.Shown.Read).unreachable)
  }

  /** With something kept, tries that all go unanswered leave it shown with a small line, never a screen of their own. */
  @Test fun triesSpentLeaveWhatsKeptWithASmallLine() = runTest {
    var reachable = true
    val model = model(Phone { r -> if (!reachable) Asked.Unreachable(LinkFailure.NoAnswer) else reads(r) })
    model.start(wait = {})
    reachable = false
    model.start(wait = {})
    val shown = model.shown.value as WatchModel.Shown.Read
    assertEquals(LinkFailure.NoAnswer, shown.unreachable)
    assertEquals(1, shown.needsYou.count)
  }

  /** Nothing kept at all (a first open): the linking screen, and the watch goes on trying behind it. */
  @Test fun aFirstOpenLinksAndGoesOnTrying() = runTest {
    var tries = 0
    val waits = mutableListOf<Long>()
    val model = model(Phone { r -> if (r is WatchRequest.NeedsYou && ++tries < 6) Asked.Unreachable(LinkFailure.NoAnswer) else reads(r) })
    assertTrue(model.linking())
    model.start(wait = { waits += it; assertTrue("the linking screen while it tries", model.linking()) })
    assertEquals(RETRY_BACKOFF_MS + LINKING_RETRY_MS + LINKING_RETRY_MS, waits)
    assertFalse(model.linking())
  }

  /** Anything kept (the conversation) opens at once: no linking screen. */
  @Test fun anythingKeptOpensWithoutTheLinkingScreen() {
    val thread = Thread().apply { turns = listOf(KeptTurn(WatchModel.Said("Move my call to six", t), done)) }
    assertFalse(model(Phone { Asked.Unreachable() }, thread).linking())
  }

  /** Row 20: on a watch that can't keep the Inbox, an answer opened there stays in it. */
  @Test fun anOpenedAnswerStaysInTheInboxThatIsntKept() = runTest {
    val model = model(Phone(::reads), secure = false)
    model.took(done)
    model.open(done)
    assertTrue(model.ready().isEmpty())
    assertEquals(listOf(done), model.answered())
  }

  // MARK: A taf's chat (row 13)

  private fun tafChat(answer: (WatchRequest.Ask) -> Asked) = model(Phone { r -> if (r is WatchRequest.Ask) answer(r) else reads(r) }).apply { tafChatOn = true }

  /** Typed words in a taf's chat (the keyboard's result) reach Check before sending, named for the taf, even after a put-away. */
  @Test fun typedWordsInATafsChatReachCheckBeforeSendingAndSurviveAPutAway() = runTest {
    val model = tafChat { Asked.Answered(WatchReply.AskTaken(taken.copy(kind = AskLine.TAF))) }
    model.refresh()
    model.openTaf("taf_1")
    // The keyboard's own screen puts the app away; its result comes back after.
    model.putAway()
    model.review("why is it not connected", tafId = "taf_1")
    val review = model.voice.value as WatchModel.Voice.Review
    assertEquals("taf_1", review.tafId)
    assertEquals("Refunds", review.tafName)
    // The screen goes off on the review: the words come back.
    model.putAway()
    assertEquals(review, model.voice.value)
    model.sendReviewed()
    assertNull(model.voice.value)
    assertEquals(listOf("why is it not connected"), model.taf.value!!.chat.turns.map { it.said })
  }

  /** The account's switch off: the words go back to Check before sending, kept, and the chat says so before the next recording. */
  @Test fun theSwitchOffKeepsTheWordsAndSaysSoBeforeRecording() = runTest {
    val model = tafChat { Asked.Answered(WatchReply.TafChatOff) }
    model.refresh()
    model.openTaf("taf_1")
    model.review("why is it not connected", tafId = "taf_1")
    model.sendReviewed()
    val kept = model.voice.value as WatchModel.Voice.Review
    assertEquals("why is it not connected", kept.text)
    assertEquals(Words.Watch.askNotOn, kept.notice)
    assertTrue(model.tafChatOff.value)
    model.deleteReviewed()
    model.readyToRecord(tafId = "taf_1")
    assertFalse("nothing recorded to be dropped", model.voice.value is WatchModel.Voice.Ready)
  }

  /** A phone that refuses for another reason: the words stay too, with its words, never Open on phone alone. */
  @Test fun aRefusedTafMessageKeepsTheWords() = runTest {
    val model = tafChat { Asked.Answered(WatchReply.Failed("Open on phone")) }
    model.refresh()
    model.openTaf("taf_1")
    model.review("why is it not connected", tafId = "taf_1")
    model.sendReviewed()
    assertEquals(WatchModel.Voice.Review("why is it not connected", null, tafId = "taf_1", tafName = "Refunds", notice = "Open on phone"), model.voice.value)
  }

  /** A taf's chat opens on its newest message, with the send bar under it. */
  @Test fun aTafsChatOpensOnItsNewestMessage() {
    val lines = (1..8).map { bot.tafs.watchlink.ChatLine("m$it", mine = it % 2 == 0, at = "2026-11-25T01:0$it:00Z", text = "message $it") }
    val open = WatchModel.OpenTaf("taf_1", tafs.tafs.single(), WatchModel.TafRead.Asking, WatchModel.TafChat(WatchModel.TafChat.History.Read(lines)))
    // The header, one day's header, eight messages: the send bar is the 11th item, the newest message just above it.
    assertEquals(10, tafChatLatest(open))
  }

  // MARK: What's kept, as the Apple Watch keeps it: 30 messages a thread, 20 threads, 30 days unopened

  private fun line(n: Int) = bot.tafs.watchlink.ChatLine("m$n", mine = n % 2 == 0, at = "2026-11-25T01:00:00Z", text = "message $n")

  @Test fun aThreadKeepsItsNewest30Messages() {
    val lines = KeptThreads.trimmed(KeptThread(t, lines = (1..40).map(::line)))
    assertEquals(30, lines.lines.size)
    assertEquals("message 11", lines.lines.first().text)
    // A turn sent from the watch is two messages, its words and its answer.
    val turns = (1..20).map { KeptTurn(WatchModel.Said("said $it", t + it), done.copy(id = "wa_$it")) }
    val trimmed = KeptThreads.trimmed(KeptThread(t, lines = (1..10).map(::line), turns = turns))
    assertEquals(15, trimmed.turns.size)
    assertEquals("said 6", trimmed.turns.first().said.text)
    assertTrue("lines go first", trimmed.lines.isEmpty())
  }

  @Test fun atMost20ThreadsTheOpenedLastStay() {
    val threads = (1..25).associate { tafThread("taf_$it") to KeptThread(t - it * 1_000L) }
    val kept = KeptThreads.pruned(threads, t)
    assertEquals(20, kept.size)
    assertEquals((1..20).map { tafThread("taf_$it") }.toSet(), kept.keys)
  }

  @Test fun aThreadNotOpenedFor30DaysGoes() = runTest {
    val thread = Thread().apply {
      threads = mapOf(
        COACH_THREAD to KeptThread(t - UNOPENED_MS - 1, turns = listOf(KeptTurn(WatchModel.Said("Old", t - UNOPENED_MS - 1), done))),
        tafThread("taf_1") to KeptThread(t - UNOPENED_MS + 60_000, lines = listOf(line(1))),
      )
    }
    val model = model(Phone(::reads), thread)
    assertTrue("read at open: the old one isn't shown", model.thread().isEmpty())
    // Written: the old one goes from the watch too.
    model.say("Move my call to six")
    assertEquals(setOf(COACH_THREAD, tafThread("taf_1")), thread.threads.keys)
    assertEquals(listOf("Move my call to six"), thread.turns.map { it.said.text })
  }

  /** A taf's chat opens at once on what's kept, and is kept with what's sent from the watch. */
  @Test fun aTafsChatOpensOnItsKeptThreadAndKeepsWhatsSent() = runTest {
    val thread = Thread().apply { threads = mapOf(tafThread("taf_1") to KeptThread(t - 1_000, lines = listOf(line(1), line(2)))) }
    var reachable = false
    val tafTaken = taken.copy(id = "wa_t1", kind = AskLine.TAF)
    val model = model(Phone { r -> if (!reachable) Asked.Unreachable() else if (r is WatchRequest.Ask) Asked.Answered(WatchReply.AskTaken(tafTaken)) else reads(r) }, thread)
    model.openTaf("taf_1")
    assertEquals("kept, before the phone answers", WatchModel.TafChat.History.Read(listOf(line(1), line(2))), model.taf.value!!.chat.history)
    assertEquals("opened now", t, thread.threads.getValue(tafThread("taf_1")).openedAt)
    reachable = true
    model.review("why is it not connected", tafId = "taf_1")
    model.sendReviewed()
    assertEquals(listOf(tafTaken), thread.threads.getValue(tafThread("taf_1")).turns.map { it.ask })
    // Its answer, as a kept item from the phone with the app closed: into that taf's kept thread.
    AskResults.shown = null
    val answered = tafTaken.copy(state = AskLine.DONE, line = "Chrome is closed.", updated = "2026-11-16T19:05:00Z")
    KeptResults.ask(context, answered, KeptResults.Stores(thread, Inbox(), secure = true, signedOut = false), now = t)
    assertEquals(answered, thread.threads.getValue(tafThread("taf_1")).turns.single().ask)
    assertTrue("never in the Inbox", model.inbox.value.isEmpty())
  }

  /** The phone's chat pushed as it changed: the open chat moves at once; closed, the kept thread follows. */
  @Test fun aTafsChatPushedByThePhoneUpdatesTheOpenChatAndTheKeptThread() = runTest {
    val thread = Thread()
    val model = model(Phone(::reads), thread)
    model.openTaf("taf_1")
    val lines = listOf(line(1), line(2), line(3))
    model.tafChatChanged("taf_1", lines)
    assertEquals(WatchModel.TafChat.History.Read(lines), model.taf.value!!.chat.history)
    assertEquals(lines, thread.threads.getValue(tafThread("taf_1")).lines)
    // The app closed: the listener writes it.
    KeptResults.chats = null
    KeptResults.chat("taf_1", lines + line(4), KeptResults.Stores(thread, null, secure = true, signedOut = false), now = t)
    assertEquals(4, thread.threads.getValue(tafThread("taf_1")).lines.size)
    // A taf the watch keeps nothing of: nothing written.
    KeptResults.chat("taf_9", lines, KeptResults.Stores(thread, null, secure = true, signedOut = false), now = t)
    assertFalse(tafThread("taf_9") in thread.threads)
  }

  // MARK: tafs cloud's newer fields: the taf chat's switch with the asks, and a cut Coach answer read whole

  @Test fun theSwitchWithTheAsksSaysSoBeforeRecording() = runTest {
    var on: Boolean? = false
    val model = model(Phone { r -> if (r is WatchRequest.Asks) Asked.Answered(WatchReply.AskList(emptyList(), tafChat = on)) else reads(r) })
    model.readAsks()
    assertTrue(model.tafChatOff.value)
    model.refresh()
    model.openTaf("taf_1")
    model.readyToRecord(tafId = "taf_1")
    assertNull("nothing recorded", model.voice.value)
    // An older tafs cloud says nothing: as before, learnt from a refusal.
    on = null
    val older = model(Phone { r -> if (r is WatchRequest.Asks) Asked.Answered(WatchReply.AskList(emptyList(), tafChat = on)) else reads(r) })
    older.readAsks()
    assertFalse(older.tafChatOff.value)
  }

  @Test fun aCutCoachAnswerIsReadWholeAndHeardWhole() = runTest {
    val cut = done.copy(line = "Your Chrome isn't reachable (it's closed, or the computer is…", more = true)
    val whole = "Your Chrome isn't reachable (it's closed, or the computer is asleep). Once Chrome is open, ask me again."
    val phone = Phone { r -> if (r is WatchRequest.AskFull) Asked.Answered(WatchReply.AskFull(whole)) else reads(r) }
    val model = model(phone)
    assertEquals(cut.line, model.heard(cut))
    model.readWhole(cut.id)
    assertEquals(WatchModel.Whole.Read(whole), model.wholes.value[cut.id])
    assertEquals("Hear reads the whole", whole, model.heard(cut))
    assertTrue(phone.asked.contains(WatchRequest.AskFull(t, "wa_01")))
  }
}
