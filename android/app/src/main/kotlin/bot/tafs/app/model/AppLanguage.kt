package bot.tafs.app.model

import java.util.Locale

/**
 * The language tafs's lines are in, as Account shows it: named in itself ("Français", "Polski"), so anyone can find their own.
 * A regional variant is named with its region ("Português (Brasil)"), so it reads apart from its language ("Português"), and
 * Traditional Chinese with its script, so it reads apart from Simplified.
 */
object AppLanguage {
  fun name(tag: String = Res.language): String {
    val locale = Locale.forLanguageTag(tag)
    val name = if (locale.country.isNotEmpty() || locale.script.isNotEmpty()) locale.getDisplayName(locale) else locale.getDisplayLanguage(locale)
    return name.replaceFirstChar { it.titlecase(locale) }
  }
}
