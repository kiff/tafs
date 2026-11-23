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
    assertFalse(AppLocale.keep(kept, "sw"))
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

  // zh-Hant, once it ships: a script, not a region. The phone may name it "zh-Hant", "zh-Hant-TW" or only by its region
  // ("zh-TW", "zh-HK", "zh-MO"); Simplified Chinese ("zh-CN", "zh-Hans", "zh") isn't Traditional, and isn't shipped.
  @Test fun traditionalChineseIsReadByItsScriptOrItsRegion() {
    val shipped = AppLocale.TAGS + "zh-Hant"
    for (tag in listOf("zh-Hant", "zh-Hant-TW", "zh-TW", "zh_TW", "zh-HK", "zh-MO")) assertEquals(tag, "zh-Hant", AppLocale.known(tag, shipped))
    for (tag in listOf("zh", "zh-CN", "zh-Hans", "zh-Hans-CN", "zh-SG")) assertNull(tag, AppLocale.known(tag, shipped))
    // It ships: a phone in Taiwan reads as Traditional Chinese, as tafs ships it.
    assertEquals("zh-Hant", AppLocale.known("zh-TW"))
    assertNull(AppLocale.known("zh-CN"))
    // The other languages read as before.
    assertEquals("pt-BR", AppLocale.known("pt-BR", shipped))
    assertEquals("de", AppLocale.known("de-DE", shipped))
  }

  // Android 11 to 13 name Indonesian "in", its old code, even when the phone's tag is "id"; a kept tag may be either.
  // Batch 4, read as each would ship. Serbian in Latin script ("sr-Latn"): a phone set to it reads it; one set to Serbian alone,
  // which Android writes in Cyrillic, doesn't. Azerbaijani ("az", Latin by default) whether the phone names the script or not.
  // Hebrew by either code.
  @Test fun batchFourIsReadByScriptAndOldCode() {
    val shipped = AppLocale.TAGS + listOf("sr-Latn", "he", "az", "sq", "ka", "hy", "bn")
    for (tag in listOf("sr-Latn", "sr-Latn-RS", "sr-Latn-ME", "sr_Latn_RS")) assertEquals(tag, "sr-Latn", AppLocale.known(tag, shipped))
    for (tag in listOf("sr", "sr-RS", "sr-Cyrl-RS")) assertNull(tag, AppLocale.known(tag, shipped))
    for (tag in listOf("az", "az-AZ", "az-Latn-AZ")) assertEquals(tag, "az", AppLocale.known(tag, shipped))
    for (tag in listOf("he", "he-IL", "iw", "iw-IL")) assertEquals(tag, "he", AppLocale.known(tag, shipped))
    for (tag in listOf("sq-AL", "ka-GE", "hy-AM", "bn-BD")) assertEquals(tag, tag.substringBefore('-'), AppLocale.known(tag, shipped))
  }

  // Batch 5, read as each would ship: plain codes, as the libraries' folders are. Uzbek is Latin by default; a phone that names
  // Cyrillic reads the Latin lines too, as Uzbekistan's official script is Latin. Russian serves Kazakhstan and Kyrgyzstan.
  @Test fun batchFiveIsReadByItsPlainCodes() {
    val shipped = AppLocale.TAGS + listOf("ca", "mn", "ne", "km", "lo", "mk", "tk", "hi", "ru", "ms", "uz")
    for (tag in listOf("ru", "ru-RU", "ru-KZ", "ru-KG")) assertEquals(tag, "ru", AppLocale.known(tag, shipped))
    for (tag in listOf("uz", "uz-UZ", "uz-Latn-UZ", "uz-Cyrl-UZ")) assertEquals(tag, "uz", AppLocale.known(tag, shipped))
    for (tag in listOf("mk-MK", "ca-ES", "ca-AD", "mn-MN", "ne-NP", "km-KH", "lo-LA", "ms-MY", "ms-BN")) assertEquals(tag, tag.substringBefore('-'), AppLocale.known(tag, shipped))
  }

  @Test fun anOldLanguageCodeIsReadAsTheNewOne() {
    for (tag in listOf("id", "in", "in-ID", "in_ID", "id-ID")) assertEquals(tag, "id", AppLocale.known(tag))
    assertEquals("id", AppLocale.fromLocales("in-ID,en-US"))
    // Hebrew and Yiddish too, once they ship.
    val shipped = AppLocale.TAGS + "he" + "yi"
    assertEquals("he", AppLocale.known("iw-IL", shipped))
    assertEquals("yi", AppLocale.known("ji", shipped))
    // Hebrew ships: a phone that still names it by its old code reads it.
    assertEquals("he", AppLocale.known("iw"))
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
    assertEquals(43, rows.size)
    assertEquals(AppLocale.Choice(null, "This phone's language"), rows.first())
    assertEquals(
      listOf("en" to "English", "de" to "Deutsch", "fr" to "Français", "es" to "Español", "it" to "Italiano", "pt" to "Português", "nl" to "Nederlands", "pl" to "Polski", "ko" to "한국어", "ja" to "日本語", "et" to "Eesti", "lt" to "Lietuvių", "lv" to "Latviešu", "cs" to "Čeština", "sk" to "Slovenčina", "sl" to "Slovenščina", "hu" to "Magyar", "ro" to "Română", "bg" to "Български", "hr" to "Hrvatski", "tr" to "Türkçe", "id" to "Indonesia", "vi" to "Tiếng Việt", "th" to "ไทย", "ar" to "العربية", "sr-Latn" to "Srpski (latinica)", "sq" to "Shqip", "ka" to "ქართული", "hy" to "Հայերեն", "az" to "Azərbaycan", "bn" to "বাংলা", "he" to "עברית", "ca" to "Català", "mk" to "Македонски", "mn" to "Монгол", "ne" to "नेपाली", "km" to "ខ្មែរ", "lo" to "ລາວ", "tk" to "Türkmen dili", "hi" to "हिन्दी", "pt-BR" to "Português (Brasil)"),
      rows.drop(1).filter { it.tag != "zh-Hant" }.map { it.tag to it.title },
    )
    // Traditional Chinese is named with its script, so it doesn't read as Simplified: "中文 (繁體)", whichever brackets the system uses.
    val hant = rows.single { it.tag == "zh-Hant" }.title
    assertTrue(hant, hant.startsWith("中文") && "繁體" in hant)
    assertEquals("This tablet's language", AppLocale.choices(device = "tablet").first().title)
  }

  @Test fun theEightAreTheGeneratedLocalesInTheirOrder() {
    val declared = Regex("""android:name="([A-Za-z-]+)"""").findAll(File("src/main/res/xml/locales_config.xml").readText()).map { it.groupValues[1] }.toList()
    assertEquals(declared, AppLocale.TAGS)
  }
}
