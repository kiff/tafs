package bot.tafs.wear

import androidx.compose.ui.test.assertCountEquals
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
  private val actions = Actions(
    openOnPhone = {}, tryAgain = {}, refuse = {}, halt = {}, askHaltAll = { haltAllAsked += Unit }, confirmHaltAll = { haltAllConfirmed += Unit },
    cancelHaltAll = {}, listen = {}, closeVoice = {}, retry = {}, open = { opened += it }, hear = {}, reply = {}, closeResult = {}, dismiss = {},
  )

  private fun show(shown: WatchModel.Shown, page: Page = Page.Coach, ready: List<AskLine> = emptyList(), confirmingHaltAll: Boolean = false, pages: List<Page> = pages(false)) =
    compose.setContent {
      MaterialTheme {
        WatchPages(
          shown, outcome = null, confirmingHaltAll = confirmingHaltAll, voice = null, result = null, working = emptyList(), ready = ready, act = actions,
          pages = pages, pager = rememberPagerState(initialPage = pages.indexOf(page)) { pages.size },
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
}
