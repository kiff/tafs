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

/** What the balance offers this person: raising the spend limit, in tafs cloud's words for its button, from the limit now. */
@Serializable
data class BalanceRaise(val words: String, @SerialName("from_cents") val fromCents: Long? = null)

/**
 * The balance now (docs/accounts.md#the-balance-read): `state` ok, warning or waiting, and while waiting `why` (credits_used_up,
 * limit_reached, no_plan); a bar at `meterPct` where something bounds it; and every line in tafs cloud's words.
 */
@Serializable
data class BalanceNow(
  val state: String = OK,
  val why: String? = null,
  @SerialName("meter_pct") val meterPct: Int? = null,
  val words: String,
  val detail: String? = null,
  val warning: String? = null,
  val raise: BalanceRaise? = null,
  /** What the store may offer this person: Solo, packs, or neither. */
  val buy: kotlinx.serialization.json.JsonElement? = null,
  /** Whether Plan & usage shows the Credits panel: false only with no plan and no run lately. A tafs cloud without it: shown. */
  @SerialName("credits_panel") val creditsPanel: Boolean = true,
) {
  val waiting: Boolean get() = state == WAITING

  /** Whether `buy` offers [what] (`solo` or `packs`): true, or an offer named under it. */
  fun offers(what: String): Boolean {
    val offer = (buy as? kotlinx.serialization.json.JsonObject)?.get(what) ?: return false
    if (offer is kotlinx.serialization.json.JsonNull) return false
    return (offer as? kotlinx.serialization.json.JsonPrimitive)?.let { it.isString || it.content == "true" } ?: true
  }

  /**
   * The First month pack (`buy.first_month`, `{ready, words}`): null when not offered, else its state. `ready` true: it can be
   * bought. `ready` false: the pack shows with tafs cloud's `words` for why it waits, and no button.
   */
  val firstMonth: FirstMonth?
    get() {
      if (!offers(FIRST_MONTH)) return null
      val offer = (buy as? kotlinx.serialization.json.JsonObject)?.get(FIRST_MONTH) as? kotlinx.serialization.json.JsonObject
        ?: return FirstMonth(ready = true, words = null)
      val words = (offer["words"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
      val ready = (offer["ready"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
      return FirstMonth(ready = ready, words = words)
    }

  data class FirstMonth(val ready: Boolean, val words: String?)

  /** What Coach's help includes (`buy.coach`), tafs cloud's sentence for under the Solo offer; null when it sent none. */
  val coachIncluded: String?
    get() = ((buy as? kotlinx.serialization.json.JsonObject)?.get("coach") as? kotlinx.serialization.json.JsonPrimitive)
      ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

  companion object {
    const val OK = "ok"
    const val WARNING = "warning"
    const val WAITING = "waiting"
    const val FIRST_MONTH = "first_month"
  }
}

/** `GET /v1/usage/balance`: the balance, Settings' Plan card line in its tone, and Home's line when the plan needs the person. */
@Serializable
data class Balance(
  val balance: BalanceNow,
  @SerialName("plan_line") val planLine: String,
  @SerialName("plan_tone") val planTone: String = BalanceNow.OK,
  @SerialName("home_line") val homeLine: String? = null,
)
