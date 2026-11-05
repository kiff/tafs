package bot.tafs.app

import bot.tafs.app.model.Chat
import bot.tafs.app.model.ChatItem
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.LiveAnswer
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** A chat row's key stays the same from its first frame to its last, so the list updates it in place (docs/chat.md). */
class ChatKeysTest {
  private val t0 = Instant.parse("2026-11-05T09:00:00Z")
  private fun owner(at: Long, session: String, id: String?) = ChatItem.Message(ChatMessage(t0.plusSeconds(at), ChatMessage.From.owner, "Refund Ana", session, serverId = id))
  private fun taf(at: Long, id: String?, text: String = "Refunded Ana.") = ChatItem.Message(ChatMessage(t0.plusSeconds(at), ChatMessage.From.taf, text, "s1", serverId = id))

  @Test fun anOwnersMessageKeepsItsKeyFromTheStreamsIdToTheThreads() {
    // On its way, the stream named it `run_1.in`; read again, the thread has it under its session and time.
    val sent = Chat.rowKeys(listOf(owner(0, "pending", "run_1.in"))).single()
    val read = Chat.rowKeys(listOf(owner(1, "s1", "run_1.in"))).single()
    assertEquals(sent, read)
  }

  @Test fun theAnswerLandsUnderTheKeyItsLiveRowHad() {
    val asked = listOf(owner(0, "s1", "run_1.in"))
    val live = LiveAnswer(started = t0, session = "s1", run = "run_1", text = "Refunded Ana.")
    val liveKey = Chat.liveKey(asked, live, "working")
    val answered = asked + taf(5, "run_1.7")
    assertEquals(liveKey, Chat.rowKeys(answered).last())
  }

  @Test fun eachPartOfARunHasItsOwnKeyAndTheNextPartTheNext() {
    val live = LiveAnswer(started = t0, session = "s1", run = "run_1")
    val firstPart = listOf(owner(0, "s1", "run_1.in"), taf(5, "run_1.4", "I'll ask first."))
    // The first part is in the thread; the live row is the run's next message.
    val next = Chat.liveKey(firstPart, live, "working")
    val both = firstPart + taf(9, "run_1.9", "Refunded Ana.")
    val keys = Chat.rowKeys(both)
    assertEquals(next, keys.last())
    assertEquals(3, keys.toSet().size)
  }

  @Test fun beforeTheStreamNamesTheRunTheLiveRowHasItsFallback() {
    assertEquals("working", Chat.liveKey(emptyList(), LiveAnswer(started = t0), "working"))
    assertEquals("working", Chat.liveKey(emptyList(), null, "working"))
  }

  @Test fun keysNeverRepeatEvenForTheSameMessageTwice() {
    // The owner's message still on its way and already in the thread, for a moment: two rows, two keys.
    val keys = Chat.rowKeys(listOf(owner(0, "pending", "run_1.in"), owner(1, "s1", "run_1.in"), taf(5, null), taf(5, null)))
    assertEquals(keys.size, keys.toSet().size)
  }
}
