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
   * [language] without its region ("pt" for "pt-BR"): the language KIFF's pages and kiff.dev's are opened in. A regional
   * variant is the app's own lines; `Accept-Language` sends both (`KiffClient.screenLanguage`).
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
  val locale: Locale get() = figures(Locale.forLanguageTag(language))

  /**
   * Languages whose figures the app writes in Western digits, 0–9, though their own locale writes others: Arabic, as its lines and
   * its legal pages write figures (iOS's `Words.localeIdentifiers`). Dates are `java.time`'s, already 0–9
   * and Gregorian.
   */
  internal val LATIN_DIGITS = setOf("ar")

  /** [locale] as the app writes figures in: with Western digits for a language in [LATIN_DIGITS], else as it is. */
  fun figures(locale: Locale): Locale =
    if (locale.language in LATIN_DIGITS) Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "latn").build() else locale

  /** A count's number as the lines' language writes it: "1,000" in English, "1.000" in German. */
  fun number(n: Int): String = java.text.NumberFormat.getIntegerInstance(locale).format(n.toLong())

  /** A string with no arguments, as written. */
  fun s(id: Int): String = source.string(id)

  /** A string with its arguments (`%1$s`, `%2$d`), formatted as Android's `getString(id, args)` does. */
  fun s(id: Int, vararg args: Any?): String = source.let { String.format(figures(it.locale), it.string(id), *isolated(args, it.locale.language)) }

  /** A plural's form for [count], with its arguments: [count] itself is the first unless the words say otherwise. */
  fun p(id: Int, count: Int, vararg args: Any?): String = source.let { String.format(figures(it.locale), it.plural(id, count), *isolated(args, it.locale.language)) }

  /** Whether [language] is written right to left. The rule is the watch's too: `watchlink`'s [bot.tafs.watchlink.Bidi]. */
  fun rightToLeft(language: String): Boolean = bot.tafs.watchlink.Bidi.rightToLeft(language)

  /** Each text value set apart in a right-to-left language, as on the watch ([bot.tafs.watchlink.Bidi.isolated], iOS's #1557). */
  fun isolated(args: Array<out Any?>, language: String): Array<out Any?> = bot.tafs.watchlink.Bidi.isolated(args, language)

  /** The text with its direction characters taken out ([bot.tafs.watchlink.Bidi.withoutDirection]). */
  fun withoutDirection(text: String): String = bot.tafs.watchlink.Bidi.withoutDirection(text)

  /**
   * What the app copies: the text alone, with no direction character, so a code, a key or an address copied from a right-to-left
   * screen (whose values are set apart, [isolated]) pastes as its own characters.
   */
  fun clip(label: String, text: String): android.content.ClipData = android.content.ClipData.newPlainText(label, withoutDirection(text))
}
