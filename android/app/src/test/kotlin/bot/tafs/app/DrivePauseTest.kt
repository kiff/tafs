package bot.tafs.app

import bot.tafs.app.drive.Acted
import bot.tafs.app.drive.Action
import bot.tafs.app.drive.DriveAnswer
import bot.tafs.app.drive.DriveApi
import bot.tafs.app.drive.DriveController
import bot.tafs.app.drive.DriveEvent
import bot.tafs.app.drive.DriveHost
import bot.tafs.app.drive.DriveImage
import bot.tafs.app.drive.DriveInfo
import bot.tafs.app.drive.DriveInput
import bot.tafs.app.drive.DriveRead
import bot.tafs.app.drive.DriveRequest
import bot.tafs.app.drive.DriveResumed
import bot.tafs.app.drive.DriveEnd
import bot.tafs.app.drive.DriveState
import bot.tafs.app.drive.DriveWords
import bot.tafs.app.drive.Look
import bot.tafs.app.drive.driveDismissal
import bot.tafs.app.drive.driveTyped
import bot.tafs.app.kiff.CloudError
import bot.tafs.app.kiff.KiffClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The person's touch pauses Coach's drive (docs/drive.md, "When the person touches the app"): the app pauses at once and refuses
 * every request until Continue, whatever tafs cloud says; Continue puts the keyboard away first; a third tap on a control since
 * the pause, the pausing one included, is a clear takeover; typing is one too, and the words typed are kept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrivePauseTest {
  private val drive = DriveInfo("d_1", "s_1", "Run Chargebacks")

  private class FakeApi : DriveApi {
    val events = Channel<DriveEvent>(Channel.UNLIMITED)
    val answers = mutableListOf<Pair<String, DriveAnswer>>()
    val stops = mutableListOf<String>()
    val pauses = mutableListOf<String>()
    val resumes = mutableListOf<String>()
    /** How many pause posts fail before one lands. */
    var pauseFails = 0

    /** What a failing pause or resume throws: the network by default, or tafs cloud's refusal. */
    var failure: () -> Exception = { java.io.IOException("The network dropped") }
    var resumeFails = false
    var read = DriveRead()

    override suspend fun driveStream(drive: String): Flow<DriveEvent> = events.receiveAsFlow()

    override suspend fun driveAnswer(drive: String, request: String, answer: DriveAnswer) {
      answers += request to answer
    }

    override suspend fun stopDrive(drive: String, why: String) {
      stops += why
    }

    override suspend fun readDrive(): DriveRead = read

    override suspend fun pauseDrive(drive: String) {
      pauses += drive
      if (pauses.size <= pauseFails) throw failure()
    }

    var resumeAnswer: DriveResumed? = null

    override suspend fun resumeDrive(drive: String): DriveResumed? {
      resumes += drive
      if (resumeFails) throw failure()
      return resumeAnswer
    }
  }

  private class FakeHost : DriveHost {
    val acts = mutableListOf<Action>()
    var keyboardAway = 0

    override suspend fun look(tree: Boolean, diagnostics: Boolean) = Look(DriveImage(data = "AAAA"))

    override suspend fun act(action: Action): Acted {
      acts += action
      return Acted(true, "Tapped Run now")
    }

    override suspend fun await(event: String, timeoutMs: Long): Boolean = false

    override fun show(state: DriveState) {}

    override fun putKeyboardAway() {
      keyboardAway++
    }
  }

  private val tap = """{"action":"tap","line":"Tapping Run now","at":{"x":10,"y":20}}"""

  private fun request(id: String, args: String) =
    DriveEvent.Request(DriveRequest(id, "app_act", KiffClient.json.parseToJsonElement(args).jsonObject, 18_000))

  private fun kotlinx.coroutines.test.TestScope.driveScope() =
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) + kotlinx.coroutines.SupervisorJob())

  @After fun off() {
    DriveInput.turn(false)
    DriveInput.personsInput = {}
    DriveInput.personsTouch = {}
    DriveInput.coachStep = { false }
  }

  @Test fun aPausedDriveRefusesEveryRequestAndTellsTafsCloud() = runTest {
    val api = FakeApi()
    val host = FakeHost()
    val c = DriveController(api, host, driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    assertTrue(c.pause())
    assertTrue(c.paused)
    api.events.send(request("r1", tap))
    advanceUntilIdle()
    assertTrue("nothing is done while the person uses the app", host.acts.isEmpty())
    assertEquals("refused", api.answers.single().second.outcome)
    assertEquals(DriveWords.PAUSED, api.answers.single().second.line)
    assertEquals(listOf("d_1"), api.pauses)
    assertTrue("a pause ends nothing", api.stops.isEmpty())
  }

  @Test fun thePauseStandsWhenTafsCloudCantBeTold() = runTest {
    val api = FakeApi().apply { pauseFails = 2 }
    val host = FakeHost()
    val c = DriveController(api, host, driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    c.pause()
    api.events.send(request("r1", tap))
    advanceUntilIdle()
    assertTrue(c.paused)
    assertTrue(host.acts.isEmpty())
    assertEquals(DriveWords.PAUSED, api.answers.single().second.line)
    // Posted again with a backoff until it landed: two failures, then the third took.
    assertEquals(3, api.pauses.size)
  }

  @Test fun aPauseThatNeverLandsStopsBeingPostedAtContinue() = runTest {
    val api = FakeApi().apply { pauseFails = Int.MAX_VALUE }
    val c = DriveController(api, FakeHost(), driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    c.pause()
    testScheduler.advanceTimeBy(5_000)
    testScheduler.runCurrent()
    val posted = api.pauses.size
    assertTrue("posted again meanwhile", posted > 1)
    c.resume()
    testScheduler.advanceTimeBy(60_000)
    testScheduler.runCurrent()
    assertEquals("nothing more after Continue", posted, api.pauses.size)
  }

  @Test fun continuePutsTheKeyboardAwayThenCoachGoesOn() = runTest {
    val api = FakeApi()
    val host = FakeHost()
    val c = DriveController(api, host, driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    c.pause()
    c.resume()
    assertFalse(c.paused)
    assertEquals(1, host.keyboardAway)
    api.events.send(request("r1", tap))
    advanceUntilIdle()
    assertEquals(1, host.acts.size)
    assertEquals(listOf("d_1"), api.resumes)
    // Continue on a drive that isn't paused changes nothing.
    c.resume()
    assertEquals(1, host.keyboardAway)
  }

  @Test fun continueOnAHaltedAccountEndsTheDriveInTafsCloudsWords() = runTest {
    val api = FakeApi().apply { resumeAnswer = DriveResumed(resumed = false, ended = DriveEnd("halted", words = "The account is halted, so Coach stopped")) }
    val c = DriveController(api, FakeHost(), driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    c.pause()
    c.resume()
    advanceUntilIdle()
    assertEquals(DriveState.Over(drive, "halted", "The account is halted, so Coach stopped"), c.state.value)
    assertTrue("tafs cloud ended it: nothing is sent back", api.stops.isEmpty())
  }

  private fun gone() = CloudError(404, "not found")

  @Test fun aPauseAnswered404EndsTheDriveInItsOwnEndsWords() = runTest {
    val api = FakeApi().apply {
      pauseFails = Int.MAX_VALUE
      failure = { gone() }
      read = DriveRead(DriveInfo("d_1", ended = DriveEnd("idle", words = "Nothing was done for 15 minutes, so Coach stopped")))
    }
    val c = DriveController(api, FakeHost(), driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    c.pause()
    advanceUntilIdle()
    assertEquals(DriveState.Over(drive, "idle", "Nothing was done for 15 minutes, so Coach stopped"), c.state.value)
    assertEquals("posted once: a 404 is never asked again", 1, api.pauses.size)
    assertTrue("tafs cloud has no such drive: nothing is sent back", api.stops.isEmpty())
  }

  @Test fun aPauseAnswered404WhileAnotherDriveIsCurrentSaysAnotherStarted() = runTest {
    val api = FakeApi().apply { pauseFails = Int.MAX_VALUE; failure = { gone() }; read = DriveRead(DriveInfo("d_2")) }
    val c = DriveController(api, FakeHost(), driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    c.pause()
    advanceUntilIdle()
    val over = c.state.value as DriveState.Over
    assertEquals("replaced", over.why)
    assertEquals(DriveWords.ended("replaced"), over.words)
  }

  @Test fun aPauseAnswered404WithNoDriveSaysCoachStoppedNeverTheyTookOver() = runTest {
    val api = FakeApi().apply { pauseFails = Int.MAX_VALUE; failure = { gone() } }
    val c = DriveController(api, FakeHost(), driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    c.pause()
    advanceUntilIdle()
    val over = c.state.value as DriveState.Over
    assertEquals("other", over.why)
    assertEquals(DriveWords.ended("other"), over.words)
  }

  @Test fun aResumeAnswered404EndsTheDriveToo() = runTest {
    val api = FakeApi().apply { resumeFails = true; failure = { gone() } }
    val c = DriveController(api, FakeHost(), driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    c.pause()
    c.resume()
    advanceUntilIdle()
    assertEquals("other", (c.state.value as DriveState.Over).why)
  }

  @Test fun onlyTheNetworkA5xxA429OrA401IsAskedAgain() = runTest {
    // A drive tafs cloud still has: asked again, and the third post lands.
    for (status in listOf(503, 429, 401)) {
      val busy = FakeApi().apply { pauseFails = 2; failure = { CloudError(status, "try again") } }
      val c = DriveController(busy, FakeHost(), driveScope()) { testScheduler.currentTime }
      c.begin(drive)
      c.pause()
      advanceUntilIdle()
      assertEquals("$status", 3, busy.pauses.size)
      assertTrue("$status", c.paused)
    }
    // Another refusal won't change by asking again: posted once, and the pause here stands.
    val refused = FakeApi().apply { pauseFails = Int.MAX_VALUE; failure = { CloudError(400, "bad request") } }
    val d = DriveController(refused, FakeHost(), driveScope()) { testScheduler.currentTime }
    d.begin(drive)
    d.pause()
    advanceUntilIdle()
    assertEquals(1, refused.pauses.size)
    assertTrue(d.paused)
  }

  @Test fun aThirdTapOnAControlSinceThePauseIsAClearTakeover() = runTest {
    val api = FakeApi()
    val c = DriveController(api, FakeHost(), driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    c.pause()
    // The tap that paused, on a control, is the first; a scroll or empty space counts for nothing.
    c.personsTap(onControl = true)
    c.personsTap(onControl = false)
    c.personsTap(onControl = true)
    assertTrue(c.on)
    c.personsTap(onControl = true)
    assertEquals("owner_took_over", (c.state.value as DriveState.Over).why)
    advanceUntilIdle()
    assertEquals(listOf("owner_took_over"), api.stops)
  }

  @Test fun theCountStartsAgainAtContinue() = runTest {
    val c = DriveController(FakeApi(), FakeHost(), driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    c.pause()
    c.personsTap(onControl = true)
    c.personsTap(onControl = true)
    c.resume()
    c.pause()
    c.personsTap(onControl = true)
    c.personsTap(onControl = true)
    assertTrue("two since the last Continue", c.on)
  }

  @Test fun thePersonsTypingIsKeptAndEndsTheDrive() {
    val ends = mutableListOf<String>()
    DriveInput.personsInput = { ends += it }
    DriveInput.coachStep = { false }
    DriveInput.turn(true)
    var text = ""
    driveTyped<String> { text = it }("Refund 12")
    assertEquals("Refund 12", text)
    assertEquals(listOf("owner_took_over"), ends)
  }

  @Test fun aDismissalCoachDidntMakePausesAndStillHappens() {
    var paused = 0
    val ends = mutableListOf<String>()
    DriveInput.personsTouch = { paused++ }
    DriveInput.personsInput = { ends += it }
    DriveInput.coachStep = { false }
    DriveInput.turn(true)
    var open = true
    driveDismissal { open = false }()
    assertFalse(open)
    assertEquals(1, paused)
    assertTrue(ends.isEmpty())
  }
}
