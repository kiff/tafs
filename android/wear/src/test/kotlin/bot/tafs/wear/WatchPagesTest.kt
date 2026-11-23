package bot.tafs.wear

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.MaterialTheme
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import org.junit.Assert.assertNull
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performRotaryScrollInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Wear v2's pages (docs/watch-wear.md, Version 2), step (ii): Coach home with the microphone, the pages side by side, and the menu
 * with Halt at its foot. Off on a watch until every step is in.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = WatchApplication::class)
class WatchPagesTest {
  @get:Rule val compose = createComposeRule()

  @Before fun words() {
    // Tall, so a whole page is drawn at once and every line can be found.
    RuntimeEnvironment.setQualifiers("en-w192dp-h2400dp-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
  }

  private val call = HeldLine("hold_1", "Late parcels", "Reship order #5098", "€12", "Reships wait for your yes", refusable = true)
  private val tafs = WatchModel.TafsRead(
    listOf(TafLine("taf_1", "Late parcels", "Waiting for you", halted = false), TafLine("taf_2", "Support replies", "Next run 14:00", halted = false)),
    allHalted = false,
  )
  private val read = WatchModel.Shown.Read(WatchModel.NeedsYouRead(1, "1 held call needs you", listOf(call)), tafs)
  private val answered = AskLine(id = "ask_1", clientId = "c1", state = "done", tafName = "Support replies", words = "Answered", updated = "2026-11-19T08:00:00Z")

  private val opened = mutableListOf<AskLine>()
  private val did = mutableListOf<String>()
  private val actions = Actions(
    openOnPhone = {}, tryAgain = {}, refuse = {}, halt = {}, askHaltAll = {}, confirmHaltAll = {},
    cancelHaltAll = {}, listen = { did += "listen" }, closeVoice = { did += "close" }, retry = {}, open = { opened += it }, hear = {}, reply = {}, closeResult = { did += "close result" }, dismiss = {},
    start = { did += "start ${it ?: ""}".trim() }, stop = { did += "stop" }, send = { did += "send" }, rerecord = { did += "rerecord" },
    delete = { did += "delete" }, type = { did += "type" }, quick = { did += "quick $it" },
    keep = { did += "keep" }, deleteKept = { did += "delete kept" }, pickToHalt = { did += "pick to halt" },
    openItem = { did += "open ${it.id}" }, dismissItem = { did += "dismiss $it" }, clearRead = { did += "clear read" },
  )

  private fun show(
    shown: WatchModel.Shown,
    page: Page = Page.Coach,
    ready: List<AskLine> = emptyList(),
    confirmingHaltAll: Boolean = false,
    pages: List<Page> = pages(false),
    voice: WatchModel.Voice? = null,
    kept: WatchModel.Draft? = null,
    thread: List<Pair<WatchModel.Said, AskLine>> = emptyList(),
  ) =
    compose.setContent {
      MaterialTheme {
        WatchPages(
          shown, outcome = null, confirmingHaltAll = confirmingHaltAll, voice = voice, result = null, working = emptyList(), ready = ready, act = actions,
          pages = pages, pager = rememberPagerState(initialPage = pages.indexOf(page)) { pages.size }, kept = kept, thread = thread,
        )
      }
    }

  @Test fun v2StaysOffUntilEveryStepIsIn() {
    assertFalse("a watch shows v1 whole until the last v2 step lands", WATCH_V2)
  }

  @Test fun thePagesAreCoachInboxTafsAndAccountWithBriefOnlyOnItsSwitch() {
    assertEquals(listOf(Page.Coach, Page.Inbox, Page.Tafs, Page.Account), pages(briefOn = false))
    assertEquals(listOf(Page.Coach, Page.Inbox, Page.Tafs, Page.Brief, Page.Account), pages(briefOn = true))
    assertEquals(pages(BRIEF_ON_WATCH), pages())
  }

  @Test fun coachsHomeIsTheMicrophoneThenWhatWaitsInTheInbox() {
    show(read, ready = listOf(answered))
    compose.onNodeWithText(Words.Coach.ask).assertExists()
    compose.onNodeWithText(Words.Watch.voiceTextOnly).assertExists()
    // One held call and one result not yet opened.
    compose.onNodeWithText(Words.Watch.inboxUnread(2)).performClick()
    compose.waitForIdle()
    compose.onNodeWithText("Reship order #5098").assertExists()
    compose.onNodeWithText("Support replies · Answered").performClick()
    assertEquals(listOf(answered), opened)
  }

  @Test fun theMicrophoneIsNamedForCoach() {
    show(read)
    compose.onNodeWithText(Words.Coach.ask).assertExists()
    compose.onAllNodesWithText(Words.Watch.ask).assertCountEquals(0)
    compose.onAllNodesWithText(Words.Watch.inboxUnread(1)).assertCountEquals(1)
  }

  private val none = read.copy(needsYou = WatchModel.NeedsYouRead(0, "Nothing needs you", emptyList()))

  @Test fun withNothingWaitingHomeShowsNoCount() {
    show(none)
    compose.onAllNodesWithText(Words.Watch.inboxUnread(0)).assertCountEquals(0)
  }

  @Test fun anEmptyInboxSaysWhatLandsThere() {
    show(none, page = Page.Inbox)
    compose.onNodeWithText(Words.Watch.Page.inbox).assertExists()
    compose.onNodeWithText(Words.Watch.inboxEmpty).assertExists()
  }

  @Test fun tafsShowsEachTafsStateAndNoHalt() {
    show(read, page = Page.Tafs)
    for (words in listOf(Words.Home.title, "Late parcels", "Waiting for you", "Support replies", "Next run 14:00")) compose.onNodeWithText(words).assertExists()
    compose.onAllNodesWithText(Words.Halt.now).assertCountEquals(0)
    compose.onAllNodesWithText(Words.Halt.allNow).assertCountEquals(0)
  }

  @Test fun accountSaysWhatTheWatchHoldsAndCantDo() {
    show(read, page = Page.Account)
    compose.onNodeWithText(Words.Settings.title).assertExists()
    compose.onNodeWithText(Words.Watch.trust).assertExists()
  }

  @Test fun theMenuNamesEveryPageAndHaltIsAtItsFoot() {
    show(read)
    compose.onNodeWithText(Words.Watch.menu).performClick()
    for (page in pages(false)) compose.onNodeWithText(page.title).assertExists()
    // Step (v): Halt opens Choose which to halt, which asks once more (TafScreensTest).
    compose.onNodeWithText(Words.Taf.Halt.title).performClick()
    assertEquals(listOf("pick to halt"), did)
  }

  @Test fun theMenuGoesToAPage() {
    show(read)
    compose.onNodeWithText(Words.Watch.menu).performClick()
    compose.onNodeWithText(Words.Home.title).performClick()
    compose.waitForIdle()
    compose.onNodeWithText("Next run 14:00").assertExists()
  }

  @Test fun withEveryTafHaltedTheMenuOffersNoHalt() {
    show(read.copy(tafs = tafs.copy(allHalted = true)))
    compose.onNodeWithText(Words.Watch.menu).performClick()
    compose.onAllNodesWithText(Words.Taf.Halt.title).assertCountEquals(0)
  }

  @Test fun beforeThePhoneAnswersHomeSaysSoAsV1() {
    show(WatchModel.Shown.SignedOut)
    compose.onNodeWithText(Words.Coach.ask).assertExists()
    compose.onNodeWithText(Words.Watch.signInOnPhone).assertExists()
  }

  @Test fun beforeThePhoneAnswersAPageSaysSoAsV1() {
    show(WatchModel.Shown.SignedOut, page = Page.Tafs)
    compose.onNodeWithText(Words.Watch.signInOnPhone).assertExists()
  }

  // MARK: Step (iii): record, check, send. Nothing leaves the watch before Send.

  @Test fun theMicrophoneOpensReadyAndStartsOnStart() {
    show(read, voice = WatchModel.Voice.Ready("ask_1"))
    compose.onNodeWithText("${Words.Watch.ready} · 30 s").assertExists()
    compose.onNodeWithText(Words.Watch.tapStart).assertExists()
    compose.onNodeWithText("0:00 / 0:30").assertExists()
    // Start is its own control: the brand's round button with the microphone, never Stop's.
    compose.onAllNodesWithTag(RECORD_STOP).assertCountEquals(0)
    compose.onNodeWithTag(RECORD_START).assertContentDescriptionEquals(Words.Coach.start).performClick()
    assertEquals(listOf("start ask_1"), did)
  }

  @Test fun listeningShowsTheWordsAsHeardAndStop() {
    show(read, voice = WatchModel.Voice.Listening("Move my call", null, since = System.currentTimeMillis()))
    compose.onNodeWithText("● ${Words.Watch.recording} · 30 s").assertExists()
    compose.onNodeWithText("Move my call").assertExists()
    // Stop is another control, not Start relabelled: the red one with the square.
    compose.onAllNodesWithTag(RECORD_START).assertCountEquals(0)
    compose.onNodeWithTag(RECORD_STOP).assertContentDescriptionEquals(Words.Chat.stop).performClick()
    compose.onNodeWithText(Words.Common.cancel).performClick()
    assertEquals(listOf("stop", "close"), did)
  }

  @Test fun theReviewShowsTheWordsInFullWithReRecordDeleteAndSend() {
    val long = "Ask Late parcels to tell me before it sends any new parcel today, and keep the customer posted. For order 5098, wait until " +
      "tomorrow morning: the carrier said the scan may come late. If it still hasn't moved by then, send the new parcel and tell the customer."
    show(read, voice = WatchModel.Voice.Review(long, null))
    compose.onNodeWithText(Words.Watch.review).assertExists()
    compose.onNodeWithText(long).assertExists()
    compose.onNodeWithContentDescription(Words.Watch.rerecord).performClick()
    compose.onNodeWithContentDescription(Words.Chat.delete).performClick()
    compose.onNodeWithContentDescription(Words.Common.send).performClick()
    assertEquals(listOf("rerecord", "delete", "send"), did)
  }

  // MARK: The round face (the Galaxy Watch 5 test, W8 and W9)

  /** The Galaxy Watch 5's 40 mm face, the smaller: 198 dp across, round. */
  private fun roundFace() {
    RuntimeEnvironment.setQualifiers("en-w198dp-h198dp-round-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
  }

  /** True when every corner of [box] lies inside the round face the root draws on. */
  private fun insideTheFace(box: androidx.compose.ui.unit.DpRect, margin: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)): Boolean {
    val face = compose.onRoot().getBoundsInRoot()
    val r = (face.right - face.left) / 2 - margin
    val cx = face.left + (face.right - face.left) / 2
    val cy = face.top + (face.right - face.left) / 2
    return listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom).all { (x, y) ->
      val dx = (x - cx).value
      val dy = (y - cy).value
      dx * dx + dy * dy <= r.value * r.value
    }
  }

  /** A round button's own circle, inside the face: its centre's distance from the face's centre plus its radius. */
  private fun roundInsideTheFace(box: androidx.compose.ui.unit.DpRect): Boolean {
    val face = compose.onRoot().getBoundsInRoot()
    val r = (face.right - face.left) / 2
    val dx = ((box.left + box.right) / 2 - (face.left + r)).value
    val dy = ((box.top + box.bottom) / 2 - (face.top + r)).value
    return kotlin.math.sqrt(dx * dx + dy * dy) + (box.right - box.left).value / 2 <= r.value
  }

  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test fun theReviewKeepsDeleteAgainAndSendInReachWithoutScrolling() {
    roundFace()
    show(read, voice = WatchModel.Voice.Review(WearV2Screenshots.LONG, null))
    // Nothing scrolled: each is on the face, inside its curve, named for TalkBack, and does what it says.
    for (tag in listOf(REVIEW_DELETE, REVIEW_AGAIN, REVIEW_SEND)) {
      val button = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
      assertTrue("$tag reaches past the round face", roundInsideTheFace(button.getBoundsInRoot()))
    }
    compose.onNodeWithTag(REVIEW_DELETE).assertContentDescriptionEquals(Words.Chat.delete).performClick()
    compose.onNodeWithTag(REVIEW_AGAIN).assertContentDescriptionEquals(Words.Watch.rerecord).performClick()
    compose.onNodeWithTag(REVIEW_SEND).assertContentDescriptionEquals(Words.Common.send).performClick()
    assertEquals(listOf("delete", "rerecord", "send"), did)
  }

  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test fun aLongReviewScrollsOnItsOwnToItsLastLineAboveTheButtons() {
    roundFace()
    show(read, voice = WatchModel.Voice.Review(WearV2Screenshots.LONG, null))
    val words = compose.onNodeWithText(WearV2Screenshots.LONG)
    val face = compose.onRoot().getBoundsInRoot()
    assertTrue("the long message fits without scrolling: the test proves nothing", words.getUnclippedBoundsInRoot().bottom > face.bottom)
    // The words scroll, the buttons stay: to the end, the last line rests above them.
    repeat(6) { compose.onNode(androidx.compose.ui.test.hasScrollAction()).performTouchInput { swipeUp() } }
    compose.waitForIdle()
    val highest = listOf(REVIEW_DELETE, REVIEW_AGAIN, REVIEW_SEND).minOf { compose.onNodeWithTag(it).assertIsDisplayed().getBoundsInRoot().top }
    assertTrue("the last line ends under the buttons", words.getUnclippedBoundsInRoot().bottom <= highest)
  }

  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test fun theRecordingScreensTopLineIsShortAndBelowTheCurve() {
    roundFace()
    show(read, voice = WatchModel.Voice.Listening("Move my call", null, since = System.currentTimeMillis()))
    val line = compose.onNodeWithTag(RECORD_STATE)
    line.assertTextEquals("● ${Words.Watch.recording} · 30 s")
    // TalkBack reads the limit in full.
    line.assertContentDescriptionEquals("● ${Words.Watch.recording} · ${Words.Watch.limit}")
    assertTrue("the top line meets the face's curve", insideTheFace(line.getBoundsInRoot()))
  }

  /** On the 40 mm face, the heard words' two lines stay clear of the curve, with room to spare. */
  @Test fun theHeardWordsStayInsideTheFaceAt40mm() {
    roundFace()
    show(read, voice = WatchModel.Voice.Listening("Move my call with Nord Atelier to Thursday at ten, and tell Ivo", null, since = System.currentTimeMillis()))
    val words = compose.onNodeWithTag(RECORD_HEARD).getBoundsInRoot()
    assertTrue("the heard words run to the face's edge: $words", insideTheFace(words, androidx.compose.ui.unit.Dp(4f)))
  }

  /** Row 44, D: the last read on screen while a fresh one comes shows one small dot, named for TalkBack; a fresh read, none. */
  @Test fun anUpdatingReadShowsItsDotAndAFreshOneDoesnt() {
    show(read.copy(updating = true))
    compose.onNodeWithTag(UPDATING).assertContentDescriptionEquals(Words.Common.loading)
  }

  @Test fun aFreshReadShowsNoUpdatingDot() {
    show(read)
    compose.onAllNodesWithTag(UPDATING).assertCountEquals(0)
  }

  @Test fun deletedSaysNothingWasSent() {
    show(read, voice = WatchModel.Voice.Deleted)
    compose.onNodeWithText(Words.Watch.deletedNothingSent).assertExists()
  }

  @Test fun typeAndQuickReplyAreOnCoachsHome() {
    show(read)
    compose.onNodeWithText(Words.Watch.type).performClick()
    compose.onNodeWithText(Words.Watch.quickReply).performClick()
    for (line in quickReplies()) compose.onNodeWithText(line).assertExists()
    compose.onNodeWithText(Words.Watch.Qr.needsMeToday).performClick()
    assertEquals(listOf("type", "quick ${Words.Watch.Qr.needsMeToday}"), did)
    // Picked: back to the pages, the review shown by the model.
    compose.onNodeWithText(Words.Coach.ask).assertExists()
  }

  // MARK: Keep for later

  @Test fun notSureItArrivedOffersKeepForLater() {
    show(read, voice = WatchModel.Voice.Unsure("Move my call", "w_00000001", null))
    compose.onNodeWithContentDescription(Words.Watch.retry).assertExists()
    compose.onNodeWithText(Words.Watch.keepForLater).performClick()
    assertEquals(listOf("keep"), did)
  }

  @Test fun aKeptMessageShowsOnCoachsHomeWithDelete() {
    show(read, kept = WatchModel.Draft("Move my call to six", "w_00000001", null))
    compose.onNodeWithText("Move my call to six").assertExists()
    compose.onNodeWithText(Words.Watch.keptToSend).assertExists()
    compose.onNodeWithText(Words.Chat.delete).performClick()
    assertEquals(listOf("delete kept"), did)
  }

  @Test fun withNothingKeptHomeSaysNothingOfIt() {
    show(read)
    compose.onAllNodesWithText(Words.Watch.keptToSend).assertCountEquals(0)
  }

  @Test fun v1OffersNoKeepForLater() {
    compose.setContent { MaterialTheme { VoiceScreen(WatchModel.Voice.Unsure("Move my call", "w_00000001", null), Actions.none) } }
    compose.onNodeWithContentDescription(Words.Watch.retry).assertExists()
    compose.onAllNodesWithText(Words.Watch.keepForLater).assertCountEquals(0)
  }

  // MARK: Play and Pause

  private class FakePlayback : Playback {
    override val playing = kotlinx.coroutines.flow.MutableStateFlow(false)
    var toggled = 0
    override fun toggle(audio: VoiceAudio) { toggled++; playing.value = !playing.value }
    override fun positionMs() = 3_000L
    override fun release() {}
  }

  private val recording = object : VoiceAudio {
    override val durationMs = 12_000L
    override fun delete() {}
  }

  @Test fun theReviewPlaysAndPausesItsRecordingFromWhereItWas() {
    val playback = FakePlayback()
    compose.setContent { MaterialTheme { VoiceScreenV2(WatchModel.Voice.Review("Move my call", null, recording), Actions(
      openOnPhone = {}, tryAgain = {}, refuse = {}, halt = {}, askHaltAll = {}, confirmHaltAll = {}, cancelHaltAll = {}, listen = {}, closeVoice = {},
      retry = {}, open = {}, hear = {}, reply = {}, closeResult = {}, dismiss = {}, playback = playback)) } }
    compose.onNodeWithText("0:03 / 0:12").assertExists()
    compose.onNodeWithTag(PLAYBACK_PLAY).assertExists().performClick()
    compose.onNodeWithTag(PLAYBACK_PAUSE).assertExists()
    compose.onNodeWithContentDescription(Words.Taf.pause).performClick()
    compose.onNodeWithTag(PLAYBACK_PLAY).assertExists()
    assertEquals(2, playback.toggled)
  }

  @Test fun aReviewWithNoRecordingHasNoPlay() {
    show(read, voice = WatchModel.Voice.Review("What needs me today?", null))
    compose.onAllNodesWithText(Words.Watch.play).assertCountEquals(0)
    compose.onAllNodesWithContentDescription(Words.Watch.play).assertCountEquals(0)
  }

  // MARK: Ask Coach as one conversation (W4, the owner-approved mockup)

  /** Coach's page shows today's turns from this watch: the person's words, then Coach's answer, which opens its card. */
  @Test fun coachsPageShowsTheConversationAndAnAnswerOpens() {
    val working = answered.copy(id = "ask_2", state = "working", words = "Working", line = null)
    show(read, thread = listOf(
      WatchModel.Said("Pause the refunds until noon", 1_000L) to answered.copy(line = "Refunds are paused until 12:00."),
      WatchModel.Said("What needs me today?", 2_000L) to working,
    ))
    compose.onNodeWithText("Pause the refunds until noon").assertExists()
    compose.onNodeWithTag(THREAD_ANSWER + answered.id, useUnmergedTree = true).assertExists()
    compose.onNodeWithText("What needs me today?").assertExists()
    // While Coach works, its side is tafs cloud's state, and it opens nothing.
    compose.onNodeWithTag(THREAD_WORKING + working.id, useUnmergedTree = true).assertExists()
    compose.onNodeWithText("Refunds are paused until 12:00.").performClick()
    assertEquals(listOf(answered.id), opened.map { it.id })
  }

  /** After a send, Coach's page is back on the microphone, with the new turn just above it, however long the conversation. */
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test fun aNewTurnBringsThePageBackToTheMicrophone() {
    roundFace()
    val turns = androidx.compose.runtime.mutableStateOf((1..6).map { n ->
      WatchModel.Said("Question $n", n * 1_000L) to answered.copy(id = "ask_$n", line = "Answer $n")
    })
    compose.setContent {
      MaterialTheme {
        WatchPages(
          read, outcome = null, confirmingHaltAll = false, voice = null, result = null, working = emptyList(), ready = emptyList(), act = actions,
          pages = pages(false), pager = rememberPagerState(initialPage = 0) { pages(false).size }, thread = turns.value,
        )
      }
    }
    compose.onNodeWithText(Words.Coach.ask).assertIsDisplayed()
    turns.value = turns.value + (WatchModel.Said("Question 7", 7_000L) to answered.copy(id = "ask_7", line = "Answer 7"))
    compose.waitForIdle()
    compose.onNodeWithText(Words.Coach.ask).assertIsDisplayed()
    compose.onNodeWithText("Question 7").assertIsDisplayed()
  }

  /** TalkBack hears "Coach: …" for Coach's side, as VoiceOver does on the Apple Watch. */
  @Test fun coachsSideIsNamedForTalkBack() {
    show(read, thread = listOf(WatchModel.Said("Pause the refunds until noon", 1_000L) to answered.copy(line = "Refunds are paused until 12:00.")))
    compose.onNodeWithContentDescription(Words.Coach.title + ": Refunds are paused until 12:00.", useUnmergedTree = true).assertExists()
  }

  /** While Coach works, TalkBack hears the state line as it is, with no second "Coach:" before it. */
  @Test fun aWorkingTurnIsNamedOnce() {
    show(read, thread = listOf(WatchModel.Said("Pause the refunds until noon", 1_000L) to
      answered.copy(state = "working", tafName = Words.Coach.title, words = "Working", line = null)))
    val line = Words.Coach.title + " · Working"
    compose.onNodeWithContentDescription(line, useUnmergedTree = true).assertExists()
    compose.onNodeWithContentDescription(Words.Coach.title + ": " + line, useUnmergedTree = true).assertDoesNotExist()
  }

  @Test fun withNoConversationCoachsPageIsAsBefore() {
    show(read)
    compose.onAllNodesWithTag(THREAD_TURN + answered.id, useUnmergedTree = true).assertCountEquals(0)
    compose.onNodeWithText(Words.Coach.ask).assertExists()
  }

  // MARK: Sent (W2, the owner's Watch 5 test)

  private val working = answered.copy(state = "working", words = "Working", line = null)

  /** v2's "Sent" stays until the answer, with tafs cloud's state in its words and a ring turning: it never closes by itself. */
  @Test fun v2sSentStaysWithTafsCloudsStateUntilTheAnswer() {
    compose.mainClock.autoAdvance = false
    show(read, voice = WatchModel.Voice.Sent(working))
    compose.mainClock.advanceTimeByFrame()
    compose.onNodeWithText(Words.Watch.sent).assertExists()
    compose.onNodeWithText("Support replies · Working").assertExists()
    compose.onNodeWithTag(SENT_WORKING, useUnmergedTree = true).assertExists()
    compose.mainClock.advanceTimeBy(WatchActivity.SENT_SHOWN_MS * 4)
    assertTrue("v2's Sent waits for the answer", "close" !in did)
    // A tap goes back to Coach's home; the ask goes on.
    compose.onNodeWithTag(SENT_V2).performClick()
    assertEquals(listOf("close"), did)
  }

  @Test fun v2sSentOnceTheAskEndedStopsTurning() {
    show(read, voice = WatchModel.Voice.Sent(answered))
    compose.onAllNodesWithTag(SENT_WORKING, useUnmergedTree = true).assertCountEquals(0)
    compose.onNodeWithText("Support replies · Answered").assertExists()
  }

  /** v1's "Sent" is as before: the microphone comes back by itself. */
  @Test fun v1sSentStillClosesByItself() {
    compose.mainClock.autoAdvance = false
    compose.setContent { MaterialTheme { VoiceScreen(WatchModel.Voice.Sent(working), actions) } }
    compose.mainClock.advanceTimeByFrame()
    assertTrue("close" !in did)
    compose.mainClock.advanceTimeBy(WatchActivity.SENT_SHOWN_MS + 100)
    assertEquals(listOf("close"), did)
  }

  // MARK: The answer (W3, the owner's Watch 5 test)

  /** A tap on the answer's words never closes it, a long one scrolls to its end, and Close closes it. */
  @Test fun theAnswerStaysUntilCloseAndReadsToItsEnd() {
    val long = answered.copy(line = "Your refund for order A-1039 is paused until noon; Rae asks first for anything over the limit, and Ivo stays paused until Monday 08:00.")
    compose.setContent { MaterialTheme { ResultScreen(long, actions) } }
    compose.onNodeWithText(long.line!!).performClick()
    assertFalse("a tap on the words never closes it", "close result" in did)
    compose.onNodeWithTag(RESULT_SCREEN).performScrollToNode(hasText(Words.Common.close))
    compose.onNodeWithText(Words.Common.close).assertIsDisplayed().performClick()
    assertEquals(listOf("close result"), did)
  }

  /** The crown scrolls the answer, as a swipe does: a long one reads to Close with the crown alone. */
  @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
  @Test fun theCrownScrollsTheAnswer() {
    val long = answered.copy(line = "Your refund for order A-1039 is paused until noon; Rae asks first for anything over the limit, and Ivo stays paused until Monday 08:00.")
    compose.setContent { MaterialTheme { ResultScreen(long, actions, timing = "listen 1.2 s · receipt 0.4 s · result 6.1 s") } }
    compose.onNodeWithTag(RESULT_SCREEN).performRotaryScrollInput { rotateToScrollVertically(2000f) }
    compose.onNodeWithText(Words.Common.close).assertIsDisplayed()
  }

  /** The alert's words keep no amount, as the Inbox doesn't; when one was taken out, the rest is on the phone. */
  @Test fun theAnswersAlertKeepsNoAmount() {
    assertEquals("Refunded for order A-1039.\n" + Words.Watch.moreOnPhone, AskResults.alertText(answered.copy(line = "Refunded €84 for order A-1039.")))
    assertEquals("Paused until Monday 08:00.", AskResults.alertText(answered.copy(line = "Paused until Monday 08:00.")))
    assertEquals(Words.Watch.moreOnPhone, AskResults.alertText(answered.copy(line = "€84")))
    // Spacing or a trailing ":" alone takes nothing out, so nothing is added.
    assertEquals("Here's what I found:", AskResults.alertText(answered.copy(line = "Here's what I found:")))
  }

  /** The answer's alert is on a high-importance channel, so it peeks and stays; the old default one is gone. */
  @Test fun theAnswersAlertIsOnAHighImportanceChannel() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
    manager.createNotificationChannel(android.app.NotificationChannel("results", "Results", android.app.NotificationManager.IMPORTANCE_DEFAULT))
    AskResults.notify(context, answered)
    assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(AskResults.CHANNEL).importance)
    assertNull(manager.getNotificationChannel("results"))
  }

  // MARK: The Inbox kept on the watch (step iv)

  private val heldLine = InboxItem(InboxItem.heldId("hold_1"), InboxItem.Kind.HELD, "Late parcels", "Reship order #5098", 2)
  private val answerLine = InboxItem(InboxItem.askId("wa_02"), InboxItem.Kind.ASK, "Support replies", "Replied to maria@…", 1, read = true)

  private fun showInbox(inbox: List<InboxItem>, page: Page = Page.Inbox, shown: WatchModel.Shown = read) = compose.setContent {
    MaterialTheme {
      WatchPages(shown, outcome = null, confirmingHaltAll = false, voice = null, result = null, working = emptyList(), ready = emptyList(), act = actions,
        pages = pages(false), pager = rememberPagerState(initialPage = pages(false).indexOf(page)) { pages(false).size }, inbox = inbox)
    }
  }

  @Test fun theInboxShowsEachLineWithItsUnreadDotAndDismiss() {
    showInbox(listOf(heldLine, answerLine))
    compose.onNodeWithText("Reship order #5098").assertExists()
    compose.onNodeWithText("Replied to maria@…").assertExists()
    compose.onNodeWithTag(UNREAD_DOT + heldLine.id, useUnmergedTree = true).assertExists()
    compose.onAllNodesWithTag(UNREAD_DOT + answerLine.id, useUnmergedTree = true).assertCountEquals(0)
    compose.onNodeWithText(Words.Watch.clearRead).performClick()
    compose.onAllNodesWithText(Words.Watch.dismiss)[0].performClick()
    assertEquals(listOf("clear read", "dismiss ${heldLine.id}"), did)
  }

  /** An answer that was only an amount is kept blank, since no amount is kept on the watch: its line says the rest is on the phone. */
  @Test fun anAnswerKeptBlankSaysTheRestIsOnThePhone() {
    showInbox(listOf(answerLine.copy(line = "")))
    compose.onNodeWithText(Words.Watch.moreOnPhone).assertExists()
  }

  /** A held call Needs you still holds opens whole (step vi), with Refuse and Approve on your phone there; the line is read. */
  @Test fun aHeldLineOpensTheCallWhole() {
    showInbox(listOf(heldLine))
    compose.onNodeWithTag(INBOX_ITEM + heldLine.id).performClick()
    compose.onNodeWithText(Words.Watch.nothingSentUntil).assertExists()
    compose.onNodeWithText(Words.NeedsYou.refuse).assertExists()
    assertEquals(listOf("open ${heldLine.id}"), did)
  }

  @Test fun aHeldLineNeedsYouNoLongerHoldsSaysSo() {
    showInbox(listOf(heldLine), shown = read.copy(needsYou = WatchModel.NeedsYouRead(0, "Nothing needs you", emptyList())))
    compose.onNodeWithText(Words.Watch.alreadyAnswered).assertExists()
    compose.onAllNodesWithText(Words.NeedsYou.refuse).assertCountEquals(0)
  }

  @Test fun anEmptyKeptInboxSaysWhatLandsThere() {
    showInbox(emptyList())
    compose.onNodeWithText(Words.Watch.inboxEmpty).assertExists()
  }

  @Test fun homeCountsOnlyUnreadLines() {
    showInbox(listOf(heldLine, answerLine), page = Page.Coach)
    compose.onNodeWithText(Words.Watch.inboxUnread(1)).assertExists()
  }

  @Test fun theStoreKeepsEachLineAsWritten() {
    val lines = listOf(heldLine, answerLine, InboxItem(InboxItem.askId("wa_03"), InboxItem.Kind.ASK, null, "Done", 3))
    assertEquals(lines, PreferencesInboxStore.decode(PreferencesInboxStore.encode(lines)))
  }

  // MARK: The batch's pre-review (#1408)

  /** A Halt confirmed from the menu comes back to it: what came of it is said there, "set a screen lock" included. */
  @Test fun theMenuSaysWhatCameOfAHaltAskedFromIt() {
    compose.setContent {
      MaterialTheme {
        WatchPages(read, outcome = WatchModel.Outcome.NeedsLock, confirmingHaltAll = false, voice = null, result = null, working = emptyList(),
          ready = emptyList(), act = actions, pages = pages(false))
      }
    }
    compose.onNodeWithText(Words.Watch.menu).performClick()
    compose.onNodeWithText(Words.Watch.needsLock).assertExists()
  }

  /** The Inbox not kept (locked, no screen lock): the call that needs the person is shown as the phone answered it, never "Nothing new". */
  @Test fun anInboxNotKeptShowsWhatThePhoneAnsweredNow() {
    compose.setContent {
      MaterialTheme {
        WatchPages(read, outcome = null, confirmingHaltAll = false, voice = null, result = null, working = emptyList(), ready = emptyList(), act = actions,
          pages = pages(false), pager = rememberPagerState(initialPage = 1) { pages(false).size }, inbox = null)
      }
    }
    compose.onNodeWithText("Reship order #5098").assertExists()
    compose.onAllNodesWithText(Words.Watch.inboxEmpty).assertCountEquals(0)
  }
}
