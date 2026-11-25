package bot.tafs.wear

import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.ChatLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * While a taf's chat shows "{taf} is answering", the watch follows the answer through the phone, bounded (row 13, the owner's
 * Galaxy Watch 5 re-test: with the phone's push missing, the answer never came until the app was reopened).
 */
class FollowAnswerTest {
  private val t = 1_763_244_000_000L
  private val tafs = WatchReply.Tafs(listOf(TafLine("taf_1", "Wiki lookup", "Ready", halted = false)), allHalted = false)
  private val taken = AskLine("wa_1", "w_00000001", AskLine.ACCEPTED, "Wiki lookup", words = "Sent", updated = "2026-11-25T17:03:42Z", kind = AskLine.TAF)
  private val working = taken.copy(state = AskLine.WORKING, words = "Wiki lookup is answering", updated = "2026-11-25T17:03:43Z")
  private val answered = taken.copy(state = AskLine.DONE, line = "Rambouillet is a French commune in the Yvelines.", words = "Done", updated = "2026-11-25T17:04:06Z")

  private class Phone(var asks: () -> List<AskLine>) : PhoneLink {
    var asksRead = 0
    override suspend fun ask(request: WatchRequest): Asked = when (request) {
      is WatchRequest.NeedsYou -> Asked.Answered(WatchReply.NeedsYou(0, "Nothing needs you", emptyList()))
      is WatchRequest.Tafs -> Asked.Answered(WatchReply.Tafs(listOf(TafLine("taf_1", "Wiki lookup", "Ready", halted = false)), allHalted = false))
      is WatchRequest.Taf -> Asked.Answered(WatchReply.TafPage("taf_1", "Wiki lookup", "Ready", halted = false, job = "Looks a word up"))
      is WatchRequest.TafChat -> Asked.Answered(WatchReply.TafChat(listOf<ChatLine>()))
      is WatchRequest.Ask -> Asked.Answered(WatchReply.AskTaken(AskLine("wa_1", "w_00000001", AskLine.ACCEPTED, "Wiki lookup", words = "Sent", updated = "2026-11-25T17:03:42Z", kind = AskLine.TAF)))
      is WatchRequest.Asks -> { asksRead++; Asked.Answered(WatchReply.AskList(asks())) }
      else -> Asked.Unreachable()
    }
  }

  private suspend fun sent(phone: Phone): WatchModel =
    WatchModel(phone, now = { t }, unlocked = { true }, newClientId = { "w_00000001" }, reviewFirst = true).also {
      it.tafChatOn = true
      it.refresh()
      it.openTaf("taf_1")
      it.review("Rambouillet", tafId = "taf_1")
      it.sendReviewed()
    }

  @Test fun theAnswerIsFollowedUntilItLandsThenTheReadsStop() = runTest {
    var reads = 0
    val phone = Phone { if (++reads < 3) listOf(working) else listOf(answered) }
    val m = sent(phone)
    m.took(working)
    assertTrue(m.taf.value!!.chat.answering)
    val waits = mutableListOf<Long>()
    m.followAnswer("taf_1", wait = { waits += it })
    assertEquals(listOf(ANSWER_READ_EVERY_MS, ANSWER_READ_EVERY_MS, ANSWER_READ_EVERY_MS), waits)
    assertEquals(answered, m.taf.value!!.chat.turns.single().ask)
    assertFalse(m.taf.value!!.chat.answering)
    assertFalse(m.taf.value!!.chat.waitedOut)
    // Never in the Inbox: a taf's answer is its chat's.
    assertTrue(m.inbox.value.isEmpty())
  }

  @Test fun afterTwoMinutesItStopsAndSaysSo() = runTest {
    val phone = Phone { listOf(working) }
    val m = sent(phone)
    m.took(working)
    var waited = 0L
    m.followAnswer("taf_1", wait = { waited += it })
    assertEquals(ANSWER_FOLLOW_MS, waited)
    assertEquals((ANSWER_FOLLOW_MS / ANSWER_READ_EVERY_MS).toInt(), phone.asksRead)
    assertTrue(m.taf.value!!.chat.waitedOut)
    // The answer still lands when it's pushed, and the next message follows afresh.
    m.took(answered)
    assertFalse(m.taf.value!!.chat.answering)
  }

  @Test fun aClosedChatStopsTheReads() = runTest {
    val phone = Phone { listOf(working) }
    val m = sent(phone)
    m.took(working)
    m.followAnswer("taf_1", wait = { m.closeTaf() })
    assertEquals(0, phone.asksRead)
  }

  @Test fun nothingAnsweringReadsNothing() = runTest {
    val phone = Phone { listOf(answered) }
    val m = sent(phone)
    m.took(answered)
    m.followAnswer("taf_1", wait = { error("no wait") })
    assertEquals(0, phone.asksRead)
  }
}
