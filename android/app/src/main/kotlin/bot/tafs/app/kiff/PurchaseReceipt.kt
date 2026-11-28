package bot.tafs.app.kiff

import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * KIFF's signed receipt for a purchase (contracts/purchase.json, `receipt`; docs/purchases.md): what was decided and what
 * happened, every word KIFF's, `unknown` said as unknown. KIFF signs it with Ed25519 over the canonical JSON (RFC 8785) of its
 * `value` without `sig`, under a `kid`. The phone checks it against keys it holds ([check]); it decides nothing from it.
 */
data class PurchaseReceipt(
  val caseId: String,
  val version: Int,
  val digest: String,
  /** KIFF's states: the case's (`done`, `not_bought`, `unknown`…), the money's and the order's. */
  val caseState: String,
  val path: String?,
  val words: Words,
  val kid: String,
  /** What KIFF signed: the receipt's `value` without `sig`, as sent. */
  internal val signed: JsonObject,
  internal val sig: String,
) {
  /** KIFF's words for the Record, each as sent; only `line` and `state` are always there. */
  data class Words(
    val line: String,
    val state: String,
    val allowed: String? = null,
    val card: String? = null,
    /** The order's state, as the shop confirmed it (`words.order`). */
    val order: String? = null,
    val refunds: String? = null,
  ) {
    /** The lines under the state, in the contract's order. */
    val lines: List<String> get() = listOfNotNull(allowed, card, order, refunds)
  }

  /** What the phone's check of a receipt found. */
  sealed interface Check {
    /** Signed by KIFF with a key this phone holds, and unchanged since. */
    data object Verified : Check

    /** Signed with a key this phone holds, but its signature doesn't match what it says: changed, or not KIFF's. */
    data object Changed : Check

    /** Signed with a key this phone doesn't hold, or one KIFF revoked: never shown as checked. */
    data object UnknownKey : Check

    /** It isn't a receipt for the case its line names. */
    data object OtherCase : Check
  }

  /** Checks the receipt against [keys] (each kid's raw 32-byte Ed25519 public key, [PINNED] in the app), for the case [caseId] its line names. */
  fun check(caseId: String, keys: Map<String, ByteArray> = PINNED, revoked: Set<String> = emptySet()): Check {
    if (caseId != this.caseId) return Check.OtherCase
    val key = keys[kid]?.takeIf { it.size == 32 && kid !in revoked } ?: return Check.UnknownKey
    val signature = decode(sig)?.takeIf { it.size == 64 } ?: return Check.Changed
    val bytes = Jcs.canonical(signed).toByteArray(Charsets.UTF_8)
    val ok = runCatching {
      Ed25519Signer().run {
        init(false, Ed25519PublicKeyParameters(key, 0))
        update(bytes, 0, bytes.size)
        verifySignature(signature)
      }
    }.getOrDefault(false)
    return if (ok) Check.Verified else Check.Changed
  }

  companion object {
    const val SCHEMA = "kiff.purchase-receipt.v1"

    /**
     * Reads KIFF's answer: the receipt's `value` itself, or an object holding it as `value`. Null when it isn't a purchase
     * receipt this app can draw: another schema, or no case id, version, digest, kid, signature, line or state.
     */
    fun read(json: JsonElement): PurchaseReceipt? {
      val root = json as? JsonObject ?: return null
      val v = (root["value"] as? JsonObject) ?: root
      if (v.text("schema") != SCHEMA) return null
      val w = v["words"] as? JsonObject ?: return null
      fun word(key: String) = w.text(key)?.let(KiffMapping::visible)?.takeIf { it.isNotBlank() }
      val line = word("line") ?: return null
      val state = word("state") ?: return null
      val states = v["states"] as? JsonObject
      return PurchaseReceipt(
        caseId = v.text("case_id") ?: return null,
        version = (v["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null,
        digest = v.text("digest") ?: return null,
        caseState = states?.text("case").orEmpty(),
        path = v.text("path"),
// `order`, or `shipping` on a receipt issued before the order line had its own name.
        words = Words(line, state, word("allowed"), word("card"), word("order") ?: word("shipping"), word("refunds")),
        kid = v.text("kid") ?: return null,
        signed = JsonObject(v - "sig"),
        sig = v.text("sig") ?: return null,
      )
    }

    /**
     * KIFF's receipt keys, pinned in this build (raw 32-byte Ed25519 public keys by `kid`), as [ReceiptCheck.KIFF_SIGNER] is for the
     * Record's receipts: a receipt checks only against a key the app already holds, never one fetched with it. KIFF's published key
     * set is for others; a new key ships here before KIFF signs with it, and a revoked one leaves in a release.
     * `kiff-receipts-1`: KIFF's first purchase receipt key (28 Nov 2026), its public `x` as KIFF publishes it.
     */
    val PINNED: Map<String, ByteArray> = mapOf(
      "kiff-receipts-1" to decode("_6t9hrjlcGOCqoGUrOf95mAk6oW9Z8zEj1_rtj2c4Pg")!!,
    )

    /** Base64url or standard base64, padded or not. */
    internal fun decode(s: String): ByteArray? = runCatching {
      Base64.getDecoder().decode(s.trim().replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) })
    }.getOrNull()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
  }
}
