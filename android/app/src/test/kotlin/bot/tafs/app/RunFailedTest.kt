package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.model.ChatItem
import bot.tafs.app.model.ChatMessage
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A run that ended without an answer (tafs cloud's `failed` on the thread): its words are the chat's last line, so the owner reads
 * that the turn didn't happen. On 18 Nov a Coach turn after an approval was refused for the provider's balance, and the chat read
 * as "Waiting for you", then as nothing at all.
 */
class RunFailedTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-11-18T10:11:00Z")
  private val conversation = "GET /v1/tafs/setup_1/conversation"

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay","capabilities":["members.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach"}}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
  }

  private val said = """{"at":"2026-11-18T10:09:00Z","from":"taf","text":"Next you'll approve Day summary's two actions.","session":"s1"}"""
  private val words = "Coach couldn't answer just now: the AI provider refused the turn. Nothing was done after your approval. Try again later."

  private suspend fun read(thread: String): AppModel {
    transport.answer(conversation, 200, thread)
    val m = model()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    return m
  }

  private fun last(m: AppModel) = m.state.value.chatItems(COACH_CHAT).last()

  @Test fun aRunThatEndedWithoutAnAnswerIsTheChatsLastLineInTafsCloudsWords() = runTest {
    reads()
    val m = read("""{"messages":[$said],"working":null,"failed":{"run":"r9","code":"ai_unavailable","words":"$words"}}""")
    val line = last(m) as ChatItem.Message
    assertEquals(words, line.message.text)
    assertEquals(ChatMessage.From.taf, line.message.from)
    // Nothing reads as at work: no run, so the composer is the plain one.
    assertNull(m.state.value.chatWorking[COACH_CHAT])
  }

  @Test fun aLaterReadWithNoFailureClearsIt() = runTest {
    reads()
    val m = read("""{"messages":[$said],"failed":{"run":"r9","code":"ai_unavailable","words":"$words"}}""")
    transport.answer(conversation, 200, """{"messages":[$said]}""")
    m.loadChat(COACH_CHAT)
    assertTrue(m.state.value.chatItems(COACH_CHAT).none { it is ChatItem.Message && it.message.text == words })
  }

  @Test fun aFailureWithNoWordsOrOfAnotherShapeIsNoLine() = runTest {
    reads()
    val m = read("""{"messages":[$said],"failed":{"run":"r9","words":"  "}}""")
    assertEquals("Next you'll approve Day summary's two actions.", (last(m) as ChatItem.Message).message.text)
    transport.answer(conversation, 200, """{"messages":[$said],"failed":"refused"}""")
    m.loadChat(COACH_CHAT)
    assertEquals("Next you'll approve Day summary's two actions.", (last(m) as ChatItem.Message).message.text)
  }
}
