package bot.tafs.app.model

import java.util.Locale
import java.util.ServiceLoader

/**
 * The app's words, read from the `strings` files under `res/values` and its language folders, in the app's language (docs/languages.md): what the person reads,
 * never what a server reads. JSON keys, codes, tool names and anything sent to a server stay in code.
 *
 * The app installs its resources at start ([TafsApplication]). Unit tests run on the JVM without them, so there the words come
 * from the English `strings*.xml` themselves, through the one [Source] a test's service file names: every test still checks the
 * exact English words.
 */
object Res {
  /** Where the words come from: the app's resources, or, in a unit test, the English files read directly. */
  interface Source {
    /** The string as written, before any formatting. */
    fun string(id: Int): String

    /** The plural's form for [count], before any formatting. */
    fun plural(id: Int, count: Int): String

    /** The language numbers in the words are formatted for, as Android's own `getString` does. */
    val locale: Locale
  }

  @Volatile private var installed: Source? = null

  /** Sets where the words come from; the app does this once, before anything is shown. */
  fun install(source: Source) {
    installed = source
  }

  private val loaded: Source by lazy {
    ServiceLoader.load(Source::class.java).firstOrNull() ?: error("No words: the app installs its resources at start (Res.install)")
  }

  private val source: Source get() = installed ?: loaded

  /** A string with no arguments, as written. */
  fun s(id: Int): String = source.string(id)

  /** A string with its arguments (`%1$s`, `%2$d`), formatted as Android's `getString(id, args)` does. */
  fun s(id: Int, vararg args: Any?): String = source.let { String.format(it.locale, it.string(id), *args) }

  /** A plural's form for [count], with its arguments: [count] itself is the first unless the words say otherwise. */
  fun p(id: Int, count: Int, vararg args: Any?): String = source.let { String.format(it.locale, it.plural(id, count), *args) }
}
