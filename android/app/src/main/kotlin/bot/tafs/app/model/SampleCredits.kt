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
  private const val NOTE = "Each run counts once, when it ends, in whole credits."

  val balance = Balance(
    balance = BalanceNow(
      state = BalanceNow.OK,
      meterPct = 48,
      words = "Solo · 260 of 500 credits left this period",
      detail = "Then 900 in packs, which never expire",
    ),
    planLine = "Solo · 260 credits left",
    planTone = BalanceNow.OK,
  )

  fun credits(previous: Boolean): Credits = if (previous) {
    Credits(
      period = CreditsPeriod(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), current = false),
      credits = 2310, runs = 126,
      words = "Last period: 2,310 credits used · 1 Oct to 31 Oct",
      tafs = listOf(
        CreditsTaf("sample-refunds", 1480, 74, "Refunds · 1,480 credits · 74 runs"),
        CreditsTaf("sample-invoices", 830, 52, "Overdue invoices · 830 credits · 52 runs"),
      ),
      note = NOTE,
    )
  } else {
    Credits(
      period = CreditsPeriod(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z")),
      credits = 1240, runs = 69,
      words = "This period: 1,240 credits used · ends on 30 Nov",
      tafs = listOf(
        CreditsTaf("sample-refunds", 820, 41, "Refunds · 820 credits · 41 runs"),
        CreditsTaf("sample-invoices", 420, 28, "Overdue invoices · 420 credits · 28 runs"),
      ),
      notCounted = CreditsNotCounted(
        runs = 9, words = "Not counted: 9 runs",
        lines = listOf(
          CreditsWhy("coach", 6, "Coach, within its fair use · 6 runs"),
          CreditsWhy("trial", 2, "Trials of a new version · 2 runs"),
          CreditsWhy("failed", 1, "Failed on our side · 1 run"),
        ),
      ),
      note = NOTE,
    )
  }
}
