package bot.tafs.app.model

/**
 * tafs's own legal pages on kiff.dev, `/tafs/terms` and `/tafs/privacy`: the ones Google's consent screen names, apart from
 * KIFF's. kiff.dev serves them in each of the app's languages (`/de/tafs/terms`), so a link opens the page in the lines'
 * language, and English (the unprefixed page) for any other. docs/android-app.md, Words and languages.
 */
object Legal {
  private const val SITE = "https://kiff.dev"

  /** The languages kiff.dev serves tafs's legal pages in, besides English (the unprefixed page, and the one that binds). */
  val SERVED = setOf("de", "es", "fr", "it", "nl", "pl", "pt")

  private fun page(name: String, language: String): String =
    if (language in SERVED) "$SITE/$language/tafs/$name" else "$SITE/tafs/$name"

  fun terms(language: String = Res.language): String = page("terms", language)

  fun privacy(language: String = Res.language): String = page("privacy", language)

  /** KIFF's own Terms and Privacy, the account's, beside tafs's (docs/onboarding.md): `/de/terms`, English for any other language. */
  fun kiffTerms(language: String = Res.language): String = kiffPage("terms", language)

  fun kiffPrivacy(language: String = Res.language): String = kiffPage("privacy", language)

  private fun kiffPage(name: String, language: String): String =
    if (language in SERVED) "$SITE/$language/$name" else "$SITE/$name"

  /**
   * A link's words: as they are in English and in a language kiff.dev serves the page in; in any other, saying the page is in
   * English ("… (in English)"), as kiff.dev does.
   */
  fun label(words: String, language: String = Res.language): String =
    if (language == "en" || language in SERVED) words else Words.Settings.legalInEnglish(label = words)
}
