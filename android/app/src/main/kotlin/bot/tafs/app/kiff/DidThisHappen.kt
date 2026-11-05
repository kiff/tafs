package bot.tafs.app.kiff

import bot.tafs.app.model.Money
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A money call whose outcome KIFF can't prove (`GET /v1/me/did-this-happen`): the answer was lost, or the vendor refused it with
 * nothing proving no effect. It stays counted on its Card until the owner answers **No**; **Yes** keeps it counted.
 */
@Serializable
data class UnsureCall(
  val proposal: String,
  val agent: String = "",
  val tool: String = "",
  val title: String = "",
  /** unknown (the answer was lost), refused_by_tool, or probation (one of a new money operation's first three). */
  val status: String = UNKNOWN,
  /** In minor units, with [unit] and [scale]. */
  val amount: Long? = null,
  val unit: String? = null,
  val scale: Int = 0,
  val at: String? = null,
  /** KIFF's sentence for what happened, when it sends one. */
  val words: String? = null,
  /** KIFF's line for why it can't tell, when it sends one. */
  @SerialName("status_words") val statusWords: String? = null,
  /** Which taf made it, with its name as KIFF has it. */
  val taf: UnsureTaf? = null,
  /** The vendor, as the connection's profile names it. */
  val vendor: String? = null,
  /** The amount in its unit, in KIFF's words: "€150.00". */
  @SerialName("amount_words") val amountWords: String? = null,
  /** What it acted on, in the profile's words. */
  val counterparty: Counterparty? = null,
  /** When it was sent, in the account's time zone, in KIFF's words: "today 09:14". */
  @SerialName("when_words") val whenWords: String? = null,
  /** Why KIFF can't tell, in its words. */
  val why: String? = null,
  /** Where to look at the vendor, when the reviewed profile gives a record address. */
  @SerialName("record_url") val recordUrl: String? = null,
  /** The answers, in KIFF's words, each with what follows and the key it takes; none from an older KIFF. */
  val answers: List<UnsureAnswer> = emptyList(),
) {
  val money: Money? get() = amount?.let { Money(it, unit, scale) }

  companion object {
    const val UNKNOWN = "unknown"
    const val REFUSED = "refused_by_tool"
    const val PROBATION = "probation"
  }
}

@Serializable
data class UnsureTaf(val agent: String = "", val name: String? = null)

/** What a call acted on: the profile's noun for it, its id, and its own name where the vendor gives one. */
@Serializable
data class Counterparty(val noun: String = "", val id: String? = null, val words: String? = null)

/** One answer to "Did this happen?": `yes`, `no` or `not_sure`, its words, what follows from it, and the key KIFF takes it from. */
@Serializable
data class UnsureAnswer(val answer: String, val words: String = "", val detail: String = "", val key: String = "") {
  companion object {
    const val YES = "yes"
    const val NO = "no"
    const val NOT_SURE = "not_sure"
  }
}

@Serializable
data class UnsureCalls(val calls: List<UnsureCall> = emptyList())
