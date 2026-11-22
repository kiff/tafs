package bot.tafs.app

import bot.tafs.app.kiff.inAppLanguage
import bot.tafs.app.model.Legal
import org.junit.Assert.assertEquals
import org.junit.Test

/** The legal links open tafs's own pages, the ones Google's consent screen names, never KIFF's. */
class LegalTest {
  // The owner on Android 95: Terms and Privacy opened KIFF's pages, not tafs's own.
  @Test fun theLinksOpenTafssOwnPages() {
    assertEquals("https://kiff.dev/tafs/terms", Legal.terms("en"))
    assertEquals("https://kiff.dev/tafs/privacy", Legal.privacy("en"))
  }

  // kiff.dev serves them in every language the app ships: each opens its own, and English is the unprefixed page.
  @Test fun eachLanguageOpensItsOwnPage() {
    for (lang in listOf("de", "es", "fr", "it", "nl", "pl", "pt", "ko", "ja", "et", "lt", "lv", "cs", "sk", "sl", "hu", "ro", "bg", "hr", "tr", "id", "vi", "th", "zh", "sr", "sq", "ka", "hy", "az")) {
      assertEquals("https://kiff.dev/$lang/tafs/terms", Legal.terms(lang))
      assertEquals("https://kiff.dev/$lang/tafs/privacy", Legal.privacy(lang))
      // In its own language, so the link doesn't say "in English".
      assertEquals("Terms", Legal.label("Terms", lang))
    }
    // Every shipped language but English is served.
    // Traditional Chinese (zh-Hant) is served under its language, /zh/.
    val shipped = listOf("en", "de", "fr", "es", "it", "pt", "nl", "pl", "ko", "ja", "et", "lt", "lv", "cs", "sk", "sl", "hu", "ro", "bg", "hr", "tr", "id", "vi", "th", "zh", "sr", "sq", "ka", "hy", "az")
    assertEquals(shipped.filter { it != "en" }.toSet(), Legal.SERVED)
  }

  // A language kiff.dev doesn't serve the pages in opens English, and says so, as kiff.dev does.
  // Brazil's lines are pt-BR, and kiff.dev serves Portuguese pages: they open, with no "in English".
  @Test fun traditionalChineseOpensTheChinesePages() {
    assertEquals("https://kiff.dev/zh/tafs/terms", Legal.terms("zh-Hant"))
    assertEquals("https://kiff.dev/zh/privacy", Legal.kiffPrivacy("zh-Hant"))
    assertEquals("Terms", Legal.label("Terms", "zh-Hant"))
  }

  // Serbian ships in Latin script, as sr-Latn; kiff.dev serves it under its language, /sr/.
  @Test fun serbianInLatinScriptOpensTheSerbianPages() {
    assertEquals("https://kiff.dev/sr/tafs/terms", Legal.terms("sr-Latn"))
    assertEquals("https://kiff.dev/sr/privacy", Legal.kiffPrivacy("sr-Latn"))
    assertEquals("Terms", Legal.label("Terms", "sr-Latn"))
  }

  @Test fun aRegionalVariantOpensItsLanguagesPages() {
    assertEquals("https://kiff.dev/pt/tafs/terms", Legal.terms("pt-BR"))
    assertEquals("https://kiff.dev/pt/tafs/privacy", Legal.privacy("pt-BR"))
    assertEquals("https://kiff.dev/pt/terms", Legal.kiffTerms("pt-BR"))
    assertEquals("https://kiff.dev/pt/privacy", Legal.kiffPrivacy("pt-BR"))
    assertEquals("Terms", Legal.label("Terms", "pt-BR"))
  }

  @Test fun aLanguageWithNoPageOpensEnglishAndSaysSo() {
    assertEquals("https://kiff.dev/tafs/terms", Legal.terms("sv"))
    assertEquals("https://kiff.dev/tafs/privacy", Legal.privacy("sv"))
    assertEquals("Terms", Legal.label("Terms", "en"))
    // The lines here resolve to English, so the mark reads as English's own line.
    assertEquals("Terms (in English)", Legal.label("Terms", "sv"))
  }

  @Test fun kiffsApprovalLinkNamesTheAppsLanguageOnce() {
    fun start(link: String) = bot.tafs.app.kiff.DeviceStart("dc", "X", "u", link, 600, 5)
    assertEquals("https://app.kiff.dev/auth/device?code=X&lang=de", start("https://app.kiff.dev/auth/device?code=X").inAppLanguage("de").verificationUriComplete)
    assertEquals("https://app.kiff.dev/auth/device?lang=pl", start("https://app.kiff.dev/auth/device").inAppLanguage("pl").verificationUriComplete)
    // A link KIFF already gave a language keeps it.
    assertEquals("https://app.kiff.dev/auth/device?code=X&lang=fr", start("https://app.kiff.dev/auth/device?code=X&lang=fr").inAppLanguage("de").verificationUriComplete)
  }
}
