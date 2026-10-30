package bot.tafs.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Speaking a message instead of typing it (kiff/tafs#208, as iOS's `Dictation`), in a taf's chat and in Coach. The words go
 * into the message box as they're spoken, and the owner sends them as if typed: dictation never sends anything. Recognition
 * runs on the phone only ([SpeechEngine]), so no audio leaves it; only the text the owner sends reaches tafs cloud.
 */
class Dictation(private val engine: SpeechEngine) {
  sealed interface State {
    data object Idle : State
    data object Listening : State

    /** Dictation can't run: why, in the owner's words; [settings] when the phone's settings can change that. */
    data class Blocked(val why: String, val settings: Boolean = false) : State
  }

  private val _state = MutableStateFlow<State>(State.Idle)
  val state: StateFlow<State> = _state

  val listening: Boolean get() = _state.value == State.Listening

  /** The phone can recognise speech on itself, and the microphone may be used. */
  val available: Boolean get() = engine.available
  val mayListen: Boolean get() = engine.mayListen

  /** The box when dictation started, and each finished stretch of speech since: what's heard now goes after it. */
  private var before = ""
  private var write: ((String) -> Unit)? = null

  /** How many times the recognizer ended by itself with nothing heard since: after [QUIET_ENDS], listening stops. */
  private var quietEnds = 0

  /** Starts dictating into the box, or stops. [text] is the box as it is now; [write] puts its new text back. */
  fun toggle(text: String, write: (String) -> Unit) = if (listening) stop() else start(text, write)

  fun start(text: String, write: (String) -> Unit) {
    if (!engine.available) {
      _state.value = State.Blocked(NOT_ON_DEVICE)
      return
    }
    if (!engine.mayListen) {
      _state.value = State.Blocked(NO_MICROPHONE, settings = true)
      return
    }
    before = text
    this.write = write
    quietEnds = 0
    _state.value = State.Listening
    engine.listen(listener)
  }

  /** Stops listening: what was heard stays in the box, unsent. */
  fun stop() {
    if (!listening) return
    _state.value = State.Idle
    write = null
    engine.stop()
  }

  /** The owner read why dictation can't run. */
  fun dismiss() {
    if (_state.value is State.Blocked) _state.value = State.Idle
  }

  private val listener = object : SpeechEngine.Listener {
    override fun heard(words: String) {
      write?.invoke(join(before, words))
    }

    override fun finished(words: String) {
      if (!listening) return
      if (words.isNotBlank()) {
        before = join(before, words)
        write?.invoke(before)
        quietEnds = 0
      } else {
        quietEnds++
      }
      // The recognizer stops after a pause; listening goes on until the owner stops it, or it stays quiet.
      if (quietEnds >= QUIET_ENDS) stop() else engine.listen(this)
    }

    override fun failed(why: String) {
      if (!listening) return
      _state.value = State.Blocked(why)
      write = null
      engine.stop()
    }
  }

  companion object {
    const val QUIET_ENDS = 2
    const val NOT_ON_DEVICE = "This phone can't recognise speech on the phone itself, so tafs doesn't dictate: your voice would have to leave it."
    const val NO_MICROPHONE = "Allow tafs to use the microphone to dictate. Your voice becomes text on this phone and isn't sent anywhere."

    /** What's been heard after what the box had: a space between, unless one is there already. */
    fun join(before: String, heard: String): String {
      val words = heard.trim()
      if (words.isEmpty()) return before
      if (before.isEmpty() || before.last().isWhitespace()) return before + words
      return "$before $words"
    }
  }
}

/** The phone's speech recognizer, on the phone only: tests use a fake. */
interface SpeechEngine {
  /** It can recognise speech on the phone itself. */
  val available: Boolean

  /** The microphone may be used. */
  val mayListen: Boolean

  /** Listens until a pause: what it hears so far, then its final words, or why it failed. */
  fun listen(listener: Listener)

  fun stop()

  interface Listener {
    fun heard(words: String)
    fun finished(words: String)
    fun failed(why: String)
  }
}
