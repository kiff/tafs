package bot.tafs.app

import bot.tafs.app.kiff.inAppLanguage
import bot.tafs.app.model.Legal
import org.junit.Assert.assertEquals
import org.junit.Test

/** The legal links open kiff.dev's page in the language the app's lines are in. */
class LegalTest {
  @Test fun englishKeepsItsOwnPathsWithTheTafsSection() {
    assertEquals("https://kiff.dev/terms#tafs", Legal.terms())
    assertEquals("https://kiff.dev/privacy#tafs", Legal.privacy())
  }

  @Test fun kiffsApprovalLinkNamesTheAppsLanguageOnce() {
    fun start(link: String) = bot.tafs.app.kiff.DeviceStart("dc", "X", "u", link, 600, 5)
    assertEquals("https://app.kiff.dev/auth/device?code=X&lang=de", start("https://app.kiff.dev/auth/device?code=X").inAppLanguage("de").verificationUriComplete)
    assertEquals("https://app.kiff.dev/auth/device?lang=pl", start("https://app.kiff.dev/auth/device").inAppLanguage("pl").verificationUriComplete)
    // A link KIFF already gave a language keeps it.
    assertEquals("https://app.kiff.dev/auth/device?code=X&lang=fr", start("https://app.kiff.dev/auth/device?code=X&lang=fr").inAppLanguage("de").verificationUriComplete)
  }

  @Test fun anotherLanguageTakesItsPrefix() {
    assertEquals("https://kiff.dev/de/terms#tafs", Legal.terms("de"))
    assertEquals("https://kiff.dev/pl/privacy#tafs", Legal.privacy("pl"))
  }
}
