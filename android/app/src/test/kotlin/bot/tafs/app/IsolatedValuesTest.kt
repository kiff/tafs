package bot.tafs.app

import bot.tafs.app.model.Res
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A line in a right-to-left language sets each text value apart (FSI … PDI), so "Rae" or "€150.00" inside an Arabic or Hebrew
 * sentence keeps its own direction without turning the sentence, as iOS's #1557. Left to right, values are exactly as passed,
 * so no line in today's eight languages changes. What's copied carries no direction character.
 */
class IsolatedValuesTest {
  @Test fun rightToLeftLinesIsolateTheirValues() {
    for (language in listOf("ar", "he", "iw", "fa", "ur")) {
      assertArrayEquals(language, arrayOf<Any?>("\u2068Rae\u2069", "\u2068€150.00\u2069", 3), Res.isolated(arrayOf<Any?>("Rae", "€150.00", 3), language))
    }
  }

  @Test fun leftToRightLinesAreUnchanged() {
    for (language in listOf("en", "de", "fr", "es", "it", "pt", "nl", "pl", "ja", "ko")) {
      val args = arrayOf<Any?>("Rae", "€150.00", 3)
      assertArrayEquals(language, args, Res.isolated(args, language))
      assertFalse(Res.rightToLeft(language))
    }
    assertTrue(Res.rightToLeft("ar-SA"))
  }

  /** A value's own direction characters are taken out inside the isolate, so none can close it early, whoever calls. */
  @Test fun aValueCantCloseItsIsolate() =
    assertArrayEquals(arrayOf<Any?>("\u2068AcmeLTD ok\u2069"), Res.isolated(arrayOf("Acme\u202ELTD \u2069ok\u2066"), "ar"))

  @Test fun whatsCopiedCarriesNone() =
    assertEquals("Rae paid DE89 3704", Res.withoutDirection("\u2068Rae\u2069 paid \u2066DE89 3704\u2069\u200F"))
}
