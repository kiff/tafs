package bot.tafs.app.design

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import bot.tafs.app.model.AppLanguage
import bot.tafs.app.model.Res
import bot.tafs.app.model.ThisDevice
import bot.tafs.app.model.Words
import java.util.Locale

/**
 * The language the person picks for tafs in Account › Language (docs/android-app.md, "Words and languages"): one it ships, or
 * the phone's own (none). Android 13+ keeps the choice itself, as the per-app language (`LocaleManager`), and recreates the
 * screen. On Android 11 and 12 it's kept here, in the app's own preferences: each context is made in it before anything reads
 * its resources (`TafsApplication` and `MainActivity`, `attachBaseContext`), and the screen is recreated after a change. Either
 * way [Res] is installed again at once, so the lines drawn outside the screen change too; the app's model lives in
 * `TafsApplication`, so the session and what's open stay.
 */
object AppLocale {
  /** The languages tafs ships (app/strings/languages.json's `shipped`), in its order, as `locales_config` lists them. */
  val TAGS: List<String> = listOf("en", "de", "fr", "es", "it", "pt", "nl", "pl", "ko")

  /** Where the choice is kept on Android 11 and 12: a tag, or none for the phone's language. */
  interface Store {
    fun get(): String?
    fun set(tag: String?)
  }

  /** A row of the picker: [tag] null is the phone's language. */
  data class Choice(val tag: String?, val title: String)

  /** The picker's rows: the phone's language first, then each it ships, named in itself. */
  fun choices(device: String = ThisDevice.word): List<Choice> =
    listOf(Choice(null, Words.Settings.languageDevice(device))) + TAGS.map { Choice(it, AppLanguage.name(it)) }

  /** [tag] as one of the 8 ("de-DE" is "de"), or null for a language tafs doesn't ship. */
  fun known(tag: String?): String? {
    if (tag.isNullOrBlank()) return null
    return Locale.forLanguageTag(tag.trim()).language.takeIf { it in TAGS }
  }

  /** The kept choice; none (the phone's language) when nothing, or a tag tafs doesn't ship, is kept. */
  fun stored(store: Store): String? = known(store.get())

  /**
   * Keeps [tag], or clears the choice for the phone's language (null). A tag tafs doesn't ship changes nothing. True when the
   * choice is now another one, so the screen is recreated.
   */
  fun keep(store: Store, tag: String?): Boolean {
    val next = if (tag == null) null else known(tag) ?: return false
    val changed = stored(store) != next
    if (store.get() != next) store.set(next)
    return changed
  }

  /** The app's own languages as Android 13+ keeps them (`LocaleList.toLanguageTags`): the first, if tafs ships it; none when empty. */
  fun fromLocales(tags: String): String? = known(tags.split(',').firstOrNull())

  private const val FILE = "tafs.display"
  private const val KEY = "language"

  /** The app's own preferences, where Android 11 and 12 keep the choice. */
  fun preferences(context: Context): Store = object : Store {
    private val file = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    override fun get(): String? = file.getString(KEY, null)
    // Written at once: the screen is recreated right after, and reads it as it's made.
    override fun set(tag: String?) {
      file.edit().apply { if (tag == null) remove(KEY) else putString(KEY, tag) }.commit()
    }
  }

  /** The choice now, for the picker's mark: Android 13+'s per-app language, else the kept one; null is the phone's language. */
  fun current(context: Context): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      fromLocales(context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty())
    } else {
      runCatching { stored(preferences(context)) }.getOrNull()
    }

  /** [base] in the kept language on Android 11 and 12; as it is on 13+, where Android does this, or with none kept. */
  fun wrap(base: Context): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
    val tag = runCatching { stored(preferences(base)) }.getOrNull() ?: return base
    return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) })
  }

  /**
   * A choice kept on Android 11 or 12 before the phone moved to 13: handed to Android's per-app language once, so the app keeps
   * the person's language, and forgotten here.
   */
  fun carryOver(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    runCatching {
      val store = preferences(context)
      if (store.get() == null) return
      val tag = stored(store)
      val locales = context.getSystemService(LocaleManager::class.java)
      if (tag != null && locales != null && locales.applicationLocales.isEmpty) locales.applicationLocales = LocaleList.forLanguageTags(tag)
      store.set(null)
    }
  }

  /**
   * Applies [tag], or the phone's language for null, at once: the words are installed again in it, and the screen is recreated
   * in it (by Android on 13+, here on 11 and 12). A tag tafs doesn't ship, or the choice already made, changes nothing.
   */
  fun choose(activity: Activity, tag: String?) {
    val next = if (tag == null) null else known(tag) ?: return
    if (next == current(activity)) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Res.install(words(activity, next))
      // Android keeps it, and recreates the screen in it: MainActivity installs the words again from its own configuration.
      activity.getSystemService(LocaleManager::class.java)?.applicationLocales = next?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
    } else {
      keep(preferences(activity), next)
      Res.install(words(activity, next))
      activity.recreate()
    }
  }

  /** The words in [tag], or in the phone's languages for none, from the application's context as `AndroidWords.of` makes them. */
  private fun words(context: Context, tag: String?): AndroidWords {
    val locales = if (tag != null) LocaleList.forLanguageTags(tag) else Resources.getSystem().configuration.locales
    val configuration = Configuration(context.resources.configuration).apply { setLocales(locales) }
    return AndroidWords(context.applicationContext.createConfigurationContext(configuration))
  }
}
