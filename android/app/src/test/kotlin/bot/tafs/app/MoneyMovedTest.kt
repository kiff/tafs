package bot.tafs.app

import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.kiff.ActivityAmount
import bot.tafs.app.kiff.ActivityCall
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.kiff.ToolOutcome
import bot.tafs.app.model.ReceiptWords
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.views.RecordFilter
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "Money moved" only when KIFF says so (its `payment`, or tafs cloud's `money_moved`): an amount alone, such as an invoice written
 * or a transfer requested, moved nothing, so its receipt says "Done." and the Record's Money moved filter leaves it out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MoneyMovedTest {
  @Before fun words() {
    RuntimeEnvironment.setQualifiers("en")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
  }

  private fun entry(moved: Boolean?, kind: RecordEntry.Kind = RecordEntry.Kind.done) =
    RecordEntry(tafId = "t", kind = kind, title = "Refund a charge", detail = "", at = Instant.parse("2026-11-27T00:00:00Z"), amountText = "€30.00", moneyMoved = moved)

  @Test fun aPaymentKiffMarksReadsMovedAndCountsUnderMoneyMoved() {
    assertEquals(Words.Receipt.Outcome.moved("€30.00"), ReceiptWords.outcome(entry(true)))
    assertTrue(RecordFilter.MoneyMoved.keeps(entry(true)))
  }

  @Test fun anAmountAloneMovedNothing() {
    assertEquals(Words.Receipt.Outcome.done, ReceiptWords.outcome(entry(null)))
    assertFalse(RecordFilter.MoneyMoved.keeps(entry(null)))
    // A held payment moved nothing yet, whatever its flag.
    assertFalse(RecordFilter.MoneyMoved.keeps(entry(true, RecordEntry.Kind.held)))
  }

  @Test fun kiffsOwnPaymentFlagSetsItOnTheRecordAndNothingElseDoes() {
    fun call(id: String, payment: Boolean?) = ActivityCall(proposalId = id, actionName = "refund_charge", actorId = "taf_1", outcome = "allowed",
      createdAt = Instant.parse("2026-11-27T00:00:00Z"), toolOutcome = ToolOutcome(status = "done"), amount = ActivityAmount("30.00", "EUR"), payment = payment)
    val record = KiffMapping.record(listOf(call("p1", true), call("p2", null)), holders = setOf("taf_1"))
    assertEquals(listOf(true, null), record.map { it.moneyMoved })
  }
}
