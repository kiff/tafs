package bot.tafs.app

import bot.tafs.app.views.opensWords
import bot.tafs.app.views.signInWords
import bot.tafs.app.views.waitingWords
import org.junit.Assert.assertEquals
import org.junit.Test

/** A sign-in card names the app: the owner's read "Sign in at the vendor" for Gmail. */
class SignInCardWordsTest {
  @Test fun theCardNamesTheAppFromItsServiceElseItsConnection() {
    assertEquals("Sign in at Stripe", signInWords("Stripe", "Gmail"))
    assertEquals("Sign in at Gmail", signInWords(null, "Gmail"))
    assertEquals("Sign in at Gmail", signInWords(" ", "Gmail"))
    assertEquals("Sign in at the vendor", signInWords(null, null))
  }

  // The owner on Android 97: "If the vendor's sign-in page opened…" and "Opens the vendor's page" for Gmail.
  @Test fun theLinesUnderASignInNameTheApp() {
    assertEquals("If Gmail's sign-in page opened, finish it there and come back.", waitingWords("Gmail"))
    assertEquals("If the vendor's sign-in page opened, finish it there and come back.", waitingWords(null))
    assertEquals("Opens Gmail's sign-in", opensWords(null, "Gmail", "the vendor's page"))
    // A service the card names is shown as it is: a host the owner can check.
    assertEquals("Opens mcp.stripe.com", opensWords("mcp.stripe.com", "Stripe", "the vendor's page"))
    assertEquals("Opens the vendor's page", opensWords(null, null, "the vendor's page"))
  }
}
