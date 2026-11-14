package bot.tafs.app.model

/**
 * tafs's own legal pages on kiff.dev, `/tafs/terms` and `/tafs/privacy`: the ones Google's consent screen names, apart from
 * KIFF's. They're in English only for now (`/de/tafs/terms` isn't served), so every language opens them; a page in the lines'
 * language comes once kiff.dev serves one. docs/android-app.md, Words and languages.
 */
object Legal {
  private const val SITE = "https://kiff.dev/tafs"

  fun terms(): String = "$SITE/terms"

  fun privacy(): String = "$SITE/privacy"
}
