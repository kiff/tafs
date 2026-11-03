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
) {
  val money: Money? get() = amount?.let { Money(it, unit, scale) }

  companion object {
    const val UNKNOWN = "unknown"
    const val REFUSED = "refused_by_tool"
    const val PROBATION = "probation"
  }
}

@Serializable
data class UnsureCalls(val calls: List<UnsureCall> = emptyList())
