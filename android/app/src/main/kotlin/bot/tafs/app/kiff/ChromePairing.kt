package bot.tafs.app.kiff

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The phone's side of pairing a Chrome (docs/chrome.md, "Pairing"), as iOS's `ChromePairing`: the code it shows, the check
 * of the extension's proof, and the confirmation it signs. The code's last six characters are a secret this phone makes
 * and never sends: the extension proves its key under it, and this phone proves its own key under it, so planque, which
 * relays both, can't put another key on either side. Nothing here asks for the fingerprint: the approval key signs the
 * bytes this builds, behind its own prompt. The shared vectors (vectors/chrome-pairing.json) hold it to the extension and iOS.
 */
object ChromePairing {
  /** The code's characters: no 0, 1, I, L, O or U. */
  const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
  const val SECRET_LENGTH = 6

  const val PAIR_LABEL = "tafs-chrome/pair/v1"
  const val EXTENSION_PROOF_LABEL = "tafs-chrome/ext/v1"
  const val PHONE_PROOF_LABEL = "tafs-chrome/phone/v1"

  /** A name the owner gives a Chrome is at most this long. */
  const val NAME_MAX = 40

  private val random = SecureRandom()

  /** A new secret, from the system's secure random source. */
  fun newSecret(): String = String(CharArray(SECRET_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

  /** The code as the phone shows it: the lookup planque gave, then the secret ("K7QF-29XMDP"). */
  fun code(lookup: String, secret: String): String = "$lookup-$secret"

  /** HMAC-SHA256 under the secret, over label ‖ 0x00 ‖ data. */
  fun hmac(secret: String, label: String, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
      update(label.toByteArray(Charsets.UTF_8))
      update(0)
      doFinal(data)
    }

  /**
   * Whether [proof] is the extension's proof of [key] (its SPKI DER) under our secret. A wrong one means another extension
   * used the code's lookup: nothing is paired.
   */
  fun extensionProofHolds(secret: String, key: ByteArray, proof: String): Boolean {
    val given = base64UrlDecoded(proof) ?: return false
    return MessageDigest.isEqual(given, hmac(secret, EXTENSION_PROOF_LABEL, key))
  }

  /** The SHA-256 of the extension's SPKI DER, in lowercase hex, as the signed body names it. */
  fun chromeKeyHash(key: ByteArray): String = sha256(key).joinToString("") { "%02x".format(it) }

  /** This phone's proof of its key and the extension's under the secret: HMAC(secret, phone label ‖ phone key ‖ sha256(chrome key)). */
  fun phoneProof(secret: String, phoneKey: ByteArray, chromeKey: ByteArray): String =
    base64Url(hmac(secret, PHONE_PROOF_LABEL, phoneKey + sha256(chromeKey)))

  /** What the phone signs to confirm the pairing, and sends with its signature. */
  data class Confirmation(val chromeKeySha256: String, val name: String, val phoneKey: String, val phoneProof: String)

  fun confirmation(secret: String, chromeKey: ByteArray, name: String, phoneKey: ByteArray): Confirmation =
    Confirmation(chromeKeyHash(chromeKey), name, base64Url(phoneKey), phoneProof(secret, phoneKey, chromeKey))

  /**
   * The bytes the phone signs for [c] of pairing [pairing]: label ‖ 0x00 ‖ JCS of
   * `{pairing, chrome_key_sha256, name, phone_key, phone_proof}`.
   */
  fun signedBytes(pairing: String, c: Confirmation): ByteArray {
    val body = buildJsonObject {
      put("pairing", JsonPrimitive(pairing))
      put("chrome_key_sha256", JsonPrimitive(c.chromeKeySha256))
      put("name", JsonPrimitive(c.name))
      put("phone_key", JsonPrimitive(c.phoneKey))
      put("phone_proof", JsonPrimitive(c.phoneProof))
    }
    return PAIR_LABEL.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + Jcs.canonical(body).toByteArray(Charsets.UTF_8)
  }

  /**
   * A signature as the contract sends it: raw r‖s (IEEE P1363, 64 bytes), base64url without padding, from the DER the
   * Android Keystore gives (`SHA256withECDSA`). Null for anything that isn't a P-256 ECDSA signature in DER.
   */
  fun rawSignature(der: ByteArray): String? {
    // SEQUENCE { INTEGER r, INTEGER s }, each at most 33 bytes (a leading zero keeps it positive).
    if (der.size < 8 || der[0] != 0x30.toByte()) return null
    var i = 1
    val seqLen = der[i++].toInt() and 0xff
    if (seqLen and 0x80 != 0 || 2 + seqLen != der.size) return null
    fun integer(): ByteArray? {
      if (i + 2 > der.size || der[i++] != 0x02.toByte()) return null
      val len = der[i++].toInt() and 0xff
      if (len == 0 || len > 33 || i + len > der.size) return null
      val v = der.copyOfRange(i, i + len)
      i += len
      val trimmed = v.dropWhile { it == 0.toByte() }.toByteArray()
      if (trimmed.size > 32) return null
      return ByteArray(32 - trimmed.size) + trimmed
    }
    val r = integer() ?: return null
    val s = integer() ?: return null
    if (i != der.size) return null
    return base64Url(r + s)
  }

  /** A name for the Chrome: the owner's, trimmed, at most [NAME_MAX] characters; the browser's own words when they gave none. */
  fun name(typed: String, browser: String): String = typed.trim().ifEmpty { browser }.take(NAME_MAX)

  fun base64Url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

  /** Base64url without padding only; null for anything else. */
  fun base64UrlDecoded(text: String): ByteArray? {
    if (text.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) return null
    return runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
  }

  private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
