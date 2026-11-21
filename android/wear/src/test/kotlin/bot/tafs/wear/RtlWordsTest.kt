package bot.tafs.wear

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The watch fills its lines as the phone does (docs/watch-wear.md): in a right-to-left language each text value is set apart,
 * so "Invoices" or "€42.00" in an Arabic line keeps its own direction; left to right, nothing changes.
 */
class RtlWordsTest {
  // A count is written in the language's own digits (String.format with the line's locale: Arabic-Indic for 3, as on the
  // phone); a text value such as an amount is as sent, set apart.
  @Test fun anArabicLineSetsItsValuesApart() =
    assertEquals("\u0627\u0644\u0645\u0647\u0645\u0629 \u2068Invoices\u2069: \u2068€42.00\u2069 · \u0663",
      Res.fill("\u0627\u0644\u0645\u0647\u0645\u0629 %1\$s: %2\$s · %3\$d", "ar", arrayOf<Any?>("Invoices", "€42.00", 3)))

  @Test fun aLeftToRightLineIsAsBefore() =
    assertEquals("Invoices: €42.00 · 3", Res.fill("%1\$s: %2\$s · %3\$d", "en", arrayOf<Any?>("Invoices", "€42.00", 3)))
}
