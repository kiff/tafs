package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import bot.tafs.app.app.AppState
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.drive.LocalSheetBeside
import bot.tafs.app.kiff.CloudError
import bot.tafs.app.kiff.TafsCloud
import bot.tafs.app.model.ErrorWords
import bot.tafs.app.model.Words
import bot.tafs.app.views.CoachChatActions
import bot.tafs.app.views.LocalWatchDays
import bot.tafs.app.views.PreviousConversations
import bot.tafs.app.views.watchDayTitle
import bot.tafs.app.views.watchTurnTime
import bot.tafs.app.watch.StubWatchDaysStore
import bot.tafs.app.watch.TafsCloudWatchDaysStore
import bot.tafs.app.watch.WatchDay
import bot.tafs.app.watch.WatchDays
import bot.tafs.app.watch.WatchDaysFeature
import bot.tafs.app.watch.WatchDaysStore
import bot.tafs.app.watch.WatchTurn
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * From your watch (docs/android-app.md, From your watch): the switch off draws and asks nothing; on, the days newest first with
 * their counts and the account's Today, a day's turns in tafs cloud's order, read only, each the watch's label, what was said and
 * the taf's side in tafs cloud's words, a failed day said in Coach's words; and tafs cloud's two answers read as the app reads
 * them, a 404 as the switch off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w411dp-h2000dp")
class WatchDaysTest {
  @get:Rule val compose = createComposeRule()

  /** A store, counting what the sheet asks of it; a day's read fails while [failDay] is set. */
  private class Counting(private val inner: WatchDaysStore = StubWatchDaysStore()) : WatchDaysStore {
    val asked = mutableListOf<String>()
    var failDay: Exception? = null
    override suspend fun days(): WatchDays? { asked += "days"; return inner.days() }
    override suspend fun day(date: LocalDate): List<WatchTurn>? {
      asked += "day $date"
      failDay?.let { throw it }
      return inner.day(date)
    }
  }

  // The sheet drawn in the page, as beside the sidebar, so its rows are this test's to read.
  private fun sheet(store: WatchDaysStore?, on: Boolean) = compose.setContent {
    TafsTheme {
      CompositionLocalProvider(LocalSheetBeside provides true, LocalWatchDays provides store) {
        PreviousConversations(AppState(), CoachChatActions(), watchDaysOn = on) {}
      }
    }
  }

  private val nov19 = LocalDate.parse("2026-11-19")
  private val nov18 = LocalDate.parse("2026-11-18")
  private val nov16 = LocalDate.parse("2026-11-16")

  /** The stub's titles: its account's today is the 19th. */
  private fun title(day: LocalDate) = watchDayTitle(day, today = nov19)

  private fun open(day: LocalDate) {
    compose.onNodeWithText(title(day)).performScrollTo().performClick()
    compose.waitForIdle()
  }

  private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
  private fun left(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.left

  // MARK: The switch

  @Test fun fromYourWatchShipsOn() {
    assertTrue("From your watch is on since Android 124 (the owner's 19 Nov rule; tafs cloud's routes are live)", WatchDaysFeature.on)
  }

  @Test fun withTheSwitchOffTheSheetDrawsNothingOfTheWatchAndAsksNothing() {
    val store = Counting()
    sheet(store, on = false)
    compose.waitForIdle()
    compose.onNodeWithText(Words.Coach.Chats.previous).assertExists()
    compose.onAllNodesWithText(Words.Coach.Chats.fromYourWatch).assertCountEquals(0)
    compose.onAllNodesWithTag("watch.day").assertCountEquals(0)
    assertEquals(emptyList<String>(), store.asked)
  }

  @Test fun withNoStoreAsTheAppMakesNoneWithTheSwitchOffNothingIsDrawn() {
    sheet(null, on = true)
    compose.waitForIdle()
    compose.onAllNodesWithText(Words.Coach.Chats.fromYourWatch).assertCountEquals(0)
  }

  private fun answering(answer: WatchDays?) = object : WatchDaysStore {
    override suspend fun days(): WatchDays? = answer
    override suspend fun day(date: LocalDate): List<WatchTurn>? = null
  }

  @Test fun tafsCloudsSwitchOffDrawsNoSection() {
    sheet(answering(null), on = true)
    compose.waitForIdle()
    compose.onAllNodesWithText(Words.Coach.Chats.fromYourWatch).assertCountEquals(0)
  }

  @Test fun noDaysDrawsNoSection() {
    sheet(answering(WatchDays(emptyList(), nov19)), on = true)
    compose.waitForIdle()
    compose.onAllNodesWithText(Words.Coach.Chats.fromYourWatch).assertCountEquals(0)
  }

  @Test fun aDaysReadThatFailsDrawsNoSectionAndNoError() {
    val failing = object : WatchDaysStore {
      override suspend fun days(): WatchDays? = throw CloudError(500, "down")
      override suspend fun day(date: LocalDate): List<WatchTurn>? = null
    }
    sheet(failing, on = true)
    compose.waitForIdle()
    compose.onAllNodesWithText(Words.Coach.Chats.fromYourWatch).assertCountEquals(0)
    compose.onAllNodesWithText(ErrorWords.of(CloudError(500, "down")), substring = true).assertCountEquals(0)
    compose.onNodeWithText(Words.Coach.Chats.previous).assertExists()
  }

  // MARK: The days

  @Test fun theDaysAreListedNewestFirstWithTheirCountsAndTheAccountsTodayIsToday() {
    val store = Counting()
    sheet(store, on = true)
    compose.waitForIdle()
    compose.onNodeWithText(Words.Coach.Chats.fromYourWatch).assertExists()
    assertEquals(3, compose.onAllNodesWithTag("watch.day", useUnmergedTree = true).fetchSemanticsNodes().size)
    assertEquals(Words.Calendar.today, title(nov19))
    val tops = listOf(nov19, nov18, nov16).map { top(title(it)) }
    assertEquals(tops.sorted(), tops)
    val counts = compose.onAllNodesWithTag("watch.day.count", useUnmergedTree = true).fetchSemanticsNodes()
      .map { it.config[SemanticsProperties.Text].joinToString("") }
    assertEquals(listOf("3", "1", "2"), counts)
    assertEquals(listOf("days"), store.asked)
  }

  @Test fun onAnAccountADayBehindThePhoneTodayIsTheAccounts() {
    // The account's today is the 18th while the phone's is the 19th: "Today" is the 18th, the 19th keeps its date.
    assertEquals(Words.Calendar.today, watchDayTitle(nov18, today = nov18, phoneToday = nov19))
    assertNotEquals(Words.Calendar.today, watchDayTitle(nov19, today = nov18, phoneToday = nov19))
    // A tafs cloud that gives no today leaves the phone's own.
    assertEquals(Words.Calendar.today, watchDayTitle(nov19, today = null, phoneToday = nov19))
    val other = watchDayTitle(nov18, today = null, phoneToday = nov19)
    assertTrue(other, other.contains("18") && other.contains("November") && other.contains("Wednesday"))
    // Drawn so in the sheet.
    sheet(StubWatchDaysStore(StubWatchDaysStore.DAYS.replace("\"today\":\"2026-11-19\"", "\"today\":\"2026-11-18\"")), on = true)
    compose.waitForIdle()
    compose.onAllNodesWithText(Words.Calendar.today).assertCountEquals(1)
    assertTrue(top(Words.Calendar.today) > top(watchDayTitle(nov19, today = nov18)))
  }

  // MARK: A day

  @Test fun aDayShowsItsTurnsInTafsCloudsOrderEachSentFromTheWatchWithTheLineElseTheWords() {
    val store = Counting()
    sheet(store, on = true)
    compose.waitForIdle()
    open(nov19)
    assertEquals(listOf("days", "day 2026-11-19"), store.asked)
    // The day in the sheet's place: its title, the list gone.
    compose.onNodeWithText(Words.Calendar.today).assertExists()
    compose.onAllNodesWithText(Words.Coach.Chats.fromYourWatch).assertCountEquals(0)
    compose.onAllNodesWithText(Words.Coach.Chats.sentFromYourWatch).assertCountEquals(3)
    val said = listOf("Pause the support replies until noon", "And tell me when it starts again", "Which parcels are late today?")
    val tops = said.map(::top)
    assertEquals(tops.sorted(), tops)
    // A reply sits indented under the turn it replies to.
    assertTrue(left(said[1]) > left(said[0]))
    assertEquals(left(said[0]), left(said[2]), 0.5f)
    // When it was said.
    compose.onNodeWithText("· " + watchTurnTime(Instant.parse("2026-11-19T09:12:00Z"))).assertExists()
    // The taf's line where there is one; tafs cloud's words where there isn't, never the state itself.
    compose.onNodeWithText("Support replies is paused until 12:00.").assertExists()
    compose.onAllNodesWithText("Done").assertCountEquals(1) // the sheet's own Done: the state's words stay under a line
    compose.onNodeWithText("Support replies has it").assertExists()
    compose.onNodeWithText("Late parcels is on it").assertExists()
    compose.onAllNodesWithText("working").assertCountEquals(0)
    // Back is the list again.
    compose.onNodeWithText(Words.Coach.Chats.back).performClick()
    compose.waitForIdle()
    compose.onNodeWithText(Words.Coach.Chats.fromYourWatch).assertExists()
  }

  @Test fun aStateTheAppDoesntKnowIsDrawnWithTafsCloudsWords() {
    sheet(StubWatchDaysStore(), on = true)
    compose.waitForIdle()
    open(nov16)
    compose.onNodeWithText("Sent Nord Atelier a follow-up about the 40 chairs.").assertExists()
    compose.onNodeWithText("Waiting on KIFF").assertExists()
    compose.onAllNodesWithText("waiting_on_kiff").assertCountEquals(0)
  }

  @Test fun aTurnNoTafTookIsTafsCloudsWordsAlone() {
    sheet(StubWatchDaysStore(), on = true)
    compose.waitForIdle()
    open(nov18)
    compose.onNodeWithText("Book a table for Friday").assertExists()
    compose.onNodeWithText("None of your tafs does this yet").assertExists()
    compose.onAllNodesWithText(Words.Coach.title).assertCountEquals(0)
  }

  @Test fun aDayOffersNoReply() {
    sheet(StubWatchDaysStore(), on = true)
    compose.waitForIdle()
    open(nov19)
    compose.onAllNodesWithText(Words.Coach.Chats.sentFromYourWatch).assertCountEquals(3)
    compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    compose.onAllNodes(hasText(Words.Common.send)).assertCountEquals(0)
    compose.onAllNodes(hasText(Words.Coach.ask)).assertCountEquals(0)
  }

  @Test fun aDayThatCantBeReadSaysSoInCoachsWordsWithTryAgain() {
    val store = Counting().apply { failDay = CloudError(500, "down") }
    sheet(store, on = true)
    compose.waitForIdle()
    open(nov19)
    val why = ErrorWords.of(CloudError(500, "down"))
    compose.onNodeWithText(Words.Coach.conversationNotLoaded(why)).assertExists()
    compose.onNodeWithText("The conversation couldn't be loaded: $why").assertExists()
    compose.onAllNodesWithText(Words.Coach.Chats.sentFromYourWatch).assertCountEquals(0)
    store.failDay = null
    compose.onNodeWithText(Words.Common.tryAgain).performClick()
    compose.waitForIdle()
    assertEquals(listOf("days", "day 2026-11-19", "day 2026-11-19"), store.asked)
    compose.onAllNodesWithText(Words.Coach.conversationNotLoaded(why)).assertCountEquals(0)
    compose.onAllNodesWithText(Words.Coach.Chats.sentFromYourWatch).assertCountEquals(3)
  }

  // MARK: tafs cloud's answers

  private val transport = FakeTransport()
  private val cloud = TafsCloud(transport, token = "at_1")
  private val store = TafsCloudWatchDaysStore(cloud)

  @Test fun theDaysAnswerIsReadInTafsCloudsOrderWithTheAccountsToday() = runBlocking {
    transport.answer(
      "GET /v1/watch/days", 200,
      """{"today":"2026-11-19","days":[{"date":"2026-11-19","count":3},{"date":"2026-11-18","count":1},{"count":1},{"date":"not a day","count":9}]}""",
    )
    assertEquals(WatchDays(listOf(WatchDay(nov19, 3), WatchDay(nov18, 1)), today = nov19), store.days())
    assertEquals("Bearer at_1", transport.requestsTo("GET /v1/watch/days").single().headers["Authorization"])
    // A tafs cloud that gives no today.
    transport.reset()
    transport.answer("GET /v1/watch/days", 200, """{"days":[{"date":"2026-11-16","count":2}]}""")
    assertEquals(WatchDays(listOf(WatchDay(nov16, 2)), today = null), store.days())
  }

  @Test fun aDaysTurnsAreReadWithAndWithoutTheirOptionalParts() = runBlocking {
    transport.answer(
      "GET /v1/watch/days/2026-11-19", 200,
      """{"date":"2026-11-19","turns":[
        {"id":"wa_1","said":"Pause the refunds until noon","said_at":"2026-11-19T09:12:00Z","taf":{"id":"taf_1","name":"Support replies"},"state":"done","line":"Refunds are paused until 12:00.","words":"Done"},
        {"id":"wa_2","said":"Thanks","said_at":"2026-11-19T09:13:00.5Z","state":"no_taf","line":"","words":"No taf for this","reply_to":"wa_1","new_field":true},
        {"said":"no id: left out"},
        {"id":"wa_3","said":"Later","said_at":"whenever","state":"paused_somehow","words":"Paused"}
      ]}""",
    )
    val turns = store.day(nov19)!!
    assertEquals(listOf("wa_1", "wa_2", "wa_3"), turns.map { it.id })
    assertEquals(
      WatchTurn("wa_1", "Pause the refunds until noon", Instant.parse("2026-11-19T09:12:00Z"), "Support replies", "done", "Refunds are paused until 12:00.", "Done"),
      turns[0],
    )
    assertEquals("Refunds are paused until 12:00.", turns[0].answer)
    // No taf, an empty line that is none, a reply.
    assertEquals(WatchTurn("wa_2", "Thanks", Instant.parse("2026-11-19T09:13:00.5Z"), null, "no_taf", null, "No taf for this", replyTo = "wa_1"), turns[1])
    assertEquals("No taf for this", turns[1].answer)
    // A time that can't be read isn't guessed; a state the app doesn't know keeps tafs cloud's words.
    assertNull(turns[2].saidAt)
    assertEquals("Paused", turns[2].answer)
  }

  @Test fun a404IsTafsCloudsSwitchOff() = runBlocking {
    transport.answer("GET /v1/watch/days", 404, """{"error":"watch_off","code":"watch_off"}""")
    transport.answer("GET /v1/watch/days/2026-11-19", 404, """{"error":"watch_off","code":"watch_off"}""")
    assertNull(store.days())
    assertNull(store.day(nov19))
  }

  @Test fun anotherRefusalIsntTakenForTheSwitchOff() = runBlocking {
    transport.answer("GET /v1/watch/days", 500, """{"error":"down"}""")
    val thrown = runCatching { store.days() }.exceptionOrNull()
    assertEquals(500, (thrown as CloudError).status)
  }
}
