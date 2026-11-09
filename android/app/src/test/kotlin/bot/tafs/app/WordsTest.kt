package bot.tafs.app

import bot.tafs.app.model.ExportWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's words in `res/values` (docs/languages.md): every one the code names is there, and reads as written. */
class WordsTest {
  private val english = XmlWords()

  @Test fun everyStringTheCodeNamesIsInTheEnglishFiles() {
    val named = R.string::class.java.fields.map { it.name }.toSet()
    assertEquals(emptySet<String>(), named - english.stringNames)
    val plurals = runCatching { Class.forName("bot.tafs.app.R\$plurals").fields.map { it.name }.toSet() }.getOrDefault(emptySet())
    assertEquals(emptySet<String>(), plurals - english.pluralNames)
  }

  @Test fun everyPluralHasItsOtherForm() {
    for (name in english.pluralNames) assertTrue("$name has no other", english.pluralNamed(name)!!.containsKey("other"))
  }

  @Test fun theWordsReadAsWrittenWithTheirArguments() {
    assertEquals("Export my chats", ExportWords.ROW)
    assertEquals("Your chats weren't exported. No network.", ExportWords.notExported("No network."))
  }

  @Test fun aaptEscapesAndSpacesReadAsOnThePhone() {
    assertEquals("It's \"here\"", XmlWords.aapt("It\\'s \\\"here\\\""))
    assertEquals("a b", XmlWords.aapt("  a \n   b  "))
    assertEquals(" kept  ", XmlWords.aapt("\" kept  \""))
    assertEquals("150 €", XmlWords.aapt("150 €"))
    assertEquals("line\nnext", XmlWords.aapt("line\\nnext"))
  }
}
