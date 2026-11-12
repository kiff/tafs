package bot.tafs.app.model

import java.util.Locale

/** The language tafs's lines are in, as Account shows it: named in itself ("Français", "Polski"), so anyone can find their own. */
object AppLanguage {
  fun name(tag: String = Res.language): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
  }
}
