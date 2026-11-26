@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.wear

import androidx.test.core.app.ApplicationProvider
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.KeptSignedOut
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One person's account never shows under another's on the watch (docs/watch-wear.md, "What the watch keeps"): the phone's account
 * mark, random per sign-in, rides on every reply; a change of it, or a reply without it once the watch holds one, wipes everything
 * kept before anything of the reply is drawn, and nothing kept is drawn before a reply this open carried the mark held. The
 * phone's signed-out item wipes without asking, the app closed or open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WatchApplication::class)
class AccountMarkTest {
  private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val t = 1_763_244_000_000L
  private val markA = "AAAAAAAAAAAAAAAAAAAAAA"
  private val markB = "BBBBBBBBBBBBBBBBBBBBBB"

  @Before fun words() = Res.install(context)

  // A's account: one held call, one taf, an answer in Coach's conversation, an Inbox line and a message kept for later.
  private val heldA = HeldLine("hold_a", "Refunds A", "Refund order A-1", "€64.00", "Over €50 each", refusable = true)
  private val needsYouA = WatchReply.NeedsYou(1, "1 held call needs you", listOf(heldA))
  private val tafsA = WatchReply.Tafs(listOf(TafLine("taf_a", "Refunds A", "Ready", halted = false)), allHalted = false)
  private val askA = AskLine("wa_a", "w_000000aa", AskLine.DONE, tafName = "Refunds A", line = "Refunded order A-1.", words = "Done", updated = "2026-11-16T19:01:00Z")

  // B's: nothing held, another taf.
  private val needsYouB = WatchReply.NeedsYou(0, "Nothing needs you", emptyList())
  private val tafsB = WatchReply.Tafs(listOf(TafLine("taf_b", "Leads B", "Ready", halted = false)), allHalted = false)

  private class Phone(var answers: (WatchRequest) -> Asked) : PhoneLink {
    val asked = mutableListOf<WatchRequest>()
    override suspend fun ask(request: WatchRequest): Asked = answers(request).also { asked += request }
  }

  private fun readsAs(mark: String?, needsYou: WatchReply.NeedsYou, tafs: WatchReply.Tafs): (WatchRequest) -> Asked = { r ->
    when (r) {
      is WatchRequest.NeedsYou -> Asked.Answered(needsYou, mark)
      is WatchRequest.Tafs -> Asked.Answered(tafs, mark)
      is WatchRequest.Asks -> Asked.Answered(WatchReply.AskList(emptyList()), mark)
      is WatchRequest.InboxMarks -> Asked.Answered(WatchReply.InboxMarks(), mark)
      is WatchRequest.Ask -> Asked.Answered(WatchReply.AskTaken(AskLine("wa_new", r.clientId, AskLine.ACCEPTED, words = "Sent", updated = "2026-11-16T19:02:00Z")), mark)
      else -> Asked.Unreachable()
    }
  }

  private val asA = readsAs(markA, needsYouA, tafsA)
  private val asB = readsAs(markB, needsYouB, tafsB)

  private inner class Thread : ThreadStore {
    var threads: Map<String, KeptThread> = emptyMap()
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

  private class Memory : CacheStore {
    var text: String? = null
    override fun read() = text
    override fun write(text: String) { this.text = text }
    override fun clear() { text = null }
  }

  private class Drafts : DraftStore {
    var draft: WatchModel.Draft? = null
    override fun read() = draft
    override fun write(draft: WatchModel.Draft) { this.draft = draft }
    override fun clear() { draft = null }
  }

  private class Marks : MarkStore {
    var mark: String? = null
    override fun read() = mark
    override fun write(mark: String) { this.mark = mark }
    override fun clear() { mark = null }
  }

  private class Tile : TileCount.Store {
    var kept: Pair<Int, Long>? = null
    override fun read() = kept
    override fun write(count: Int, at: Long) { kept = count to at }
    override fun clear() { kept = null }
  }

  private val thread = Thread()
  private val inbox = Inbox()
  private val cacheStore = Memory()
  private val cache = WatchCache(cacheStore) { t }
  private val drafts = Drafts()
  private val marks = Marks()
  private val tile = Tile()
  private var wipes = 0

  private fun model(phone: PhoneLink) = WatchModel(
    phone, now = { t }, unlocked = { true }, secure = { true }, tile = TileCount(tile), newClientId = { "w_000000bb" }, reviewFirst = true,
    drafts = drafts, inboxStore = inbox, cache = cache, thread = thread, marks = marks, wiped = { wipes++ },
  )

  private fun stores() = KeptResults.Stores(thread, inbox, secure = true, signedOut = false, marks = marks, cache = cacheStore, drafts = drafts, tile = tile)

  /** The watch after A's sign-in, as an earlier open left it: A's read, conversation, Inbox, kept message, tile and mark. */
  private fun keptFromA() {
    cache.save(WatchModel.NeedsYouRead(1, "", emptyList()), WatchModel.TafsRead(tafsA.tafs, allHalted = false))
    thread.threads = mapOf(COACH_THREAD to KeptThread(t, turns = listOf(KeptTurn(WatchModel.Said("Refund order A-1", t), askA))))
    inbox.items = listOf(InboxItem(InboxItem.heldId("hold_a"), InboxItem.Kind.HELD, "Refunds A", "Refund order A-1", t))
    drafts.draft = WatchModel.Draft("Keep this for A", "w_000000cc", null, markA)
    tile.kept = 1 to t
    marks.mark = markA
  }

  /** Nothing of A's on the watch's disk: B's own read may be kept by now, never A's. */
  private fun nothingOfAKept() {
    assertTrue("conversation", thread.threads.values.none { t -> t.turns.any { it.ask.id == "wa_a" } })
    assertTrue("Inbox", inbox.items.none { it.id == "held:hold_a" })
    assertTrue("cache", cacheStore.text?.contains("taf_a") != true && cache.load()?.count != 1)
    assertTrue("kept message", drafts.draft?.text != "Keep this for A")
    assertTrue("tile", tile.kept?.first != 1)
  }

  /** Whether [shown] draws anything of A's. */
  private fun ofA(shown: WatchModel.Shown): Boolean {
    val read = shown as? WatchModel.Shown.Read ?: return false
    return read.needsYou.count == 1 || read.needsYou.calls.any { it.holdId == "hold_a" } || read.tafs?.tafs?.any { it.tafId == "taf_a" } == true
  }

  /** Every value the model draws, as it's drawn, with what it holds of A's in memory at each: none may be A's. */
  private fun kotlinx.coroutines.test.TestScope.watch(m: WatchModel): MutableList<String> {
    val ofA = mutableListOf<String>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.shown.collect { if (ofA(it)) ofA += "shown: $it" } }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.inbox.collect { if (it.isNotEmpty()) ofA += "inbox: $it" } }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.kept.collect { if (it != null) ofA += "kept: $it" } }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.asks.collect { if (it.isNotEmpty()) ofA += "asks: $it" } }
    return ofA
  }

  /** A held call pushed while the app is open (`kept/needs-you`): nothing before this open has heard the mark; then at once. */
  @Test fun aPushedHeldCallIsDrawnOnlyOnceThisOpenHeardTheMark() = runTest {
    keptFromA()
    inbox.items = emptyList()
    val phone = Phone { Asked.Unreachable() }
    val m = model(phone)
    val refund = HeldLine("hold_2", "Refunds A", "Refund order A-2", null, null, refusable = true)
    m.needsYouPushed(2, "2 held calls need you", listOf(heldA, refund))
    assertTrue("not before a reply in this open carried the mark", m.inbox.value.isEmpty())
    phone.answers = asA
    m.refresh()
    m.needsYouPushed(2, "2 held calls need you", listOf(heldA, refund))
    assertEquals("the pushed call first", "held:hold_2", m.inbox.value.first().id)
    val read = m.shown.value as WatchModel.Shown.Read
    assertEquals(2, read.needsYou.count)
    // A call the live read already showed keeps its amount and reason; the pushed one has none.
    assertEquals(heldA.amount, read.needsYou.calls.first { it.holdId == "hold_a" }.amount)
    assertEquals(2, tile.kept?.first)
    // Answered on the phone: the push lists it no more, and its line goes.
    m.needsYouPushed(1, "1 held call needs you", listOf(heldA))
    assertTrue(m.inbox.value.none { it.id == "held:hold_2" })
  }

  /** Row 29: a hold that ran out (or was answered) leaves the Inbox at the watch's next read, never a Refuse on a dropped call. */
  @Test fun anEndedHoldLeavesTheInboxAtTheNextRead() = runTest {
    keptFromA()
    val phone = Phone(asA)
    val m = model(phone)
    m.refresh()
    assertTrue(m.inbox.value.any { it.id == "held:hold_a" })
    phone.answers = readsAs(markA, WatchReply.NeedsYou(0, "Nothing is waiting.", emptyList()), tafsA)
    m.refresh()
    assertTrue("the ended hold is gone", m.inbox.value.none { it.id == "held:hold_a" })
    assertEquals(0, (m.shown.value as WatchModel.Shown.Read).needsYou.count)
  }

  /** Row 25: a recording the 30 s limit stopped keeps what was heard, and its review says it was cut there; Stop doesn't. */
  @Test fun aRecordingStoppedByTheLimitSaysSoAndKeepsTheWords() = runTest {
    val m = model(Phone(asA))
    assertTrue(m.talk())
    val since = (m.voice.value as WatchModel.Voice.Listening).since!!
    m.reachedLimit(since)
    m.heard("I want to know what people in Bucaramanga eat on holiday, is it something that people do or")
    val review = m.voice.value as WatchModel.Voice.Review
    assertTrue(review.text.endsWith("people do or"))
    assertEquals(Words.Watch.stoppedAtLimit(limitShort()), review.notice)
    // Stopped by the person: no word of a limit.
    m.closeVoice()
    assertTrue(m.talk())
    m.heard("Which tafs are ready")
    assertNull((m.voice.value as WatchModel.Voice.Review).notice)
  }

  /** Edit before sending: the review's words changed in place, the recording dropped; blank keeps them as they were. */
  @Test fun theReviewsWordsCanBeEditedBeforeSending() = runTest {
    val m = model(Phone(asA))
    assertTrue(m.talk())
    m.heard("What do people in bucharamanga eat")
    m.editReview("What do people in Bucaramanga eat")
    val review = m.voice.value as WatchModel.Voice.Review
    assertEquals("What do people in Bucaramanga eat", review.text)
    assertNull("the recording no longer says it", review.audio)
    m.editReview("   ")
    assertEquals("What do people in Bucaramanga eat", (m.voice.value as WatchModel.Voice.Review).text)
  }

  @Test fun theSameMarkKeepsWhatWasKeptAndDrawsIt() = runTest {
    keptFromA()
    val phone = Phone(asA)
    val m = model(phone)
    m.refresh()
    assertEquals(listOf("wa_a"), m.thread().map { it.second.id })
    assertEquals(listOf("held:hold_a"), m.inbox.value.map { it.id })
    // The message kept for later goes to the same sign-in, as before marks.
    assertTrue(phone.asked.any { it is WatchRequest.Ask && it.clientId == "w_000000cc" })
    assertEquals(1, (m.shown.value as WatchModel.Shown.Read).needsYou.count)
    assertEquals(markA, marks.mark)
    assertEquals(0, wipes)
  }

  /** Signed in as A, the watch filled; the phone moved to B while the watch was out of reach; then back in reach. */
  @Test fun aSwitchWhileOutOfReachDrawsNothingOfAAtAnyPointThenB() = runTest {
    keptFromA()
    val phone = Phone { Asked.Unreachable() }
    val m = model(phone)
    val drawnOfA = watch(m)
    // Opened out of reach: nothing kept is drawn, the cache included, before a reply says it's still A.
    assertTrue("nothing of A before any reply", m.thread().isEmpty() && m.inbox.value.isEmpty() && m.kept.value == null)
    m.refresh()
    assertEquals(WatchModel.Shown.PhoneUnreachable(LinkFailure.NotReachable), m.shown.value)
    assertTrue("the linking screen, never A's read", m.linking())
    // Back in reach, on B.
    phone.answers = asB
    m.refresh()
    m.sendKept()
    assertEquals(emptyList<String>(), drawnOfA)
    val shown = m.shown.value as WatchModel.Shown.Read
    assertEquals(listOf("taf_b"), shown.tafs?.tafs?.map { it.tafId })
    nothingOfAKept()
    assertEquals(markB, marks.mark)
    assertEquals(1, wipes)
    assertFalse("A's kept message is never sent as B", phone.asked.any { it is WatchRequest.Ask })
  }

  @Test fun aSwitchWithTheAppOpenWipesBeforeBIsDrawn() = runTest {
    keptFromA()
    val phone = Phone(asA)
    val m = model(phone)
    m.refresh()
    assertTrue(ofA(m.shown.value))
    // From the phone's first answer as B, nothing of A may be drawn again (before it, the watch can't know, and shows the last read).
    var after = false
    val drawnAfter = mutableListOf<WatchModel.Shown>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.shown.collect { if (after) drawnAfter += it } }
    phone.answers = { r -> after = true; asB(r) }
    m.refresh()
    assertTrue("A after the switch: $drawnAfter", drawnAfter.none(::ofA))
    assertTrue(m.thread().isEmpty())
    assertTrue(m.inbox.value.isEmpty())
    assertNull(m.kept.value)
    nothingOfAKept()
  }

  /** Fails closed: once the watch holds a mark, a reply without one (an old path, a downgraded phone) wipes and shows nothing of it. */
  @Test fun aReplyWithNoMarkOnceOneIsHeldWipesAndShowsNothing() = runTest {
    keptFromA()
    val phone = Phone(readsAs(null, needsYouA, tafsA))
    val m = model(phone)
    val drawnOfA = watch(m)
    m.refresh()
    assertEquals(emptyList<String>(), drawnOfA)
    assertFalse(m.shown.value is WatchModel.Shown.Read)
    nothingOfAKept()
    assertEquals(1, wipes)
  }

  /** A reply with nothing of an account (a failure, a refusal) proves nothing and wipes nothing. */
  @Test fun aNeutralReplyWipesNothing() = runTest {
    keptFromA()
    val m = model(Phone { Asked.Answered(WatchReply.Failed("Open tafs on your phone")) })
    m.refresh()
    assertEquals(0, wipes)
    assertEquals("Keep this for A", drafts.draft?.text)
    assertTrue("still nothing drawn", m.thread().isEmpty())
  }

  /** A phone before marks, and a watch that never held one: as before marks, what's kept opens at once. */
  @Test fun neverAMarkIsAsBefore() = runTest {
    keptFromA()
    marks.mark = null
    var seen: WatchModel.Shown? = null
    lateinit var m: WatchModel
    m = model(Phone { r -> seen = seen ?: m.shown.value; readsAs(null, needsYouA, tafsA)(r) })
    assertEquals(listOf("wa_a"), m.thread().map { it.second.id })
    m.refresh()
    assertTrue("drawn from the cache before the phone answered", (seen as WatchModel.Shown.Read).updating)
    assertEquals(0, wipes)
  }

  /** The first time the watch hears from an updated phone, what it kept before marks goes, once, before any of the reply is drawn. */
  @Test fun theFirstMarkEverClearsWhatWasKeptBeforeMarksOnce() = runTest {
    keptFromA()
    marks.mark = null
    lateinit var m: WatchModel
    var answered = false
    val drawnAfter = mutableListOf<String>()
    m = model(Phone { r -> answered = true; asB(r) })
    // Before marks, it opens on what it kept, as before (no regression for a phone that never sends marks).
    assertEquals(listOf("wa_a"), m.thread().map { it.second.id })
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.shown.collect { if (answered && ofA(it)) drawnAfter += "shown: $it" } }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.inbox.collect { if (answered && it.isNotEmpty()) drawnAfter += "inbox: $it" } }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.asks.collect { if (answered && "wa_a" in it) drawnAfter += "asks: $it" } }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.kept.collect { if (answered && it != null) drawnAfter += "kept: $it" } }
    m.refresh()
    assertEquals(emptyList<String>(), drawnAfter)
    nothingOfAKept()
    assertTrue(m.thread().isEmpty())
    assertEquals(markB, marks.mark)
    assertEquals(1, wipes)
    // Once: the same mark again wipes nothing more.
    model(Phone(asB)).refresh()
    assertEquals(1, wipes)
  }

  /** Signed out on the phone with the watch off: its kept item is there when the watch comes back on, and wipes with the app closed. */
  @Test fun aSignOutWithTheWatchOffLeavesNothingOfAWhenItsOn() = runTest {
    keptFromA()
    // The Data Layer delivers the signed-out item once the watch is on again (AskResultService, the app closed).
    KeptResults.signedOut(context, KeptSignedOut(t, markA), stores())
    nothingOfAKept()
    assertNull(marks.mark)
    // Then opened: the phone is signed out, or signed in as B; nothing of A either way.
    val m = model(Phone(asB))
    val drawnOfA = watch(m)
    m.refresh()
    assertEquals(emptyList<String>(), drawnOfA)
    assertEquals(markB, marks.mark)
  }

  @Test fun theSignedOutItemWithTheAppOpenWipesTheModel() = runTest {
    keptFromA()
    val m = model(Phone(asA))
    m.refresh()
    KeptResults.signedOut = { m.phoneSignedOut() }
    try {
      KeptResults.signedOut(context, KeptSignedOut(t, markA), stores())
    } finally {
      KeptResults.signedOut = null
    }
    assertEquals(WatchModel.Shown.SignedOut, m.shown.value)
    assertTrue(m.thread().isEmpty() && m.inbox.value.isEmpty() && m.kept.value == null)
    nothingOfAKept()
    assertNull(marks.mark)
  }

  /** A late signed-out item, for a sign-in before the one the watch holds now, never wipes the new one. */
  @Test fun aLateSignedOutItemForAnEarlierSignInWipesNothing() = runTest {
    keptFromA()
    marks.mark = markB
    KeptResults.signedOut(context, KeptSignedOut(t, markA), stores())
    assertEquals("Keep this for A", drafts.draft?.text)
    assertEquals(markB, marks.mark)
    // Nor one naming none, once a mark is held: the phone that gave the watch its mark names it when it signs out.
    KeptResults.signedOut(context, KeptSignedOut(t, null), stores())
    assertEquals("Keep this for A", drafts.draft?.text)
    // Holding none (a phone before marks), its sign-out wipes, as before.
    marks.mark = null
    KeptResults.signedOut(context, KeptSignedOut(t, null), stores())
    nothingOfAKept()
  }

  /** A watch holding no mark takes nothing marked from the phone: an item marked A, then the phone's first reply marked B. */
  @Test fun aKeptItemMarkedAIsNotTakenWhileNoMarkIsHeldThenBShowsNothingOfA() = runTest {
    val m = model(Phone(asB))
    val drawnOfA = watch(m)
    // The phone kept A's answer for the watch before the watch ever held a mark: with the app open (into the model)...
    val item = bot.tafs.watchlink.WatchLink.encodeKept(bot.tafs.watchlink.KeptAsk(t, askA, markA))
    AskResults.shown = { m.took(it) }
    try {
      KeptResults.kept(context, bot.tafs.watchlink.WatchLink.keptAskPath("wa_a"), item, stores()) { true }
    } finally {
      AskResults.shown = null
    }
    // ...and with it closed (into the stores).
    KeptResults.kept(context, bot.tafs.watchlink.WatchLink.keptAskPath("wa_a"), item, stores()) { true }
    // A signed-out item from a phone not connected now is ignored, whatever it says.
    KeptResults.kept(context, bot.tafs.watchlink.WatchLink.KEPT_SIGNED_OUT, bot.tafs.watchlink.WatchLink.encodeKept(KeptSignedOut(t, null)), stores()) { false }
    m.refresh()
    m.reload()
    assertEquals(emptyList<String>(), drawnOfA)
    assertTrue(m.thread().none { it.second.id == "wa_a" })
    nothingOfAKept()
    assertEquals("the reply set the mark, never the item", markB, marks.mark)
  }

  /** A message kept for later goes only to the sign-in it was kept under, even if the stores disagree. */
  @Test fun aQueuedAskFromAIsNeverSentAsB() = runTest {
    keptFromA()
    // B's mark held, A's message still on disk (a race the wipe didn't win): verified as B, then the kept message is dropped.
    marks.mark = markB
    val phone = Phone(asB)
    val m = model(phone)
    m.refresh()
    // The model didn't draw it before B was verified, so take it in as a draft from the store directly.
    drafts.draft = WatchModel.Draft("Keep this for A", "w_000000cc", null, markA)
    m.reload()
    m.sendKept()
    assertFalse(phone.asked.any { it is WatchRequest.Ask })
  }

  /**
   * The phone as it answers a marked act: on another sign-in, signed out, and nothing reaches tafs cloud. It switches to [next]
   * once it has answered Your tafs: after the watch verified A, before the kept message is sent.
   */
  private inner class SwitchingPhone(var current: String, val next: String? = null) : PhoneLink {
    val toCloud = mutableListOf<WatchRequest>()
    val marksSent = mutableListOf<String?>()
    override suspend fun ask(request: WatchRequest): Asked = ask(request, null)
    override suspend fun ask(request: WatchRequest, mark: String?): Asked {
      marksSent += mark
      if (mark != null && mark != current) return Asked.Answered(WatchReply.SignedOut)
      val answer = (if (current == markA) asA else asB)(request)
      if (request is WatchRequest.Ask) toCloud += request
      if (request is WatchRequest.Tafs && next != null) current = next
      return answer
    }
  }

  /** The race closed: a message kept under A, the phone switched to B between the watch's check and the send. */
  @Test fun aKeptMessageFromAIsRefusedByAPhoneThatSwitchedToBAfterTheCheck() = runTest {
    keptFromA()
    val phone = SwitchingPhone(markA, next = markB)
    val m = model(phone)
    m.refresh()
    assertTrue("the act went with A's mark", markA in phone.marksSent)
    assertTrue("nothing reached tafs cloud", phone.toCloud.isEmpty())
    assertEquals(WatchModel.Shown.SignedOut, m.shown.value)
    nothingOfAKept()
    assertNull(drafts.draft)
    assertNull(marks.mark)
  }

  /** A phone before marks never sees the field: the watch sends none until a reply has carried one. */
  @Test fun aPhoneBeforeMarksNeverSeesTheField() = runTest {
    keptFromA()
    marks.mark = null
    drafts.draft = WatchModel.Draft("Keep this", "w_000000cc", null, null)
    val sent = mutableListOf<String?>()
    val phone = object : PhoneLink {
      override suspend fun ask(request: WatchRequest): Asked = ask(request, null)
      override suspend fun ask(request: WatchRequest, mark: String?): Asked { sent += mark; return readsAs(null, needsYouA, tafsA)(request) }
    }
    model(phone).refresh()
    assertTrue(sent.isNotEmpty())
    assertTrue(sent.all { it == null })
  }

  private class SignIn : WatchSignIn {
    var out = false
    var waiting: Pair<String, Long>? = null
    override fun signedOut() = out
    override fun signOut() { out = true; waiting = null }
    override fun signIn() { out = false; waiting = null }
    override fun asked() = waiting
    override fun ask(id: String, at: Long) { waiting = id to at }
    override fun forgetAsk() { waiting = null }
  }

  private fun signedOutModel(signIn: SignIn) = WatchModel(
    Phone(asA), now = { t }, unlocked = { true }, secure = { true }, tile = TileCount(tile), newClientId = { "w_000000bb" },
    reviewFirst = true, drafts = drafts, inboxStore = inbox, cache = cache, thread = thread, marks = marks, wiped = { wipes++ },
    signIn = signIn,
  ).also { it.signOutHere() }

  /**
   * Row 18, as on the Apple Watch (tafs#2132): the phone's Allow signs the watch in only with the phone's account mark, and that mark
   * is heard as a checked reply's is. Another sign-in's wipes what's kept first and becomes the one held; the same one wipes nothing.
   */
  @Test fun anAllowSignsInOnlyWithThePhonesMarkAndHearsIt() = runTest {
    marks.mark = markA
    val signIn = SignIn()
    val m = signedOutModel(signIn)
    // No mark, or one that isn't a mark: still signed out, and the watch says it couldn't talk to the phone.
    for (bad in listOf(null, "not a mark")) {
      signIn.ask("w_000000s1", t)
      assertFalse(m.signInAnswered(bot.tafs.watchlink.KeptSignIn(t, "w_000000s1", allowed = true, mark = bad)))
      assertTrue(m.signedOutHere.value)
      assertTrue(m.signInAsk.value is WatchModel.SignInAsk.NotReached)
      assertNull("the ask is spent", signIn.asked())
    }
    // B's Allow: A's mark goes, everything kept is wiped first, and B's is held.
    val before = wipes
    signIn.ask("w_000000s2", t)
    assertTrue(m.signInAnswered(bot.tafs.watchlink.KeptSignIn(t, "w_000000s2", allowed = true, mark = markB)))
    assertFalse(m.signedOutHere.value)
    assertEquals(markB, marks.mark)
    assertEquals(before + 1, wipes)
    // The same sign-in again: nothing more is wiped.
    m.signOutHere()
    val after = wipes
    signIn.ask("w_000000s3", t)
    assertTrue(m.signInAnswered(bot.tafs.watchlink.KeptSignIn(t, "w_000000s3", allowed = true, mark = markB)))
    assertEquals(after, wipes)
    assertEquals(markB, marks.mark)
  }

  /** The same with the app closed (the kept item reaches [KeptResults]): a missing mark keeps it signed out; another's wipes first. */
  @Test fun anAllowWithTheAppClosedNeedsTheMarkToo() {
    val signIn = SignIn().apply { out = true }
    keptFromA()
    // No mark: still signed out, nothing of A touched, the ask spent.
    signIn.ask("w_000000s4", t)
    KeptResults.signInAnswer(context, bot.tafs.watchlink.KeptSignIn(t, "w_000000s4", allowed = true), stores(), now = t, store = signIn)
    assertTrue(signIn.out)
    assertNull(signIn.asked())
    assertEquals(markA, marks.mark)
    assertEquals("Keep this for A", drafts.draft?.text)
    // B's Allow: A's kept items go, B's mark is held, and the watch is signed in.
    signIn.ask("w_000000s5", t)
    KeptResults.signInAnswer(context, bot.tafs.watchlink.KeptSignIn(t, "w_000000s5", allowed = true, mark = markB), stores(), now = t, store = signIn)
    assertFalse(signIn.out)
    nothingOfAKept()
    assertEquals(markB, marks.mark)
    // A's own Allow keeps what's A's.
    keptFromA()
    signIn.out = true
    signIn.ask("w_000000s6", t)
    KeptResults.signInAnswer(context, bot.tafs.watchlink.KeptSignIn(t, "w_000000s6", allowed = true, mark = markA), stores(), now = t, store = signIn)
    assertFalse(signIn.out)
    assertEquals("Keep this for A", drafts.draft?.text)
  }

  @Test fun theRulesForKeptItems() {
    assertTrue("a phone before marks", AccountMark.keptTaken(null, null))
    assertFalse("marked, while none is held", AccountMark.keptTaken(null, markA))
    assertTrue(AccountMark.keptTaken(markA, markA))
    assertFalse("another sign-in's", AccountMark.keptTaken(markA, markB))
    assertFalse("none, once one is held", AccountMark.keptTaken(markA, null))
    assertEquals(AccountMark.Seen.Missing, AccountMark.seen(markA, null))
    assertEquals(AccountMark.Seen.Legacy, AccountMark.seen(null, null))
    assertEquals(AccountMark.Seen.Changed, AccountMark.seen(markA, markB))
  }
}
