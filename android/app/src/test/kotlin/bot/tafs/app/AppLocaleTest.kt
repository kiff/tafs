package bot.tafs.app

import bot.tafs.app.design.AppLocale
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Account › Language's own picker (docs/android-app.md, "Words and languages"): the choice kept on Android 11 and 12, and the list. */
class AppLocaleTest {
  private class Kept(var tag: String? = null) : AppLocale.Store {
    override fun get(): String? = tag
    override fun set(tag: String?) { this.tag = tag }
  }

  @Test fun theKeptChoiceIsReadBack() {
    val kept = Kept()
    assertTrue(AppLocale.keep(kept, "fr"))
    assertEquals("fr", AppLocale.stored(kept))
    assertTrue(AppLocale.keep(kept, "pl"))
    assertEquals("pl", AppLocale.stored(kept))
    // The same one again changes nothing, so the screen isn't recreated for it.
    assertFalse(AppLocale.keep(kept, "pl"))
  }

  @Test fun thePhonesLanguageClearsIt() {
    val kept = Kept("de")
    assertTrue(AppLocale.keep(kept, null))
    assertNull(kept.tag)
    assertNull(AppLocale.stored(kept))
    assertFalse(AppLocale.keep(kept, null))
  }

  @Test fun aLanguageTafsDoesntShipIsIgnored() {
    val kept = Kept("it")
    assertFalse(AppLocale.keep(kept, "tr"))
    assertFalse(AppLocale.keep(kept, ""))
    assertEquals("it", kept.tag)
    // One kept somehow reads as the phone's language.
    assertNull(AppLocale.stored(Kept("xx")))
    assertNull(AppLocale.known("zz-ZZ"))
    // A region is the language's: Android 13+ may keep "de-DE".
    assertEquals("de", AppLocale.known("de-DE"))
    assertEquals("de", AppLocale.fromLocales("de-DE,fr"))
    assertNull(AppLocale.fromLocales(""))
  }

  // pt-BR ships beside pt: Brazil keeps its own lines, and any other region of Portuguese reads pt's.
  @Test fun aRegionalVariantTafsShipsIsKeptAsItself() {
    assertEquals("pt-BR", AppLocale.known("pt-BR"))
    assertEquals("pt-BR", AppLocale.known("pt_BR"))
    assertEquals("pt-BR", AppLocale.fromLocales("pt-BR,en"))
    assertEquals("pt", AppLocale.known("pt-PT"))
    assertEquals("pt", AppLocale.known("pt"))
    assertEquals("es", AppLocale.known("es-MX"))
    val kept = Kept("pt")
    assertTrue(AppLocale.keep(kept, "pt-BR"))
    assertEquals("pt-BR", AppLocale.stored(kept))
    assertTrue(AppLocale.keep(kept, "pt"))
    assertEquals("pt", AppLocale.stored(kept))
  }

  @Test fun theListIsThePhonesLanguageThenTheShippedOnesInOrderNamedInThemselves() {
    val rows = AppLocale.choices(device = "phone")
    assertEquals(12, rows.size)
    assertEquals(AppLocale.Choice(null, "This phone's language"), rows.first())
    assertEquals(
      listOf("en" to "English", "de" to "Deutsch", "fr" to "Français", "es" to "Español", "it" to "Italiano", "pt" to "Português", "nl" to "Nederlands", "pl" to "Polski", "ko" to "한국어", "ja" to "日本語", "pt-BR" to "Português (Brasil)"),
      rows.drop(1).map { it.tag to it.title },
    )
    assertEquals("This tablet's language", AppLocale.choices(device = "tablet").first().title)
  }

  @Test fun theEightAreTheGeneratedLocalesInTheirOrder() {
    val declared = Regex("""android:name="([A-Za-z-]+)"""").findAll(File("src/main/res/xml/locales_config.xml").readText()).map { it.groupValues[1] }.toList()
    assertEquals(declared, AppLocale.TAGS)
  }
}
