package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.model.CoachWords
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the owner wrote and didn't send stays with the app, per chat; and Coach starts on improving one taf. */
class DraftsTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-10-30T15:00:00Z")

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["members.manage","cards.manage"]}""")
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}},
          {"id":"taf_2","name":"Bills","kiff_agent":"bills-1","color":2,"state":"idle","card":{"ids":["c2"],"active":true}}],"others":[],"removing":[]}""",
    )
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":true},"bills-1":{"use":true}}}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
  }

  @Test fun eachChatsUnsentWordsStayWhileTheOwnerIsElsewhere() = runTest {
    reads()
    val m = model()
    m.setDraft(COACH_CHAT, "Set up refunds for")
    m.setDraft("refunds-1", "Refund order 1001")
    // Leaving Coach and the taf's chat changes nothing the app keeps.
    assertEquals("Set up refunds for", m.state.value.drafts[COACH_CHAT])
    assertEquals("Refund order 1001", m.state.value.drafts["refunds-1"])
    // Sent or cleared: gone.
    m.setDraft("refunds-1", "")
    assertNull(m.state.value.drafts["refunds-1"])
  }

  @Test fun signingOutDropsTheDrafts() = runTest {
    reads()
    val m = model()
    m.setDraft(COACH_CHAT, "half a thought")
    m.signOut(tellKiff = false)
    assertTrue(m.state.value.drafts.isEmpty())
  }

  @Test fun coachStartsOnImprovingATafThisPersonManages() = runTest {
    reads()
    val m = model()
    m.refresh()
    // Only Refunds: Bills is used here, not managed.
    assertEquals(listOf("refunds-1"), CoachWords.improvable(m.state.value).map { it.id })
    m.improveTaf("refunds-1")
    assertEquals("refunds-1", m.state.value.coachImproving)
    assertTrue(m.state.value.coachFresh)
  }
}
