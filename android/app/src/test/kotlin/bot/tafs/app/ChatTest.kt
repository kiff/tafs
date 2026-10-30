package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.model.Answer
import bot.tafs.app.model.Chat
import bot.tafs.app.model.ChatItem
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.TafState
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTest {
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private fun model() = testModel(clock = { t0 })

  private fun AppModel.messages(tafId: String) = state.value.chats[tafId].orEmpty()

  // MARK: Stamps and words

  @Test fun aSentMessageIsNeverStampedBeforeTheThreadsLatest() {
    assertEquals(t0, Chat.stamp(null, t0))
    assertEquals(t0, Chat.stamp(t0.minusSeconds(5), t0))
    assertEquals(t0.plusMillis(1), Chat.stamp(t0, t0))
    assertEquals(t0.plusSeconds(3).plusMillis(1), Chat.stamp(t0.plusSeconds(3), t0))
  }

  @Test fun aTafsMessageKeepsItsIdWhileItsTextGrowsAndAnOwnersDoesNot() {
    val a = ChatMessage(t0, ChatMessage.From.taf, "Hello", "s")
    assertEquals(a.id, a.copy(text = "Hello there").id)
    val b = ChatMessage(t0, ChatMessage.From.owner, "one", "s")
    assertTrue(b.id != b.copy(text = "two").id)
  }

  // MARK: The sample's threads

  @Test fun theSampleStartsRefundsWithAMorningsConversation() {
    val m = model()
    val refunds = m.state.value.tafs.first { it.name == "Refunds" }
    val thread = m.messages(refunds.id)
    assertEquals(listOf(ChatMessage.From.taf, ChatMessage.From.owner, ChatMessage.From.taf), thread.map { it.from })
    assertEquals("How did this morning go?", thread[1].text)
    assertTrue(thread[2].text.contains("Sample reply"))
  }

  @Test fun everyMessageInAThreadHasItsOwnId() {
    val m = model()
    val ids = m.messages(m.state.value.tafs.first { it.name == "Refunds" }.id).map { it.id }
    assertEquals(ids.size, ids.toSet().size)
  }

  @Test fun otherTafsStartTheirThreadWithAHelloOnce() = runTest {
    val m = model()
    val deploys = m.state.value.tafs.first { it.name == "Deploys" }
    assertTrue(m.messages(deploys.id).isEmpty())
    m.loadChat(deploys.id)
    m.loadChat(deploys.id)
    val thread = m.messages(deploys.id)
    assertEquals(1, thread.size)
    assertTrue(thread[0].text.startsWith("Hi, I'm Deploys."))
  }

  // MARK: Sending

  @Test fun aSentMessageIsFollowedByTheTafsAnswer() = runTest {
    val m = model()
    val id = m.state.value.tafs.first { it.name == "Deploys" }.id
    m.loadChat(id)
    assertTrue(m.deliverChat("  what did you do?  ", id))
    val thread = m.messages(id)
    assertEquals(3, thread.size)
    assertEquals("what did you do?", thread[1].text)
    assertEquals(ChatMessage.From.owner, thread[1].from)
    assertEquals(ChatMessage.From.taf, thread[2].from)
    assertTrue(thread.none { it.session == "pending" })
    assertFalse(m.state.value.thinking(id))
  }

  @Test fun theAnswerNamesWhatItDidAndWhatWaits() = runTest {
    val m = model()
    val waiting = m.state.value.held.first()
    assertTrue(m.deliverChat("how did it go", waiting.tafId))
    val reply = m.messages(waiting.tafId).last().text
    assertTrue(reply.startsWith("Lately:"))
    assertTrue(reply.contains("waits on you in Needs you") || reply.contains("wait on you in Needs you"))
  }

  @Test fun anEmptyMessageIsNotSent() = runTest {
    val m = model()
    val id = m.state.value.tafs.first().id
    m.loadChat(id)
    val before = m.messages(id).size
    assertFalse(m.deliverChat("   ", id))
    assertEquals(before, m.messages(id).size)
  }

  @Test fun aTooLongMessageIsNotSentAndSaysWhy() = runTest {
    val m = model()
    val id = m.state.value.tafs.first().id
    m.loadChat(id)
    val before = m.messages(id).size
    assertFalse(m.deliverChat("x".repeat(Chat.LIMIT + 1), id))
    assertEquals("That's longer than 2000 characters. Say it in fewer words.", m.state.value.chatErrors[id])
    assertEquals(before, m.messages(id).size)
    // The next one that goes clears it.
    assertTrue(m.deliverChat("hi", id))
    assertNull(m.state.value.chatErrors[id])
  }

  @Test fun tellingATafToPauseStopsItAndTellingItToGoOnResumesIt() = runTest {
    val m = model()
    val id = m.state.value.tafs.first { it.state == TafState.working }.id
    m.loadChat(id)
    assertTrue(m.deliverChat("please pause for now", id))
    assertEquals(TafState.paused, m.state.value.taf(id)!!.state)
    assertTrue(m.messages(id).last().text.startsWith("Paused."))
    assertTrue(m.deliverChat("carry on", id))
    assertEquals(TafState.working, m.state.value.taf(id)!!.state)
    assertEquals("Back to work.", m.messages(id).last().text)
  }

  @Test fun aTafWontGiveItselfMoreRoom() = runTest {
    val m = model()
    val id = m.state.value.tafs.first().id
    m.loadChat(id)
    val cardBefore = m.state.value.taf(id)!!.card
    assertTrue(m.deliverChat("raise your limit please", id))
    assertTrue(m.messages(id).last().text.startsWith("I can't give myself more room"))
    // The Card is as it was: a message can't widen it.
    assertEquals(cardBefore, m.state.value.taf(id)!!.card)
  }

  // MARK: The timeline

  @Test fun theTimelineHoldsMessagesAndTheCallsWaitingForTheOwner() = runTest {
    val m = model()
    val call = m.state.value.held.first()
    m.loadChat(call.tafId)
    val items = m.state.value.chatItems(call.tafId)
    assertTrue(items.any { it is ChatItem.Message })
    assertTrue(items.any { it is ChatItem.Held && it.call.id == call.id })
  }

  @Test fun anAnsweredCallBecomesAnOutcomeLineInPlace() = runTest {
    val m = model()
    val call = m.state.value.held.first()
    m.loadChat(call.tafId)
    m.answer(call, Answer.Refuse)
    val items = m.state.value.chatItems(call.tafId)
    assertTrue(items.none { it is ChatItem.Held && it.call.id == call.id })
    val outcome = items.filterIsInstance<ChatItem.Outcome>().first { it.entry.title.startsWith("Refused by you") }
    // "Refused by you · 09:00 · Refund order #4502": who, when, and what.
    val said = Chat.outcomeText(outcome.entry)
    assertTrue(said, said.startsWith("Refused by you · "))
    assertTrue(said, said.endsWith(" · ${call.proposed.summary}"))
  }

  @Test fun whatHappenedBeforeTheThreadsFirstMessageIsNotInIt() {
    val m = model()
    val id = m.state.value.tafs.first { it.name == "Deploys" }.id
    // Its record has lines from before it was ever opened; nothing of them shows until it has a thread.
    assertTrue(m.state.value.chatItems(id).none { it is ChatItem.Outcome })
  }

  @Test fun theTimelineIsInTheOrderThingsHappened() = runTest {
    val m = model()
    val id = m.state.value.tafs.first { it.name == "Deploys" }.id
    m.loadChat(id)
    m.deliverChat("a", id)
    m.deliverChat("b", id)
    val texts = m.state.value.chatItems(id).filterIsInstance<ChatItem.Message>().map { it.message.from to it.message.text }
    assertEquals(ChatMessage.From.owner to "a", texts[1])
    assertEquals(ChatMessage.From.owner to "b", texts[3])
  }

  // MARK: What an outcome says

  @Test fun anOutcomeSaysWhatBecameOfTheCall() {
    fun entry(kind: RecordEntry.Kind, title: String, detail: String = "") = RecordEntry(tafId = "t", kind = kind, title = title, detail = detail, at = t0)
    assertEquals("Approved once by you · Refund", Chat.outcomeText(entry(RecordEntry.Kind.approved, "Approved once by you · Refund")))
    assertEquals("Refund: approved by you, done", Chat.outcomeText(entry(RecordEntry.Kind.approved, "Refund")))
    assertEquals("Refund: refused, not sent", Chat.outcomeText(entry(RecordEntry.Kind.refused, "Refund")))
    assertEquals("Refund: the tool refused", Chat.outcomeText(entry(RecordEntry.Kind.notDone, "Refund", "the tool refused")))
    assertEquals("Refund: sent, waiting for the tool", Chat.outcomeText(entry(RecordEntry.Kind.sending, "Refund")))
    assertEquals("Refund: outcome unknown", Chat.outcomeText(entry(RecordEntry.Kind.unknown, "Refund")))
  }
}
