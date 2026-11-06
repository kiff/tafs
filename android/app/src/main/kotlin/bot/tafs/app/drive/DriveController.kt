package bot.tafs.app.drive

import bot.tafs.app.kiff.CloudError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** tafs cloud's drive routes, as the controller uses them: [bot.tafs.app.kiff.TafsCloud] in the app, a fake in tests. */
interface DriveApi {
  /** `GET /v1/coach/drive/{drive}/stream`: the drive's requests as they come. */
  suspend fun driveStream(drive: String): Flow<DriveEvent>

  /** `POST /v1/coach/drive/{drive}/results/{request}`. */
  suspend fun driveAnswer(drive: String, request: String, answer: DriveAnswer)

  /** `POST /v1/coach/drive/stop {drive, why}`. */
  suspend fun stopDrive(drive: String, why: String)

  /** `GET /v1/coach/drive`. */
  suspend fun readDrive(): DriveRead
}

/** A point in the last screenshot's pixels, or an element of the last tree. */
sealed interface Target {
  data class At(val x: Int, val y: Int) : Target

  data class Element(val id: String) : Target
}

/** One of Coach's actions (docs/drive.md, `app_act`), with the caption the person sees while it's done. */
sealed interface Action {
  val line: String

  data class Tap(val target: Target, override val line: String) : Action

  data class LongPress(val target: Target, override val line: String) : Action

  data class Swipe(val from: Target, val to: Target.At, val ms: Long, override val line: String) : Action

  /** [direction] is where the content goes into view: `down` shows what's below. */
  data class Scroll(val direction: String, val element: String?, override val line: String) : Action

  data class Type(val text: String, override val line: String) : Action

  data class Back(override val line: String) : Action

  data class Done(override val line: String) : Action

  companion object {
    /** Coach's arguments as tafs cloud checked them; null when they don't make an action this app knows. */
    fun of(args: JsonObject): Action? {
      fun str(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull
      fun point(key: String): Target.At? {
        val o = args[key] as? JsonObject ?: return null
        val x = (o["x"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val y = (o["y"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return Target.At(Math.round(x).toInt(), Math.round(y).toInt())
      }
      val line = str("line")?.trim()?.take(60).orEmpty()
      val target: Target? = str("element")?.let(Target::Element) ?: point("at")
      return when (str("action")) {
        "tap" -> target?.let { Tap(it, line) }
        "long_press" -> target?.let { LongPress(it, line) }
        "swipe" -> {
          val to = point("to") ?: return null
          val ms = (args["ms"] as? JsonPrimitive)?.longOrNull ?: 300L
          target?.let { Swipe(it, to, ms.coerceIn(50, 3_000), line) }
        }
        "scroll" -> str("direction")?.takeIf { it in setOf("up", "down", "left", "right") }?.let { Scroll(it, str("element"), line) }
        "type" -> str("text")?.takeIf(String::isNotEmpty)?.let { Type(it, line) }
        "back" -> Back(line)
        "done" -> Done(line)
        else -> null
      }
    }
  }
}

/** What a look gives back: the screenshot, and the tree and diagnostics when asked. */
data class Look(val image: DriveImage?, val tree: JsonElement? = null, val diagnostics: JsonElement? = null)

/** What came of an action: done or refused, the app's line for it, and what it touched. */
data class Acted(val done: Boolean, val line: String, val touched: Touched? = null) {
  companion object {
    fun refused(line: String) = Acted(false, line)
  }
}

/** The phone's half of a drive: the app's windows, as Android shows them (AndroidDriveHost), or a fake in tests. */
interface DriveHost {
  /** The screenshot of the app's own windows, and the tree and diagnostics when asked. */
  suspend fun look(tree: Boolean, diagnostics: Boolean): Look

  /** Does one action, after checking it may (the confirm mark), and says what came of it in the app's words. */
  suspend fun act(action: Action): Acted

  /** Waits at most [timeoutMs] for one of the app's events (docs/drive.md, `wait`); true when it came. */
  suspend fun await(event: String, timeoutMs: Long): Boolean

  /** The drive changed: draw the border, the caption and the dot, or drop them. Called on the main thread's state. */
  fun show(state: DriveState)
}

/** Where the person's drive stands on this phone. */
sealed interface DriveState {
  data object Off : DriveState

  /** Coach is driving: [caption] is the step it's on, in its words. */
  data class On(val drive: DriveInfo, val caption: String? = null) : DriveState

  /** It ended, and never comes back: [words] say why, for the caption and the chat. */
  data class Over(val drive: DriveInfo, val why: String, val words: String) : DriveState
}

/**
 * The person's drive on this phone (docs/drive.md): follows its stream, does each request in turn and answers it, and ends
 * at once on the person's input, the app leaving the screen, a lock, a sign-out, or tafs cloud's `ended`. An ended drive
 * never comes back. Nothing is done after the end, and nothing is sent but the stop.
 */
class DriveController(
  private val api: DriveApi,
  private val host: DriveHost,
  private val scope: CoroutineScope,
  /** The app's own clock in milliseconds, which only moves forward: what `start_within_ms` is counted on. */
  private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
  private val _state = MutableStateFlow<DriveState>(DriveState.Off)
  val state: StateFlow<DriveState> = _state.asStateFlow()

  /** True while a drive is on. */
  val on: Boolean get() = _state.value is DriveState.On

  /** A step of Coach's under way: a field that changes meanwhile is Coach's, any other change the person's. */
  @Volatile
  var stepInFlight: Boolean = false
    private set

  private var job: Job? = null

  /** The drive tafs cloud just started: follow its stream. A drive already on here is over first. */
  fun begin(drive: DriveInfo) {
    (_state.value as? DriveState.On)?.let { end(it.drive, "replaced", null) }
    set(DriveState.On(drive))
    job = scope.launch { follow(drive) }
  }

  /**
   * Ends the drive at once, for [why] (`owner_took_over`, `left_app`, `stopped`, `locked`, `signed_out`): nothing more is
   * done, and tafs cloud is told. False when no drive was on.
   */
  fun stop(why: String): Boolean {
    val on = _state.value as? DriveState.On ?: return false
    end(on.drive, why, null)
    scope.launch(NonCancellable) { runCatching { api.stopDrive(on.drive.id, why) } }
    return true
  }

  /** The ended drive is read: back to no drive. */
  fun clear() {
    if (_state.value is DriveState.Over) set(DriveState.Off)
  }

  private fun set(s: DriveState) {
    _state.value = s
    host.show(s)
  }

  private fun end(drive: DriveInfo, why: String, words: String?) {
    if ((_state.value as? DriveState.On)?.drive?.id != drive.id) return
    job?.cancel()
    job = null
    stepInFlight = false
    set(DriveState.Over(drive, why, words ?: DriveWords.ended(why)))
  }

  private object Reopened : Exception()

  private suspend fun follow(drive: DriveInfo) = coroutineScope {
    // One request at a time, in the order they came, each with when it came by the app's clock.
    val queue = Channel<Pair<DriveRequest, Long>>(Channel.UNLIMITED)
    launch { for ((request, came) in queue) handle(drive, request, came) }
    var failures = 0
    while (isActive) {
      try {
        api.driveStream(drive.id).collect { e ->
          when (e) {
            is DriveEvent.Opened -> failures = 0
            is DriveEvent.Request -> queue.trySend(e.request to now())
            is DriveEvent.Ended -> { end(drive, e.why, e.words); return@collect }
            DriveEvent.Reopen -> throw Reopened
          }
        }
        // The stream closed with no word: open it again, unless the drive ended.
        if (!isActive) break
      } catch (_: Reopened) {
        failures = 0
        continue
      } catch (e: CancellationException) {
        throw e
      } catch (e: CloudError) {
        // Not this phone's drive any more (404), or over (409): what tafs cloud keeps says how it ended.
        if (e.status == 404 || e.status == 409) {
          val read = runCatching { api.readDrive() }.getOrNull()
          val ended = read?.drive?.takeIf { it.id == drive.id }?.ended
          end(drive, ended?.why ?: "replaced", ended?.words)
          break
        }
        failures++
      } catch (_: Exception) {
        failures++
      }
      delay(minOf(500L shl minOf(failures, 4), 8_000L))
    }
    queue.close()
  }

  private suspend fun handle(drive: DriveInfo, request: DriveRequest, came: Long) {
    // Started in time or never: past it, Coach has given up on this step and may have sent its next.
    if (now() - came > request.startWithinMs) {
      answer(drive, request, DriveAnswer.refused(DriveWords.TOO_LATE))
      return
    }
    val answer = try {
      stepInFlight = true
      when (request.tool) {
        "app_look" -> look(request.args)
        "app_act" -> act(drive, request.args)
        else -> DriveAnswer.refused(DriveWords.UNKNOWN)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      DriveAnswer.refused(DriveWords.failed(e.message))
    } finally {
      stepInFlight = false
    }
    answer(drive, request, answer)
  }

  private suspend fun look(args: JsonObject): DriveAnswer {
    fun flag(key: String) = (args[key] as? JsonPrimitive)?.booleanOrNull == true
    val waited = (args["wait"] as? JsonPrimitive)?.contentOrNull?.let { event ->
      val seconds = ((args["timeout_s"] as? JsonPrimitive)?.longOrNull ?: 10L).coerceIn(1, 20)
      Waited(event, host.await(event, seconds * 1_000))
    }
    val seen = host.look(flag("tree"), flag("diagnostics"))
    return DriveAnswer("done", DriveWords.looked(waited), seen.image, seen.tree, seen.diagnostics, waited = waited)
  }

  private suspend fun act(drive: DriveInfo, args: JsonObject): DriveAnswer {
    val action = Action.of(args) ?: return DriveAnswer.refused(DriveWords.UNKNOWN)
    if (action.line.isNotEmpty() && (_state.value as? DriveState.On)?.drive?.id == drive.id) set(DriveState.On(drive, action.line))
    val acted = host.act(action)
    // Every answer carries the screen after the step.
    val after = host.look(tree = false, diagnostics = false)
    return DriveAnswer(if (acted.done) "done" else "refused", acted.line, after.image, touched = acted.touched)
  }

  private suspend fun answer(drive: DriveInfo, request: DriveRequest, answer: DriveAnswer) {
    // An answer that doesn't arrive is Coach's to notice: it looks again.
    runCatching { api.driveAnswer(drive.id, request.id, answer) }
  }

  /** The steps of the drive just ended, for the "What I did" card: tafs cloud's record of them. */
  suspend fun steps(drive: String): List<DriveStep> =
    runCatching { api.readDrive() }.getOrNull()?.takeIf { it.drive?.id == drive }?.steps.orEmpty()
}

/** The app's own words in a drive (docs/drive.md, "The app's lines"), the same on iOS. */
object DriveWords {
  const val CONFIRM = "That's yours to confirm"
  const val TOO_LATE = "Too late"
  const val NOTHING_THERE = "Nothing Coach can use there"
  const val NOT_ON_SCREEN = "That isn't on screen now"
  const val NO_FIELD = "No field is focused"
  const val SECRET_FIELD = "That's yours to fill"
  const val NOTHING_BACK = "Nothing to go back to"
  const val UNKNOWN = "This app can't do that step"
  const val SWIPED = "Swiped"
  const val WENT_BACK = "Went back"
  const val DONE = "Done"

  const val DRIVE_CARD = "Coach asks to drive the app"
  const val DRIVE_NOTE = "Coach taps and types in this app while you watch, for this request only. It never confirms anything. Touch the app or tap Stop to take over."
  const val WHAT_I_DID = "What I did"
  const val NO_STEPS = "Nothing was done."

  /** What the fingerprint prompt says when the person approves a drive. */
  fun approval(task: String) = "Let Coach drive the app: $task".take(120)

  fun tapped(words: String?) = words?.let { "Tapped $it" } ?: "Tapped"

  fun longPressed(words: String?) = words?.let { "Long-pressed $it" } ?: "Long-pressed"

  fun scrolled(direction: String) = "Scrolled $direction"

  fun cantScroll(direction: String) = "Can't scroll $direction there"

  fun typed(field: String?) = field?.let { "Typed into $it" } ?: "Typed"

  fun failed(why: String?) = "That step failed" + (why?.takeIf(String::isNotBlank)?.let { ": ${it.take(120)}" } ?: "")

  fun looked(waited: Waited?): String = when {
    waited == null -> "Looked"
    waited.came -> "Looked once ${waited.event} came"
    else -> "Looked: ${waited.event} didn't come in time"
  }

  /** The caption once it's over, when tafs cloud's words didn't come: the same words tafs cloud keeps. */
  fun ended(why: String): String = when (why) {
    "owner_took_over" -> "You touched the app, so it's yours now"
    "left_app" -> "The app left the screen, so Coach stopped"
    "stopped" -> "You stopped Coach"
    "locked" -> "The phone locked, so Coach stopped"
    "signed_out" -> "You signed out, so Coach stopped"
    "halted" -> "The account is halted, so Coach stopped"
    "done" -> "Coach is done"
    "idle" -> "Nothing was done for 15 minutes, so Coach stopped"
    "replaced" -> "Another drive started"
    else -> "Coach stopped"
  }
}

/** The host of the activity on screen now, so the drive outlives no screen: with none, a step is refused and a look has no image. */
class DriveHostProxy : DriveHost {
  @Volatile
  var current: DriveHost? = null

  override suspend fun look(tree: Boolean, diagnostics: Boolean): Look = current?.look(tree, diagnostics) ?: Look(null)

  override suspend fun act(action: Action): Acted = current?.act(action) ?: Acted.refused(DriveWords.NOTHING_THERE)

  override suspend fun await(event: String, timeoutMs: Long): Boolean = DriveSignals.await(event, timeoutMs)

  override fun show(state: DriveState) {
    current?.show(state)
  }
}

/** A drive that ended, for the "What I did" card: the request, why it ended, and tafs cloud's record of its steps. */
data class DoneDrive(val task: String, val words: String, val steps: List<DriveStep>)
