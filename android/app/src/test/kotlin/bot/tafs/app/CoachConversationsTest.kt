package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coach's conversations (docs/coach.md): a new one, reopening and archiving earlier ones, improving a taf, and the news of
 * what Coach handed tafs. A fake KIFF and tafs cloud stand in: no real Coach conversation is touched.
 */
class CoachConversationsTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val send = "POST /v1/tafs/setup_1/messages"

  private fun reads(conversation: String = """{"messages":[]}""") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["members.manage","cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach","kiff_agent":"setup-acme"}}""")
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, conversation)
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also {
      it.restore()
      it.loadCoach()
    }
  }

  private fun body(key: String, i: Int = 0) = KiffClient.json.parseToJsonElement(String(transport.requestsTo(key)[i].body!!)).jsonObject

  @Test fun aNewConversationStartsWithItsFirstMessageAndIsTheOneShownAfter() = runTest {
    reads()
    transport.answer(send, 200, """{"session":"s9","new_session":true}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s9", 200, """{"messages":[]}""")
    val m = model()
    m.newCoachChat()
    assertTrue(m.state.value.coachFresh)
    assertTrue(m.deliverChat("Make sure customers can get their refunds", COACH_CHAT))
    assertEquals("true", body(send).getValue("new_chat").jsonPrimitive.content)
    assertFalse(m.state.value.coachFresh)
    assertEquals("s9", m.state.value.coachChat)
    // From then on, that conversation is read alone.
    assertTrue(transport.requestsTo("GET /v1/tafs/setup_1/conversation/s9").isNotEmpty())
  }

  @Test fun aReopenedConversationIsReadAloneAndMessagesGoOnInIt() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/conversation/s4", 200, """{"messages":[{"at":"2026-10-27T09:00:00Z","from":"owner","text":"Hi","session":"s4"}]}""")
    transport.answer(send, 200, """{"session":"s4","new_session":false}""")
    val m = model()
    m.openCoachChat("s4")
    assertEquals(listOf("Hi"), m.state.value.chats[COACH_CHAT].orEmpty().map { it.text })
    m.deliverChat("And now?", COACH_CHAT)
    assertEquals("s4", body(send).getValue("session").jsonPrimitive.content)
    assertFalse(body(send).containsKey("new_chat"))
  }

  @Test fun improvingATafStartsWithTheImproveRouteAndTakesNoFiles() = runTest {
    reads()
    transport.answer("POST /v1/tafs/taf_1/improve", 200, """{"session":"s7","new_session":true}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s7", 200, """{"messages":[]}""")
    val m = model()
    m.improveTaf("refunds-1")
    assertEquals("refunds-1", m.state.value.coachImproving)
    assertFalse("the improve route takes words only", m.state.value.canAttach(COACH_CHAT))
    assertTrue(m.deliverChat("How is it going?", COACH_CHAT))
    assertEquals("How is it going?", body("POST /v1/tafs/taf_1/improve").getValue("text").jsonPrimitive.content)
    assertTrue(transport.requestsTo(send).isEmpty())
    assertEquals("s7", m.state.value.coachChat)
    assertNull(m.state.value.coachImproving)
  }

  @Test fun changingTheLimitInCoachModeOpensCoachOnTheTaf() = runTest {
    reads()
    val m = model()
    m.improveInCoach("refunds-1")
    assertTrue(m.state.value.coachRequested)
    assertEquals("refunds-1", m.state.value.coachImproving)
    m.coachOpened()
    assertFalse(m.state.value.coachRequested)
  }

  @Test fun earlierConversationsAreListedAndOneArchivedLeavesTheListAndTheScreen() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats", 200, """{"chats":[{"session":"s4","started":"2026-10-27T09:00:00Z","updated":"2026-10-27T10:00:00Z","title":"Refunds for Acme"},{"session":"s3","started":"2026-10-26T09:00:00Z","updated":"2026-10-26T10:00:00Z","title":"Connect Stripe"}]}""")
    transport.answer("GET /v1/tafs/setup_1/conversation/s4", 200, """{"messages":[]}""")
    transport.answer("POST /v1/tafs/setup_1/chats/s4/archive", 200, """{"archived":true}""")
    val m = model()
    m.loadCoachChats()
    assertEquals(listOf("Refunds for Acme", "Connect Stripe"), m.state.value.coachChats.map { it.title })
    m.openCoachChat("s4")
    assertTrue(m.archiveCoachChat("s4"))
    assertEquals(listOf("s3"), m.state.value.coachChats.map { it.session })
    // The one shown was archived: a new conversation takes its place.
    assertTrue(m.state.value.coachFresh)
  }

  @Test fun anArchiveTafsCloudRefusedSaysSo() = runTest {
    reads()
    transport.answer("POST /v1/tafs/setup_1/chats/s4/archive", 503, """{"error":"unavailable"}""")
    val m = model()
    assertFalse(m.archiveCoachChat("s4"))
    assertEquals("Not archived. It isn't working on our side right now. Nothing to do on your side; try again later.", m.state.value.lastError)
  }

  @Test fun anArchivedConversationIsPutBack() = runTest {
    reads()
    transport.answer("GET /v1/tafs/setup_1/chats/archived", 200, """{"chats":[{"session":"s2","started":"2026-10-25T09:00:00Z","updated":"2026-10-25T10:00:00Z","title":"Old"}]}""")
    transport.answer("POST /v1/tafs/setup_1/chats/s2/unarchive", 200, """{"archived":false}""")
    transport.answer("GET /v1/tafs/setup_1/chats", 200, """{"chats":[{"session":"s2","started":"2026-10-25T09:00:00Z","updated":"2026-10-25T10:00:00Z","title":"Old"}]}""")
    val m = model()
    m.loadArchivedCoachChats()
    assertTrue(m.unarchiveCoachChat("s2"))
    assertTrue(m.state.value.coachArchived.isEmpty())
    assertEquals(listOf("s2"), m.state.value.coachChats.map { it.session })
  }

  @Test fun theNewsOfWhatCoachHandedTafsIsReadAndOneTheAppCantReadIsLeftOut() = runTest {
    reads(
      conversation = """{"messages":[],"coach_lines":[{"at":"2026-10-28T08:00:00Z","taf":"Refunds","said":"Refunded 2 payments","away":true},{"at":"2026-10-28T08:30:00Z","taf":"Chargebacks","said":"Saved a draft"},{"taf":7}]}""",
    )
    val m = model()
    m.loadChat(COACH_CHAT)
    val lines = m.state.value.coachLines
    assertEquals(listOf("Refunds", "Chargebacks"), lines.map { it.taf })
    assertEquals(listOf(true, null), lines.map { it.away })
  }
}
