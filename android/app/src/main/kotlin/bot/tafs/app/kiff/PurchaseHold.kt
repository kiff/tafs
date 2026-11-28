package bot.tafs.app.kiff

import bot.tafs.app.model.PurchaseApproval
import bot.tafs.app.model.PurchaseCharge
import bot.tafs.app.model.PurchaseLine
import bot.tafs.app.model.PurchaseWords
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A held purchase (contracts/purchase.json, `approval.value`; docs/purchases.md): KIFF serves it on the hold as `purchase`, and the
 * apps draw it and sign it as it came. The phone decides nothing about it: it reads KIFF's words, and signs the case's id, version
 * and digest as the answer's last line, so the answer binds exactly the case KIFF drew.
 */
object PurchaseHold {
  private val CASE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
  private val DIGEST = Regex("^[0-9a-f]{64}$")

  /** What a hold's `purchase` block is: absent (any other hold), [Readable], or [Unreadable], which is never approved. */
  sealed interface Read {
    data class Readable(val approval: PurchaseApproval) : Read

    /** [line] is the answer's purchase line when the case's id, version and digest could be read, so it can still be refused. */
    data class Unreadable(val line: String?) : Read
  }

  /**
   * Reads a hold's `purchase` block. Null when there's none (JSON null or absent). A block whose id, version or digest isn't one,
   * or whose title, total, delivery address or prompt is missing, is [Read.Unreadable]: drawn as such, never approved.
   */
  fun read(block: JsonElement?): Read? {
    if (block == null || block is JsonNull) return null
    val o = block as? JsonObject ?: return Read.Unreadable(null)
    val caseId = o.text("case_id")
    val version = (o["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    val digest = o.text("digest")
    val line = if (caseId != null && CASE_ID.matches(caseId) && version != null && version >= 1 && digest != null && DIGEST.matches(digest)) {
      line(caseId, version, digest)
    } else {
      return Read.Unreadable(null)
    }
    val w = o["words"] as? JsonObject ?: return Read.Unreadable(line)
    val title = w.text("title")?.let(KiffMapping::visible)?.takeIf { it.isNotBlank() }
    val total = w.text("total")?.let(KiffMapping::visible)?.takeIf { it.isNotBlank() }
    val prompt = w.text("prompt")?.let(KiffMapping::visible)?.takeIf { it.isNotBlank() }
    // The delivery address is signed with the case: a purchase drawn without it isn't the case approved.
    val delivery = w.text("delivery")?.let(KiffMapping::visible)?.takeIf { it.isNotBlank() }
    if (title == null || total == null || prompt == null || delivery == null) return Read.Unreadable(line)
    val lines = (w["lines"] as? JsonArray).orEmpty().map { e ->
      val l = e as? JsonObject ?: return Read.Unreadable(line)
      PurchaseLine(
        name = l.text("name")?.let(KiffMapping::visible) ?: return Read.Unreadable(line),
        quantity = l.text("quantity")?.let(KiffMapping::visible).orEmpty(),
        amount = l.text("amount")?.let(KiffMapping::visible) ?: return Read.Unreadable(line),
      )
    }
    val charges = (w["charges"] as? JsonArray).orEmpty().map { e ->
      val c = e as? JsonObject ?: return Read.Unreadable(line)
      PurchaseCharge(
        name = c.text("name")?.let(KiffMapping::visible) ?: return Read.Unreadable(line),
        amount = c.text("amount")?.let(KiffMapping::visible) ?: return Read.Unreadable(line),
      )
    }
    fun say(key: String) = w.text(key)?.let(KiffMapping::visible)?.takeIf { it.isNotBlank() }
    return Read.Readable(
      PurchaseApproval(
        caseId = caseId, version = version, digest = digest,
        words = PurchaseWords(
          title = title, subtitle = say("subtitle"), total = total, totalNote = say("total_note"), delivery = delivery,
          showMore = say("show_more"), showLess = say("show_less"), lines = lines, charges = charges, priceRule = say("price_rule"), grant = say("grant"),
          buysNow = say("buys_now"), nothingUntil = say("nothing_until"), reason = say("reason"), prompt = prompt,
        ),
      ),
    )
  }

  /** The answer's last line for a purchase: `purchase:<case_id>:<version>:<digest>`, after kiff.answer.v1's own (the contract's `answer`). */
  fun line(caseId: String, version: Int, digest: String): String = "purchase:$caseId:$version:$digest"

  private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
