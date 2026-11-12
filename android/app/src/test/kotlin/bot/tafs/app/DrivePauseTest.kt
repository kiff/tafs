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
    var pauseFails = false

    override suspend fun driveStream(drive: String): Flow<DriveEvent> = events.receiveAsFlow()

    override suspend fun driveAnswer(drive: String, request: String, answer: DriveAnswer) {
      answers += request to answer
    }

    override suspend fun stopDrive(drive: String, why: String) {
      stops += why
    }

    override suspend fun readDrive(): DriveRead = DriveRead()

    override suspend fun pauseDrive(drive: String) {
      pauses += drive
      if (pauseFails) throw java.io.IOException("The network dropped")
    }

    var resumeAnswer: DriveResumed? = null

    override suspend fun resumeDrive(drive: String): DriveResumed? {
      resumes += drive
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
    val api = FakeApi().apply { pauseFails = true }
    val host = FakeHost()
    val c = DriveController(api, host, driveScope()) { testScheduler.currentTime }
    c.begin(drive)
    c.pause()
    advanceUntilIdle()
    api.events.send(request("r1", tap))
    advanceUntilIdle()
    assertTrue(c.paused)
    assertTrue(host.acts.isEmpty())
    assertEquals(DriveWords.PAUSED, api.answers.single().second.line)
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
