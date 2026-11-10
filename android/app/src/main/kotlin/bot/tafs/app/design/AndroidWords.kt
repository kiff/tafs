package bot.tafs.app.design

import android.content.Context
import bot.tafs.app.model.Res
import java.util.Locale

/** The app's own resources as [Res]'s source: the words in the language Android resolved for tafs. */
class AndroidWords(private val context: Context) : Res.Source {
  override fun string(id: Int): String = context.resources.getString(id)

  override fun plural(id: Int, count: Int): String = context.resources.getQuantityString(id, count)

  override val locale: Locale get() = context.resources.configuration.locales[0] ?: Locale.getDefault()

  override fun list(items: List<String>, language: String): String =
    android.icu.text.ListFormatter.getInstance(Locale.forLanguageTag(language)).format(items)

  override fun ordinal(n: Long, language: String): String =
    android.icu.text.MessageFormat("{0,ordinal}", Locale.forLanguageTag(language)).format(arrayOf<Any>(n))

  companion object {
    /**
     * The words as a screen resolves them: in the app's own language when the person picked one for tafs (Settings › Apps ›
     * tafs › Language), which can differ from the phone's. The application's context can keep the language the process started
     * with after that choice changes, and once showed a German app's line in English, or in an earlier choice's language. A
     * context made from the screen's configuration, not the screen itself, so nothing holds on to it.
     */
    fun of(screen: Context): AndroidWords =
      AndroidWords(screen.applicationContext.createConfigurationContext(android.content.res.Configuration(screen.resources.configuration)))
  }
}
