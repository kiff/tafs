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

    /** [items] joined as [language] lists them: "Refunds, Bills, and Save". */
    fun list(items: List<String>, language: String): String

    /** [n] as [language] writes it in order: "21st", "21.", "21e". */
    fun ordinal(n: Long, language: String): String
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

  /**
   * The language the lines are in, as Android resolved them (each generated folder names its own): a possessive and a count's
   * number follow it, never the phone's setting, which may be a language tafs doesn't ship and show English lines.
   */
  val language: String get() = source.string(bot.tafs.app.R.string.words_language)

  /**
   * [language] without its region ("pt" for "pt-BR"): the language KIFF's pages, kiff.dev's, tafs cloud and planque answer in.
   * A regional variant is the app's own lines only; the services speak its language.
   */
  val pageLanguage: String get() = language.substringBefore('-')

  /** Names joined as the lines' language lists them: "Refunds and Bills", "Refunds, Bills, and Save". */
  fun list(items: List<String>): String = source.list(items, language)

  /** A number in order, as the lines' language writes it: "21st" in English, "21." in German. */
  fun ordinal(n: Long): String = source.ordinal(n, language)

  /**
   * The lines' language as a locale, for numbers and money: an English app on a French phone writes "€2,000", a French app
   * "2 000 €", whatever the phone's region.
   */
  val locale: Locale get() = Locale.forLanguageTag(language)

  /** A count's number as the lines' language writes it: "1,000" in English, "1.000" in German. */
  fun number(n: Int): String = java.text.NumberFormat.getIntegerInstance(locale).format(n.toLong())

  /** A string with no arguments, as written. */
  fun s(id: Int): String = source.string(id)

  /** A string with its arguments (`%1$s`, `%2$d`), formatted as Android's `getString(id, args)` does. */
  fun s(id: Int, vararg args: Any?): String = source.let { String.format(it.locale, it.string(id), *isolated(args, it.locale.language)) }

  /** A plural's form for [count], with its arguments: [count] itself is the first unless the words say otherwise. */
  fun p(id: Int, count: Int, vararg args: Any?): String = source.let { String.format(it.locale, it.plural(id, count), *isolated(args, it.locale.language)) }

  /** The languages written right to left (their codes as `languages.json` and Android name them). */
  private val RIGHT_TO_LEFT = setOf("ar", "he", "iw", "fa", "ur", "yi", "ji", "ps", "sd", "ug", "dv", "ckb")

  fun rightToLeft(language: String): Boolean = language.substringBefore('-').substringBefore('_').lowercase() in RIGHT_TO_LEFT

  /**
   * In a right-to-left language each text value is set apart (U+2068 FIRST STRONG ISOLATE … U+2069 POP DIRECTIONAL ISOLATE),
   * so a taf's or person's name in Latin letters takes its own direction without turning the sentence around it, as iOS's
   * `Words.isolated` (#1557). Inside, the value carries no direction character of its own, so none can close it early, whoever
   * calls. Left to right, and for numbers, values are exactly as passed.
   */
  fun isolated(args: Array<out Any?>, language: String): Array<out Any?> =
    if (!rightToLeft(language)) args else Array(args.size) { i -> (args[i] as? CharSequence)?.let { "\u2068${withoutDirection(it.toString())}\u2069" } ?: args[i] }

  /** The text with U+202A–U+202E, U+2066–U+2069, U+200E, U+200F and U+061C taken out, and nothing else changed. */
  fun withoutDirection(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
      val c = text.codePointAt(i)
      if (!(c in 0x202A..0x202E || c in 0x2066..0x2069 || c == 0x200E || c == 0x200F || c == 0x061C)) out.appendCodePoint(c)
      i += Character.charCount(c)
    }
    return out.toString()
  }

  /**
   * What the app copies: the text alone, with no direction character, so a code, a key or an address copied from a right-to-left
   * screen (whose values are set apart, [isolated]) pastes as its own characters (qa-kiff, #1557).
   */
  fun clip(label: String, text: String): android.content.ClipData = android.content.ClipData.newPlainText(label, withoutDirection(text))
}
