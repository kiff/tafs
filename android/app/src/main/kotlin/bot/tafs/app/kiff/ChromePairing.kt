package bot.tafs.app.kiff

import bot.tafs.app.model.Words
import bot.tafs.app.signing.SignerException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
  /** 17 characters of 30, about 83 bits: planque keeps the extension's proof under it, so it must not be guessable offline. */
  const val SECRET_LENGTH = 17

  const val PAIR_LABEL = "tafs-chrome/pair/v1"
  const val EXTENSION_PROOF_LABEL = "tafs-chrome/ext/v1"

  /** The extension's proof that it checks one fingerprint for the pairing and its KIFF device (`one_touch`). */
  const val ONE_TOUCH_PROOF_LABEL = "tafs-chrome/ext-one-touch/v1"
  const val PHONE_PROOF_LABEL = "tafs-chrome/phone/v1"

  /** What the phone signs when the owner approves a browser grant: its sites, and each sensitive site allowed by name. */
  const val SITES_LABEL = "tafs-chrome/sites/v1"
  const val SENSITIVE_LABEL = "tafs-chrome/sensitive/v1"

  /** A name the owner gives a Chrome is at most this long. */
  const val NAME_MAX = 40

  private val random = SecureRandom()

  /** A new secret, from the system's secure random source. */
  fun newSecret(): String = String(CharArray(SECRET_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

  /**
   * The code as the phone shows it: the lookup planque gave, then the secret in groups of four ("K7QF-29XM-DPQR-STVW-XYZ2-3"),
   * as iOS shows it. The extension takes it with or without the dashes.
   */
  fun code(lookup: String, secret: String): String = (listOf(lookup) + secret.chunked(4)).joinToString("-")

  /** HMAC-SHA256 under the secret, over label ‖ 0x00 ‖ data. */
  fun hmac(secret: String, label: String, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
      update(label.toByteArray(Charsets.UTF_8))
      update(0)
      doFinal(data)
    }

  /**
   * Whether [proof] is the extension's proof of [key] (its SPKI DER) and [kiffOffer] (its KIFF device's `kiff.pair.v1` offer, as
   * its text; empty when it sent none) under our secret: HMAC(secret, ext label ‖ key ‖ kiff_offer). A wrong one means another
   * extension used the code's lookup, or planque swapped a key: nothing is paired.
   */
  fun extensionProofHolds(secret: String, key: ByteArray, proof: String, kiffOffer: String = ""): Boolean {
    val given = base64UrlDecoded(proof) ?: return false
    return MessageDigest.isEqual(given, hmac(secret, EXTENSION_PROOF_LABEL, key + kiffOffer.toByteArray(Charsets.UTF_8)))
  }

  /**
   * Whether the extension's `one_touch` flag is its own: the same HMAC as [extensionProofHolds], under its own label, so only
   * someone with the code's secret could add it. Only then does the phone sign the pairing and its KIFF device as one.
   */
  fun oneTouchProofHolds(secret: String, key: ByteArray, proof: String, kiffOffer: String = ""): Boolean {
    val given = base64UrlDecoded(proof) ?: return false
    return MessageDigest.isEqual(given, hmac(secret, ONE_TOUCH_PROOF_LABEL, key + kiffOffer.toByteArray(Charsets.UTF_8)))
  }

  /** The SHA-256 of the extension's SPKI DER, in lowercase hex, as the signed body names it. */
  fun chromeKeyHash(key: ByteArray): String = sha256(key).joinToString("") { "%02x".format(it) }

  /** This phone's proof of its key and the extension's under the secret: HMAC(secret, phone label ‖ phone key ‖ sha256(chrome key)). */
  fun phoneProof(secret: String, phoneKey: ByteArray, chromeKey: ByteArray): String =
    base64Url(hmac(secret, PHONE_PROOF_LABEL, phoneKey + sha256(chromeKey)))

  /** What the phone signs to confirm the pairing, and sends with its signature. */
  data class Confirmation(
    val chromeKeySha256: String,
    val name: String,
    val phoneKey: String,
    val phoneProof: String,
    /**
     * A re-pairing's carried connections (docs/chrome.md "Your Chrome" 1): each grant that moves to this Chrome and its sites,
     * read from the old Chrome's signed copy, in the order the phone writes them. Empty for a first pairing, and then not in
     * the signed body at all, so a first pairing's bytes are as they always were.
     */
    val carried: List<Carried> = emptyList(),
  )

  /** One connection a re-pairing carries: its grant and its sites, as signed. */
  data class Carried(val grant: String, val sites: List<String>)

  fun confirmation(secret: String, chromeKey: ByteArray, name: String, phoneKey: ByteArray): Confirmation =
    Confirmation(chromeKeyHash(chromeKey), name, base64Url(phoneKey), phoneProof(secret, phoneKey, chromeKey))

  /**
   * The bytes the phone signs for [c] of pairing [pairing]: label ‖ 0x00 ‖ JCS of
   * `{pairing, chrome_key_sha256, name, phone_key, phone_proof}`, and `carried: [{grant, sites}]` when a re-pairing carries
   * connections, as the extension rebuilds it (`checkConfirmation`).
   */
  fun signedBytes(pairing: String, c: Confirmation): ByteArray {
    val body = buildJsonObject {
      put("pairing", JsonPrimitive(pairing))
      put("chrome_key_sha256", JsonPrimitive(c.chromeKeySha256))
      put("name", JsonPrimitive(c.name))
      put("phone_key", JsonPrimitive(c.phoneKey))
      put("phone_proof", JsonPrimitive(c.phoneProof))
      if (c.carried.isNotEmpty()) {
        put("carried", kotlinx.serialization.json.JsonArray(c.carried.map { e ->
          buildJsonObject {
            put("grant", JsonPrimitive(e.grant))
            put("sites", kotlinx.serialization.json.JsonArray(e.sites.map { JsonPrimitive(it) }))
          }
        }))
      }
    }
    return PAIR_LABEL.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + Jcs.canonical(body).toByteArray(Charsets.UTF_8)
  }

  /**
   * The bytes the phone signs for a browser grant's sites on one paired Chrome: label ‖ 0x00 ‖ JCS of
   * `{chrome_id, grant, sites}`. The extension runs a step only on sites inside them (`protocol.checkSites`).
   */
  fun sitesBytes(chromeId: String, grant: String, sites: List<String>): ByteArray = labelled(
    SITES_LABEL,
    buildJsonObject {
      put("chrome_id", JsonPrimitive(chromeId))
      put("grant", JsonPrimitive(grant))
      put("sites", JsonArray(sites.map { JsonPrimitive(it) }))
    },
  )

  /**
   * The bytes the phone signs to allow one sensitive site for one grant on one paired Chrome: label ‖ 0x00 ‖ JCS of
   * `{chrome_id, grant, site}`. Without it, the extension never opens that site (`protocol.checkAllowances`).
   */
  fun sensitiveBytes(chromeId: String, grant: String, site: String): ByteArray = labelled(
    SENSITIVE_LABEL,
    buildJsonObject {
      put("chrome_id", JsonPrimitive(chromeId))
      put("grant", JsonPrimitive(grant))
      put("site", JsonPrimitive(site))
    },
  )

  /**
   * The signature `chrome_sites_sig` carries when the owner approves a grant that uses their own Chrome, as iOS's
   * `ChromePairing.sitesSignature`: [sign] signs the bytes of `{chrome_id, grant, sites}` with the approval key, behind the
   * fingerprint of that approval, and the answer goes as raw r‖s. Null for a grant that doesn't use the owner's Chrome.
   * Refused when the grant names no Chrome or no sites: nothing is signed over an open list, and planque never approves such a grant.
   */
  suspend fun sitesSignature(browser: GrantBrowser?, grant: String, sign: suspend (built: () -> ByteArray) -> String): String? {
    if (browser?.driver != GrantBrowser.EXTENSION) return null
    val chrome = browser.chrome?.takeIf { it.isNotEmpty() } ?: throw PlanqueError.DigestMismatch
    val sites = browser.sites?.takeIf { it.isNotEmpty() } ?: throw PlanqueError.DigestMismatch
    val der = sign { sitesBytes(chrome, grant, sites) }
    return rawSignature(Base64.getMimeDecoder().decode(der)) ?: throw SignerException(Words.Approval.keyCouldNotSign)
  }

  private fun labelled(label: String, body: JsonObject): ByteArray =
    label.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + Jcs.canonical(body).toByteArray(Charsets.UTF_8)

  /**
   * The sites the extension blocks unless the phone allows them one by one (`chrome/src/sites.js` `SENSITIVE`,
   * vectors/chrome-sensitive.json): each a site and its subdomains. The approval card marks a grant's sensitive sites from
   * this, and signs an allowance only for one the owner ticked.
   */
  val SENSITIVE_SITES: List<String> = listOf(
    "1password.com", "lastpass.com", "bitwarden.com", "dashlane.com", "keepersecurity.com", "nordpass.com", "proton.me",
    "coinbase.com", "binance.com", "kraken.com", "metamask.io", "blockchain.com", "crypto.com", "ledger.com", "bitstamp.net",
    "paypal.com", "wise.com", "revolut.com", "n26.com", "venmo.com", "cash.app", "klarna.com", "payoneer.com",
    "ameli.fr", "nhs.uk", "mychart.com", "healthcare.gov",
  )

  /** A host with one of these labels names a bank, a government or a health account. The extension's own pattern. */
  const val SENSITIVE_LABELS =
    "(^|[.-])(bank|banking|banque|bancaire|banca|banco|bancario|sparkasse|volksbank|bausparkasse|credit-?union|tresor|impots|" +
      "irs|hmrc|gov|gouv|gob|bund|gc|nic|health|sante|gesundheit|salud|saude|patient|medicare|medicaid)([.-]|$)"

  private val sensitiveLabels = Regex(SENSITIVE_LABELS)

  /**
   * Whether the extension treats [host] as sensitive, read as its `hostOf` reads a host: lower case, an ideographic full stop
   * as a dot, and no trailing dot. So the card marks exactly the sites the extension blocks.
   */
  fun isSensitive(host: String): Boolean {
    val h = host.lowercase().replace(Regex("[。．｡]"), ".").removeSuffix(".")
    return SENSITIVE_SITES.any { h == it || h.endsWith(".$it") } || sensitiveLabels.containsMatchIn(h)
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

  /** [raw] r‖s (64 bytes, base64url) as the DER `SHA256withECDSA` verifies; null when it isn't 64 bytes. */
  fun derSignature(raw: String): ByteArray? {
    val b = base64UrlDecoded(raw)?.takeIf { it.size == 64 } ?: return null
    fun integer(v: ByteArray): ByteArray {
      val trimmed = v.dropWhile { it == 0.toByte() }.toByteArray()
      val t = if (trimmed.isEmpty()) byteArrayOf(0) else trimmed
      val body = if (t[0].toInt() and 0x80 != 0) byteArrayOf(0) + t else t
      return byteArrayOf(0x02, body.size.toByte()) + body
    }
    val seq = integer(b.copyOfRange(0, 32)) + integer(b.copyOfRange(32, 64))
    return byteArrayOf(0x30, seq.size.toByte()) + seq
  }

  /** Whether [raw] (r‖s, base64url) is the key [publicKeyDER]'s P-256 signature of [message]. Anything malformed is false. */
  fun verifiesRaw(publicKeyDER: ByteArray, message: ByteArray, raw: String): Boolean = runCatching {
    val der = derSignature(raw) ?: return false
    val key = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyDER))
    java.security.Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(der) }
  }.getOrDefault(false)

  /**
   * What a re-pairing carries from [oldChrome] (docs/chrome.md "Your Chrome" 1): each connection on it whose sites this phone
   * signed for it, read from the phone's own signature (`chrome_sites_sig`), checked here with [approvalKeyDER], never from
   * planque's list as given. A connection whose signature doesn't hold, or that has none (one carried before), isn't carried:
   * it ends with the old Chrome. Ended or failed connections aren't carried either; planque refuses them. That status rule is
   * the app's until planque lists each Chrome's live connections ("Your Chrome" 4), as iOS's `ChromeConnections.on`.
   */
  fun carriedFrom(oldChrome: String, grants: List<Grant>, approvalKeyDER: ByteArray): List<Carried> =
    grants.mapNotNull { g ->
      val b = g.request.browser
      val sites = b?.sites.orEmpty()
      val sig = g.chromeSitesSig
      when {
        b?.driver != GrantBrowser.EXTENSION || b.chrome != oldChrome -> null
        g.status == "disconnected" || g.status == "failed" -> null
        sites.isEmpty() || sig.isNullOrEmpty() -> null
        !verifiesRaw(approvalKeyDER, sitesBytes(oldChrome, g.id, sites), sig) -> null
        else -> Carried(g.id, sites)
      }
    }

  /**
   * What the pick starts on, as iOS's `ChromeReplace.preselect`: the paired Chrome whose profile id is the offer's; else, when one
   * side has no id (an extension from before ids, or one reinstalled), the one paired Chrome on the same browser and system. None
   * otherwise ("a new Chrome"), never a guess between two.
   */
  fun replacedBy(offer: ChromeOffer, chromes: List<Chrome>): Chrome? {
    if (offer.profileId.isNotEmpty()) chromes.firstOrNull { it.profileId == offer.profileId }?.let { return it }
    val alike = chromes.filter { it.profileId.isNullOrEmpty() || offer.profileId.isEmpty() }
      .filter { offer.browser.isNotEmpty() && it.browser == offer.browser && offer.os.isNotEmpty() && it.os == offer.os }
    return alike.singleOrNull()
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
