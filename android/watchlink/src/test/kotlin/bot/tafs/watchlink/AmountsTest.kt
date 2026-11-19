package bot.tafs.watchlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule the phone's relay and the watch share for what a watch keeps: every amount with a currency out, numbers kept. */
class AmountsTest {
  @Test fun everyAmountWithACurrencyGoesAndOtherNumbersStay() {
    for ((said, kept) in listOf(
      "Refunded €84 for order A-1039" to "Refunded for order A-1039",
      "Two calls wait: a refund of €84 for order A-1039" to "Two calls wait: a refund of for order A-1039",
      "Refund order 1042 EUR 150.00" to "Refund order 1042",
      "Refund order 4502 150 €" to "Refund order 4502",
      "Zwrot 150zł" to "Zwrot",
      "Rückerstattung CHF 1'234.50 an Ana" to "Rückerstattung an Ana",
      "Remboursement de 1 250,50 € à Ana" to "Remboursement de à Ana",
      "Refund 50 euros to Ana" to "Refund to Ana",
      "Ship 3 crates to EUROPE" to "Ship 3 crates to EUROPE",
      "Zamów 4 krzesła" to "Zamów 4 krzesła",
      "Paused until Monday 08:00" to "Paused until Monday 08:00",
    )) {
      assertEquals(said, kept, Amounts.strip(said))
    }
  }

  /** An amount taken out is told apart from spacing tidied: a double space or a trailing ":" has none. */
  @Test fun onlyAnAmountCountsAsTakenOut() {
    assertTrue(Amounts.hasAmount("Refunded €84 for order A-1039"))
    for (plain in listOf("Paused  until Monday", "Here's what I found:", " Ivo is paused, ", "Order 4502 is late")) {
      assertFalse(plain, Amounts.hasAmount(plain))
    }
  }
}
