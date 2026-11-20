package bot.tafs.wear

import android.content.Context
import java.util.Locale

/**
 * The watch's words, read from its `strings` files (generated from app/strings, docs/watch-wear.md) in the watch's language,
 * as the phone's `Res` does. [WatchApplication] installs the context at start.
 */
object Res {
  @Volatile private var context: Context? = null

  fun install(context: Context) {
    this.context = context.applicationContext
  }

  private val resources get() = (context ?: error("No words: the watch installs its resources at start (Res.install)")).resources

  /** The language the lines are in, as Android resolved them: a possessive and a count's number follow it. */
  val language: String get() = resources.getString(R.string.words_language)

  internal val locale: Locale get() = Locale.forLanguageTag(language)

  /** The watch's 12- or 24-hour setting, for a message's time. */
  val is24Hour: Boolean get() = android.text.format.DateFormat.is24HourFormat(context ?: error("No context: Res.install"))

  /** A count's number as the lines' language writes it. */
  fun number(n: Int): String = java.text.NumberFormat.getIntegerInstance(locale).format(n.toLong())

  fun s(id: Int): String = resources.getString(id)

  fun s(id: Int, vararg args: Any?): String = String.format(locale, resources.getString(id), *args)

  fun p(id: Int, count: Int, vararg args: Any?): String = String.format(locale, resources.getQuantityString(id, count), *args)
}
