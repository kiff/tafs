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
}
