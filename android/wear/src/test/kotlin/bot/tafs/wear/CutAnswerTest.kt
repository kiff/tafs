package bot.tafs.wear

import androidx.compose.ui.test.onNodeWithTag
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
import bot.tafs.watchlink.WatchReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A cut answer in a taf's chat (rows 10 and 28, the Galaxy Watch 5 re-test: "Versailles" ended in "The rest is on your
 * phone." with nothing to tap). A message sent from the watch keeps its turn, so Read more reads it whole; a cut line with no turn
 * of its own gets Open on phone.
 */
class CutAnswerMergeTest {
  private val sentAt = java.time.Instant.parse("2026-11-25T17:06:00Z")
  private val answered = AskLine("wa_1", "w_1", AskLine.DONE, "Wiki lookup", line = "Searching \"Versailles\" went straight to…", words = "Done", updated = "2026-11-25T17:06:30Z", more = true, kind = AskLine.TAF)
  private val turn = KeptTurn(WatchModel.Said("Versailles", sentAt.toEpochMilli()), answered)
  private val mine = ChatLine("m1", mine = true, at = "2026-11-25T17:06:02Z", text = "Versailles")
  private val answer = ChatLine("m2", mine = false, at = "2026-11-25T17:06:30Z", text = "Searching \"Versailles\" went straight to…", more = true)
  private val older = ChatLine("m0", mine = false, at = "2026-11-25T16:00:00Z", text = "An older answer")

  @Test fun anAnsweredTurnStaysOverThePhonesLinesOfIt() {
    val merged = KeptThreads.withLines(KeptThread(0, turns = listOf(turn)), listOf(older, mine, answer))
    assertEquals(listOf(turn), merged.turns)
    // Its words and answer show once, as the turn, with Read more.
    assertEquals(listOf(older), KeptThreads.shownLines(merged.lines, merged.turns))
    assertEquals(WatchModel.Foot.ReadMore, footOf(merged.turns.single().ask, null))
  }

  @Test fun aTurnStillAnsweringStaysWhileThePhoneHasOnlyThePersonsLine() {
    val asked = turn.copy(ask = answered.copy(state = AskLine.WORKING, line = null, more = false))
    val merged = KeptThreads.withLines(KeptThread(0, turns = listOf(asked)), listOf(mine))
    assertEquals(listOf(asked), merged.turns)
    assertTrue(KeptThreads.shownLines(merged.lines, merged.turns).isEmpty())
  }

  @Test fun anUnansweredTurnGivesWayToThePhonesAnswer() {
    val asked = turn.copy(ask = answered.copy(state = AskLine.WORKING, line = null, more = false))
    val merged = KeptThreads.withLines(KeptThread(0, turns = listOf(asked)), listOf(mine, answer))
    assertTrue(merged.turns.isEmpty())
    assertEquals(listOf(mine, answer), KeptThreads.shownLines(merged.lines, merged.turns))
    assertEquals(listOf("wa_1"), merged.sent)
  }

  @Test fun otherWordsOrAnotherTimeAreAnotherMessage() {
    val later = mine.copy(at = "2026-11-25T18:30:00Z")
    val merged = KeptThreads.withLines(KeptThread(0, turns = listOf(turn)), listOf(later, answer))
    assertEquals(listOf(later, answer), KeptThreads.shownLines(merged.lines, merged.turns))
  }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = WatchApplication::class)
class CutAnswerScreenTest {
  @get:Rule val compose = createComposeRule()

  @Before fun words() {
    RuntimeEnvironment.setQualifiers("en-w192dp-h2400dp-watch-xhdpi")
    Res.install(ApplicationProvider.getApplicationContext())
  }

  private val did = mutableListOf<String>()
  private val actions = Actions(
    openOnPhone = { did += "phone $it" }, tryAgain = {}, refuse = {}, halt = {}, askHaltAll = {}, confirmHaltAll = {}, cancelHaltAll = {},
    listen = {}, closeVoice = {}, retry = {}, open = {}, hear = {}, reply = {}, closeResult = {}, dismiss = {},
    readMore = { did += "more $it" },
    readMoreLine = { did += "line $it" },
  )
  private val page = WatchReply.TafPage("taf_1", "Wiki lookup", "Ready", halted = false, job = "Looks a word up")

  private fun show(chat: WatchModel.TafChat) = compose.setContent {
    MaterialTheme { TafScreen(WatchModel.OpenTaf("taf_1", null, WatchModel.TafRead.Read(page), chat), null, actions, openInbox = {}, chat = true, pager = rememberPagerState { 3 }) }
  }

  private val cutLines = listOf(
    ChatLine("m1", mine = true, at = "2026-11-25T17:06:02Z", text = "Versailles"),
    ChatLine("m2", mine = false, at = "2026-11-25T17:06:30Z", text = "Searching went straight to…", more = true),
  )

  /** Row 10: a taf's cut answer from the phone's thread gets Read more (one tap), with Open on phone still under it. */
  @Test fun aCutLineFromThePhoneOffersReadMoreAndOpenOnPhone() {
    show(WatchModel.TafChat(WatchModel.TafChat.History.Read(cutLines)))
    compose.onNodeWithTag(LINE_READ_MORE + "m2").performScrollTo().performClick()
    compose.onAllNodesWithText(Words.Watch.openOnPhone)[0].performScrollTo().performClick()
    assertEquals(listOf("line m2", "phone taf/taf_1"), did)
  }

  @Test fun aCutLineReadWholeShowsItAllInPlace() {
    show(WatchModel.TafChat(WatchModel.TafChat.History.Read(cutLines), whole = mapOf(lineKey("m2") to WatchModel.Whole.Read("Searching went straight to the Palace of Versailles."))))
    compose.onNodeWithText("Searching went straight to the Palace of Versailles.").assertExists()
    compose.onAllNodesWithText(Words.Watch.readMore).assertCountEquals(0)
  }

  @Test fun aCutLineThatCouldntBeReadSaysTheRestIsOnThePhone() {
    show(WatchModel.TafChat(WatchModel.TafChat.History.Read(cutLines), whole = mapOf(lineKey("m2") to WatchModel.Whole.OnPhone)))
    compose.onNodeWithText(Words.Watch.moreOnPhone).assertExists()
    compose.onAllNodesWithText(Words.Watch.readMore).assertCountEquals(0)
    compose.onAllNodesWithText(Words.Watch.openOnPhone)[0].performScrollTo().performClick()
    assertEquals(listOf("phone taf/taf_1"), did)
  }

  @Test fun aCutAnswerThatCouldntBeReadWholeOffersOpenOnPhone() {
    val ask = AskLine("wa_1", "w_1", AskLine.DONE, "Wiki lookup", line = "Searching went straight to…", words = "Done", updated = "u", more = true, kind = AskLine.TAF)
    show(WatchModel.TafChat(WatchModel.TafChat.History.Read(emptyList()), listOf(WatchModel.TafTurn("Versailles", 0L, ask)), whole = mapOf("wa_1" to WatchModel.Whole.OnPhone)))
    compose.onAllNodesWithText(Words.Watch.openOnPhone)[0].performScrollTo().performClick()
    assertEquals(listOf("phone taf/taf_1"), did)
  }

  @Test fun anAnswerFollowedTwoMinutesOffersOpenOnPhoneBesideAnswering() {
    val ask = AskLine("wa_1", "w_1", AskLine.WORKING, "Wiki lookup", words = "Working", updated = "u", kind = AskLine.TAF)
    show(WatchModel.TafChat(WatchModel.TafChat.History.Read(emptyList()), listOf(WatchModel.TafTurn("Versailles", 0L, ask)), waitedOut = true))
    compose.onNodeWithText(Words.Chat.tafIsAnswering("Wiki lookup")).assertExists()
    compose.onAllNodesWithText(Words.Watch.openOnPhone)[0].performScrollTo().performClick()
    assertEquals(listOf("phone taf/taf_1"), did)
  }
}

/** The sent message shows whole after Send (the Galaxy Watch 5 re-test: it sat a millimetre above the top edge). */
class SentFocusTest {
  private val page = WatchReply.TafPage("taf_1", "Wiki lookup", "Ready", halted = false, job = "Looks a word up")
  private val sent = WatchModel.TafTurn("Versailles", 0L, AskLine("wa_1", "w_1", AskLine.WORKING, "Wiki lookup", words = "Working", updated = "u", kind = AskLine.TAF))

  @Test fun theChatCentresOnTheLineUnderTheNewestSentMessage() {
    val open = WatchModel.OpenTaf("taf_1", null, WatchModel.TafRead.Read(page), WatchModel.TafChat(WatchModel.TafChat.History.Read(emptyList()), listOf(sent)))
    // The name, its state, the day's header, the sent words, then "{taf} is answering": that last is centred, the words just above.
    assertEquals(4, tafChatFocus(open))
    assertEquals(tafChatLatest(open) - 1, tafChatFocus(open))
  }

  @Test fun withNothingSentHereItOpensOnTheNewestMessageAsBefore() {
    val open = WatchModel.OpenTaf("taf_1", null, WatchModel.TafRead.Read(page),
      WatchModel.TafChat(WatchModel.TafChat.History.Read(listOf(ChatLine("m1", mine = false, at = "x", text = "Hi")))))
    assertEquals(tafChatLatest(open), tafChatFocus(open))
  }
}
