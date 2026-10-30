package bot.tafs.app.kiff

import java.math.BigInteger
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.asn1.x9.X9IntegerConverter
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECAlgorithms

/**
 * Checks a KIFF receipt on the phone (kiff/tafs#167), as KIFF's own verification does and as iOS's `ReceiptCheck` does: the
 * signed digest is keccak256(ascii(sha256hex(causal_chain))), and the signer recovered from the 65-byte signature must be
 * KIFF's.
 *
 * The chain's bytes are taken from the response as KIFF wrote them, never re-encoded: `GET /v1/receipts/{trace_id}` writes
 * the stored receipt with Go's encoder, so its `causal_chain` is byte for byte what KIFF hashed. Only the chain is signed:
 * what the sheet shows as checked comes from its steps.
 */
sealed interface ReceiptCheck {
  /** The signature recovers to KIFF's signer over this chain. */
  data class Verified(val signer: String) : ReceiptCheck

  /** KIFF signs in its next sweep, about 30 s after the decision. */
  data object NotSignedYet : ReceiptCheck

  /** It doesn't check out, and why. */
  data class Failed(val why: String) : ReceiptCheck

  companion object {
    /**
     * KIFF's receipt signer in production (kiff/tafs#167): one key for every account, the deployer of KIFF's anchor
     * contract and the sender of its anchor transactions. A rotation ships in a new build first.
     */
    const val KIFF_SIGNER = "0x2f297affd7b62ef712a1e3158421ccb4bf2efcbe"

    const val ALGORITHM = "secp256k1+keccak256"

    fun check(response: ByteArray, signer: String = KIFF_SIGNER): ReceiptCheck {
      val receipt = runCatching { KiffClient.json.parseToJsonElement(String(response)) as? JsonObject }.getOrNull()
        ?: return Failed("The receipt couldn't be read.")
      val attestation = receipt["attestation"] as? JsonObject ?: return NotSignedYet
      if (attestation["algorithm"]?.jsonPrimitive?.contentOrNull != ALGORITHM) return Failed("It's signed in a way this app doesn't know.")
      val chain = RawJson.valueOf("causal_chain", response) ?: return Failed("The receipt has no steps to check.")
      val signature = attestation["signature"]?.jsonPrimitive?.contentOrNull?.let(Hex::decode)
      if (signature == null || signature.size != 65) return Failed("The signature is malformed.")
      val declared = attestation["wallet_address"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()

      val chainHash = Hex.encode(MessageDigest.getInstance("SHA-256").digest(chain))
      val digest = keccak256(chainHash.toByteArray(Charsets.US_ASCII))
      val recovered = recoverAddress(digest, signature) ?: return Failed("The signature doesn't match these steps.")
      if (recovered != declared) return Failed("The signature doesn't match these steps.")
      if (recovered != signer.lowercase()) return Failed("It isn't signed by KIFF.")
      return Verified(recovered)
    }

    fun keccak256(data: ByteArray): ByteArray = KeccakDigest(256).run {
      update(data, 0, data.size)
      ByteArray(32).also { doFinal(it, 0) }
    }

    private val curve = CustomNamedCurves.getByName("secp256k1")

    /**
     * The Ethereum-style address that signed [digest]: keccak256 of the uncompressed public key recovered from the
     * signature, its last 20 bytes. Null when the signature can't be parsed or recovers no key.
     */
    fun recoverAddress(digest: ByteArray, signature: ByteArray): String? = runCatching { recover(digest, signature) }.getOrNull()

    private fun recover(digest: ByteArray, signature: ByteArray): String? {
      if (digest.size != 32 || signature.size != 65) return null
      var v = signature[64].toInt() and 0xff
      if (v >= 27) v -= 27
      if (v !in 0..3) return null
      val n = curve.n
      val r = BigInteger(1, signature.copyOfRange(0, 32))
      val s = BigInteger(1, signature.copyOfRange(32, 64))
      if (r.signum() <= 0 || r >= n || s.signum() <= 0 || s >= n) return null
      // R's x is r, or r + n for the rarer v of 2 or 3; its y's parity is v's low bit.
      val x = r.add(n.multiply(BigInteger.valueOf((v / 2).toLong())))
      if (x >= curve.curve.field.characteristic) return null
      val encoded = X9IntegerConverter().integerToBytes(x, 1 + X9IntegerConverter().getByteLength(curve.curve))
      encoded[0] = if (v and 1 == 1) 0x03 else 0x02
      val big = curve.curve.decodePoint(encoded)
      if (!big.multiply(n).isInfinity) return null
      val e = BigInteger(1, digest)
      val rInv = r.modInverse(n)
      // Q = r⁻¹(sR − eG)
      val q = ECAlgorithms.sumOfTwoMultiplies(curve.g, e.negate().mod(n).multiply(rInv).mod(n), big, rInv.multiply(s).mod(n)).normalize()
      if (q.isInfinity) return null
      val key = q.getEncoded(false)
      return "0x" + Hex.encode(keccak256(key.copyOfRange(1, key.size)).copyOfRange(12, 32))
    }
  }
}

object Hex {
  fun encode(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }

  fun decode(text: String): ByteArray? {
    val s = text.removePrefix("0x")
    if (s.length % 2 != 0) return null
    return runCatching { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
  }
}

/** A top-level value's own bytes in a JSON object, as written: the receipt's signed chain must be hashed as KIFF wrote it, not as a decoder would write it again. */
object RawJson {
  fun valueOf(key: String, data: ByteArray): ByteArray? {
    var i = skipSpace(data, 0)
    if (i >= data.size || data[i] != '{'.code.toByte()) return null
    i += 1
    while (true) {
      i = skipSpace(data, i)
      if (i >= data.size || data[i] == '}'.code.toByte()) return null
      if (data[i] != '"'.code.toByte()) return null
      val keyEnd = endOfString(data, i) ?: return null
      val name = runCatching { KiffClient.json.parseToJsonElement(String(data, i, keyEnd - i)).jsonPrimitive.content }.getOrNull()
      i = skipSpace(data, keyEnd)
      if (i >= data.size || data[i] != ':'.code.toByte()) return null
      i = skipSpace(data, i + 1)
      val valueEnd = endOfValue(data, i) ?: return null
      if (name == key) return data.copyOfRange(i, valueEnd)
      i = skipSpace(data, valueEnd)
      if (i >= data.size) return null
      if (data[i] == ','.code.toByte()) {
        i += 1
        continue
      }
      return null
    }
  }

  private fun isSpace(b: Byte) = b == 0x20.toByte() || b == 0x09.toByte() || b == 0x0A.toByte() || b == 0x0D.toByte()

  private fun skipSpace(b: ByteArray, from: Int): Int {
    var i = from
    while (i < b.size && isSpace(b[i])) i++
    return i
  }

  /** The index after a string's closing quote; [start] is its opening quote. */
  private fun endOfString(b: ByteArray, start: Int): Int? {
    var i = start + 1
    while (i < b.size) {
      if (b[i] == '\\'.code.toByte()) {
        i += 2
        continue
      }
      if (b[i] == '"'.code.toByte()) return i + 1
      i++
    }
    return null
  }

  /** The index after a value: a string, an object or array (to its matching close), or a literal or number (to the next delimiter). */
  private fun endOfValue(b: ByteArray, start: Int): Int? {
    if (start >= b.size) return null
    return when (b[start]) {
      '"'.code.toByte() -> endOfString(b, start)
      '{'.code.toByte(), '['.code.toByte() -> {
        var depth = 0
        var i = start
        while (i < b.size) {
          when (b[i]) {
            '"'.code.toByte() -> {
              i = endOfString(b, i) ?: return null
              continue
            }
            '{'.code.toByte(), '['.code.toByte() -> depth++
            '}'.code.toByte(), ']'.code.toByte() -> {
              depth--
              if (depth == 0) return i + 1
            }
          }
          i++
        }
        null
      }
      else -> {
        var i = start
        while (i < b.size && b[i] != ','.code.toByte() && b[i] != '}'.code.toByte() && b[i] != ']'.code.toByte() && !isSpace(b[i])) i++
        if (i > start) i else null
      }
    }
  }
}

/** One step of a receipt, as KIFF signed it: what the sheet shows as happened. */
data class ReceiptStep(
  val kind: String,
  /** KIFF's message for it, else its kind in words. */
  val words: String,
  val at: java.time.Instant?,
  /** Its data's hash, shortened for reading: "aa12…ff09". */
  val hash: String,
  /** What the step says the call acted on, as KIFF hashed it. */
  val onHash: String? = null,
  /** The amount the decision step signed, when it has one. */
  val amountValue: String? = null,
  val amountUnit: String? = null,
  /** A hold_settled step's answer, as KIFF records it ("reject" for a refusal). */
  val answer: String? = null,
)

object ReceiptSteps {
  /** The signed chain's steps, from the receipt as KIFF wrote it; none when it can't be read. */
  fun of(raw: ByteArray): List<ReceiptStep> {
    val chain = RawJson.valueOf("causal_chain", raw) ?: return emptyList()
    val list = runCatching { KiffClient.json.parseToJsonElement(String(chain)) as? kotlinx.serialization.json.JsonArray }.getOrNull() ?: return emptyList()
    return list.mapNotNull { element ->
      val s = element as? JsonObject ?: return@mapNotNull null
      fun text(o: JsonObject?, key: String) = (o?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
      val kind = text(s, "kind").orEmpty()
      val data = s["data"] as? JsonObject
      val amount = data?.get("amount") as? JsonObject
      val hash = text(s, "data_hash").orEmpty()
      ReceiptStep(
        kind = kind,
        words = text(s, "message")?.takeIf { it.isNotEmpty() } ?: kind.replace("_", " "),
        at = text(s, "occurred_at")?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() },
        hash = if (hash.length > 8) hash.take(4) + "…" + hash.takeLast(4) else hash,
        onHash = text(data, "on_hash"),
        amountValue = text(amount, "value"),
        amountUnit = text(amount, "unit"),
        answer = text(data, "answer"),
      )
    }
  }

  /**
   * Why a line doesn't match the receipt KIFF signed for it, or null when it does: what it acted on, its amount, and the
   * answer, each only where the receipt signed one. A verified receipt with a line that doesn't match doesn't check out.
   */
  fun mismatch(entry: bot.tafs.app.model.RecordEntry, steps: List<ReceiptStep>): String? {
    val on = entry.onValue
    val signedOn = steps.firstNotNullOfOrNull { it.onHash }
    if (on != null && signedOn != null && !KiffMapping.isOn(on, signedOn)) return "The payment on this line doesn't match KIFF's receipt."
    val signedAmount = steps.firstOrNull { it.amountValue != null }
    if (entry.amountValue != null && signedAmount != null &&
      (entry.amountValue != signedAmount.amountValue || entry.amountUnit.orEmpty() != signedAmount.amountUnit.orEmpty())
    ) return "The amount on this line doesn't match KIFF's receipt."
    val signedAnswer = steps.firstOrNull { it.kind == "hold_settled" }?.answer
    val answer = entry.answer
    if (answer != null && signedAnswer != null && (answer.decision == "refused") != (signedAnswer == "reject")) {
      return "The answer on this line doesn't match KIFF's receipt."
    }
    return null
  }
}
