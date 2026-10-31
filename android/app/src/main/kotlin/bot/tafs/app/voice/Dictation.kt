package bot.tafs.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Speaking a message instead of typing it (kiff/tafs#208, as iOS's `Dictation`), in a taf's chat and in Coach. The words go
 * into the message box as they're spoken, and the owner sends them as if typed: dictation never sends anything. Recognition
 * runs on the phone only ([SpeechEngine]), so no audio leaves it; only the text the owner sends reaches tafs cloud.
 */
class Dictation(private val engine: SpeechEngine, private val memory: LanguageMemory = LanguageMemory.None) {
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

  /** The phone's own languages to dictate in, the owner's first (their keyboards', then the phone's): the switch goes through them. */
  val languages: StateFlow<List<String>> get() = engine.languages

  /** The language chosen last, kept for next time; [current] is the one dictated in. */
  private val chosen = MutableStateFlow(memory.load())
  val language: StateFlow<String?> = chosen

  /** The language it dictates in: the one chosen while the phone still has it, else the owner's first; null before it's known. */
  fun current(): String? {
    val have = engine.languages.value
    return chosen.value?.takeIf { it in have } ?: have.firstOrNull()
  }

  /**
   * The next of the owner's languages, as iOS's switch: kept for next time, and while dictating, listening starts again in it,
   * after what was already heard.
   */
  fun switchLanguage(text: String, write: (String) -> Unit) {
    val have = engine.languages.value
    if (have.size < 2) return
    val next = have[(have.indexOf(current()) + 1) % have.size]
    val was = listening
    if (was) stop()
    chosen.value = next
    memory.save(next)
    if (was) start(text, write)
  }

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
    engine.listen(listener, current())
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
      if (quietEnds >= QUIET_ENDS) stop() else engine.listen(this, current())
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
    const val NOT_ON_DEVICE = "This phone can't recognise speech on the phone itself, so the mic doesn't work here: your voice would have to leave it. You can type instead."
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

/**
 * Which language the phone's own recognizer dictates in. Its models are per variant (en-US, en-GB…), and a phone set to a
 * locale with none of its own (English in France, en-FR) still dictates in a variant of its language. Ready now beats
 * downloading: the phone's own locale, then its language's main variant (fr-FR for fr), then en-GB before en-US, then any.
 */
object SpeechLanguage {
  sealed interface Choice {
    /** A language whose model is on the phone: dictation starts with it. */
    data class Use(val tag: String) : Choice

    /** A language the recognizer offers but hasn't downloaded: the phone gets it first. */
    data class Download(val tag: String) : Choice

    /** Nothing in the phone's language. */
    data object None : Choice
  }

  /**
   * The owner's own languages among those the phone dictates in, one form each, theirs first: their keyboards' (where
   * dictation's languages are), then the phone's; every one it has when none of theirs is there. As iOS's `owners`.
   */
  fun owners(installed: List<String>, keyboards: List<String>, preferred: List<String>): List<String> {
    val theirs = keyboards + preferred
    val codes = theirs.map(::code).toSet()
    val all = ordered(installed, theirs)
    return all.filter { code(it) in codes }.ifEmpty { all }
  }

  /** The phone's languages, one form each, the owner's first: for each, the form they use when the phone has it, else another. */
  fun ordered(installed: List<String>, preferred: List<String>): List<String> {
    val have = installed.map { it.replace('_', '-') }
    val out = mutableListOf<String>()
    fun add(tag: String) {
      if (out.none { code(it) == code(tag) }) out += tag
    }
    for (p in preferred.map { it.replace('_', '-') }) {
      (have.firstOrNull { it.equals(p, ignoreCase = true) } ?: bestForm(code(p), have))?.let(::add)
    }
    have.sorted().forEach(::add)
    return out
  }

  /** The form of a language to dictate in when the owner's own isn't on the phone: English, British then American; another, its own country's. */
  fun bestForm(code: String, have: List<String>): String? {
    val forms = have.filter { code(it) == code }.sorted()
    val want = if (code == "en") listOf("en-GB", "en-US") else listOf("$code-${code.uppercase()}")
    return want.firstNotNullOfOrNull { w -> forms.firstOrNull { it.equals(w, ignoreCase = true) } } ?: forms.firstOrNull()
  }

  private fun code(tag: String) = tag.takeWhile { it != '-' && it != '_' }.lowercase()

  /** The switch's label: "FR". */
  fun label(tag: String) = code(tag).uppercase()

  /** A language's own name, as its speakers write it: "Français", "Español", "English". */
  fun name(tag: String): String {
    val locale = java.util.Locale.forLanguageTag(tag.replace('_', '-'))
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
  }

  fun choose(locale: java.util.Locale, installed: List<String>, supported: List<String>): Choice {
    best(locale, installed)?.let { return Choice.Use(it) }
    best(locale, supported)?.let { return Choice.Download(it) }
    return Choice.None
  }

  private fun best(locale: java.util.Locale, tags: List<String>): String? {
    tags.firstOrNull { it.equals(locale.toLanguageTag(), ignoreCase = true) }?.let { return it }
    val language = locale.language.lowercase()
    val same = tags.filter { java.util.Locale.forLanguageTag(it).language.lowercase() == language }
    if (same.isEmpty()) return null
    val order = listOf("$language-${language.uppercase()}", "en-GB", "en-US", language)
    return order.firstNotNullOfOrNull { want -> same.firstOrNull { it.equals(want, ignoreCase = true) } } ?: same.sorted().first()
  }

  /** The owner's words when the phone gets its language first: it can't dictate yet, and the mic works once it's done. */
  fun downloading(language: String) = "This phone is getting $language for dictation. The mic works once it's ready, in a minute or two."

  /** The owner's words when the phone has nothing in their language: nothing to do but type. */
  fun cannot(language: String) = "This phone can't recognise $language on the phone itself, so the mic doesn't work here. You can type instead."
}

/** The phone's speech recognizer, on the phone only: tests use a fake. */
interface SpeechEngine {
  /** It can recognise speech on the phone itself. */
  val available: Boolean

  /** The microphone may be used. */
  val mayListen: Boolean

  /** The languages it dictates in on the phone, the owner's first; none until it knows, and none where it can't say. */
  val languages: StateFlow<List<String>> get() = NO_LANGUAGES

  /** Listens until a pause, in [language] when one is chosen: what it hears so far, then its final words, or why it failed. */
  fun listen(listener: Listener, language: String?)

  fun stop()

  interface Listener {
    fun heard(words: String)
    fun finished(words: String)
    fun failed(why: String)
  }

  companion object {
    val NO_LANGUAGES: StateFlow<List<String>> = MutableStateFlow(emptyList())
  }
}

/** Where the dictation language chosen last is kept, for next time. */
interface LanguageMemory {
  fun load(): String?
  fun save(tag: String)

  object None : LanguageMemory {
    override fun load(): String? = null
    override fun save(tag: String) {}
  }
}
