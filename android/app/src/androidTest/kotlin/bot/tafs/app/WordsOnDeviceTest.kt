package bot.tafs.app

import androidx.test.platform.app.InstrumentationRegistry
import bot.tafs.app.design.AndroidWords
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The words through the phone's own resources and formatters (docs/android-app.md, Words and languages): the language the
 * lines are in, a list, an ordinal, a count's number and a possessive, as the unit tests' English stand-in says them.
 */
class WordsOnDeviceTest {
  @Before fun install() {
    Res.install(AndroidWords(InstrumentationRegistry.getInstrumentation().targetContext))
  }

  @Test fun englishLinesSayTheirLanguageAndWriteItsListsNumbersAndPossessives() {
    assertEquals("en", Res.language)
    assertEquals("Refunds", Res.list(listOf("Refunds")))
    assertEquals("Refunds and Bills", Res.list(listOf("Refunds", "Bills")))
    assertEquals("Refunds, Bills, and Save", Res.list(listOf("Refunds", "Bills", "Save")))
    assertEquals(listOf("1st", "2nd", "3rd", "11th", "21st", "112th"), listOf(1L, 2L, 3L, 11L, 21L, 112L).map(Res::ordinal))
    assertEquals("1,000", Res.number(1000))
    assertEquals("Refunds'", Words.possessive("Refunds"))
    assertEquals("Coach's", Words.possessive("Coach"))
    assertEquals("1 call a day", Words.Card.callsWindow(count = 1, window = "a day"))
  }
}
