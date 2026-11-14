package bot.tafs.app

import bot.tafs.app.views.signInWords
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
}
