package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.model.Answer
import bot.tafs.app.model.RecordEntry
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KIFF mode: the account's tafs, held calls and Record, read from tafs cloud and KIFF. */
class RefreshTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()

  private fun model(scope: kotlinx.coroutines.CoroutineScope? = null): AppModel = testModel(auth = auth, transport = transport, secrets = secrets, start = false, scope = scope)

  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}"""

  private fun taf(agent: String, name: String, state: String = "working", extra: String = "") =
    """{"id":"id_$agent","name":"$name","kiff_agent":"$agent","color":1,"state":"$state","card":{"ids":["c_$agent"],"active":true,"tools":["Payments"],"rules":[]}$extra}"""

  private fun home(vararg tafs: String, others: String = "") = """{"tafs":[${tafs.joinToString(",")}],"others":[$others],"removing":[]}"""

  private fun held(id: String, agent: String, at: String = "2026-10-28T09:00:00Z") =
    """{"id":"$id","subject":"$agent","action":"create_refund","title":"Refund a payment","entity_id":"tc-$id","proposal_id":"prop_$id","accounting_version":1,
       "breaches":[{"outcome":"per_action_exceeded","reason":"over","quantity":"sum(amount)","limit":10000,"unit":"EUR","scale":2}],
       "parameters":{"amount":15000},"parameter_units":{"amount":{"unit":"EUR","scale":2}},"arguments":{"payment_intent":"pi_$id"},
       "created_at":"$at","expires_at":"2099-01-01T00:00:00Z"}"""

  private fun call(proposal: String, agent: String, at: String, outcome: String = "allowed") =
    """{"proposal_id":"$proposal","action_name":"create_refund","title":"Refund a payment","actor_id":"$agent","outcome":"$outcome","created_at":"$at","tool_outcome":{"status":"done"}}"""

  private fun answers(home: String, held: String = "", activity: String = "") {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$held]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[$activity]}""")
  }

  private suspend fun signedIn(scope: kotlinx.coroutines.CoroutineScope? = null): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return model(scope).also { it.restore() }
  }

  // MARK: Reading

  @Test fun theAccountsTafsHeldCallsAndRecordAreShown() = runTest {
    answers(
      home(taf("refunds-1", "Refunds", "waiting"), taf("access-1", "Access")),
      held = held("ce_1", "refunds-1"),
      activity = call("p1", "refunds-1", "2026-10-28T08:00:00Z"),
    )
    val s = signedIn().state.value
    assertEquals(Mode.kiff, s.mode)
    assertEquals(setOf("refunds-1", "access-1"), s.tafs.map { it.id }.toSet())
    assertEquals(listOf("ce_1"), s.held.map { it.kiffId })
    assertEquals(listOf("p1"), s.record.map { it.id }.map { id -> if (id == KiffMapping.recordId("p1")) "p1" else id })
    assertEquals("Acme Pay", s.accountLabel)
    assertTrue(s.listLoaded)
    assertFalse(s.listUnread)
    assertNull(s.lastError)
  }

  @Test fun theListSaysItIsLoadingUntilItHasBeenRead() = runTest {
    val m = model()
    assertFalse(m.state.value.listLoaded)
    answers(home(taf("a-1", "A")))
    m.useSession("at_1", "t_01")
    assertTrue(m.state.value.listLoaded)
  }

  @Test fun everyReadCarriesTheSessionAndAsksForWhatItNeeds() = runTest {
    answers(home(taf("a-1", "A")))
    signedIn()
    assertEquals("Bearer at_1", transport.requestsTo("GET /v1/home").single().headers["Authorization"])
    assertEquals("Bearer at_1", transport.requestsTo("GET /v1/me/card-exceptions").single().headers["Authorization"])
    assertEquals("https://cloud.tafs.kiff.dev/v1/home", transport.requestsTo("GET /v1/home").single().url)
    assertEquals("https://api.kiff.dev/v1/me/card-exceptions?status=held", transport.requestsTo("GET /v1/me/card-exceptions").single().url)
    assertEquals("https://api.kiff.dev/v1/me/activity?limit=100", transport.requestsTo("GET /v1/me/activity").single().url)
  }

  @Test fun aSecondRefreshReplacesWhatWasShownNotAddsToIt() = runTest {
    answers(home(taf("a-1", "A"), taf("b-1", "B")), held = held("ce_1", "a-1"))
    val m = signedIn()
    assertEquals(2, m.state.value.tafs.size)
    assertEquals(1, m.state.value.held.size)
    transport.answer("GET /v1/home", 200, home(taf("a-1", "A")))
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    m.refresh()
    assertEquals(listOf("a-1"), m.state.value.tafs.map { it.id })
    assertTrue(m.state.value.held.isEmpty())
  }

  @Test fun theAccountIsReadOnceNotOnEveryRefresh() = runTest {
    answers(home(taf("a-1", "A")))
    val m = signedIn()
    m.refresh()
    m.refresh()
    assertEquals(1, transport.requestsTo("GET /v1/me").size)
  }

  @Test fun theRecordHoldsOnlyTheCallsOfTafsThatAreShown() = runTest {
    answers(
      home(taf("a-1", "A")),
      activity = listOf(call("p1", "a-1", "2026-10-28T08:00:00Z"), call("p2", "stranger", "2026-10-28T09:00:00Z")).joinToString(","),
    )
    val s = signedIn().state.value
    assertEquals(listOf(KiffMapping.recordId("p1")), s.record.map { it.id })
  }

  @Test fun theRecordIsNewestFirst() = runTest {
    answers(
      home(taf("a-1", "A")),
      activity = listOf(call("p1", "a-1", "2026-10-28T08:00:00Z"), call("p2", "a-1", "2026-10-28T10:00:00Z"), call("p3", "a-1", "2026-10-28T09:00:00Z")).joinToString(","),
    )
    val s = signedIn().state.value
    assertEquals(listOf("p2", "p3", "p1").map(KiffMapping::recordId), s.record.map { it.id })
  }

  @Test fun otherAgentsWithCardsAreKeptApartFromTheTafs() = runTest {
    answers(home(taf("a-1", "A"), others = """{"name":"Billing bot","kiff_agent":"billing-x","color":1,"state":"idle","card":{"ids":["c9"],"active":true}}"""))
    val s = signedIn().state.value
    assertEquals(listOf("a-1"), s.tafs.map { it.id })
    assertEquals(listOf("billing-x"), s.otherAgents.map { it.id })
    // A held call of one of them is shown too.
    assertNotNull(s.taf("billing-x"))
  }

  @Test fun theListHasTheTafWaitingOnTheOwnerFirstThenTheOneUsedMostRecently() = runTest {
    answers(
      home(taf("old-1", "Old"), taf("recent-1", "Recent"), taf("waits-1", "Waits"), taf("never-1", "Never")),
      held = held("ce_1", "waits-1"),
      activity = listOf(call("p1", "old-1", "2026-10-27T08:00:00Z"), call("p2", "recent-1", "2026-10-28T08:00:00Z")).joinToString(","),
    )
    val s = signedIn().state.value
    assertEquals(listOf("waits-1", "recent-1", "old-1", "never-1"), s.listedTafs.map { it.id })
  }

  // MARK: Not reading

  @Test fun anActivityThatCannotBeReadLeavesTheRecordAsItWas() = runTest {
    answers(home(taf("a-1", "A")), activity = call("p1", "a-1", "2026-10-28T08:00:00Z"))
    val m = signedIn()
    assertEquals(1, m.state.value.record.size)
    transport.answer("GET /v1/me/activity", 500, """{"error":"boom"}""")
    m.refresh()
    assertEquals(1, m.state.value.record.size)
    // The list itself was read, so nothing is said to be wrong.
    assertNull(m.state.value.lastError)
    assertTrue(m.state.value.listLoaded)
  }

  @Test fun aListTafsCloudRefusesIsKiffsWordsNotAnEmptyList() = runTest {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 500, """{"error":"boom"}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    val s = signedIn().state.value
    // The app's own refresh never says "Not done": one quiet line, and it tries again (kiff/tafs#318).
    assertNull(s.lastError)
    assertEquals("Couldn't load your tafs", s.unreachable)
    assertTrue(s.listUnread)
    assertTrue(s.listLoaded)
    // The account's name still shows.
    assertEquals("Acme Pay", s.accountLabel)
  }

  @Test fun aFailureAfterAListWasReadDoesNotUnreadIt() = runTest {
    answers(home(taf("a-1", "A")))
    val m = signedIn()
    transport.answer("GET /v1/home", 500, """{"error":"boom"}""")
    m.refresh()
    assertFalse(m.state.value.listUnread)
    assertEquals(listOf("a-1"), m.state.value.tafs.map { it.id })
    assertEquals("Couldn't load your tafs", m.state.value.unreachable)
    assertNull(m.state.value.lastError)
  }

  @Test fun aFailedRefreshTriesAgainByItselfAndTheLineGoesOnceOneGetsThrough() = runTest {
    answers(home(taf("a-1", "A")))
    val m = signedIn(backgroundScope)
    transport.answer("GET /v1/home", 500, """{"error":"boom"}""")
    transport.answer("GET /v1/home", 200, home(taf("a-1", "A"), taf("b-2", "B")))
    m.refresh()
    assertEquals("Couldn't load your tafs", m.state.value.unreachable)
    assertTrue(m.state.value.retryingRefresh)
    // The first try again comes after 2 s, by itself.
    testScheduler.advanceTimeBy(2_100)
    testScheduler.runCurrent()
    assertNull(m.state.value.unreachable)
    assertFalse(m.state.value.retryingRefresh)
    assertEquals(listOf("a-1", "b-2"), m.state.value.tafs.map { it.id }.sorted())
  }

  @Test fun afterItsTriesTheAppWaitsForAReturnOrAPull() = runTest {
    answers(home(taf("a-1", "A")))
    val m = signedIn(backgroundScope)
    transport.answer("GET /v1/home", 500, """{"error":"boom"}""")
    m.refresh()
    testScheduler.advanceTimeBy(AppModel.REFRESH_RETRY_SECONDS.sum() * 1_000 + 1_000)
    testScheduler.runCurrent()
    assertEquals("Couldn't load your tafs", m.state.value.unreachable)
    assertFalse("no more tries until the person comes back or pulls", m.state.value.retryingRefresh)
    val reads = transport.requestsTo("GET /v1/home").size
    testScheduler.advanceTimeBy(600_000)
    testScheduler.runCurrent()
    assertEquals(reads, transport.requestsTo("GET /v1/home").size)
  }

  @Test fun anEndedSessionIsSignedOutWhicheverReadSaysSoFirst() = runTest {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 401, """{"error":"Sign in with KIFF first."}""")
    transport.answer("GET /v1/me/card-exceptions", 401, """{"error":"unauthorized"}""")
    val m = signedIn()
    assertEquals(Mode.signedOut, m.state.value.mode)
    assertEquals("Signed out of KIFF. Sign in again.", m.state.value.lastError)
    assertNull(secrets.load(AppModel.TOKEN))
  }

  @Test fun theSampleNeverReadsKiff() = runTest {
    val m = testModel(transport = transport)
    m.refresh()
    assertTrue(transport.requests.isEmpty())
    assertEquals(Mode.sample, m.state.value.mode)
  }

  @Test fun aNewSessionNeverShowsTheLastOnesAccount() = runTest {
    answers(home(taf("a-1", "A")), held = held("ce_1", "a-1"))
    val m = signedIn()
    assertEquals(1, m.state.value.held.size)
    m.signOut()
    assertTrue(m.state.value.tafs.isEmpty())
    assertTrue(m.state.value.held.isEmpty())
    assertTrue(m.state.value.record.isEmpty())
    assertFalse(m.state.value.listLoaded)
  }

  // MARK: Held calls

  @Test fun aSessionKiffDoesntKnowThisPhonesKeyForCannotAnswerAndTheOwnerIsToldWhere() = runTest {
    answers(home(taf("a-1", "A", "waiting")), held = held("ce_1", "a-1"))
    // A session from before the phone's key: KIFF checks no signature of this phone's on it.
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    val m = model().also { it.restore() }
    val call = m.state.value.held.single()
    assertEquals("https://app.kiff.dev/needs-you/ce_1", m.answerPage(call))
    assertFalse(m.state.value.canAnswer(call))
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertFalse(m.answer(call, Answer.Refuse))
    // Nothing was confirmed or sent, and the call still waits.
    assertTrue(auth.asked.isEmpty())
    assertEquals(1, m.state.value.held.size)
    assertTrue(m.state.value.record.none { it.kind == RecordEntry.Kind.approved })
    assertEquals("Sign out and in again to answer from this phone, so KIFF knows its key. Or answer this one in KIFF.", m.state.value.lastError)
    assertTrue(transport.requests.none { it.method == "POST" })
  }

  @Test fun theSamplesCallsStillGoToThePhone() = runTest {
    val m = testModel(auth = auth, transport = transport)
    val call = m.state.value.held.first()
    assertNull(m.answerPage(call))
    assertTrue(m.answer(call, Answer.Refuse))
  }

  @Test fun aRecordLineIsWaitingOnlyWhileKiffStillHoldsItsCall() = runTest {
    answers(
      home(taf("a-1", "A", "waiting")),
      held = held("ce_1", "a-1"),
      activity = call("prop_ce_1", "a-1", "2026-10-28T09:00:00Z", outcome = "owner_required"),
    )
    val m = signedIn()
    val entry = m.state.value.record.single()
    assertEquals(RecordEntry.Kind.held, entry.kind)
    assertTrue(m.state.value.isWaiting(entry))
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    m.refresh()
    assertFalse(m.state.value.isWaiting(m.state.value.record.single()))
  }

  @Test fun beforeTheListIsReadAHeldLineCountsAsWaiting() {
    val entry = RecordEntry(tafId = "a", kind = RecordEntry.Kind.held, title = "x", detail = "Waiting for you", at = Instant.EPOCH)
    assertTrue(bot.tafs.app.app.AppState().isWaiting(entry))
  }
}
