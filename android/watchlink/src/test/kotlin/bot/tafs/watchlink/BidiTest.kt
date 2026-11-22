package bot.tafs.watchlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The right-to-left rule both apps fill their lines through (the phone's `Res`, the watch's `Res`). */
class BidiTest {
  @Test fun rightToLeftLanguagesSetEachTextValueApart() {
    for (language in listOf("ar", "ar-SA", "he", "iw", "fa", "ur")) {
      assertTrue(language, Bidi.rightToLeft(language))
      assertArrayEquals(language, arrayOf<Any?>("\u2068Rae\u2069", "\u2068€150.00\u2069", 3), Bidi.isolated(arrayOf<Any?>("Rae", "€150.00", 3), language))
    }
  }

  @Test fun leftToRightValuesAreAsPassed() {
    for (language in listOf("en", "de", "ja", "et", "lt", "lv", "cs", "pt-BR")) {
      val args = arrayOf<Any?>("Rae", "€150.00", 3)
      assertFalse(language, Bidi.rightToLeft(language))
      assertArrayEquals(language, args, Bidi.isolated(args, language))
    }
  }

  @Test fun aValueCantCloseItsIsolate() =
    assertArrayEquals(arrayOf<Any?>("\u2068AcmeLTD ok\u2069"), Bidi.isolated(arrayOf("Acme\u202ELTD \u2069ok\u2066"), "ar"))

  @Test fun withoutDirectionTakesOutOnlyDirectionCharacters() =
    assertEquals("Rae paid DE89 3704 \u0645", Bidi.withoutDirection("\u2068Rae\u2069 paid \u2066DE89 3704\u2069\u200F \u061C\u0645"))
}
