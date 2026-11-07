package bot.tafs.app.kiff

import bot.tafs.app.model.InstantAsString
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The credits the account's runs used, read-only (docs/accounts.md#credits): tafs cloud's reads, every line in its words. The app
// draws the words as they are and derives nothing from the numbers but their order.

@Serializable
data class CreditsPeriod(
  @Serializable(with = InstantAsString::class) val start: Instant,
  @Serializable(with = InstantAsString::class) val end: Instant,
  val current: Boolean = true,
)

/** One taf's part of the period. `taf` is null on the one row for tafs removed since, which doesn't open. */
@Serializable
data class CreditsTaf(val taf: String? = null, val credits: Int = 0, val runs: Int = 0, val words: String)

/** Coach past its fair use: its runs count, on their own row. */
@Serializable
data class CreditsCoach(val credits: Int = 0, val runs: Int = 0, val words: String)

/** One reason runs weren't counted: `coach`, `trial` or `failed`, and a reason this app doesn't know reads by its words. */
@Serializable
data class CreditsWhy(val why: String = "", val runs: Int = 0, val words: String)

@Serializable
data class CreditsNotCounted(val runs: Int = 0, val words: String, val lines: List<CreditsWhy> = emptyList())

/** `GET /v1/usage/credits`: the period's credits, each taf's, Coach's past its fair use, and what wasn't counted. */
@Serializable
data class Credits(
  val period: CreditsPeriod,
  val credits: Int = 0,
  val runs: Int = 0,
  val words: String,
  val tafs: List<CreditsTaf> = emptyList(),
  val coach: CreditsCoach? = null,
  @SerialName("not_counted") val notCounted: CreditsNotCounted? = null,
  val note: String? = null,
)

/** One run and what it used. `opens`: its row opens the run's request, while kiff/agents keeps the run. */
@Serializable
data class CreditsRun(
  val run: String,
  @Serializable(with = InstantAsString::class) val at: Instant,
  val credits: Int = 0,
  val counted: Boolean = true,
  val why: String? = null,
  val words: String,
  val opens: Boolean = false,
)

/** `GET /v1/usage/credits/tafs/{id}`: one taf's runs in the period, newest first, a page at a time. */
@Serializable
data class TafCredits(
  val period: CreditsPeriod,
  val taf: String,
  val credits: Int = 0,
  val runs: Int = 0,
  val words: String,
  val items: List<CreditsRun> = emptyList(),
  val next: String? = null,
)
