package bot.tafs.wear

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.MaterialTheme
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.CardLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Wear v2's back (docs/watch-wear.md, Version 2): the system's back gesture closes what's drawn over the pages, through the same
 * action as its Cancel or Close, and never the app; on the pages it's the system's own. Pressed here on the activity's own
 * dispatcher, as the gesture does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = WatchApplication::class)
class WatchBackTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Before fun words() {
    // Tall, so a whole page is drawn at once and every line can be found.
    RuntimeEnvironment.setQualifiers("en-w192dp-h2400dp-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
  }

  private val parcels = TafLine("taf_1", "Late parcels", "Waiting for you", halted = false)
  private val support = TafLine("taf_2", "Support replies", "Next run 14:00", halted = false)
  private val call = HeldLine("hold_1", "Late parcels", "Reship order #5098", "€12", "Reships wait for your yes", refusable = true)
  private val read = WatchModel.Shown.Read(
    WatchModel.NeedsYouRead(1, "1 held call needs you", listOf(call)), WatchModel.TafsRead(listOf(parcels, support), allHalted = false),
  )
  private val page = WatchReply.TafPage("taf_1", "Late parcels", "Working", halted = false, job = "Reships parcels that are late",
    cards = listOf(CardLine("Reships", "€12 of €50", "used today", listOf("Up to €50 a day"))), waiting = 0)

  private val did = mutableListOf<String>()

  private fun actions(
    closeVoice: () -> Unit = { did += "close voice" },
    closeTaf: () -> Unit = { did += "close taf" },
    pick: WatchModel? = null,
  ) = Actions(
    openOnPhone = { did += "phone" }, tryAgain = {}, refuse = { did += "refuse" }, halt = { did += "halt $it" }, askHaltAll = {},
    confirmHaltAll = {}, cancelHaltAll = {}, listen = {}, closeVoice = closeVoice, retry = {}, open = {}, hear = {}, reply = {},
    closeResult = { did += "close result" }, dismiss = {}, quick = { did += "quick" }, closeTaf = closeTaf,
    pickToHalt = { pick?.pickToHalt() }, togglePick = { pick?.togglePick(it) }, askPicked = { pick?.askPicked() },
    cancelPick = { pick?.cancelPick() }, haltPicked = { did += "halt picked" },
    closeCalendar = { did += "close calendar" }, signOut = { did += "sign out" },
  )

  private fun back() {
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.waitForIdle()
  }

  /** Whether anything on the screen takes back: none on the pages, so the system's own leaves the app. */
  private fun backIsTaken(): Boolean {
    var taken = false
    compose.runOnUiThread { taken = compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
    return taken
  }

  private fun show(
    page: Page = Page.Coach,
    act: Actions = actions(),
    voice: WatchModel.Voice? = null,
    result: AskLine? = null,
    outcome: WatchModel.Outcome? = null,
    taf: () -> WatchModel.OpenTaf? = { null },
    haltPick: () -> WatchModel.HaltPick? = { null },
  ) = compose.setContent {
    MaterialTheme {
      val pages = pages(false)
      WatchPages(
        read, outcome = outcome, confirmingHaltAll = false, voice = voice, result = result, working = emptyList(), ready = emptyList(), act = act,
        pages = pages, pager = rememberPagerState(initialPage = pages.indexOf(page)) { pages.size }, taf = taf(), haltPick = haltPick(),
      )
    }
  }

  @Test fun onThePagesBackIsTheSystems() {
    show()
    compose.onNodeWithText(Words.Coach.ask).assertExists()
    assertFalse("the pages leave back to the system: it leaves the app", backIsTaken())
  }

  @Test fun backClosesTheMenu() {
    show()
    compose.onNodeWithText(Words.Watch.menu).performClick()
    compose.onNodeWithText(Words.Taf.Halt.title).assertExists()
    back()
    compose.onAllNodesWithText(Words.Taf.Halt.title).assertCountEquals(0)
    compose.onNodeWithText(Words.Coach.ask).assertExists()
    assertFalse(backIsTaken())
  }

  @Test fun backClosesQuickReplyWithNothingPicked() {
    show()
    compose.onNodeWithText(Words.Watch.quickReply).performClick()
    compose.onNodeWithText(Words.Watch.Qr.needsMeToday).assertExists()
    back()
    compose.onAllNodesWithText(Words.Watch.Qr.needsMeToday).assertCountEquals(0)
    assertEquals(emptyList<String>(), did)
  }

  @Test fun backGoesFromTheHaltQuestionToTheChoiceThenTheMenuThenThePages() {
    val model = WatchModel(
      { r -> Asked.Answered(if (r is WatchRequest.NeedsYou) WatchReply.NeedsYou(1, "1 held call needs you", listOf(call)) else WatchReply.Tafs(listOf(parcels, support), false)) },
      unlocked = { true },
    )
    runBlocking { model.refresh() }
    val act = actions(pick = model)
    compose.setContent {
      MaterialTheme {
        val pick by model.haltPick.collectAsState()
        val pages = pages(false)
        WatchPages(read, null, false, null, null, emptyList(), emptyList(), act, pages = pages, pager = rememberPagerState { pages.size }, haltPick = pick)
      }
    }
    compose.onNodeWithText(Words.Watch.menu).performClick()
    compose.onNodeWithText(Words.Taf.Halt.title).performClick()
    compose.onNodeWithText("Support replies").performClick()
    compose.onNodeWithText(Words.Watch.haltNowCount(1)).performClick()
    compose.onNodeWithText(Words.Halt.Explain.runningOne).assertExists()
    back()
    // The choice, as it was.
    assertEquals(WatchModel.HaltPick(listOf(parcels, support), setOf("taf_2")), model.haltPick.value)
    compose.onNodeWithText(Words.Watch.haltNowCount(1)).assertExists()
    back()
    assertNull(model.haltPick.value)
    compose.onNodeWithText(Words.Common.close).assertExists()
    back()
    compose.onNodeWithText(Words.Coach.ask).assertExists()
    assertEquals("nothing was halted", emptyList<String>(), did)
  }

  @Test fun backGoesFromATafsSettingsToItsPageThenThePages() {
    var open by mutableStateOf<WatchModel.OpenTaf?>(WatchModel.OpenTaf("taf_1", parcels, WatchModel.TafRead.Read(page)))
    show(Page.Tafs, act = actions(closeTaf = { did += "close taf"; open = null }), taf = { open })
    compose.onNodeWithText("${Words.Watch.itsSettings} ›").performClick()
    compose.waitForIdle()
    compose.onNodeWithText(Words.Watch.haltThis).performClick()
    compose.onNodeWithText(Words.Halt.Explain.runningOne).assertExists()
    // The question, then its settings, then its page, then the Tafs page.
    back()
    compose.onNodeWithText(Words.Watch.settingsOf("Late parcels")).assertExists()
    back()
    compose.onNodeWithText(page.job).assertExists()
    compose.onAllNodesWithText(Words.Watch.settingsOf("Late parcels")).assertCountEquals(0)
    back()
    compose.onNodeWithText("Next run 14:00").assertExists()
    assertEquals(listOf("close taf"), did)
  }

  @Test fun backClosesAHeldCallWithNothingAnswered() {
    show(Page.Inbox)
    compose.onNodeWithText("Reship order #5098").performClick()
    compose.onNodeWithText(Words.Watch.nothingSentUntil).assertExists()
    back()
    compose.onAllNodesWithText(Words.Watch.nothingSentUntil).assertCountEquals(0)
    compose.onNodeWithText(Words.Watch.Page.inbox).assertExists()
    assertEquals(emptyList<String>(), did)
  }

  @Test fun backClosesTheCalendarThroughTheModel() {
    show(Page.Tafs)
    compose.onNodeWithText(Words.Watch.calendarToday).performClick()
    compose.onNodeWithText(Words.Watch.calendarToday).assertDoesNotExist()
    back()
    // The Tafs page comes back once its list has laid out again.
    compose.waitUntil(2_000) { compose.onAllNodesWithText("Next run 14:00").fetchSemanticsNodes().isNotEmpty() }
    assertEquals(listOf("close calendar"), did)
  }

  @Test fun backFromSignOutsQuestionSignsNothingOut() {
    show(Page.Account)
    compose.onNodeWithText(Words.Settings.signOut).performClick()
    compose.onNodeWithText(Words.Watch.signOutQ).assertExists()
    back()
    compose.onAllNodesWithText(Words.Watch.signOutQ).assertCountEquals(0)
    assertEquals(emptyList<String>(), did)
  }

  @Test fun backOnTheMicrophonesScreensIsCancel() {
    // Ready, recording and the review: Cancel, each.
    val act = actions()
    for (voice in listOf(WatchModel.Voice.Ready(null), WatchModel.Voice.Listening("Move my call", null, since = 0L), WatchModel.Voice.Review("Move my call", null))) {
      backOf(voice, act)()
    }
    assertEquals(List(3) { "close voice" }, did)
    did.clear()
    show(voice = WatchModel.Voice.Listening("Move my call", null, since = System.currentTimeMillis()))
    back()
    assertEquals(listOf("close voice"), did)
  }

  @Test fun backOnTheReviewErasesItsRecording() {
    var erased = 0
    val recording = object : VoiceAudio {
      override val durationMs = 12_000L
      override fun delete() { erased++ }
    }
    val model = WatchModel({ Asked.Unreachable() }, unlocked = { true }, reviewFirst = true)
    model.listen()
    runBlocking { model.heard("Move my call to six", recording) }
    assertTrue(model.voice.value is WatchModel.Voice.Review)
    val act = actions(closeVoice = model::closeVoice)
    compose.setContent {
      MaterialTheme {
        val voice by model.voice.collectAsState()
        WatchPages(read, null, false, voice, null, emptyList(), emptyList(), act, pages = pages(false))
      }
    }
    compose.onNodeWithText(Words.Watch.review).assertExists()
    back()
    assertNull(model.voice.value)
    assertEquals("the recording is erased as the review is left", 1, erased)
    compose.onNodeWithText(Words.Coach.ask).assertExists()
  }

  @Test fun whileSendingBackIsTakenAndDoesNothing() {
    show(voice = WatchModel.Voice.Sending("Move my call", "w_00000001", null))
    assertTrue(backIsTaken())
    back()
    assertEquals(emptyList<String>(), did)
    compose.onNodeWithText(Words.Watch.sending).assertExists()
  }

  @Test fun backClosesAResult() {
    show(result = AskLine("ask_1", "c1", AskLine.DONE, "Support replies", "Answered maria", "Done", "2026-11-19T08:00:00Z"))
    back()
    assertEquals(listOf("close result"), did)
  }
}
