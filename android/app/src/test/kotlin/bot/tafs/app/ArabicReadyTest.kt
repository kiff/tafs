package bot.tafs.app

import androidx.compose.ui.unit.TextUnit
import bot.tafs.app.design.eyebrowStyle
import bot.tafs.app.design.ideographic
import bot.tafs.app.model.Res
import java.text.NumberFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android ready for Arabic, before it ships (docs/languages.md): its figures in Western digits, 0–9, as its lines and legal pages
 * write them and as iOS does (#1697), and no tracking that would cut its joined letters apart.
 */
class ArabicReadyTest {
  private val ascii = Regex("^[0-9.,\\s٬  ]+$")

  @Test fun arabicFiguresAreWesternDigits() {
    val arabic = Res.figures(Locale.forLanguageTag("ar"))
    assertEquals("latn", arabic.getUnicodeLocaleType("nu"))
    assertEquals("ar", arabic.language)
    val n = NumberFormat.getIntegerInstance(arabic).format(1234L)
    assertTrue(n, ascii.matches(n))
    assertEquals("1234", n.filter(Char::isDigit))
    // A count in a line, as Res.s and Res.p format it.
    assertEquals("1234", String.format(arabic, "%d", 1234))
    // A region stays: an Arabic phone in Saudi Arabia keeps its region, with Western digits.
    val saudi = Res.figures(Locale.forLanguageTag("ar-SA"))
    assertEquals("SA", saudi.country)
    assertEquals("latn", saudi.getUnicodeLocaleType("nu"))
  }

  @Test fun theArabicLocaleOnItsOwnWouldWriteOtherDigits() {
    // Why the rule exists: the plain locale's digits aren't 0–9 on this JVM's data, so without it a count would read "١٬٢٣٤".
    val plain = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ar")).format(1234L)
    assertNotEquals("1234", plain.filter { it in '0'..'9' })
  }

  @Test fun everyOtherLanguageIsAsItIs() {
    for (tag in listOf("en", "fr", "de-DE", "pt-BR", "zh-Hant", "sr-Latn", "he")) {
      val locale = Locale.forLanguageTag(tag)
      assertEquals(tag, locale, Res.figures(locale))
    }
  }

  @Test fun arabicEyebrowsKeepThePlainFaceUntracked() {
    assertTrue(ideographic("بانتظارك"))
    assertTrue(ideographic("المتبقي 9:57"))
    assertFalse(ideographic("Needs you"))
    // Hebrew's letters don't join: tracking is fine there.
    assertFalse(ideographic("ממתין לך"))
    assertEquals(TextUnit.Unspecified, eyebrowStyle("بانتظارك").letterSpacing)
    assertNotEquals(TextUnit.Unspecified, eyebrowStyle("Needs you").letterSpacing)
  }
}
