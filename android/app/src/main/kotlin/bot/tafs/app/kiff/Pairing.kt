package bot.tafs.app.kiff

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A computer asking to be paired, as its QR shows it (KIFF's pairing contract, `kiff.pair.v1`): its device key and,
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
  /** `"software"` for a computer whose device key isn't in hardware (§1.1): it never approves with that key. Else absent. */
  @SerialName("key_store") val keyStore: String? = null,
  /** A passkey computer's own platform passkey (§2.6, §5.1: Windows Hello): the pairing registers it for this device. */
  val credential: PairCredential? = null,
  /** A phone or tablet being added (KIFF's pairing contract for phones): `iphone`, `ipad`, `android_phone` or `android_tablet`. */
  @SerialName("device_kind") val deviceKind: String? = null,
) {
  /** A phone or tablet being added from this one, rather than a computer: KIFF's phone platforms. */
  val isPhone: Boolean get() = platform in PHONE_PLATFORMS

  /** The body for `POST /v1/me/pairings`: the QR's fields as they came, `approval_pub`, `key_store` and `credential` only when the computer gave them. */
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
      keyStore?.let { put("key_store", JsonPrimitive(it)) }
      credential?.let { put("credential", JsonObject(mapOf("id" to JsonPrimitive(it.id), "public_key" to JsonPrimitive(it.publicKey)))) }
      deviceKind?.let { put("device_kind", JsonPrimitive(it)) }
    },
  )

  /**
   * The 6 characters both screens show before the fingerprint (§5.2): the first 6 of base32(sha256(pub ‖ approval_pub)), so a
   * page that shows its own QR can't get this phone to enrol another key unnoticed. A computer's own passkey takes
   * approval_pub's place, so the code covers the passkey the pairing registers. Null for a key that isn't base64url.
   */
  val checkCode: String?
    get() {
      val device = decode(pub) ?: return null
      // A computer whose device key is in software never approves, so it has no second key to cover: pub alone.
      val second = if (keyStore == "software") null else credential?.publicKey ?: approvalPub
      val approval = second?.let { decode(it) ?: return null } ?: ByteArray(0)
      return base32(MessageDigest.getInstance("SHA-256").digest(device + approval)).take(6)
    }

  companion object {
    const val VERSION = "kiff.pair.v1"

    /** The kinds of computer §5.1 names: what approves on it, which the alert tells the person before they say yes. */
    val KINDS = setOf("hello", "enclave", "passkey", "keystore")

    /** The platforms of a phone or tablet: added with its own approval key, the same way as a computer. */
    val PHONE_PLATFORMS = setOf("ios", "ipados", "android")

    /**
     * A scanned QR, when it's a computer asking to be paired: the version, a key, a name, a nonce, an expiry and a kind the
     * phone can word. Else null.
     */
    fun read(text: String): PairQr? {
      val qr = runCatching { KiffClient.json.decodeFromString(serializer(), text) }.getOrNull() ?: return null
      if (qr.v != VERSION || qr.pub.isBlank() || qr.name.isBlank() || qr.nonce.isBlank() || qr.exp.isBlank() || qr.kind !in KINDS) return null
      if (qr.credential != null && (qr.credential.id.isBlank() || qr.credential.publicKey.isBlank())) return null
      return qr.takeIf { it.checkCode != null }
    }

    /**
     * A Chrome extension's own KIFF device, from the offer it sends with its Chrome's (docs/chrome.md, "The extension's KIFF
     * device"): KIFF's `kiff.pair.v1` for a `chrome` `extension` whose key is in software, with no approval key and no passkey,
     * so KIFF pairs it view-only. Anything else is null, and the Chrome isn't paired. As iOS's `chromeKIFFDevice`.
     */
    fun readChromeExtension(text: String): PairQr? {
      val qr = runCatching { KiffClient.json.decodeFromString(serializer(), text) }.getOrNull() ?: return null
      if (qr.v != VERSION || qr.platform != "chrome" || qr.kind != "extension" || qr.keyStore != "software") return null
      if (qr.pub.isBlank() || qr.name.isBlank() || qr.nonce.isBlank() || qr.exp.isBlank()) return null
      if (qr.approvalPub != null || qr.credential != null || qr.deviceKind != null || decode(qr.pub) == null) return null
      return qr
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

/** A computer's own platform passkey, as its QR carries it (§5.1): its credential id and its public key, SPKI DER, base64url. */
@Serializable
data class PairCredential(val id: String = "", @SerialName("public_key") val publicKey: String = "")

/** KIFF's answer to a pairing: its id, the check code it computed, and when it expires (§5.3). */
@Serializable
data class Pairing(val id: String = "", val code: String = "", val expires: String = "")

@Serializable
internal data class PairingBody(val pairing: Pairing = Pairing())

/**
 * What this phone or tablet shows to be added from the person's other phone (KIFF's pairing contract for phones): the same
 * `kiff.pair.v1` QR as a computer, with this device's two keys, `kind: "keystore"` and its `device_kind`, the 6-character code both
 * screens show, and the message its device key signs to claim the session once the other phone approves. It decides nothing:
 * whether the pairing holds is KIFF's, and the other phone's yes.
 */
data class PairOffer(
  val nonce: String,
  /** The device key, base64url, as the QR carries it and the claim sends it. */
  val pub: String,
  val expires: java.time.Instant,
  /** The QR's text: the JSON the other phone reads ([PairQr.read]). */
  val qr: String,
  /** The 6 characters both screens show. */
  val checkCode: String,
  /** `kiff.pair.claim.v1`, the nonce and sha256(pub) in hex: signed once by the device key, the same proof serves every poll. */
  val claimMessage: ByteArray,
) {
  override fun equals(other: Any?): Boolean = other is PairOffer && other.nonce == nonce && other.pub == pub
  override fun hashCode(): Int = nonce.hashCode() * 31 + pub.hashCode()

  companion object {
    /** Under the contract's 5 minutes, so the other phone's clock never reads it as too far ahead. As iOS. */
    const val LIFETIME_SECONDS = 4L * 60 + 30
    const val KIND = "keystore"
    const val PLATFORM = "android"

    /** [publicKey] and [approvalKey] are SPKI DER; [nonce] is 32 random bytes. */
    fun make(publicKey: ByteArray, approvalKey: ByteArray, name: String, deviceKind: String, nonce: ByteArray, now: java.time.Instant): PairOffer {
      val pub = url(publicKey)
      val nonceText = url(nonce)
      val expires = now.plusSeconds(LIFETIME_SECONDS).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
      val qr = JsonObject(
        sortedMapOf(
          "v" to JsonPrimitive(PairQr.VERSION), "pub" to JsonPrimitive(pub), "approval_pub" to JsonPrimitive(url(approvalKey)),
          "name" to JsonPrimitive(name), "platform" to JsonPrimitive(PLATFORM), "kind" to JsonPrimitive(KIND),
          "device_kind" to JsonPrimitive(deviceKind), "nonce" to JsonPrimitive(nonceText), "exp" to JsonPrimitive(expires.toString()),
        ),
      ).toString()
      val code = PairQr.base32(MessageDigest.getInstance("SHA-256").digest(publicKey + approvalKey)).take(6)
      val keyHash = MessageDigest.getInstance("SHA-256").digest(publicKey).joinToString("") { "%02x".format(it) }
      return PairOffer(nonceText, pub, expires, qr, code, listOf("kiff.pair.claim.v1", nonceText, keyHash).joinToString("\n").toByteArray())
    }

    /** 32 bytes from the system's secure random source. */
    fun freshNonce(): ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

    private fun url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}

/**
 * KIFF's answer, as the person reads it: paired with the code they checked, or, when KIFF paired another code than the one
 * shown, that, so they can sign the device out.
 */
internal fun pairedLine(qr: PairQr, pairing: Pairing): String {
  val shown = qr.checkCode.orEmpty()
  return if (pairing.code.isNotBlank() && pairing.code != shown) {
    bot.tafs.app.model.Words.Settings.Devices.pairCodeDiffers(device = qr.name, kiff = pairing.code, shown = shown)
  } else {
    bot.tafs.app.model.Words.Settings.Devices.pairDone(device = qr.name, code = shown)
  }
}
