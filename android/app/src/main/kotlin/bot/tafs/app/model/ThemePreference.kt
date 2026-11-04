package bot.tafs.app.model

/** A choice for this screen only; it changes no account setting. */
enum class ThemePreference(val stored: String, val title: String) {
  Light("light", "Light"), Dark("dark", "Dark"), System("system", "System");
  fun dark(systemDark: Boolean): Boolean = when (this) { Light -> false; Dark -> true; System -> systemDark }
  companion object { fun from(stored: String?) = entries.firstOrNull { it.stored == stored } ?: System }
}
