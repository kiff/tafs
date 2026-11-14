package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

  private fun reads(waiting: String, count: Int = 1, extra: String = "") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["holds.answer","members.manage"]}""")
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[],"others":[],"removing":[],"needs_you":{"count":$count,"words":"$count needs you."},"coach_waiting":[$waiting]$extra}""",
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

  @Test fun settingAsideAsksTafsCloudForThatChatThenTheRowGoes() = runTest {
    reads(approval.replace("\"open\":\"Open Coach\"", "\"open\":\"Open Coach\",\"set_aside\":\"Set aside\""))
    val m = model()
    m.refresh()
    assertEquals("Set aside", m.state.value.coachWaiting.single().words.setAside)
    transport.answer("POST /v1/home/coach-waiting/sess_1/set-aside", 200, """{"set_aside":["sess_1"],"withdrawn":0}""")
    // tafs cloud no longer lists it once set aside.
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[],"needs_you":{"count":0,"words":"Nothing needs you."}}""")
    assertTrue(m.setAsideCoach("sess_1"))
    assertEquals(1, transport.requestsTo("POST /v1/home/coach-waiting/sess_1/set-aside").size)
    assertTrue(m.state.value.coachWaiting.isEmpty())
    assertEquals(0, m.state.value.needsYouCount)
  }

  // Android 86: one swipe sent 11 set-asides in 3 s. A chat already being set aside isn't asked again.
  @Test fun aChatBeingSetAsideIsntAskedAgainUntilTheFirstAnswers() = runTest {
    reads(approval.replace("\"open\":\"Open Coach\"", "\"open\":\"Open Coach\",\"set_aside\":\"Set aside\""))
    val m = model()
    m.refresh()
    val route = "POST /v1/home/coach-waiting/sess_1/set-aside"
    transport.answer(route, 200, """{"set_aside":["sess_1"],"withdrawn":0}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[],"needs_you":{"count":0,"words":"Nothing needs you."}}""")
    transport.hold(route)
    val first = async { m.setAsideCoach("sess_1") }
    testScheduler.runCurrent()
    assertTrue(m.state.value.settingAside.contains("sess_1"))
    assertFalse("asked again while the first is in flight: nothing sent", m.setAsideCoach("sess_1"))
    transport.release(route)
    assertTrue(first.await())
    assertEquals(1, transport.requestsTo(route).size)
  }

  // The owner, 14 Nov: "on the main actions it should have reactions". Set aside's outcome is felt: success, or a warning.
  @Test fun settingAsideIsFeltAsItsOutcome() = runTest {
    val felt = mutableListOf<bot.tafs.app.design.Haptics.Kind>()
    bot.tafs.app.design.Haptics.performer = { felt += it }
    try {
      reads(approval.replace("\"open\":\"Open Coach\"", "\"open\":\"Open Coach\",\"set_aside\":\"Set aside\""))
      val m = model()
      m.refresh()
      transport.answer("POST /v1/home/coach-waiting/sess_1/set-aside", 503, """{"error":"down"}""")
      assertFalse(m.setAsideCoach("sess_1"))
      transport.answer("POST /v1/home/coach-waiting/sess_1/set-aside", 200, """{"set_aside":["sess_1"],"withdrawn":0}""")
      assertTrue(m.setAsideCoach("sess_1"))
      assertEquals(listOf(bot.tafs.app.design.Haptics.Kind.Warning, bot.tafs.app.design.Haptics.Kind.Success), felt)
    } finally {
      bot.tafs.app.design.Haptics.performer = null
    }
  }

  @Test fun setAllAsideIsOneCallAndARefusalKeepsTheRows() = runTest {
    reads("$approval,${approval.replace("sess_1", "sess_2")}", count = 2,
      extra = ""","coach_waiting_clear":{"words":{"button":"Set all aside","title":"End 2 waiting Coach chats?","text":"Nothing is approved or sent.","yes":"Set all aside","no":"Keep them"}}""")
    val m = model()
    m.refresh()
    assertEquals("End 2 waiting Coach chats?", m.state.value.coachWaitingClear?.words?.title)
    transport.answer("POST /v1/home/coach-waiting/set-aside", 503, """{"error":"down"}""")
    assertFalse(m.setAsideCoach(null))
    assertEquals(2, m.state.value.coachWaiting.size)
    assertTrue(m.state.value.settingAside.isEmpty())
  }

  // An audit, 14 Nov: after Set aside, a new job written in Coach went on in the ended session.
  @Test fun aChatSetAsideIsNoLongerTheOneCoachShows() = runTest {
    reads(approval.replace("\"open\":\"Open Coach\"", "\"open\":\"Open Coach\",\"set_aside\":\"Set aside\""))
    val m = model()
    m.refresh()
    m.openWaitingCoach(m.state.value.coachWaiting.single())
    assertEquals("sess_1", m.state.value.coachChat)
    transport.answer("POST /v1/home/coach-waiting/sess_1/set-aside", 200, """{"set_aside":["sess_1"],"withdrawn":0}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[],"needs_you":{"count":0,"words":"Nothing needs you."}}""")
    assertTrue(m.setAsideCoach("sess_1"))
    assertNull("the next message starts a new conversation", m.state.value.coachChat)
    assertTrue(m.state.value.coachFresh)
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
