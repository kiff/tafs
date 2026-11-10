package bot.tafs.app

import bot.tafs.app.model.Legal
import org.junit.Assert.assertEquals
import org.junit.Test

/** The legal links open kiff.dev's page in the language the app's lines are in. */
class LegalTest {
  @Test fun englishKeepsItsOwnPathsWithTheTafsSection() {
    assertEquals("https://kiff.dev/terms#tafs", Legal.terms())
    assertEquals("https://kiff.dev/privacy#tafs", Legal.privacy())
  }

  @Test fun anotherLanguageTakesItsPrefix() {
    assertEquals("https://kiff.dev/de/terms#tafs", Legal.terms("de"))
    assertEquals("https://kiff.dev/pl/privacy#tafs", Legal.privacy("pl"))
  }
}
