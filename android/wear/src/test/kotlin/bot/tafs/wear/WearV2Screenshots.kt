package bot.tafs.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.foundation.pager.rememberPagerState
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.CardLine
import bot.tafs.watchlink.EventLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Wear v2's screens for review (docs/watch-wear.md, Version 2), drawn through [WatchPages] as the watch will draw them once the switch
 * is on (it stays off: they're drawn here directly), in English with the sample tafs, at the Galaxy Watch 5's two sizes: 40 mm,
 * 396 × 396 px, and 44 mm, 450 × 450 px. Not for Play: written into android/play/wear-screenshots-v2/<size>/ only when asked
 * (`./gradlew :wear:testDebugUnitTest --tests 'bot.tafs.wear.WearV2Screenshots' -PwearScreenshots`, which leaves the store ones as
 * they are); otherwise each is still drawn, so a screen that no longer draws fails here.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = WatchApplication::class)
class WearV2Screenshots(private val size: String, private val dp: Int) {
  @get:Rule val compose = createComposeRule()

  private val repo = File(System.getProperty("tafs.repo") ?: "../..")

  private fun shot(name: String, content: @Composable () -> Unit) {
    // xhdpi: twice the dp in pixels, 396 and 450.
    RuntimeEnvironment.setQualifiers("en-w${dp}dp-h${dp}dp-round-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
    compose.setContent { WatchTheme { Box(Modifier.fillMaxSize().background(Color.Black)) { content() } } }
    compose.onRoot().captureRoboImage(File(repo, "android/play/wear-screenshots-v2/$size/$name.png").path)
    assertEquals("en", Res.language)
  }

  private val act = Actions.none
  private val parcels = TafLine("taf_1", "Late parcels", "Waiting for you", halted = false)
  private val support = TafLine("taf_2", "Support replies", "Next run 14:00", halted = false)
  private val leads = TafLine("taf_3", "Lead follow-up", "Working", halted = false)
  private val call = HeldLine("hold_1", "Late parcels", "Reship order #5098", "€12", "Reships wait for your yes", refusable = true)
  private val read = WatchModel.Shown.Read(
    WatchModel.NeedsYouRead(1, "1 held call needs you", listOf(call)), WatchModel.TafsRead(listOf(parcels, support, leads), allHalted = false),
  )
  private val working = AskLine("ask_2", "w_00000002", AskLine.WORKING, "Lead follow-up", null, "Working", "2026-11-19T08:02:00Z")
  private val answered = AskLine("ask_1", "w_00000001", AskLine.DONE, "Support replies", "Answered maria@… by email", "Done", "2026-11-19T08:00:00Z")
  private val inbox = listOf(
    InboxItem(InboxItem.heldId("hold_1"), InboxItem.Kind.HELD, "Late parcels", "Reship order #5098", at = 2),
    InboxItem(InboxItem.askId("ask_1"), InboxItem.Kind.ASK, "Support replies", "Answered maria@… by email", at = 1, read = true),
  )
  private val page = WatchReply.TafPage(
    "taf_1", "Late parcels", "Waiting for you", halted = false, job = "Reships parcels the carrier lost, and tells the customer.",
    cards = listOf(CardLine("Reships", "€12 of €50", "used today", listOf("Up to €50 a day", "Only to the order's own address"))), waiting = 1,
  )
  private val open = WatchModel.OpenTaf("taf_1", parcels, WatchModel.TafRead.Read(page))

  @Composable
  private fun pages(
    page: Page,
    voice: WatchModel.Voice? = null,
    taf: WatchModel.OpenTaf? = null,
    haltPick: WatchModel.HaltPick? = null,
    account: WatchModel.Reading<WatchReply.AccountPage>? = null,
  ) {
    val pages = pages(false)
    WatchPages(
      read, outcome = null, confirmingHaltAll = false, voice = voice, result = null, working = listOf(working), ready = emptyList(), act = act,
      pages = pages, pager = rememberPagerState(initialPage = pages.indexOf(page)) { pages.size }, inbox = inbox, taf = taf, haltPick = haltPick,
      account = account,
    )
  }

  @Test fun coachHome() = shot("01-coach") { pages(Page.Coach) }

  /** The Inbox, its held call not read yet (the blue dot) above a result already read. */
  @Test fun inbox() = shot("02-inbox") { pages(Page.Inbox) }

  @Test fun tafs() = shot("03-tafs") { pages(Page.Tafs) }

  @Test fun aTafsPage() = shot("04-taf") { pages(Page.Tafs, taf = open) }

  @Test fun itsSettings() = shot("05-taf-settings") { TafScreen(open, null, act, openInbox = {}, pager = rememberPagerState(initialPage = 1) { 2 }) }

  @Test fun chooseWhichToHalt() = shot("06-halt-pick") {
    pages(Page.Coach, haltPick = WatchModel.HaltPick(listOf(parcels, support, leads), setOf("taf_1", "taf_3")))
  }

  @Test fun aHeldCall() = shot("07-held-call") { OverScreen(Over.HeldCall("hold_1"), read, null, act, go = {}, close = {}) }

  @Test fun theCalendarDay() = shot("08-calendar") {
    val day = WatchReply.CalendarDay(
      "2026-11-19", "3 things today",
      listOf(
        EventLine("09:00", "Lead follow-up", "Call Nord Atelier back"),
        EventLine("14:00", "Support replies", "Answer new tickets", count = 3),
        EventLine("17:30", "Late parcels", "Check the carrier's scans"),
      ),
    )
    OverScreen(Over.Calendar, read, WatchModel.Reading.Read(WatchModel.DayRead(day, today = true)), act, go = {}, close = {})
  }

  @Test fun account() = shot("09-account") {
    pages(Page.Account, account = WatchModel.Reading.Read(WatchReply.AccountPage("you@example.com", "Northwind Goods", "Team · 3 tafs")))
  }

  /** The review with a long transcript: the words in full, scrolled with the crown, never cut. */
  @Test fun theReviewWithALongTranscript() = shot("10-review-long") {
    pages(Page.Coach, voice = WatchModel.Voice.Review(LONG, null))
  }

  @Test fun recording() = shot("11-recording") {
    pages(Page.Coach, voice = WatchModel.Voice.Listening("Move my call with Nord Atelier to Thursday at ten", null, since = System.currentTimeMillis() - 12_000))
  }

  /** Sent, while Coach is at work on it: the green check in Coach's turning ring. */
  @Test fun sent() = shot("12-sent") { pages(Page.Coach, voice = WatchModel.Voice.Sent(working)) }

  /** An answer's card: its taf in Coach's blue, the answer, Hear and Reply. */
  @Test fun theResult() = shot("13-result") { ResultScreen(answered, act) }

  /** Halt this taf, asked once more: Halt now in red, Cancel grey. */
  @Test fun haltAsked() = shot("14-halt-confirm") {
    pages(Page.Coach, haltPick = WatchModel.HaltPick(listOf(parcels, support, leads), setOf("taf_1"), confirming = true))
  }

  companion object {
    const val LONG = "Ask Late parcels to tell me before it sends any new parcel today, and keep the customer posted. For order 5098, wait " +
      "until tomorrow morning: the carrier said the scan may come late. If it still hasn't moved by then, send the new parcel and tell " +
      "the customer. And ask Support replies to answer Maria's question about the refund once the parcel is on its way."

    /** The Galaxy Watch 5's two faces, by their size and their width in dp at xhdpi: 396 and 450 px. */
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun sizes(): List<Array<Any>> = listOf(arrayOf("40mm", 198), arrayOf("44mm", 225))
  }
}
