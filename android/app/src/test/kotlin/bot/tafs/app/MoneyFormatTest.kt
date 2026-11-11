package bot.tafs.app

import bot.tafs.app.model.Money
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Money is written in the app's language, never the phone's region, and keeps its own currency: an English app on a German phone
 * writes "€2,000", not "2.000 €"; dollars stay dollars. (The unit tests' lines are English.)
 */
class MoneyFormatTest {
  private lateinit var phone: Locale

  @Before fun aGermanPhone() {
    phone = Locale.getDefault()
    Locale.setDefault(Locale.GERMANY)
  }

  @After fun restore() = Locale.setDefault(phone)

  @Test fun anEnglishAppOnAGermanPhoneWritesEnglishAmounts() {
    assertEquals("€2,000", Money.of(200_000, "EUR", 2).figure)
    assertEquals("€90.50", Money.of(9_050, "EUR", 2).figure)
  }

  @Test fun eachAmountKeepsItsOwnCurrency() {
    assertEquals("$800", Money.of(80_000, "USD", 2).figure)
  }

  @Test fun aNumberWithNoUnitIsWrittenInTheAppsLanguageToo() {
    assertEquals("12,000", Money.bare(12_000).figure)
  }
}
