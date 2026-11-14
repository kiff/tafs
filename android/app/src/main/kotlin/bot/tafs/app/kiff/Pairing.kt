package bot.tafs.app.kiff

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A computer asking to be paired, as its QR shows it (KIFF's docs/desktop-client.md §5.1, `kiff.pair.v1`): its device key and,
 * for Windows Hello or a Secure Enclave, its approval key, its name and platform, a one-use nonce and when it expires. The phone
 * reads it, shows the check code (§5.2), and on the person's yes signs it to KIFF with its approval key (§5.3). Whether the pairing
 * holds is KIFF's: the phone only reads the QR well enough to show what it would sign.
 */
@Serializable
data class PairQr(
  val v: String = "",
  val pub: String = "",
  @SerialName("approval_pub") val approvalPub: String? = null,
  val name: String = "",
  val platform: String = "",
  val kind: String = "",
  val nonce: String = "",
  val exp: String = "",
) {
  /** The body for `POST /v1/me/pairings`: the QR's fields as they came, `approval_pub` only when the computer gave one. */
  fun body(): JsonObject = JsonObject(
    buildMap {
      put("v", JsonPrimitive(v))
      put("pub", JsonPrimitive(pub))
      approvalPub?.let { put("approval_pub", JsonPrimitive(it)) }
      put("name", JsonPrimitive(name))
      put("platform", JsonPrimitive(platform))
      put("kind", JsonPrimitive(kind))
      put("nonce", JsonPrimitive(nonce))
      put("exp", JsonPrimitive(exp))
    },
  )

  /**
   * The 6 characters both screens show before the fingerprint (§5.2): the first 6 of base32(sha256(pub ‖ approval_pub)), so a
   * page that shows its own QR can't get this phone to enrol another key unnoticed. Null for a key that isn't base64url.
   */
  val checkCode: String?
    get() {
      val device = decode(pub) ?: return null
      val approval = approvalPub?.let { decode(it) ?: return null } ?: ByteArray(0)
      return base32(MessageDigest.getInstance("SHA-256").digest(device + approval)).take(6)
    }

  companion object {
    const val VERSION = "kiff.pair.v1"

    /** A scanned QR, when it's a computer asking to be paired: the version, a key, a name, a nonce and an expiry. Else null. */
    fun read(text: String): PairQr? {
      val qr = runCatching { KiffClient.json.decodeFromString(serializer(), text) }.getOrNull() ?: return null
      if (qr.v != VERSION || qr.pub.isBlank() || qr.name.isBlank() || qr.nonce.isBlank() || qr.exp.isBlank()) return null
      return qr.takeIf { it.checkCode != null }
    }

    /** base64url, with or without padding, as the QR carries keys. */
    internal fun decode(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text.trim()) }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** RFC 4648 base32, no padding. */
    internal fun base32(bytes: ByteArray): String {
      val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
      val out = StringBuilder()
      var buffer = 0
      var bits = 0
      for (b in bytes) {
        buffer = (buffer shl 8) or (b.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
          out.append(alphabet[(buffer shr (bits - 5)) and 31])
          bits -= 5
        }
      }
      if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 31])
      return out.toString()
    }
  }
}

/** KIFF's answer to a pairing: its id, the check code it computed, and when it expires (§5.3). */
@Serializable
data class Pairing(val id: String = "", val code: String = "", val expires: String = "")

@Serializable
internal data class PairingBody(val pairing: Pairing = Pairing())
