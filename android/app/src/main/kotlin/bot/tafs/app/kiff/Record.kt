package bot.tafs.app.kiff

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Record searched across the person's tafs (`GET /v1/record`, docs/api.md): newest first, by what a call is about (its business
 * reference), its action or its amount, as tafs cloud finds them. Each entry is a taf's Activity entry with its taf and `about`.
 * The search box's words (`hint`) and "nothing matched" (`noneWords`) are tafs cloud's.
 */
@Serializable
data class RecordSearchPage(
  val q: String = "",
  val hint: String = "",
  val entries: List<RecordHit> = emptyList(),
  val groups: List<RecordHitGroup> = emptyList(),
  @SerialName("next_cursor") val nextCursor: String? = null,
  @SerialName("end_words") val endWords: String? = null,
  @SerialName("none_words") val noneWords: String? = null,
)

@Serializable
data class RecordHitGroup(val id: String, val title: String = "", val entries: List<RecordHit> = emptyList())

/** A call found in the Record: a taf's Activity entry, with which taf made it and what it's about. */
@Serializable
data class RecordHit(
  val id: String,
  val at: String = "",
  val kind: String = "unknown",
  val title: String = "",
  val detail: String = "",
  val time: String = "",
  @SerialName("amount_text") val amountText: String? = null,
  val call: ActivityCall,
  val next: NextStep? = null,
  val taf: RecordTafRef,
  val about: String? = null,
) {
  /** The same entry as a taf's Activity draws it. */
  val asActivity: TafActivityEntry get() = TafActivityEntry(id, at, kind, title, detail, time, amountText, call, next)
}

/** The taf a call is from, by tafs cloud's id and its name. */
@Serializable
data class RecordTafRef(val id: String, val name: String = "")

/**
 * One case (`GET /v1/record/cases/{about}`): every call of the person's tafs about the same thing, oldest first, each with its receipt;
 * [failed] names the latest step that didn't go through, in tafs cloud's words, with its own next step, the only retry.
 */
@Serializable
data class RecordCase(val about: String, val steps: List<RecordHit> = emptyList(), val failed: CaseFailed? = null)

@Serializable
data class CaseFailed(val id: String, val words: String = "", val next: NextStep? = null)

/**
 * The receipts of some days for an accountant (`GET /v1/record/receipts`): KIFF's signed receipts exactly as issued, never rebuilt,
 * and tafs cloud's CSV of every call. [missing] counts calls whose receipt KIFF couldn't give just now.
 */
data class ReceiptsExport(val from: String, val to: String, val receiptsJson: String, val csv: String, val missing: Int)
