package bot.tafs.app.drive

import bot.tafs.app.model.Words
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

  /** `POST /v1/coach/drive/pause {drive}`: the person touched the app. A tafs cloud without the route answers 404, and the app's own pause stands. */
  suspend fun pauseDrive(drive: String) {}

  /** `POST /v1/coach/drive/resume {drive}`: the person tapped Continue. Null when tafs cloud said nothing of use. */
  suspend fun resumeDrive(drive: String): DriveResumed? = null

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

  /**
   * Does one action, after checking it may (the confirm mark), and says what came of it in the app's words. A pause stops it
   * before its next event: one that dispatched nothing is refused ("Paused"), one that already acted answers as it would have.
   */
  suspend fun act(action: Action): Acted

  /** Continue: the keyboard goes away, and the field keeps its text and loses focus, so nothing it commits later reads as typed. */
  fun putKeyboardAway() {}

  /** Waits at most [timeoutMs] for one of the app's events (docs/drive.md, `wait`); true when it came. */
  suspend fun await(event: String, timeoutMs: Long): Boolean

  /** The drive changed: draw the border, the caption and the dot, or drop them. Called on the main thread's state. */
  fun show(state: DriveState)
}

/** Where the person's drive stands on this phone. */
sealed interface DriveState {
  data object Off : DriveState

  /**
   * Coach is driving: [caption] is the step it's on, in its words. [paused] while the person uses the app themselves: Coach does
   * nothing until Continue.
   */
  data class On(val drive: DriveInfo, val caption: String? = null, val paused: Boolean = false) : DriveState

  /** It ended, and never comes back: [words] say why, for the caption and the chat. */
  data class Over(val drive: DriveInfo, val why: String, val words: String) : DriveState
}

/**
 * The person's drive on this phone (docs/drive.md): follows its stream, does each request in turn and answers it. The person's
 * touch pauses it at once, here, whatever tafs cloud says: every request is refused ("Paused") until Continue. It ends at once
 * on a clear takeover (their back, their typing, their third tap on a control since the pause), Stop, the app leaving the
 * screen, a lock, a sign-out, or tafs cloud's `ended`. An ended drive never comes back. Nothing is done after the end, and
 * nothing is sent but the stop.
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

  /** True while the person has paused the drive: Coach does nothing until Continue. */
  val paused: Boolean get() = (_state.value as? DriveState.On)?.paused == true

  /** The person's taps on a control since the drive paused, the pausing one included; a third ends it. */
  private var taps = 0

  /** The pause on its way to tafs cloud, posted again until it lands, Continue comes, or the drive ends. */
  private var pausing: Job? = null

  /** A step of Coach's under way: a field that changes meanwhile is Coach's, any other change the person's. */
  @Volatile
  var stepInFlight: Boolean = false
    private set

  private var job: Job? = null

  /** The person's stop on its way to tafs cloud: its end is in Coach's chat once it lands. */
  private var stopping: Job? = null

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
    stopping = scope.launch(NonCancellable) { runCatching { api.stopDrive(on.drive.id, why) } }
    return true
  }

  /**
   * The person touched the app: the drive pauses at once, and their touch is theirs. tafs cloud is told, again with a backoff
   * until it lands, so it stops sending and Coach stops calling. The pause here stands meanwhile, since a request already on its
   * way must find the app paused. False when no drive is on.
   */
  fun pause(): Boolean {
    val on = _state.value as? DriveState.On ?: return false
    if (on.paused) return true
    taps = 0
    set(on.copy(paused = true))
    pausing?.cancel()
    pausing = scope.launch {
      var wait = PAUSE_RETRY_FIRST_MS
      while (stillPaused(on.drive)) {
        if (runCatching { api.pauseDrive(on.drive.id) }.isSuccess) break
        delay(wait)
        wait = minOf(wait * 2, PAUSE_RETRY_MAX_MS)
      }
    }
    return true
  }

  private fun stillPaused(drive: DriveInfo): Boolean = (_state.value as? DriveState.On)?.let { it.drive.id == drive.id && it.paused } == true

  /** Continue: the keyboard goes away first, then Coach goes on. Its next step is a look, which tafs cloud requires. */
  fun resume() {
    val on = _state.value as? DriveState.On ?: return
    if (!on.paused) return
    host.putKeyboardAway()
    taps = 0
    pausing?.cancel()
    pausing = null
    set(on.copy(paused = false))
    scope.launch(NonCancellable) {
      // On a halted account tafs cloud ends the drive instead: it's over, in tafs cloud's words, and nothing is sent back.
      val answer = runCatching { api.resumeDrive(on.drive.id) }.getOrNull()
      answer?.ended?.takeIf { !answer.resumed }?.let { end(on.drive, it.why, it.words) }
    }
  }

  /**
   * One of the person's taps ended, [onControl] when it landed on something that acts (a button, a row, a toggle, a link, a
   * tab). The tap that paused counts too. A third with no Continue between is a clear takeover, and ends the drive.
   */
  fun personsTap(onControl: Boolean) {
    if (!paused || !onControl) return
    taps++
    if (taps >= TAKEOVER_TAPS) stop("owner_took_over")
  }

  /** Waits for the person's stop to reach tafs cloud, if one is on its way: then the drive's end is in Coach's chat. */
  suspend fun settled() {
    stopping?.join()
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
    taps = 0
    pausing?.cancel()
    pausing = null
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
    // Paused: nothing is done while the person uses the app, whatever tafs cloud sent before it heard.
    if (paused) {
      answer(drive, request, DriveAnswer.refused(DriveWords.PAUSED))
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
    (_state.value as? DriveState.On)?.takeIf { it.drive.id == drive.id && !it.paused && action.line.isNotEmpty() }?.let { set(it.copy(caption = action.line)) }
    val acted = host.act(action)
    // Every answer carries the screen after the step.
    val after = host.look(tree = false, diagnostics = false)
    return DriveAnswer(if (acted.done) "done" else "refused", acted.line, after.image, touched = acted.touched)
  }

  private suspend fun answer(drive: DriveInfo, request: DriveRequest, answer: DriveAnswer) {
    // An answer that doesn't arrive is Coach's to notice: it looks again.
    runCatching { api.driveAnswer(drive.id, request.id, answer) }
  }
}

/** The app's own words in a drive (docs/drive.md, "The app's lines"), the same on iOS. */
object DriveWords {
  val CONFIRM: String get() = Words.Drive.yoursToConfirm
  val TOO_LATE: String get() = Words.Drive.tooLate
  val NOTHING_THERE: String get() = Words.Drive.nothingThere
  val NOT_ON_SCREEN: String get() = Words.Drive.notOnScreen
  val NO_FIELD: String get() = Words.Drive.noField
  val SECRET_FIELD: String get() = Words.Drive.yoursToFill
  val NOTHING_BACK: String get() = Words.Drive.nothingBack
  val UNKNOWN: String get() = Words.Drive.cantDoStep
  val SWIPED: String get() = Words.Drive.swiped
  val WENT_BACK: String get() = Words.Drive.wentBack
  val DONE: String get() = Words.Drive.done
  val PAUSED: String get() = Words.Drive.paused

  val DRIVE_CARD: String get() = Words.Drive.cardTitle
  val DRIVE_NOTE: String get() = Words.Drive.cardNotePauses
  val WHAT_I_DID: String get() = Words.Drive.whatIDid
  val NO_STEPS: String get() = Words.Drive.noSteps

  /** What the fingerprint prompt says when the person approves a drive. */
  fun approval(task: String) = Words.Drive.approval(task = task).take(120)

  fun tapped(words: String?) = words?.let { Words.Drive.tappedOn(what = it) } ?: Words.Drive.tapped

  fun longPressed(words: String?) = words?.let { Words.Drive.longPressedOn(what = it) } ?: Words.Drive.longPressed

  fun scrolled(direction: String) = Words.Drive.scrolled(direction = direction)

  fun cantScroll(direction: String) = Words.Drive.cantScroll(direction = direction)

  fun typed(field: String?) = field?.let { Words.Drive.typedInto(field = it) } ?: Words.Drive.typed

  fun failed(why: String?) = Words.Drive.stepFailed + (why?.takeIf(String::isNotBlank)?.let { ": ${it.take(120)}" } ?: "")

  fun looked(waited: Waited?): String = when {
    waited == null -> Words.Drive.looked
    waited.came -> Words.Drive.lookedOnceCame(event = waited.event)
    else -> Words.Drive.lookedDidntCome(event = waited.event)
  }

  /** The caption once it's over, when tafs cloud's words didn't come: the same words tafs cloud keeps. */
  fun ended(why: String): String = when (why) {
    "owner_took_over" -> Words.Drive.Ended.ownerTookOver
    "left_app" -> Words.Drive.Ended.leftApp
    "stopped" -> Words.Drive.Ended.stopped
    "locked" -> Words.Drive.Ended.locked
    "signed_out" -> Words.Drive.Ended.signedOut
    "halted" -> Words.Drive.Ended.halted
    "done" -> Words.Drive.Ended.done
    "idle" -> Words.Drive.Ended.idle
    "replaced" -> Words.Drive.Ended.replaced
    else -> Words.Drive.Ended.other
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

/** A clear takeover: the person's third tap on a control since the drive paused, with no Continue between. */
const val TAKEOVER_TAPS = 3

/** A pause tafs cloud didn't take is posted again after this, doubling each time up to [PAUSE_RETRY_MAX_MS]. */
const val PAUSE_RETRY_FIRST_MS = 500L
const val PAUSE_RETRY_MAX_MS = 8_000L
