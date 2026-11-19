package bot.tafs.wear

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.MaterialTheme
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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

  private val haltAllAsked = mutableListOf<Unit>()
  private val haltAllConfirmed = mutableListOf<Unit>()
  private val opened = mutableListOf<AskLine>()
  private val did = mutableListOf<String>()
  private val actions = Actions(
    openOnPhone = {}, tryAgain = {}, refuse = {}, halt = {}, askHaltAll = { haltAllAsked += Unit }, confirmHaltAll = { haltAllConfirmed += Unit },
    cancelHaltAll = {}, listen = { did += "listen" }, closeVoice = { did += "close" }, retry = {}, open = { opened += it }, hear = {}, reply = {}, closeResult = {}, dismiss = {},
    start = { did += "start ${it ?: ""}".trim() }, stop = { did += "stop" }, send = { did += "send" }, rerecord = { did += "rerecord" },
    delete = { did += "delete" }, type = { did += "type" }, quick = { did += "quick $it" },
    keep = { did += "keep" }, deleteKept = { did += "delete kept" },
  )

  private fun show(
    shown: WatchModel.Shown,
    page: Page = Page.Coach,
    ready: List<AskLine> = emptyList(),
    confirmingHaltAll: Boolean = false,
    pages: List<Page> = pages(false),
    voice: WatchModel.Voice? = null,
    kept: WatchModel.Draft? = null,
  ) =
    compose.setContent {
      MaterialTheme {
        WatchPages(
          shown, outcome = null, confirmingHaltAll = confirmingHaltAll, voice = voice, result = null, working = emptyList(), ready = ready, act = actions,
          pages = pages, pager = rememberPagerState(initialPage = pages.indexOf(page)) { pages.size }, kept = kept,
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
    compose.onNodeWithText(Words.Taf.Halt.title).performClick()
    assertEquals(1, haltAllAsked.size)
  }

  @Test fun haltIsAskedOnceMoreThenHaltsEveryTaf() {
    show(read, confirmingHaltAll = true)
    compose.onNodeWithText(Words.Watch.menu).performClick()
    compose.onNodeWithText(Words.HaltAll.title).assertExists()
    compose.onNodeWithText(Words.Halt.allNow).performClick()
    assertEquals(1, haltAllConfirmed.size)
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
    compose.onNodeWithText("${Words.Watch.ready} · ${Words.Watch.limit}").assertExists()
    compose.onNodeWithText(Words.Watch.tapStart).assertExists()
    compose.onNodeWithText("0:00 / 0:30").assertExists()
    // Start is its own control: the brand's round button with the microphone, never Stop's.
    compose.onAllNodesWithTag(RECORD_STOP).assertCountEquals(0)
    compose.onNodeWithTag(RECORD_START).assertContentDescriptionEquals(Words.Coach.start).performClick()
    assertEquals(listOf("start ask_1"), did)
  }

  @Test fun listeningShowsTheWordsAsHeardAndStop() {
    show(read, voice = WatchModel.Voice.Listening("Move my call", null, since = System.currentTimeMillis()))
    compose.onNodeWithText("● ${Words.Watch.recording} · ${Words.Watch.limit}").assertExists()
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
    compose.onNodeWithText(Words.Watch.rerecord).performClick()
    compose.onNodeWithText(Words.Chat.delete).performClick()
    compose.onNodeWithText(Words.Common.send).performClick()
    assertEquals(listOf("rerecord", "delete", "send"), did)
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
    compose.onNodeWithText(Words.Watch.retry).assertExists()
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
    compose.onNodeWithText(Words.Watch.retry).assertExists()
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
    compose.onNodeWithText(Words.Taf.pause).performClick()
    compose.onNodeWithTag(PLAYBACK_PLAY).assertExists()
    assertEquals(2, playback.toggled)
  }

  @Test fun aReviewWithNoRecordingHasNoPlay() {
    show(read, voice = WatchModel.Voice.Review("What needs me today?", null))
    compose.onAllNodesWithText(Words.Watch.play).assertCountEquals(0)
  }
}
