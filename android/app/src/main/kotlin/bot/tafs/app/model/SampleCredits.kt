package bot.tafs.app.model

import bot.tafs.app.kiff.Balance
import bot.tafs.app.kiff.BalanceNow
import bot.tafs.app.kiff.Credits
import bot.tafs.app.kiff.CreditsNotCounted
import bot.tafs.app.kiff.CreditsPeriod
import bot.tafs.app.kiff.CreditsTaf
import bot.tafs.app.kiff.CreditsWhy
import java.time.Instant

/**
 * The sample's balance and credits, the demo of Plan & usage: words as tafs cloud writes them (docs/accounts.md#credits-and-plans),
 * the same on iPhone. Its tafs' rows don't open: the sample has no runs to read.
 */
object SampleCredits {
  private val NOTE get() = Words.Sample.Credits.note

  val balance get() = Balance(
    balance = BalanceNow(
      state = BalanceNow.OK,
      meterPct = 48,
      words = Words.Sample.Credits.balanceWords,
      detail = Words.Sample.Credits.balanceDetail,
    ),
    planLine = Words.Sample.Credits.planLine,
    planTone = BalanceNow.OK,
  )

  fun credits(previous: Boolean): Credits = if (previous) {
    Credits(
      period = CreditsPeriod(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), current = false),
      credits = 2310, runs = 126,
      words = Words.Sample.Credits.lastPeriod,
      tafs = listOf(
        CreditsTaf("sample-refunds", 1480, 74, Words.Sample.Credits.refundsLast),
        CreditsTaf("sample-invoices", 830, 52, Words.Sample.Credits.invoicesLast),
      ),
      note = NOTE,
    )
  } else {
    Credits(
      period = CreditsPeriod(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z")),
      credits = 1240, runs = 69,
      words = Words.Sample.Credits.thisPeriod,
      tafs = listOf(
        CreditsTaf("sample-refunds", 820, 41, Words.Sample.Credits.refundsThis),
        CreditsTaf("sample-invoices", 420, 28, Words.Sample.Credits.invoicesThis),
      ),
      notCounted = CreditsNotCounted(
        runs = 9, words = Words.Sample.Credits.notCounted,
        lines = listOf(
          CreditsWhy("coach", 6, Words.Sample.Credits.notCountedCoach),
          CreditsWhy("trial", 2, Words.Sample.Credits.notCountedTrials),
          CreditsWhy("failed", 1, Words.Sample.Credits.notCountedFailed),
        ),
      ),
      note = NOTE,
    )
  }
}
