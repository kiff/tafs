package bot.tafs.wear

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.MaterialTheme
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.ChatLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Chatting with a taf from the watch (docs/watch-wear.md; the owner's picks 1A, 2A and Read more): the words go to the taf the
 * person chose, named at every start, never to Coach; its chat's turns are kept nowhere (no Inbox, no Coach conversation); a
 * long answer is read whole only on Read more.
 */
class TafChatModelTest {
  private val t = 1_763_244_000_000L
  private val parcels = TafLine("taf_1", "Late parcels", "Working", halted = false)
  private val sleepy = TafLine("taf_2", "Overdue reminders", "Paused", halted = false, paused = true)
  private val tafs = WatchReply.Tafs(listOf(parcels, sleepy), allHalted = false)
  private val page = WatchReply.TafPage("taf_1", "Late parcels", "Working", halted = false, job = "Reships late parcels")
  private val history = listOf(ChatLine("m1", mine = true, at = "2026-11-19T20:00:00Z", text = "Anything late today?"),
    ChatLine("m2", mine = false, at = "2026-11-19T20:00:05Z", text = "Two parcels, both reshipped."))
  private val taken = AskLine("wa_1", "w_00000001", "accepted", "Late parcels", words = "Sent", updated = "2026-11-20T01:00:00Z", kind = AskLine.TAF)

  private class Phone(val answers: (WatchRequest) -> Asked) : PhoneLink {
    val asked = mutableListOf<WatchRequest>()
    override suspend fun ask(request: WatchRequest): Asked = answers(request).also { asked += request }
  }

  private fun reads(r: WatchRequest): Asked = when (r) {
    is WatchRequest.Taf -> Asked.Answered(page)
    is WatchRequest.TafChat -> Asked.Answered(WatchReply.TafChat(history))
    is WatchRequest.Ask -> Asked.Answered(WatchReply.AskTaken(taken))
    is WatchRequest.AskFull -> Asked.Answered(WatchReply.AskFull("Two parcels were late. Both are reshipped, the second one twice."))
    is WatchRequest.NeedsYou -> Asked.Answered(WatchReply.NeedsYou(0, "Nothing needs you", emptyList()))
    else -> Asked.Answered(tafs)
  }

  private suspend fun model(phone: Phone) =
    WatchModel(phone, now = { t }, unlocked = { true }, newClientId = { "w_00000001" }, reviewFirst = true).also {
      it.tafChatOn = true
      it.refresh()
    }

  /** On since Wear 0.4.2, once the privacy paragraph was live (kiff/cloud#19); tafs cloud's own switch still decides per account. */
  @Test fun theWatchTafChatShipsOn() {
    assertTrue(WATCH_TAF_CHAT)
    assertTrue(WatchModel(Phone(::reads), now = { t }).tafChatOn)
  }

  @Test fun openingATafReadsItsLastTurnsFromThePhone() = runTest {
    val phone = Phone(::reads)
    val m = model(phone)
    m.openTaf("taf_1")
    assertEquals(WatchModel.TafChat.History.Read(history), m.taf.value?.chat?.history)
    assertTrue(phone.asked.contains(WatchRequest.TafChat(t, "taf_1")))
  }

  @Test fun withTheSwitchOffNoChatIsRead() = runTest {
    val phone = Phone(::reads)
    val m = model(phone).also { it.tafChatOn = false }
    m.openTaf("taf_1")
    assertTrue(phone.asked.none { it is WatchRequest.TafChat })
    assertEquals(WatchModel.TafChat.History.Reading, m.taf.value?.chat?.history)
  }

  /** The words go to the taf the chat is for, titled with its name, and the turn shows in its chat: never Coach's, never the Inbox. */
  @Test fun aMessageFromATafsChatGoesToThatTafAndIsKeptNowhere() = runTest {
    val phone = Phone(::reads)
    val m = model(phone)
    m.openTaf("taf_1")
    m.review("Reship order 5098", tafId = "taf_1")
    val review = m.voice.value as WatchModel.Voice.Review
    assertEquals("taf_1", review.tafId)
    assertEquals("Late parcels", review.tafName)
    m.sendReviewed()
    assertEquals("taf_1", (phone.asked.last { it is WatchRequest.Ask } as WatchRequest.Ask).tafId)
    assertNull("back to the chat", m.voice.value)
    assertEquals(listOf("Reship order 5098"), m.taf.value?.chat?.turns?.map { it.said })
    assertTrue("not Coach's", m.asks.value.isEmpty() && m.thread().isEmpty())
    // Its answer comes in: the turn changes, and still nothing of it is kept.
    m.took(taken.copy(state = AskLine.DONE, line = "Reshipped, tracking sent.", updated = "2026-11-20T01:00:09Z"))
    assertEquals("Reshipped, tracking sent.", m.taf.value?.chat?.turns?.single()?.ask?.line)
    assertTrue("no Inbox line", m.inbox.value.isEmpty())
    assertNull("no result card", m.result.value)
    assertTrue(m.asks.value.isEmpty())
  }

  /** A start of Coach's after a taf's never sends to the taf: each start names its target. */
  @Test fun coachsStartAfterATafsNeverSendsToTheTaf() = runTest {
    val phone = Phone(::reads)
    val m = model(phone)
    m.openTaf("taf_1")
    m.readyToRecord(tafId = "taf_1")
    m.closeVoice()
    m.review("What needs me today?")
    assertNull((m.voice.value as WatchModel.Voice.Review).tafId)
    m.readyToRecord()
    assertTrue(m.listen())
    m.heard("Move my call")
    assertNull((m.voice.value as WatchModel.Voice.Review).tafId)
  }

  /** A taf chat's request read back (after a relaunch, say) is marked by tafs cloud and kept nowhere either. */
  @Test fun aTafChatsRequestReadBackIsKeptNowhere() = runTest {
    val m = model(Phone(::reads))
    m.took(taken.copy(id = "wa_9", state = AskLine.DONE, line = "Done", updated = "2026-11-20T01:00:09Z"))
    assertTrue(m.asks.value.isEmpty() && m.inbox.value.isEmpty())
  }

  @Test fun readMoreReadsTheWholeAnswerOnlyOnTheTapElseTheRestIsOnThePhone() = runTest {
    var full: Asked = Asked.Answered(WatchReply.AskFull("Two parcels were late. Both are reshipped, the second one twice."))
    val phone = Phone { if (it is WatchRequest.AskFull) full else reads(it) }
    val m = model(phone)
    m.openTaf("taf_1")
    m.review("Anything late?", tafId = "taf_1")
    m.sendReviewed()
    assertTrue("nothing read before the tap", phone.asked.none { it is WatchRequest.AskFull })
    m.readMore("wa_1")
    assertEquals(WatchModel.Whole.Read("Two parcels were late. Both are reshipped, the second one twice."), m.taf.value?.chat?.whole?.get("wa_1"))
    assertEquals(WatchRequest.AskFull(t, "wa_1"), phone.asked.last())
    // Not given (a run not finished, the taf no longer the person's): the rest is on the phone.
    full = Asked.Answered(WatchReply.Failed("The rest is on your phone."))
    m.openTaf("taf_1")
    m.review("Again?", tafId = "taf_1")
    m.sendReviewed()
    m.readMore("wa_1")
    assertEquals(WatchModel.Whole.OnPhone, m.taf.value?.chat?.whole?.get("wa_1"))
  }

  /** Not sure a taf's message arrived: Retry or Cancel, never Keep for later (kept on the watch, then sent as Coach's). */
  @Test fun aTafsMessageIsNeverKeptForLater() = runTest {
    val drafts = mutableListOf<WatchModel.Draft>()
    val phone = Phone { if (it is WatchRequest.Ask) Asked.Unreachable else reads(it) }
    val m = WatchModel(phone, now = { t }, unlocked = { true }, newClientId = { "w_00000001" }, reviewFirst = true,
      drafts = object : DraftStore { override fun read() = drafts.lastOrNull(); override fun write(d: WatchModel.Draft) { drafts += d }; override fun clear() { drafts.clear() } })
    m.tafChatOn = true
    m.refresh()
    m.openTaf("taf_1")
    m.review("Reship order 5098", tafId = "taf_1")
    m.sendReviewed()
    val unsure = m.voice.value as WatchModel.Voice.Unsure
    assertEquals("taf_1", unsure.tafId)
    m.keepForLater()
    assertTrue("nothing kept on the watch", drafts.isEmpty())
    assertNull(m.kept.value)
    assertEquals("still Retry or Cancel", unsure, m.voice.value)
  }

  @Test fun theFootUnderAnAnswer() {
    val long = taken.copy(state = AskLine.DONE, more = true)
    assertEquals(WatchModel.Foot.ReadMore, footOf(long, null))
    assertEquals(WatchModel.Foot.Reading, footOf(long, WatchModel.Whole.Reading))
    assertNull("whole once read", footOf(long, WatchModel.Whole.Read("all")))
    assertEquals(WatchModel.Foot.OnPhone, footOf(long, WatchModel.Whole.OnPhone))
    assertNull("a short answer has none", footOf(long.copy(more = false), null))
  }

  @Test fun aPausedTafStillTakesMessages() = runTest {
    val m = model(Phone(::reads))
    m.openTaf("taf_2")
    assertTrue(m.taf.value!!.paused)
  }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = WatchApplication::class)
class TafChatScreenTest {
  @get:Rule val compose = createComposeRule()

  @Before fun words() {
    RuntimeEnvironment.setQualifiers("en-w192dp-h2400dp-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
  }

  private val did = mutableListOf<String>()
  private val actions = Actions(
    openOnPhone = { did += "phone $it" }, tryAgain = {}, refuse = {}, halt = {}, askHaltAll = {}, confirmHaltAll = {}, cancelHaltAll = {},
    listen = {}, closeVoice = {}, retry = {}, open = {}, hear = {}, reply = {}, closeResult = {}, dismiss = {},
    messageTaf = { did += "message $it" }, typeTaf = { did += "type $it" }, quickTaf = { taf, text -> did += "quick $taf $text" },
    readMore = { did += "more $it" },
  )
  private val page = WatchReply.TafPage("taf_1", "Late parcels", "Working", halted = false, job = "Reships late parcels")
  private val turn = WatchModel.TafTurn("Anything late?", 0L,
    AskLine("wa_1", "w_1", AskLine.DONE, "Late parcels", line = "Two parcels were late…", words = "Done", updated = "u", more = true, kind = AskLine.TAF))

  private fun show(open: WatchModel.OpenTaf) = compose.setContent {
    MaterialTheme { TafScreen(open, null, actions, openInbox = {}, chat = true, pager = rememberPagerState { 3 }) }
  }

  @Test fun aTafOpensItsChatFirstWithMessageTypeAndQuickReply() {
    val chat = WatchModel.TafChat(WatchModel.TafChat.History.Read(listOf(ChatLine("m1", true, "a", "Anything late today?"))), listOf(turn))
    show(WatchModel.OpenTaf("taf_1", null, WatchModel.TafRead.Read(page), chat))
    compose.onNodeWithText("Anything late today?").assertExists()
    compose.onNodeWithText("Two parcels were late…").assertExists()
    compose.onNodeWithText(Words.Watch.readMore).performScrollTo().performClick()
    compose.onNodeWithText(Words.Chat.messageTaf("Late parcels")).performScrollTo().performClick()
    compose.onNodeWithText(Words.Watch.type).performScrollTo().performClick()
    assertEquals(listOf("more wa_1", "message taf_1", "type taf_1"), did)
  }

  @Test fun aPausedTafSaysItStillAnswersAndAHaltedOneSendsNothing() {
    show(WatchModel.OpenTaf("taf_2", TafLine("taf_2", "Overdue reminders", "Paused", halted = false, paused = true), WatchModel.TafRead.Asking))
    compose.onNodeWithText(Words.Watch.pausedStillAnswers).assertExists()
    compose.onNodeWithText(Words.Chat.messageTaf("Overdue reminders")).assertExists()
  }

  @Test fun aHaltedTafsChatSendsNothing() {
    show(WatchModel.OpenTaf("taf_3", TafLine("taf_3", "Deploys", "Halted", halted = true), WatchModel.TafRead.Asking))
    compose.onAllNodesWithText(Words.Chat.messageTaf("Deploys")).assertCountEquals(0)
    compose.onNodeWithText("↗ ${Words.Watch.resumeOnPhone}").assertExists()
  }
}
