package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coach waiting (docs/api.md › Coach waiting): Home lists the Coach chats paused on a card this person answers, in tafs cloud's
 * words, and Needs you draws each with a way into that chat. The phone decides nothing about it: tafs cloud says it waits.
 */
class CoachWaitingTest {
  private val t0 = Instant.parse("2026-11-14T00:30:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()

  private fun reads(waiting: String) {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["holds.answer","members.manage"]}""")
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[],"others":[],"removing":[],"needs_you":{"count":1,"words":"1 needs you."},"coach_waiting":[$waiting]}""",
    )
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore() }
  }

  private val approval =
    """{"taf":"coach-1","session":"sess_1","run":"run_1","since":"2026-11-14T00:31:28Z","words":{"line":"Coach is waiting for your approval.","open":"Open Coach"}}"""

  @Test fun homeListsWhatCoachWaitsForInTafsCloudsWordsAndNeedsYouCountsIt() = runTest {
    reads(approval)
    val m = model()
    m.refresh()
    val w = m.state.value.coachWaiting.single()
    assertEquals("sess_1", w.session)
    assertEquals("Coach is waiting for your approval.", w.words.line)
    assertEquals("Open Coach", w.words.open)
    assertEquals(1, m.state.value.needsYouCount)
    assertEquals("1 needs you.", m.state.value.needsYouWords)
  }

  @Test fun aRowWithNoChatOrNoWordsIsNeverDrawn() = runTest {
    reads("""{"taf":"coach-1","session":"","words":{"line":"Coach is waiting for your approval.","open":"Open Coach"}},{"taf":"coach-1","session":"sess_2","words":{"line":"","open":""}}""")
    val m = model()
    m.refresh()
    assertTrue(m.state.value.coachWaiting.isEmpty())
  }

  @Test fun openCoachOpensThatChat() = runTest {
    reads(approval)
    val m = model()
    m.refresh()
    m.openWaitingCoach(m.state.value.coachWaiting.single())
    assertEquals("sess_1", m.state.value.coachChat)
    assertTrue(m.state.value.coachRequested)
  }
}
