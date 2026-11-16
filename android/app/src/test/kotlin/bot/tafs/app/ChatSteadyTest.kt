package bot.tafs.app

import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.model.Chat
import bot.tafs.app.model.ChatItem
import bot.tafs.app.model.ChatMessage
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nothing on screen moves while a turn streams or the thread is read again (the owner's Coach run on Android 110, 16 Nov). */
class ChatSteadyTest {
  private val t0 = Instant.parse("2026-11-16T12:40:00Z")

  private fun owner(text: String, at: Instant, session: String = "s1", serverId: String? = null) =
    ChatItem.Message(ChatMessage(at, ChatMessage.From.owner, text, session, serverId = serverId))

  private fun coach(text: String, at: Instant) = ChatItem.Message(ChatMessage(at, ChatMessage.From.taf, text, "s1"))

  private fun card(id: String, at: Instant) = ChatItem.Card(ShownComponent(at, "s1", "r2", SetupComponent(type = "confirm", id = id)), at)

  private fun ids(items: List<ChatItem>) = items.map { it.id }

  @Test fun aCardThatComesWhileCoachAnswersStaysAfterTheMessageItAnswers() {
    val earlier = coach("The approval was refused: the rules aren't agreed yet.", t0)
    // What the owner just sent, on its way: the phone's own time and a local id.
    val sent = owner("Show me the rules to agree, then the Card again.", t0.plusSeconds(62), session = "pending")
    val shown = Chat.Shown()
    shown.order(listOf(earlier, sent))

    // The rules card comes while Coach answers. tafs cloud dates it a moment before the phone dated the message, so the thread
    // as read puts it above the message the owner just sent. On screen, it goes after.
    val rules = card("rules", t0.plusSeconds(61))
    val asRead = listOf(earlier, rules, sent)
    assertEquals(ids(listOf(earlier, sent, rules)), ids(shown.order(asRead)))

    // The turn ends and the thread is read again: the owner's message is tafs cloud's copy now, under its own id, and Coach's
    // words are in. The card is placed after Coach's words as read; on screen nothing that was shown moves.
    val copy = owner("Show me the rules to agree, then the Card again.", t0.plusSeconds(60), serverId = "m_9")
    val words = coach("Here are the rules to agree first.", t0.plusSeconds(70))
    val final = shown.order(listOf(earlier, copy, words, rules))
    assertEquals(ids(listOf(earlier, copy, rules, words)), ids(final))
  }

  @Test fun somethingOlderThanEverythingShownGoesBeforeIt() {
    val a = coach("a", t0.plusSeconds(10))
    val b = owner("b", t0.plusSeconds(20), serverId = "m_b")
    val shown = Chat.Shown()
    shown.order(listOf(a, b))
    val older = coach("older", t0)
    assertEquals(ids(listOf(older, a, b)), ids(shown.order(listOf(older, a, b))))
  }

  @Test fun aThreadNothingShownIsInIsTheThreadAsRead() {
    val shown = Chat.Shown()
    shown.order(listOf(coach("a", t0), owner("b", t0.plusSeconds(1), serverId = "m_b")))
    // Another conversation: nothing of the last one is in it, so it's as read.
    val other = listOf(owner("x", t0.plusSeconds(5), serverId = "m_x"), coach("y", t0.plusSeconds(3)))
    assertEquals(ids(other), ids(shown.order(other)))
    // And the first read of a screen is as read too.
    assertEquals(ids(other), ids(Chat.steady(emptyList(), other)))
  }

  @Test fun somethingGoneFromTheThreadGoesFromTheScreen() {
    val a = coach("a", t0)
    val b = owner("b", t0.plusSeconds(1), serverId = "m_b")
    val c = coach("c", t0.plusSeconds(2))
    val shown = Chat.Shown()
    shown.order(listOf(a, b, c))
    assertEquals(ids(listOf(a, c)), ids(shown.order(listOf(a, c))))
  }

  @Test fun twoOfTheOwnersMessagesWithTheSameWordsEachKeepTheirPlace() {
    val first = owner("ok", t0, session = "pending")
    val between = coach("Done.", t0.plusSeconds(5))
    val second = owner("ok", t0.plusSeconds(10), session = "pending")
    val shown = Chat.Shown()
    shown.order(listOf(first, between, second))
    val firstCopy = owner("ok", t0, serverId = "m_1")
    val secondCopy = owner("ok", t0.plusSeconds(10), serverId = "m_2")
    assertEquals(ids(listOf(firstCopy, between, secondCopy)), ids(shown.order(listOf(firstCopy, between, secondCopy))))
  }
}
