package bot.tafs.app.model

/**
 * kiff.dev's legal pages for a tafs user, in the language the app's lines resolved to ([Res.language]), never the phone's
 * own setting: English at its own path (`/terms#tafs`), every other language under its prefix (`/de/terms#tafs`).
 * docs/android-app.md, Words and languages.
 */
object Legal {
  private const val SITE = "https://kiff.dev"

  fun terms(language: String = Res.language): String = page("terms", "tafs", language)

  fun privacy(language: String = Res.language): String = page("privacy", "tafs", language)

  private fun page(path: String, section: String, language: String): String =
    if (language == "en") "$SITE/$path#$section" else "$SITE/$language/$path#$section"
}
