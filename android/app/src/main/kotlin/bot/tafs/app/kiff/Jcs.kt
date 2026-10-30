package bot.tafs.app.kiff

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * RFC 8785 (JSON Canonicalization Scheme), as planque computes a grant's digest: the phone recomputes it from the request it
 * shows, so the owner's confirmation approves exactly that request. Keys sort by UTF-16 code units, numbers print as
 * ECMAScript does, and an integer beyond 2^53 - 1 is refused, as planque and KIFF refuse it.
 *
 * JSON is read keeping each number's literal as written, which tells an integer from a fraction as planque's Go does.
 */
object Jcs {
  class Failure(message: String) : Exception(message)

  private const val MAX_SAFE = "9007199254740991"

  /** The canonical form of the JSON in [json]. */
  fun canonical(json: String): String {
    val value = try { Json.parseToJsonElement(json) } catch (e: Exception) { throw Failure("not JSON: " + e.message?.lines()?.first()) }
    return canonical(value)
  }

  fun canonical(value: JsonElement): String = StringBuilder().also { write(value, it) }.toString()

  private fun write(value: JsonElement, out: StringBuilder) {
    when (value) {
      is JsonNull -> out.append("null")
      is JsonPrimitive -> when {
        value.isString -> string(value.content, out)
        value.content == "true" || value.content == "false" -> out.append(value.content)
        else -> out.append(number(value.content))
      }
      is JsonArray -> {
        out.append('[')
        value.forEachIndexed { i, e -> if (i > 0) out.append(','); write(e, out) }
        out.append(']')
      }
      is JsonObject -> {
        out.append('{')
        // UTF-16 code units, which is how Kotlin strings compare.
        value.keys.sorted().forEachIndexed { i, k ->
          if (i > 0) out.append(',')
          string(k, out)
          out.append(':')
          write(value.getValue(k), out)
        }
        out.append('}')
      }
    }
  }

  private fun string(s: String, out: StringBuilder) {
    out.append('"')
    for (c in s) {
      when {
        c == '"' -> out.append("\\\"")
        c == '\\' -> out.append("\\\\")
        c == '\b' -> out.append("\\b")
        c == '\t' -> out.append("\\t")
        c == '\n' -> out.append("\\n")
        c == '\u000C' -> out.append("\\f")
        c == '\r' -> out.append("\\r")
        c.code < 0x20 -> out.append("\\u").append(String.format("%04x", c.code))
        else -> out.append(c)
      }
    }
    out.append('"')
  }

  /**
   * A number literal in its canonical form. A plain integer beyond 2^53 - 1 is refused, as planque refuses it; one written with
   * a fraction or exponent prints as a double.
   */
  fun number(literal: String): String {
    if (literal.none { it == '.' || it == 'e' || it == 'E' }) {
      var digits = literal.removePrefix("-").trimStart('0').ifEmpty { "0" }
      if (digits.length > MAX_SAFE.length || (digits.length == MAX_SAFE.length && digits > MAX_SAFE)) throw Failure("an integer beyond 2^53 - 1")
      return if (digits == "0") "0" else (if (literal.startsWith("-")) "-" else "") + digits
    }
    val d = literal.toDoubleOrNull()
    if (d == null || d.isNaN() || d.isInfinite()) throw Failure("not a finite number")
    return ecmaScript(d)
  }

  /** ECMAScript's Number::toString, which RFC 8785 uses for every number: the shortest digits that round-trip, placed as it places them. */
  fun ecmaScript(d: Double): String {
    if (d == 0.0) return "0"
    if (d < 0) return "-" + ecmaScript(-d)
    // The shortest digits that read back as the same double, and where the point goes.
    val bd = BigDecimal(d.toString()).stripTrailingZeros()
    val digits = bd.unscaledValue().toString()
    val k = digits.length
    val n = k - bd.scale()
    return when {
      n in k..21 -> digits + "0".repeat(n - k)
      n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
      n in -5..0 -> "0." + "0".repeat(-n) + digits
      else -> {
        val e = n - 1
        val head = if (k == 1) digits else digits[0] + "." + digits.substring(1)
        head + "e" + (if (e < 0) "-" else "+") + kotlin.math.abs(e)
      }
    }
  }
}
