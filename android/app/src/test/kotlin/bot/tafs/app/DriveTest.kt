package bot.tafs.app

import bot.tafs.app.drive.Acted
import bot.tafs.app.drive.Action
import bot.tafs.app.drive.DriveAnswer
import bot.tafs.app.drive.DriveApi
import bot.tafs.app.drive.DriveController
import bot.tafs.app.drive.DriveEnd
import bot.tafs.app.drive.DriveEvent
import bot.tafs.app.drive.DriveHost
import bot.tafs.app.drive.DriveImage
import bot.tafs.app.drive.DriveInfo
import bot.tafs.app.drive.DriveRead
import bot.tafs.app.drive.DriveRequest
import bot.tafs.app.drive.DriveState
import bot.tafs.app.drive.DriveWords
import bot.tafs.app.drive.Look
import bot.tafs.app.drive.Target
import bot.tafs.app.drive.Touched
import bot.tafs.app.kiff.CloudError
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.TafsCloud
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coach drives the app (docs/drive.md): the contract's start, its stream, and a drive followed step by step to its end. */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveTest {
  private val drive = DriveInfo("d_1", "s_1", "Run Chargebacks")

  private class FakeApi : DriveApi {
    val events = Channel<DriveEvent>(Channel.UNLIMITED)
    var opens = 0
    var failWith: Exception? = null
    val answers = mutableListOf<Pair<String, DriveAnswer>>()
    val stops = mutableListOf<Pair<String, String>>()
    var read = DriveRead()

    override suspend fun driveStream(drive: String): Flow<DriveEvent> {
      opens++
      failWith?.let { throw it }
      return events.receiveAsFlow()
    }

    override suspend fun driveAnswer(drive: String, request: String, answer: DriveAnswer) {
      answers += request to answer
    }

    override suspend fun stopDrive(drive: String, why: String) {
      stops += drive to why
    }

    override suspend fun readDrive(): DriveRead = read
  }

  private class FakeHost : DriveHost {
    val acts = mutableListOf<Action>()
    val shown = mutableListOf<DriveState>()
    var actMs = 0L
    var acted = Acted(true, "Tapped Run now", Touched("Run now", "Chargebacks"))
    var inFlight: (() -> Boolean)? = null
    var sawInFlight = false

    override suspend fun look(tree: Boolean, diagnostics: Boolean) =
      Look(DriveImage(data = "AAAA"), tree = if (tree) JsonArray(emptyList()) else null)

    override suspend fun act(action: Action): Acted {
      acts += action
      sawInFlight = inFlight?.invoke() == true
      delay(actMs)
      return acted
    }

    override suspend fun await(event: String, timeoutMs: Long): Boolean = event == "screen_shown"

    override fun show(state: DriveState) {
      shown += state
    }
  }

  private fun request(id: String, args: String, within: Long = 18_000) =
    DriveEvent.Request(DriveRequest(id, if (args.contains("\"action\"")) "app_act" else "app_look", KiffClient.json.parseToJsonElement(args).jsonObject, within))

  @Test
  fun theStartIsSignedAsTheContractSays() = runTest {
    // docs/drive.md's test vector: the body's hash, and the message the approval key signs.
    val body = """{"session":"s_1","task":"Run Chargebacks","component":"d1"}"""
    assertEquals("fea4f2ebc2091cd2cbf48e1cc3b6285603bcc194cddea23f5737dfdc2a78414f", sha256(body.toByteArray()))
    assertEquals(
      "kiff.request.v1\nt_acme\nPOST\n/v1/coach/drive\nfea4f2ebc2091cd2cbf48e1cc3b6285603bcc194cddea23f5737dfdc2a78414f\n2026-11-06T00:30:00Z",
      String(KiffClient.requestMessage("t_acme", "POST", "/v1/coach/drive", body.toByteArray(), "2026-11-06T00:30:00Z")),
    )

    // The app sends exactly those bytes, and signs the message over them only once the person approved.
    val transport = FakeTransport()
    transport.answer("POST /v1/coach/drive", 200, """{"drive":{"id":"d_1","session":"s_1","task":"Run Chargebacks","started":"2026-11-06T00:30:01Z"}}""")
    val cloud = TafsCloud(transport).apply { token = "tok" }
    var signed: String? = null
    val started = cloud.startDrive("s_1", "Run Chargebacks", "d1", "t_acme") { built ->
      signed = String(built())
      "c2lnbmVk"
    }
    assertEquals("d_1", started.id)
    val sent = transport.requestsTo("POST /v1/coach/drive").single()
    assertEquals(body, String(sent.body!!))
    val header = sent.headers.getValue("KIFF-Device-Signature")
    val signedAt = header.removePrefix("t=").substringBefore(",v1=")
    assertEquals("t=$signedAt,v1=c2lnbmVk", header)
    assertEquals(String(KiffClient.requestMessage("t_acme", "POST", "/v1/coach/drive", body.toByteArray(), signedAt)), signed)
  }

  @Test
  fun aStartRefusedForTheWrongKeyStartsNothing() = runTest {
    val transport = FakeTransport()
    transport.answer("POST /v1/coach/drive", 403, """{"error":"a drive starts only with your fingerprint","code":"critical_key_required"}""")
    val cloud = TafsCloud(transport).apply { token = "tok" }
    val refused = runCatching { cloud.startDrive("s_1", "Run Chargebacks", "d1", "t_acme") { "c2ln" } }.exceptionOrNull() as CloudError
    assertEquals(403, refused.status)
    assertEquals("a drive starts only with your fingerprint", refused.message)
  }

  @Test
  fun theStreamIsReadAsTheContractSays() = runTest {
    val lines = listOf(
      "event: drive", """data: {"drive":"d_1"}""", "",
      ": ping", "",
      "event: request", """data: {"id":"r1","tool":"app_act","args":{"action":"tap","line":"Tapping Run now","at":{"x":10,"y":20}},"at":"2026-11-06T00:30:02Z","start_within_ms":17000}""", "",
      "event: something_new", "data: {}", "",
      "event: reopen", "data: {}", "",
      "event: ended", """data: {"why":"done","words":"the task is done"}""", "",
    )
    val events = DriveEvent.parse(flowOf(*lines.toTypedArray())).toList()
    assertEquals(DriveEvent.Opened("d_1"), events[0])
    val r = (events[1] as DriveEvent.Request).request
    assertEquals("r1", r.id)
    assertEquals("app_act", r.tool)
    assertEquals(17_000L, r.startWithinMs)
    assertEquals(Action.Tap(Target.At(10, 20), "Tapping Run now"), Action.of(r.args))
    assertEquals(DriveEvent.Reopen, events[2])
    assertEquals(DriveEvent.Ended("done", "the task is done"), events[3])
    assertEquals(4, events.size)
  }

  @Test
  fun actionsAreReadFromTheirArguments() {
    fun of(args: String) = Action.of(KiffClient.json.parseToJsonElement(args).jsonObject)
    assertEquals(Action.Tap(Target.Element("run_now"), "Tapping Run now"), of("""{"action":"tap","line":"Tapping Run now","element":"run_now"}"""))
    assertEquals(Action.Swipe(Target.At(1, 2), Target.At(3, 4), 3_000, "Swiping"), of("""{"action":"swipe","line":"Swiping","at":{"x":1,"y":2},"to":{"x":3,"y":4},"ms":9000}"""))
    assertEquals(Action.Scroll("down", null, "Scrolling"), of("""{"action":"scroll","line":"Scrolling","direction":"down"}"""))
    assertNull(of("""{"action":"scroll","line":"Scrolling","direction":"sideways"}"""))
    assertNull(of("""{"action":"type","line":"Typing","text":""}"""))
    assertNull(of("""{"action":"tap","line":"Tapping"}"""))
    assertEquals(Action.Done("Done"), of("""{"action":"done","line":"Done"}"""))
  }

  @Test
  fun aStepIsDoneAndAnsweredWithTheScreenAfterIt() = runTest {
    val api = FakeApi()
    val host = FakeHost()
    val c = DriveController(api, host, driveScope()) { testScheduler.currentTime }
    host.inFlight = { c.stepInFlight }
    c.begin(drive)
    api.events.send(DriveEvent.Opened("d_1"))
    api.events.send(request("r1", """{"action":"tap","line":"Tapping Run now","at":{"x":10,"y":20}}"""))
    advanceUntilIdle()
    assertEquals(listOf<Action>(Action.Tap(Target.At(10, 20), "Tapping Run now")), host.acts)
    assertTrue("a field changing during Coach's step is Coach's", host.sawInFlight)
    assertFalse(c.stepInFlight)
    val (id, answer) = api.answers.single()
    assertEquals("r1", id)
    assertEquals("done", answer.outcome)
    assertEquals("Tapped Run now", answer.line)
    assertEquals(Touched("Run now", "Chargebacks"), answer.touched)
    assertNotNull("every answer carries the screen after the step", answer.image)
    assertEquals(DriveState.On(drive, "Tapping Run now"), c.state.value)
  }

  @Test
  fun aLookWaitsAndSaysWhetherTheEventCame() = runTest {
    val api = FakeApi()
    val c = DriveController(api, FakeHost(), driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    api.events.send(request("r1", """{"tree":true,"wait":"screen_shown","timeout_s":5}"""))
    api.events.send(request("r2", """{"wait":"sheet_closed","timeout_s":5}"""))
    advanceUntilIdle()
    val came = api.answers[0].second
    assertEquals("Looked once screen_shown came", came.line)
    assertEquals(true, came.waited?.came)
    assertNotNull(came.tree)
    assertEquals(false, api.answers[1].second.waited?.came)
    assertNull(api.answers[1].second.tree)
  }

  @Test
  fun aStepNotStartedInTimeIsRefusedAndNeverDone() = runTest {
    val api = FakeApi()
    val host = FakeHost().apply { actMs = 3_000 }
    val c = DriveController(api, host, driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    api.events.send(request("r1", """{"action":"tap","line":"Tapping Run now","at":{"x":10,"y":20}}""", within = 5_000))
    api.events.send(request("r2", """{"action":"tap","line":"Tapping Activity","at":{"x":30,"y":40}}""", within = 1_000))
    advanceUntilIdle()
    assertEquals(1, host.acts.size)
    assertEquals("r2" to DriveWords.TOO_LATE, api.answers[1].first to api.answers[1].second.line)
    assertEquals("refused", api.answers[1].second.outcome)
  }

  @Test
  fun tafsCloudsEndStopsAtOnce() = runTest {
    val api = FakeApi()
    val host = FakeHost()
    val c = DriveController(api, host, driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    api.events.send(DriveEvent.Ended("halted", "the account is halted"))
    advanceUntilIdle()
    assertEquals(DriveState.Over(drive, "halted", "the account is halted"), c.state.value)
    api.events.send(request("r1", """{"action":"tap","line":"Tapping Run now","at":{"x":10,"y":20}}"""))
    advanceUntilIdle()
    assertTrue("nothing is done after the end", host.acts.isEmpty())
    assertTrue(api.answers.isEmpty())
    assertTrue("tafs cloud ended it: nothing is sent back", api.stops.isEmpty())
  }

  @Test
  fun thePersonsStopEndsItAtOnceAndTellsTafsCloud() = runTest {
    val api = FakeApi()
    val host = FakeHost().apply { actMs = 10_000 }
    val c = DriveController(api, host, driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    api.events.send(request("r1", """{"action":"swipe","line":"Swiping","at":{"x":1,"y":2},"to":{"x":3,"y":400},"ms":3000}"""))
    advanceTimeBy(100)
    runCurrent()
    assertTrue(c.stepInFlight)
    assertTrue(c.stop("owner_took_over"))
    assertEquals(DriveState.Over(drive, "owner_took_over", DriveWords.ended("owner_took_over")), c.state.value)
    assertFalse(c.stepInFlight)
    advanceUntilIdle()
    assertEquals(listOf("d_1" to "owner_took_over"), api.stops)
    assertTrue("the step cut short is never answered", api.answers.isEmpty())
    assertFalse("a second stop is nothing", c.stop("stopped"))
    assertEquals(1, api.stops.size)
  }

  @Test
  fun aStreamThatLivedItsTimeIsOpenedAgain() = runTest {
    val api = FakeApi()
    val c = DriveController(api, FakeHost(), driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    api.events.send(DriveEvent.Reopen)
    advanceUntilIdle()
    assertEquals(2, api.opens)
    api.events.send(request("r1", """{"action":"back","line":"Going back"}"""))
    advanceUntilIdle()
    assertEquals("r1", api.answers.single().first)
  }

  @Test
  fun aDriveOverAtTafsCloudEndsWithItsWhy() = runTest {
    val api = FakeApi().apply {
      failWith = CloudError(409, "the drive is over")
      read = DriveRead(DriveInfo("d_1", "s_1", "Run Chargebacks", ended = DriveEnd("idle", words = "nothing was done for 15 minutes")))
    }
    val c = DriveController(api, FakeHost(), driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    advanceUntilIdle()
    assertEquals(DriveState.Over(drive, "idle", "nothing was done for 15 minutes"), c.state.value)
  }

  @Test
  fun aNewDriveReplacesTheOneOnHere() = runTest {
    val api = FakeApi()
    val c = DriveController(api, FakeHost(), driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    val next = DriveInfo("d_2", "s_1", "Turn off suggestions")
    c.begin(next)
    assertEquals(DriveState.On(next), c.state.value)
    c.stop("stopped")
    advanceUntilIdle()
    assertEquals(listOf("d_2" to "stopped"), api.stops)
  }

  /**
   * Where the drive follows its stream: the test's clock and dispatcher, but not the test's own job, so advanceUntilIdle runs it
   * (backgroundScope's work isn't waited on) and the test ends with the stream still open.
   */
  private fun kotlinx.coroutines.test.TestScope.driveScope() =
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) + kotlinx.coroutines.SupervisorJob())

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
