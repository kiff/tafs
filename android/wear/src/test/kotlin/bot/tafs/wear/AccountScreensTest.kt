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
import bot.tafs.watchlink.EventLine
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
 * Wear v2, step (vi) (docs/watch-wear.md, Version 2), the model: a calendar day and Account read from the phone, and Sign out on
 * this watch alone, which forgets everything the watch holds and reads and sends nothing until it's signed in there again.
 */
class AccountModelTest {
  private val t = 1_763_244_000_000L
  private val call = HeldLine("hold_1", "Refunds", "Refund order 1042", "€64.00", "Over €50 each", refusable = true)
  private val needsYou = WatchReply.NeedsYou(1, "1 held call needs you", listOf(call))
  private val tafs = WatchReply.Tafs(listOf(TafLine("taf_1", "Refunds", "Waiting for you", halted = false)), allHalted = false)
  private val today = WatchReply.CalendarDay("2026-11-19", "3 to come", listOf(EventLine("09:30", "Lead follow-up", "Calls Nord Atelier back")))
  private val page = WatchReply.AccountPage("you@example.com", "Nord Atelier", "Solo · 260 of 500 credits left")
  private val taken = AskLine("wa_01", "w_00000001", AskLine.ACCEPTED, words = "Sent", updated = "2026-11-16T19:00:00Z")

  private class Phone(val answers: (WatchRequest) -> Asked) : PhoneLink {
    val asked = mutableListOf<WatchRequest>()
    override suspend fun ask(request: WatchRequest): Asked = answers(request).also { asked += request }
  }

  private fun reads(r: WatchRequest): Asked = Asked.Answered(
    when (r) {
      is WatchRequest.NeedsYou -> needsYou
      is WatchRequest.Calendar -> r.day?.let { today.copy(day = it, words = "Nothing scheduled") } ?: today
      is WatchRequest.Account -> page
      is WatchRequest.Ask -> WatchReply.AskTaken(taken)
      is WatchRequest.Asks -> WatchReply.AskList(listOf(taken))
      is WatchRequest.Refuse, is WatchRequest.Halt -> WatchReply.Done("Refused")
      else -> tafs
    },
  )

  private class Drafts : DraftStore {
    var kept: WatchModel.Draft? = null
    override fun read() = kept
    override fun write(draft: WatchModel.Draft) { kept = draft }
    override fun clear() { kept = null }
  }

  private class Tile : TileCount.Store {
    var kept: Pair<Int, Long>? = null
    override fun read() = kept
    override fun write(count: Int, at: Long) { kept = count to at }
    override fun clear() { kept = null }
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

  /** The phone's person allowed this watch's sign-in ask (row 18): the only way back in. */
  private fun WatchModel.allowedBy(signIn: SignIn) =
    signInAnswered(bot.tafs.watchlink.KeptSignIn(t, signIn.asked()!!.first, allowed = true, mark = "AAAAAAAAAAAAAAAAAAAAAA"))

  private class Recording : VoiceAudio {
    var deleted = 0
    override val durationMs = 12_000L
    override fun delete() { deleted++ }
  }

  private fun v2(phone: PhoneLink, drafts: DraftStore? = null, tile: TileCount.Store? = null, signIn: WatchSignIn? = SignIn(), unlocked: () -> Boolean = { true }) =
    WatchModel(
      phone, now = { t }, unlocked = unlocked, tile = tile?.let(::TileCount), newClientId = { "w_00000001" }, seen = mutableSetOf(),
      reviewFirst = true, drafts = drafts, signIn = signIn,
    )

  @Test fun aCalendarDayIsTafsCloudsAndTodayIsThePhonesToday() = runTest {
    val phone = Phone(::reads)
    val model = v2(phone)
    assertNull("nothing until it's opened", model.calendar.value)
    model.readCalendar()
    assertEquals(WatchModel.Reading.Read(WatchModel.DayRead(today, today = true)), model.calendar.value)
    model.readCalendar("2026-11-20")
    assertEquals(WatchModel.Reading.Read(WatchModel.DayRead(today.copy(day = "2026-11-20", words = "Nothing scheduled"), today = false)), model.calendar.value)
    model.readCalendar("2026-11-19")
    assertEquals(true, (model.calendar.value as WatchModel.Reading.Read).value.today)
    // Reads only: nothing on the calendar asks the phone to sign.
    assertEquals(listOf(WatchRequest.Calendar(t), WatchRequest.Calendar(t, "2026-11-20"), WatchRequest.Calendar(t, "2026-11-19")), phone.asked)
  }

  @Test fun theCalendarSaysWhyThereIsNoDay() = runTest {
    val cases = mapOf<Asked, WatchModel.Reading<WatchModel.DayRead>>(
      Asked.Answered(WatchReply.SignedOut) to WatchModel.Reading.SignedOut,
      Asked.Unreachable() to WatchModel.Reading.PhoneUnreachable(),
      Asked.NotUnderstood to WatchModel.Reading.OpenOnPhone,
      Asked.Answered(WatchReply.Failed("Open on phone")) to WatchModel.Reading.OpenOnPhone,
      Asked.Answered(WatchReply.Refused(NotAccepted.UNKNOWN_PATH)) to WatchModel.Reading.OpenOnPhone,
    )
    for ((answer, shown) in cases) {
      val model = v2(Phone { answer })
      model.readCalendar()
      assertEquals(answer.toString(), shown, model.calendar.value)
    }
  }

  @Test fun anAnswerForAClosedCalendarIsDropped() = runTest {
    lateinit var model: WatchModel
    model = v2(Phone { r -> model.closeCalendar(); reads(r) })
    model.readCalendar()
    assertNull(model.calendar.value)
  }

  @Test fun accountIsReadFromThePhoneInItsWords() = runTest {
    val phone = Phone(::reads)
    val model = v2(phone)
    model.readAccount()
    assertEquals(WatchModel.Reading.Read(page), model.account.value)
    assertEquals(listOf(WatchRequest.Account(t)), phone.asked)
    for ((answer, shown) in mapOf<Asked, WatchModel.Reading<WatchReply.AccountPage>>(
      Asked.Answered(WatchReply.SignedOut) to WatchModel.Reading.SignedOut,
      Asked.Unreachable() to WatchModel.Reading.PhoneUnreachable(),
      Asked.NotUnderstood to WatchModel.Reading.OpenOnPhone,
      Asked.Answered(needsYou) to WatchModel.Reading.OpenOnPhone,
    )) {
      val other = v2(Phone { answer })
      other.readAccount()
      assertEquals(answer.toString(), shown, other.account.value)
    }
  }

  @Test fun signingOutOnTheWatchForgetsEverythingItHolds() = runTest {
    // The phone out of reach for asks, so the kept message stays until the sign-out.
    val phone = Phone { if (it is WatchRequest.Ask) Asked.Unreachable() else reads(it) }
    val drafts = Drafts().apply { kept = WatchModel.Draft("Thanks", "w_00000009", null) }
    val tile = Tile()
    val signIn = SignIn()
    val model = v2(phone, drafts, tile, signIn)
    model.refresh()
    model.readAsks()
    model.readCalendar()
    model.readAccount()
    model.open(taken)
    model.listen()
    val audio = Recording()
    model.heard("Move my call to six", audio)
    assertTrue(model.shown.value is WatchModel.Shown.Read)
    assertEquals(1 to t, tile.kept)
    assertEquals(drafts.kept, model.kept.value)
    phone.asked.clear()

    model.signOutHere()
    assertTrue(model.signedOutHere.value)
    assertTrue("remembered on the watch until Sign in, there", signIn.out)
    assertEquals(WatchModel.Shown.Asking, model.shown.value)
    assertNull("the kept message", model.kept.value)
    assertNull(drafts.kept)
    assertNull("the tile's count", tile.kept)
    assertEquals("the recording", 1, audio.deleted)
    assertNull(model.voice.value)
    assertTrue(model.asks.value.isEmpty())
    assertNull(model.result.value)
    assertNull(model.calendar.value)
    assertNull(model.account.value)
    assertTrue("the phone is told nothing: it stays signed in", phone.asked.isEmpty())
  }

  /** Row 18: signed out from one copy of the app, another copy (opened earlier from the tile) follows it when it comes back. */
  @Test fun aSignOutFromAnotherCopyOfTheAppHoldsWhenThisOneComesBack() = runTest {
    val signIn = SignIn()
    val phone = Phone(::reads)
    val earlier = v2(phone, signIn = signIn)
    earlier.refresh()
    assertTrue(earlier.shown.value is WatchModel.Shown.Read)
    v2(phone, signIn = signIn).signOutHere()
    // Back to the front: it reads the watch's own flag before drawing anything, and nothing of the account stays.
    earlier.reload()
    assertTrue(earlier.signedOutHere.value)
    val asked = phone.asked.size
    earlier.refresh()
    assertEquals("it asks the phone nothing", asked, phone.asked.size)
  }

  @Test fun signedOutOnTheWatchItReadsAndSendsNothingUntilSignedInThere() = runTest {
    val phone = Phone(::reads)
    val signIn = SignIn()
    val model = v2(phone, signIn = signIn)
    model.signOutHere()
    model.refresh()
    model.readAsks()
    model.readCalendar()
    model.readAccount()
    assertFalse(model.listen())
    model.readyToRecord()
    model.review("Thanks")
    model.refuse("hold_1")
    model.halt("taf_1")
    model.askHaltAll()
    model.confirmHaltAll()
    model.took(taken)
    assertTrue(phone.asked.isEmpty())
    assertNull(model.voice.value)
    assertTrue(model.asks.value.isEmpty())
    assertEquals(WatchModel.Shown.Asking, model.shown.value)
    // The app opened again: still signed out on this watch.
    val again = v2(phone, signIn = signIn)
    assertTrue(again.signedOutHere.value)
    again.refresh()
    assertTrue(phone.asked.isEmpty())
    // Sign in again: asked of the phone's person, and only their Allow signs the watch in.
    signIn.ask("w_00000002", t)
    assertTrue(again.allowedBy(signIn))
    assertFalse(signIn.out)
    again.refresh()
    assertTrue(again.shown.value is WatchModel.Shown.Read)
  }

  /**
   * Row 18: a tap on the watch alone never signs it in. The ask goes to the phone, which asks its person; only their Allow for this
   * ask, within ten minutes, signs the watch in. Deny, another ask's answer, or a late one leave it signed out.
   */
  @Test fun signingInAgainNeedsThePhonesAllowForThisAsk() = runTest {
    val signIn = SignIn()
    val phone = Phone { r -> if (r is WatchRequest.SignInAsk) Asked.Answered(WatchReply.Done("Waiting for your phone")) else reads(r) }
    val model = v2(phone, signIn = signIn)
    model.signOutHere()
    model.askSignIn()
    val ask = (phone.asked.single { it is WatchRequest.SignInAsk } as WatchRequest.SignInAsk).ask
    assertTrue("still signed out while the phone asks", model.signedOutHere.value)
    assertEquals(WatchModel.SignInAsk.Waiting, model.signInAsk.value)
    // Another ask's answer, or a late one: nothing.
    assertFalse(model.signInAnswered(bot.tafs.watchlink.KeptSignIn(t, "w_someoneelse", allowed = true)))
    signIn.ask(ask, t - SIGN_IN_ASK_MS - 1)
    assertFalse(model.signInAnswered(bot.tafs.watchlink.KeptSignIn(t, ask, allowed = true)))
    assertTrue(model.signedOutHere.value)
    // Deny: it stays signed out, and says so.
    signIn.ask(ask, t)
    assertFalse(model.signInAnswered(bot.tafs.watchlink.KeptSignIn(t, ask, allowed = false)))
    assertTrue(model.signedOutHere.value)
    assertEquals(WatchModel.SignInAsk.Denied, model.signInAsk.value)
    // Allow for the ask waiting: signed in again, with nothing from before.
    model.askSignIn()
    assertTrue(model.allowedBy(signIn))
    assertFalse(model.signedOutHere.value)
    assertTrue(model.inbox.value.isEmpty() && model.asks.value.isEmpty() && model.kept.value == null)
  }

  /** The phone not reached: nothing waits, and the watch says why. */
  @Test fun aSignInAskThePhoneDoesntGetSaysWhy() = runTest {
    val signIn = SignIn()
    val model = v2(Phone { Asked.Unreachable() }, signIn = signIn)
    model.signOutHere()
    model.askSignIn()
    assertTrue(model.signInAsk.value is WatchModel.SignInAsk.NotReached)
    assertNull(signIn.asked())
    assertTrue(model.signedOutHere.value)
  }

  @Test fun anAnswerThatLandsAfterSigningOutOnTheWatchIsDropped() = runTest {
    val tile = Tile()
    lateinit var model: WatchModel
    model = v2(Phone { r -> model.signOutHere(); reads(r) }, tile = tile)
    model.refresh()
    assertEquals(WatchModel.Shown.Asking, model.shown.value)
    assertNull(tile.kept)
    model.readAccount()
    assertNull(model.account.value)
  }

  @Test fun refuseFromTheWristGoesOnlyFromAnUnlockedWatch() = runTest {
    val phone = Phone(::reads)
    var unlocked = false
    val model = v2(phone, unlocked = { unlocked })
    model.refuse("hold_1")
    assertEquals(WatchModel.Outcome.NeedsLock, model.outcome.value)
    assertTrue(phone.asked.none { it is WatchRequest.Refuse })
    unlocked = true
    model.refuse("hold_1")
    assertEquals(listOf(WatchRequest.Refuse(t, "hold_1")), phone.asked.filterIsInstance<WatchRequest.Refuse>())
    assertEquals(WatchModel.Outcome.Done("Refused"), model.outcome.value)
  }

  @Test fun theDayBeforeAndAfterAreWholeDays() {
    assertEquals("2026-11-18", shift("2026-11-19", -1))
    assertEquals("2027-01-01", shift("2026-12-31", 1))
    assertEquals("2028-02-29", shift("2028-02-28", 1))
  }
}

/** Wear v2, step (vi), the screens: a held call whole, the hand-off to the phone, a calendar day, Account and Sign out. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = WatchApplication::class)
class AccountScreensTest {
  @get:Rule val compose = createComposeRule()

  @Before fun words() {
    // Tall, so a whole screen is drawn at once and every line can be found.
    RuntimeEnvironment.setQualifiers("en-w192dp-h2400dp-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
  }

  private val call = HeldLine("hold_1", "Late parcels", "Reship order #5098", "€12", "Reships wait for your yes", refusable = true)
  private val tafs = WatchModel.TafsRead(listOf(TafLine("taf_1", "Late parcels", "Waiting for you", halted = false)), allHalted = false)
  private fun read(vararg calls: HeldLine) = WatchModel.Shown.Read(WatchModel.NeedsYouRead(calls.size, "Held calls", calls.toList()), tafs)

  private val did = mutableListOf<String>()
  private val actions = Actions(
    openOnPhone = { did += "open on phone $it" }, tryAgain = { did += "refresh" }, refuse = { did += "refuse $it" }, halt = {}, askHaltAll = {},
    confirmHaltAll = {}, cancelHaltAll = {}, listen = { did += "listen" }, closeVoice = {}, retry = {}, open = {}, hear = {}, reply = {},
    closeResult = {}, dismiss = {},
    readCalendar = { did += "calendar ${it ?: "today"}" }, closeCalendar = { did += "close calendar" }, readAccount = { did += "account" },
    signOut = { did += "sign out" }, signIn = { did += "sign in" },
  )

  private fun show(
    shown: WatchModel.Shown,
    page: Page,
    calendar: WatchModel.Reading<WatchModel.DayRead>? = null,
    account: WatchModel.Reading<WatchReply.AccountPage>? = null,
    signedOutHere: Boolean = false,
  ) {
    val pages = pages(false)
    compose.setContent {
      MaterialTheme {
        WatchPages(
          shown, outcome = null, confirmingHaltAll = false, voice = null, result = null, working = emptyList(), ready = emptyList(), act = actions,
          pages = pages, pager = rememberPagerState(initialPage = pages.indexOf(page)) { pages.size },
          signedOutHere = signedOutHere, calendar = calendar, account = account,
        )
      }
    }
  }

  @Test fun aHeldCallOpensWholeFromTheInbox() {
    show(read(call), Page.Inbox)
    compose.onNodeWithText("Reship order #5098").performClick()
    for (words in listOf(Words.NeedsYou.title, "Late parcels", "Reship order #5098", "€12", "Reships wait for your yes", Words.Watch.nothingSentUntil, Words.Watch.approveOnPhone)) {
      compose.onNodeWithText(words).assertExists()
    }
    compose.onNodeWithText(Words.NeedsYou.refuse).performClick()
    assertEquals(listOf("refuse hold_1"), did)
    // Back on the pages, where what came of it shows.
    compose.onNodeWithText(Words.Watch.Page.inbox).assertExists()
  }

  @Test fun refuseIsOfferedOnlyWhereTheCallIsRefusable() {
    show(read(call.copy(refusable = false)), Page.Inbox)
    compose.onNodeWithText("Reship order #5098").performClick()
    compose.onNodeWithText(Words.Watch.approveOnPhone).assertExists()
    compose.onAllNodesWithText(Words.NeedsYou.refuse).assertCountEquals(0)
  }

  @Test fun approveOnYourPhoneHandsOffAndNeverApproves() {
    show(read(call), Page.Inbox)
    compose.onNodeWithText("Reship order #5098").performClick()
    compose.onNodeWithText(Words.Watch.approveOnPhone).performClick()
    assertEquals(listOf("open on phone needs-you"), did)
    compose.onNodeWithText(Words.Watch.checkPhoneTitle).assertExists()
    compose.onNodeWithText(Words.Watch.checkPhoneSub).assertExists()
    // The phone opens Needs you's list, not the one call: the line doesn't say it does.
    assertFalse(Words.Watch.checkPhoneSub.contains("this call"))
    compose.onNodeWithText(Words.Common.ok).performClick()
    // Back on the Inbox, read again; nothing was refused or approved from the wrist.
    compose.onNodeWithText(Words.Watch.Page.inbox).assertExists()
    assertEquals(listOf("open on phone needs-you", "refresh"), did)
  }

  @Test fun theCalendarsDoorIsOnTheTafsPage() {
    show(read(), Page.Tafs, calendar = WatchModel.Reading.Asking)
    compose.onNodeWithText(Words.Watch.calendarToday).performClick()
    compose.waitForIdle()
    assertEquals(listOf("calendar today"), did)
    compose.onNodeWithText(Words.Calendar.reading).assertExists()
    compose.onNodeWithText(Words.Common.close).performClick()
    assertEquals(listOf("calendar today", "close calendar"), did)
    compose.onNodeWithText(Words.Home.title).assertExists()
  }

  @Test fun aCalendarDayShowsTafsCloudsLineAndEachEventInOrder() {
    val day = WatchReply.CalendarDay(
      "2026-11-19", "3 to come",
      listOf(EventLine("09:30", "Support replies", "Answers the overnight tickets"), EventLine("14:00", "Lead follow-up", "Calls Nord Atelier back", count = 3), EventLine("18:00", "Late parcels", "Checks the day's scans")),
    )
    show(read(), Page.Tafs, calendar = WatchModel.Reading.Read(WatchModel.DayRead(day, today = true)))
    compose.onNodeWithText(Words.Watch.calendarToday).performClick()
    compose.onNodeWithText(Words.Calendar.today).assertExists()
    compose.onNodeWithText("3 to come").assertExists()
    compose.onNodeWithText(Words.Calendar.times(3)).assertExists()
    val tops = listOf("09:30", "Answers the overnight tickets", "14:00", "Calls Nord Atelier back", "18:00", "Checks the day's scans").map {
      compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
    }
    assertEquals("in tafs cloud's order", tops.sorted(), tops)
    assertTrue(tops[0] < tops[2] && tops[2] < tops[4])
    compose.onNodeWithText(Words.Calendar.nextDay).performClick()
    compose.onNodeWithText(Words.Calendar.previousDay).performClick()
    assertEquals(listOf("calendar today", "calendar 2026-11-20", "calendar 2026-11-18"), did)
  }

  @Test fun anotherDayIsNamedByItsDateNotToday() {
    val day = WatchReply.CalendarDay("2026-11-20", "Nothing scheduled", halted = true)
    show(read(), Page.Tafs, calendar = WatchModel.Reading.Read(WatchModel.DayRead(day, today = false)))
    compose.onNodeWithText(Words.Watch.calendarToday).performClick()
    compose.onNodeWithText("Friday, November 20").assertExists()
    compose.onAllNodesWithText(Words.Calendar.today).assertCountEquals(0)
    compose.onNodeWithText("Nothing scheduled").assertExists()
  }

  @Test fun accountShowsWhoIsSignedInThePlanLanguageVersionAndTrust() {
    val page = WatchReply.AccountPage("you@example.com", "Nord Atelier", "Solo · 260 of 500 credits left")
    show(read(), Page.Account, account = WatchModel.Reading.Read(page))
    compose.waitForIdle()
    assertEquals("read each time Account is shown", listOf("account"), did)
    for (words in listOf(
      Words.Settings.title, Words.Watch.signedIn, "you@example.com", "Nord Atelier", Words.Settings.plan, "Solo · 260 of 500 credits left",
      Words.Settings.languageTitle, "English", Words.Settings.version, BuildConfig.VERSION_NAME, Words.Watch.openOnPhone, Words.Settings.signOut, Words.Watch.trust,
    )) compose.onNodeWithText(words).assertExists()
    compose.onNodeWithText(Words.Watch.openOnPhone).performClick()
    assertEquals(listOf("account", "open on phone needs-you"), did)
  }

  // Row 44, D (the Apple Watch's tafs#1632, in the same lines): each failure says which, never "not in reach" for all.
  @Test fun eachLinkFailureSaysWhich() {
    show(read(), Page.Account, account = WatchModel.Reading.PhoneUnreachable(LinkFailure.NoApp))
    compose.onNodeWithText(Words.Watch.phoneNoApp).assertExists()
    compose.onAllNodesWithText(Words.Watch.phoneNotReachable).assertCountEquals(0)
    assertEquals(
      listOf(Words.Watch.phoneNotReachable, Words.Watch.phoneNoAnswer, Words.Watch.phoneNoApp, Words.Watch.phoneLinkFailed),
      LinkFailure.entries.map { it.line },
    )
    assertEquals(4, LinkFailure.entries.map { it.line }.toSet().size)
  }

  @Test fun accountOutOfReachStillSignsOutOnTheWatch() {
    show(read(), Page.Account, account = WatchModel.Reading.PhoneUnreachable())
    compose.onNodeWithText(Words.Watch.phoneNotReachable).assertExists()
    compose.onAllNodesWithText(Words.Watch.signedIn).assertCountEquals(0)
    compose.onNodeWithText(Words.Common.tryAgain).performClick()
    assertEquals(listOf("account", "account"), did)
    compose.onNodeWithText(Words.Settings.signOut).assertExists()
    compose.onNodeWithText(Words.Watch.trust).assertExists()
  }

  @Test fun signOutAsksFirstAndCancelSignsNothingOut() {
    show(read(), Page.Account, account = WatchModel.Reading.Asking)
    compose.onNodeWithText(Words.Settings.signOut).performClick()
    compose.onNodeWithText(Words.Watch.signOutQ).assertExists()
    compose.onNodeWithText(Words.Watch.signOutSub).assertExists()
    compose.onNodeWithText(Words.Common.cancel).performClick()
    assertFalse(did.contains("sign out"))
    compose.onNodeWithText(Words.Settings.signOut).performClick()
    compose.onNodeWithText(Words.Settings.signOut).performClick()
    assertEquals(1, did.count { it == "sign out" })
  }

  @Test fun signedOutOnTheWatchShowsOnlyThatAndSignIn() {
    show(read(call), Page.Coach, signedOutHere = true)
    compose.onNodeWithText(Words.Watch.signedOutHere).assertExists()
    compose.onAllNodesWithText(Words.Coach.ask).assertCountEquals(0)
    compose.onAllNodesWithText("Reship order #5098").assertCountEquals(0)
    compose.onNodeWithText(Words.Watch.askPhoneSignIn).performClick()
    assertEquals(listOf("sign in"), did)
  }
}
