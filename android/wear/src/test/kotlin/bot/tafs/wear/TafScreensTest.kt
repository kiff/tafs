package bot.tafs.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.MaterialTheme
import bot.tafs.watchlink.CardLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.NotAccepted
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
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
 * Wear v2, step (v), the model (docs/watch-wear.md, Version 2): a taf's page read from the phone, and Choose which to halt, where
 * what goes to the phone is exactly what was picked, and only once asked again from an unlocked watch.
 */
class TafModelTest {
  private val t = 1_763_244_000_000L
  private val parcels = TafLine("taf_1", "Late parcels", "Waiting for you", halted = false)
  private val support = TafLine("taf_2", "Support replies", "Next run 14:00", halted = false)
  private val deploys = TafLine("taf_3", "Deploys", "Halted", halted = true)
  private val needsYou = WatchReply.NeedsYou(0, "Nothing needs you", emptyList())
  private val tafs = WatchReply.Tafs(listOf(parcels, support, deploys), allHalted = false)
  private val page = WatchReply.TafPage("taf_1", "Late parcels", "Waiting for you", halted = false, job = "Reships late parcels",
    cards = listOf(CardLine("Reships", "€12 of €50", "today", listOf("Up to €50 a day"))), waiting = 1)

  private class Phone(val answers: (WatchRequest) -> Asked) : PhoneLink {
    val asked = mutableListOf<WatchRequest>()
    override suspend fun ask(request: WatchRequest): Asked = answers(request).also { asked += request }
  }

  private fun reads(r: WatchRequest, tafsNow: WatchReply.Tafs = tafs): Asked = when (r) {
    is WatchRequest.NeedsYou -> Asked.Answered(needsYou)
    is WatchRequest.Taf -> Asked.Answered(page)
    is WatchRequest.Halt -> Asked.Answered(WatchReply.Done("Halted"))
    else -> Asked.Answered(tafsNow)
  }

  // The page alone: a taf's chat, read first with the switch on, has its own tests (TafChatTest).
  private suspend fun model(phone: Phone, unlocked: Boolean = true) = WatchModel(phone, now = { t }, unlocked = { unlocked }).also { it.tafChatOn = false; it.refresh() }

  // MARK: A taf's page

  @Test fun aTafsPageIsReadFromThePhoneByItsId() = runTest {
    val phone = Phone { reads(it) }
    val model = model(phone)
    model.openTaf("taf_1")
    assertEquals(WatchModel.OpenTaf("taf_1", parcels, WatchModel.TafRead.Read(page)), model.taf.value)
    // A read, by its id, and nothing else.
    assertEquals(WatchRequest.Taf(t, "taf_1"), phone.asked.last())
    model.closeTaf()
    assertNull(model.taf.value)
  }

  @Test fun beforeThePhoneAnswersItsNameAndStateAreYourTafs() = runTest {
    var during: WatchModel.OpenTaf? = null
    lateinit var model: WatchModel
    model = model(Phone { if (it is WatchRequest.Taf) { during = model.taf.value; Asked.Unreachable() } else reads(it) })
    model.openTaf("taf_3")
    assertEquals(WatchModel.OpenTaf("taf_3", deploys, WatchModel.TafRead.Asking), during)
    assertEquals("Deploys", during?.name)
    assertTrue("halted, as Your tafs said", during!!.halted)
  }

  @Test fun aTafsPageNotReadSaysWhyInThePhonesWords() = runTest {
    val cases = mapOf<Asked, WatchModel.TafRead>(
      Asked.Unreachable() to WatchModel.TafRead.PhoneUnreachable(),
      Asked.Answered(WatchReply.SignedOut) to WatchModel.TafRead.SignedOut,
      Asked.Answered(WatchReply.Failed("Open on phone")) to WatchModel.TafRead.Failed("Open on phone"),
      // A phone with an older tafs doesn't know the page.
      Asked.Answered(WatchReply.Refused(NotAccepted.UNKNOWN_PATH)) to WatchModel.TafRead.UpdatePhone,
      Asked.Answered(WatchReply.Refused(NotAccepted.TOO_OLD)) to WatchModel.TafRead.Failed(null),
      Asked.NotUnderstood to WatchModel.TafRead.Failed(null),
      // Another taf's page is never drawn as this one's.
      Asked.Answered(page.copy(tafId = "taf_2")) to WatchModel.TafRead.Failed(null),
    )
    for ((answer, read) in cases) {
      val model = model(Phone { if (it is WatchRequest.Taf) answer else reads(it) })
      model.openTaf("taf_1")
      assertEquals(answer.toString(), read, model.taf.value?.read)
    }
  }

  @Test fun anAnswerForATafNoLongerOpenIsDropped() = runTest {
    lateinit var model: WatchModel
    model = model(Phone { if (it is WatchRequest.Taf) { model.closeTaf(); Asked.Answered(page) } else reads(it) })
    model.openTaf("taf_1")
    assertNull(model.taf.value)
  }

  @Test fun haltingFromATafsPageReadsItAgain() = runTest {
    var halted = false
    val phone = Phone {
      when (it) {
        is WatchRequest.Halt -> { halted = true; Asked.Answered(WatchReply.Done("Halted")) }
        is WatchRequest.Taf -> Asked.Answered(page.copy(state = if (halted) "Halted" else page.state, halted = halted))
        else -> reads(it)
      }
    }
    val model = model(phone)
    model.openTaf("taf_1")
    phone.asked.clear()
    model.halt("taf_1")
    assertEquals(listOf(WatchRequest.Halt(t, "taf_1"), WatchRequest.NeedsYou(t), WatchRequest.Tafs(t), WatchRequest.Taf(t, "taf_1")), phone.asked)
    assertEquals(WatchModel.Outcome.Done("Halted"), model.outcome.value)
    assertTrue("the page as the phone has it now", model.taf.value!!.halted)
  }

  @Test fun putAwayItForgetsTheTafAndThePick() = runTest {
    val model = model(Phone { reads(it) })
    model.openTaf("taf_1")
    model.pickToHalt()
    model.forget()
    assertNull(model.taf.value)
    assertNull(model.haltPick.value)
  }

  // MARK: Choose which to halt

  @Test fun onlyTafsNotHaltedAreOffered() = runTest {
    val model = model(Phone { reads(it) })
    model.pickToHalt()
    assertEquals(listOf(parcels, support), model.haltPick.value?.offered)
    assertEquals(emptySet<String>(), model.haltPick.value?.picked)
    // Halted already: not offered, and can't be picked.
    model.togglePick("taf_3")
    assertEquals(emptySet<String>(), model.haltPick.value?.picked)
  }

  @Test fun withEveryTafHaltedOrNoneReadNothingOpens() = runTest {
    for (tafsNow in listOf(tafs.copy(allHalted = true), tafs.copy(tafs = listOf(deploys)), tafs.copy(tafs = emptyList()))) {
      val model = model(Phone { reads(it, tafsNow) })
      model.pickToHalt()
      assertNull(tafsNow.toString(), model.haltPick.value)
    }
    val unread = WatchModel(Phone { Asked.Unreachable() }, now = { t }, unlocked = { true }).also { it.refresh() }
    unread.pickToHalt()
    assertNull(unread.haltPick.value)
  }

  @Test fun allYourTafsPicksEveryOneOfferedOrNone() = runTest {
    val model = model(Phone { reads(it) })
    model.pickToHalt()
    model.togglePickAll()
    assertEquals(setOf("taf_1", "taf_2"), model.haltPick.value?.picked)
    assertTrue(model.haltPick.value!!.all)
    model.togglePick("taf_2")
    assertFalse(model.haltPick.value!!.all)
    // Not every one on: All your tafs turns every one on; then off.
    model.togglePickAll()
    assertTrue(model.haltPick.value!!.all)
    model.togglePickAll()
    assertEquals(emptySet<String>(), model.haltPick.value?.picked)
  }

  /** Picks, and the requests that go to the phone for them. */
  private suspend fun haltsFor(picks: List<String>, unlocked: Boolean = true): Pair<List<WatchRequest>, WatchModel> {
    val phone = Phone { reads(it) }
    val model = model(phone, unlocked)
    model.pickToHalt()
    picks.forEach(model::togglePick)
    model.askPicked()
    model.haltPicked()
    return phone.asked.filterIsInstance<WatchRequest.Halt>() to model
  }

  @Test fun whatGoesToThePhoneIsWhatWasPicked() = runTest {
    // Every taf offered: one Halt all.
    assertEquals(listOf(WatchRequest.Halt(t, null)), haltsFor(listOf("taf_1", "taf_2")).first)
    // One: that taf.
    val (halts, model) = haltsFor(listOf("taf_2"))
    assertEquals(listOf(WatchRequest.Halt(t, "taf_2")), halts)
    assertNull("closed once sent", model.haltPick.value)
  }

  @Test fun aFewPickedAreHaltedEachThenTheListsAreReadAgain() = runTest {
    val three = tafs.copy(tafs = listOf(parcels, support, TafLine("taf_4", "Invoices", "Ready", halted = false)))
    val phone = Phone { reads(it, three) }
    val model = model(phone)
    phone.asked.clear()
    model.pickToHalt()
    model.togglePick("taf_4")
    model.togglePick("taf_1")
    model.askPicked()
    model.haltPicked()
    // In Your tafs' order, one each, never Halt all; then the lists as they stand.
    assertEquals(
      listOf(WatchRequest.Halt(t, "taf_1"), WatchRequest.Halt(t, "taf_4"), WatchRequest.NeedsYou(t), WatchRequest.Tafs(t)),
      phone.asked,
    )
    assertEquals(
      WatchModel.Outcome.Several(listOf("Late parcels" to WatchModel.Outcome.Done("Halted"), "Invoices" to WatchModel.Outcome.Done("Halted"))),
      model.outcome.value,
    )
    assertTrue((model.outcome.value as WatchModel.Outcome.Several).done)
  }

  @Test fun everyTafPickedShowsHaltingAllYourTafs() = runTest {
    val during = mutableListOf<WatchModel.Outcome?>()
    lateinit var model: WatchModel
    model = model(Phone { if (it is WatchRequest.Halt) { during += model.outcome.value; Asked.Answered(WatchReply.Done("Halted")) } else reads(it) })
    model.pickToHalt()
    model.togglePickAll()
    model.togglePick("taf_2")
    model.togglePick("taf_2")
    // Both picked again: Halt all; one Halting for every taf.
    model.askPicked()
    model.haltPicked()
    assertEquals(listOf(WatchModel.Outcome.Halting(null)), during)
  }

  @Test fun aHaltThatDoesntGoThroughNeverKeepsTheNextFromHalting() = runTest {
    val during = mutableListOf<WatchModel.Outcome?>()
    lateinit var model: WatchModel
    val phone = Phone {
      if (it is WatchRequest.Halt) {
        during += model.outcome.value
        if (it.tafId == "taf_1") Asked.Answered(WatchReply.Failed("tafs couldn't halt it.")) else Asked.Unreachable()
      } else reads(it, tafs.copy(tafs = listOf(parcels, support, TafLine("taf_4", "Invoices", "Ready", halted = false))))
    }
    model = model(phone)
    model.pickToHalt()
    model.togglePick("taf_1")
    model.togglePick("taf_2")
    model.askPicked()
    model.haltPicked()
    assertEquals(listOf(WatchRequest.Halt(t, "taf_1"), WatchRequest.Halt(t, "taf_2")), phone.asked.filterIsInstance<WatchRequest.Halt>())
    assertEquals(List(2) { WatchModel.Outcome.Halting("Late parcels, Support replies") }, during)
    val several = model.outcome.value as WatchModel.Outcome.Several
    assertEquals(
      listOf("Late parcels" to WatchModel.Outcome.NotDone("tafs couldn't halt it."), "Support replies" to WatchModel.Outcome.PhoneUnreachable()),
      several.each,
    )
    assertFalse(several.done)
  }

  @Test fun aLockedWatchSendsNoHaltWhateverIsPicked() = runTest {
    for (picks in listOf(listOf("taf_1", "taf_2"), listOf("taf_1"))) {
      val (halts, model) = haltsFor(picks, unlocked = false)
      assertEquals(picks.toString(), emptyList<WatchRequest>(), halts)
      assertEquals(WatchModel.Outcome.NeedsLock, model.outcome.value)
    }
    // Several (three running, two picked), locked: none sent.
    val phone = Phone { reads(it, tafs.copy(tafs = listOf(parcels, support, TafLine("taf_4", "Invoices", "Ready", halted = false)))) }
    val model = model(phone, unlocked = false)
    model.pickToHalt()
    model.togglePick("taf_1")
    model.togglePick("taf_4")
    model.askPicked()
    model.haltPicked()
    assertTrue(phone.asked.none { it is WatchRequest.Halt })
    assertEquals(WatchModel.Outcome.NeedsLock, model.outcome.value)
  }

  @Test fun cancelOrNotAskedAgainSendsNothing() = runTest {
    val phone = Phone { reads(it) }
    val model = model(phone)
    model.pickToHalt()
    model.togglePick("taf_1")
    // Not asked once more: nothing.
    model.haltPicked()
    assertFalse(model.haltPick.value!!.confirming)
    model.askPicked()
    assertTrue(model.haltPick.value!!.confirming)
    // Cancel goes back to the choice, as it was; switches don't move while it's asked.
    model.togglePick("taf_2")
    model.cancelPick()
    assertEquals(WatchModel.HaltPick(listOf(parcels, support), setOf("taf_1")), model.haltPick.value)
    model.haltPicked()
    // Cancel again closes it.
    model.cancelPick()
    assertNull(model.haltPick.value)
    model.haltPicked()
    assertTrue(phone.asked.none { it is WatchRequest.Halt })
  }

  @Test fun withNonePickedHaltNowAsksNothing() = runTest {
    val model = model(Phone { reads(it) })
    model.pickToHalt()
    model.askPicked()
    assertFalse(model.haltPick.value!!.confirming)
  }
}

/** Wear v2, step (v), the screens: a taf's page and its settings, and Choose which to halt. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = WatchApplication::class)
class TafScreensTest {
  @get:Rule val compose = createComposeRule()

  @Before fun words() {
    // Tall, so a whole page is drawn at once and every line can be found.
    RuntimeEnvironment.setQualifiers("en-w192dp-h2400dp-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
  }

  private val did = mutableListOf<String>()
  private val actions = Actions(
    openOnPhone = { did += "phone $it" }, tryAgain = {}, refuse = {}, halt = { did += "halt $it" }, askHaltAll = { did += "ask halt all" },
    confirmHaltAll = { did += "halt all" }, cancelHaltAll = {}, listen = {}, closeVoice = {}, retry = {}, open = {}, hear = {}, reply = {},
    closeResult = {}, dismiss = {},
    openTaf = { did += "open $it" }, closeTaf = { did += "close taf" }, rereadTaf = { did += "reread" }, pickToHalt = { did += "pick to halt" }, togglePick = { did += "pick $it" },
    togglePickAll = { did += "pick all" }, askPicked = { did += "ask picked" }, cancelPick = { did += "cancel pick" }, haltPicked = { did += "halt picked" },
  )

  private val parcels = TafLine("taf_1", "Late parcels", "Waiting for you", halted = false)
  private val support = TafLine("taf_2", "Support replies", "Next run 14:00", halted = false)
  private val tafs = WatchModel.TafsRead(listOf(parcels, support), allHalted = false)
  private val call = HeldLine("hold_1", "Late parcels", "Reship order #5098", "€12", "Reships wait for your yes", refusable = true)
  private val read = WatchModel.Shown.Read(WatchModel.NeedsYouRead(1, "1 held call needs you", listOf(call)), tafs)
  private val page = WatchReply.TafPage(
    "taf_1", "Late parcels", "Working", halted = false, job = "Reships parcels that are late, up to €50 a day",
    cards = listOf(CardLine("Reships", "€12 of €50", "used today", listOf("Up to €50 a day", "Only to the same address"))), waiting = 2,
  )

  private fun open(page: WatchReply.TafPage) = WatchModel.OpenTaf(page.tafId, null, WatchModel.TafRead.Read(page))

  private fun showTaf(open: WatchModel.OpenTaf, outcome: WatchModel.Outcome? = null) =
    compose.setContent { MaterialTheme { TafScreen(open, outcome, actions, openInbox = { did += "inbox" }) } }

  private fun showPages(
    page: Page = Page.Coach,
    taf: WatchModel.OpenTaf? = null,
    haltPick: WatchModel.HaltPick? = null,
    shown: WatchModel.Shown = read,
    outcome: WatchModel.Outcome? = null,
  ) = compose.setContent {
    MaterialTheme {
      val pages = pages(false)
      WatchPages(
        shown, outcome = outcome, confirmingHaltAll = false, voice = null, result = null, working = emptyList(), ready = emptyList(), act = actions,
        pages = pages, pager = rememberPagerState(initialPage = pages.indexOf(page)) { pages.size }, taf = taf, haltPick = haltPick,
      )
    }
  }

  // MARK: Tafs, and a taf's page

  @Test fun tappingATafOnTheTafsPageOpensItsPage() {
    showPages(Page.Tafs)
    compose.onNodeWithText("Support replies").performClick()
    assertEquals(listOf("open taf_2"), did)
  }

  @Test fun aTafsPageShowsWhatItDoesItsCardAndWhatWaits() {
    showTaf(open(page))
    for (words in listOf(
      "Late parcels", "Working", Words.Watch.whatItDoes, page.job, Words.Card.itsCard, "Reships", "€12 of €50 · used today",
      "Up to €50 a day", "Only to the same address", Words.Chat.waitingForYou,
    )) compose.onNodeWithText(words).assertExists()
    compose.onAllNodesWithText(Words.Watch.nothingWaits).assertCountEquals(0)
    // Its held calls are in the Inbox.
    compose.onNodeWithText(Words.Watch.needsYouCount(2)).performClick()
    // Not halted: nothing of resuming, and Halt is in its settings only.
    compose.onAllNodesWithText(Words.Halt.Explain.halted).assertCountEquals(0)
    compose.onAllNodesWithText(Words.Watch.haltThis).assertCountEquals(0)
    compose.onNodeWithText(Words.Watch.swipeSettings).assertExists()
    assertEquals(listOf("inbox"), did)
  }

  @Test fun withNothingWaitingItSaysSo() {
    showTaf(open(page.copy(waiting = 0, cards = emptyList())))
    compose.onNodeWithText(Words.Watch.nothingWaits).assertExists()
    compose.onAllNodesWithText(Words.Card.itsCard).assertCountEquals(0)
  }

  @Test fun itsHeldCallsGoToTheInbox() {
    var taf by mutableStateOf<WatchModel.OpenTaf?>(open(page))
    val act = Actions(
      openOnPhone = {}, tryAgain = {}, refuse = {}, halt = {}, askHaltAll = {}, confirmHaltAll = {}, cancelHaltAll = {}, listen = {}, closeVoice = {},
      retry = {}, open = {}, hear = {}, reply = {}, closeResult = {}, dismiss = {}, closeTaf = { taf = null },
    )
    compose.setContent {
      MaterialTheme {
        val pages = pages(false)
        WatchPages(
          read, outcome = null, confirmingHaltAll = false, voice = null, result = null, working = emptyList(), ready = emptyList(), act = act,
          pages = pages, pager = rememberPagerState(initialPage = pages.indexOf(Page.Tafs)) { pages.size }, taf = taf,
        )
      }
    }
    compose.onNodeWithText(Words.Watch.needsYouCount(2)).performClick()
    compose.waitForIdle()
    // The taf's page closed, on the Inbox, where its held call is with Refuse and Approve on your phone.
    compose.onNodeWithText("Reship order #5098").assertExists()
    compose.onNodeWithText(Words.Watch.Page.inbox).assertExists()
    compose.onAllNodesWithText("Next run 14:00").assertCountEquals(0)
  }

  @Test fun aHaltedTafSaysResumingIsThePhones() {
    showTaf(open(page.copy(state = "Halted", halted = true)))
    compose.onNodeWithText("Halted").assertExists()
    compose.onNodeWithText(Words.Halt.Explain.halted).assertExists()
    compose.onNodeWithText("↗ ${Words.Watch.resumeOnPhone}").performClick()
    // Its settings: no Halt, resuming is the phone's there too.
    compose.onNodeWithText("${Words.Watch.itsSettings} ›").performClick()
    compose.waitForIdle()
    compose.onNodeWithText(Words.Watch.settingsOf("Late parcels")).assertExists()
    compose.onAllNodesWithText(Words.Watch.haltThis).assertCountEquals(0)
    // Row 28: that taf, on the phone.
    assertEquals(listOf("phone taf/taf_1"), did)
  }

  @Test fun beforeThePhoneAnswersThePageSaysSoAsV1() {
    showTaf(WatchModel.OpenTaf("taf_1", parcels, WatchModel.TafRead.Asking))
    compose.onNodeWithText("Late parcels").assertExists()
    compose.onNodeWithText(Words.Watch.askingPhone).assertExists()
  }

  /** Row 30, call 6: no phone, the calm linking line and no Try again; the page is read again by itself while it shows. */
  @Test fun withNoPhoneItsPageIsAskedAgain() {
    compose.mainClock.autoAdvance = false
    showTaf(WatchModel.OpenTaf("taf_1", parcels, WatchModel.TafRead.PhoneUnreachable()))
    compose.onNodeWithText(Words.Watch.linking).assertExists()
    compose.onAllNodesWithText(Words.Common.tryAgain).assertCountEquals(0)
    compose.mainClock.advanceTimeBy(RETRY_BACKOFF_MS.first() + 100)
    compose.waitForIdle()
    assertEquals(listOf("reread"), did)
  }

  @Test fun anOlderPhoneSaysToUpdateIt() {
    showTaf(WatchModel.OpenTaf("taf_1", parcels, WatchModel.TafRead.UpdatePhone))
    compose.onNodeWithText(Words.Watch.updatePhone).assertExists()
  }

  @Test fun itsSettingsHaltThisTafAskedOnceMore() {
    showTaf(open(page))
    compose.onNodeWithText("${Words.Watch.itsSettings} ›").performClick()
    compose.waitForIdle()
    compose.onNodeWithText(Words.Watch.settingsOf("Late parcels")).assertExists()
    compose.onNodeWithText(Words.Watch.openOnPhone).assertExists()
    compose.onNodeWithText(Words.Watch.haltThis).performClick()
    // Asked once more: what, and what halting does. Cancel sends nothing.
    compose.onNodeWithText(Words.Halt.Explain.runningOne).assertExists()
    compose.onNodeWithText(Words.Common.cancel).performClick()
    assertEquals(emptyList<String>(), did)
    compose.onNodeWithText(Words.Watch.haltThis).performClick()
    compose.onNodeWithText(Words.Halt.now).performClick()
    assertEquals(listOf("halt taf_1"), did)
  }

  @Test fun aTafsPageIsDrawnOverThePages() {
    showPages(Page.Tafs, taf = open(page))
    compose.onNodeWithText(page.job).assertExists()
    compose.onAllNodesWithText("Next run 14:00").assertCountEquals(0)
  }

  // MARK: Choose which to halt

  @Test fun theMenusHaltOpensChooseWhichToHalt() {
    showPages()
    compose.onNodeWithText(Words.Watch.menu).performClick()
    compose.onNodeWithText(Words.Taf.Halt.title).performClick()
    assertEquals(listOf("pick to halt"), did)
  }

  @Test fun withEveryTafHaltedOneByOneTheMenuOffersNoHalt() {
    showPages(shown = read.copy(tafs = tafs.copy(tafs = tafs.tafs.map { it.copy(halted = true) })))
    compose.onNodeWithText(Words.Watch.menu).performClick()
    compose.onAllNodesWithText(Words.Taf.Halt.title).assertCountEquals(0)
  }

  @Test fun chooseWhichToHaltIsAllYourTafsThenEachWithACount() {
    showPages(haltPick = WatchModel.HaltPick(listOf(parcels, support), setOf("taf_2")))
    compose.onNodeWithText(Words.Watch.haltPickTitle).assertExists()
    compose.onNodeWithText(Words.Watch.allYourTafs).assertIsOff()
    compose.onNodeWithText("Late parcels").assertIsOff()
    compose.onNodeWithText("Support replies").assertIsOn()
    compose.onNodeWithText("Late parcels").performClick()
    compose.onNodeWithText(Words.Watch.allYourTafs).performClick()
    compose.onNodeWithText(Words.Watch.haltNowCount(1)).performClick()
    compose.onNodeWithText(Words.Common.cancel).performClick()
    assertEquals(listOf("pick taf_1", "pick all", "ask picked", "cancel pick"), did)
  }

  @Test fun withNonePickedHaltNowIsOff() {
    showPages(haltPick = WatchModel.HaltPick(listOf(parcels, support)))
    compose.onNodeWithText(Words.Watch.haltNowCount(0)).assertIsNotEnabled().performClick()
    assertEquals(emptyList<String>(), did)
  }

  @Test fun everyTafPickedIsAllYourTafs() {
    showPages(haltPick = WatchModel.HaltPick(listOf(parcels, support), setOf("taf_1", "taf_2")))
    compose.onNodeWithText(Words.Watch.allYourTafs).assertIsOn()
    compose.onNodeWithText(Words.Watch.haltNowCount(2)).assertExists()
  }

  @Test fun everyTafPickedAsksHaltAllYourTafs() {
    showPages(haltPick = WatchModel.HaltPick(listOf(parcels, support), setOf("taf_1", "taf_2"), confirming = true))
    compose.onNodeWithText(Words.Watch.haltAllQ).assertExists()
    compose.onNodeWithText(Words.Watch.allYourTafs).assertExists()
    compose.onNodeWithText(Words.Halt.Explain.runningAll).assertExists()
    compose.onNodeWithText(Words.Common.cancel).performClick()
    compose.onNodeWithText(Words.Halt.now).performClick()
    assertEquals(listOf("cancel pick", "halt picked"), did)
  }

  @Test fun oneOrAFewPickedAreAskedByName() {
    val three = listOf(parcels, support, TafLine("taf_4", "Invoices", "Ready", halted = false))
    showPages(haltPick = WatchModel.HaltPick(three, setOf("taf_2"), confirming = true))
    compose.onNodeWithText(Words.Watch.haltPickTitle).assertExists()
    compose.onNodeWithText("Support replies").assertExists()
    compose.onNodeWithText(Words.Halt.Explain.runningOne).assertExists()
    compose.onAllNodesWithText(Words.Watch.haltAllQ).assertCountEquals(0)
  }

  @Test fun aFewPickedAreAskedByNameAsTafs() {
    val three = listOf(parcels, support, TafLine("taf_4", "Invoices", "Ready", halted = false))
    showPages(haltPick = WatchModel.HaltPick(three, setOf("taf_4", "taf_1"), confirming = true))
    compose.onNodeWithText("Late parcels, Invoices").assertExists()
    compose.onNodeWithText(Words.Halt.Explain.runningAll).assertExists()
  }

  @Test fun severalHaltsSayWhatCameOfEach() {
    val several = WatchModel.Outcome.Several(listOf("Late parcels" to WatchModel.Outcome.Done("Halted"), "Invoices" to WatchModel.Outcome.PhoneUnreachable()))
    showPages(Page.Tafs, outcome = several)
    compose.onNodeWithText("Late parcels · Halted").assertExists()
    compose.onNodeWithText("Invoices · ${Words.Watch.phoneNotReachable}").assertExists()
    // One didn't go through: the phone.
    compose.onNodeWithText(Words.Watch.openOnPhone).assertExists()
  }

  @Test fun severalHaltsAllThroughOfferNoPhone() {
    val several = WatchModel.Outcome.Several(listOf("Late parcels" to WatchModel.Outcome.Done("Halted"), "Invoices" to WatchModel.Outcome.Done("Halted")))
    showPages(Page.Tafs, outcome = several)
    compose.onNodeWithText("Invoices · Halted").assertExists()
    compose.onAllNodesWithText(Words.Watch.openOnPhone).assertCountEquals(0)
  }
}
