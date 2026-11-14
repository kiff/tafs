package bot.tafs.app

import bot.tafs.app.kiff.inAppLanguage
import bot.tafs.app.model.Legal
import org.junit.Assert.assertEquals
import org.junit.Test

/** The legal links open tafs's own pages, the ones Google's consent screen names, never KIFF's. */
class LegalTest {
  // The owner on Android 95: Terms and Privacy opened KIFF's pages, not tafs's own.
  @Test fun theLinksOpenTafssOwnPages() {
    assertEquals("https://kiff.dev/tafs/terms", Legal.terms())
    assertEquals("https://kiff.dev/tafs/privacy", Legal.privacy())
  }

  @Test fun kiffsApprovalLinkNamesTheAppsLanguageOnce() {
    fun start(link: String) = bot.tafs.app.kiff.DeviceStart("dc", "X", "u", link, 600, 5)
    assertEquals("https://app.kiff.dev/auth/device?code=X&lang=de", start("https://app.kiff.dev/auth/device?code=X").inAppLanguage("de").verificationUriComplete)
    assertEquals("https://app.kiff.dev/auth/device?lang=pl", start("https://app.kiff.dev/auth/device").inAppLanguage("pl").verificationUriComplete)
    // A link KIFF already gave a language keeps it.
    assertEquals("https://app.kiff.dev/auth/device?code=X&lang=fr", start("https://app.kiff.dev/auth/device?code=X&lang=fr").inAppLanguage("de").verificationUriComplete)
  }
}
