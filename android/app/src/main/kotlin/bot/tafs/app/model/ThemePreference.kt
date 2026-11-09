package bot.tafs.app.model

/** A choice for this screen only; it changes no account setting. */
enum class ThemePreference(val stored: String) {
  Light("light"), Dark("dark"), System("system");
  val title: String get() = when (this) { Light -> Words.Settings.Theme.light; Dark -> Words.Settings.Theme.dark; System -> Words.Settings.Theme.system }
  fun dark(systemDark: Boolean): Boolean = when (this) { Light -> false; Dark -> true; System -> systemDark }
  companion object { fun from(stored: String?) = entries.firstOrNull { it.stored == stored } ?: System }
}
