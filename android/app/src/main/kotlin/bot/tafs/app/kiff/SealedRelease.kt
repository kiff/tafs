package bot.tafs.app.kiff

import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The phone's release of one sealed sign-in value for one fill (docs/chrome.md, "The phone's release card"). The extension
 * fills a sealed value only with it (`protocol.checkRelease`): the phone's approval key, behind a fresh fingerprint, over
 * `tafs-chrome/release/v1` ‖ 0x00 ‖ JCS of `{chrome_id, op, sealed, origin, field_ref, read_id, nonce, exp}`
 * (vectors/chrome-release.json). The phone never has the value; it only names which one, where, for which field.
 *
 * **Off** ([ENABLED]) until planque holds a sealed fill and puts the answer's release on its step: planque's half is to come,
 * and must match vectors/chrome-release.json.
 */
object SealedRelease {
  /** Off until planque holds a sealed fill. Nothing draws the card or signs a release while it's false. */
  const val ENABLED = false

  const val LABEL = "tafs-chrome/release/v1"

  /** A release is good for 60 s from the owner's yes (`exp`): the extension takes it at most 2 minutes ahead of its clock. */
  const val VALID_MS = 60_000L

  /** Which part of a saved sign-in a handle names: the card says it, read from the handle, never from planque's words. */
  enum class Part { PASSWORD, USERNAME }

  /** A handle as the extension makes them (`parseHandle`): exactly `sec_<id>:password` or `sec_<id>:username`, nothing else. */
  private val HANDLE = Regex("^sec_[A-Za-z0-9_-]+:(password|username)$")

  /** The part [handle] names, or null for anything that isn't one: then nothing is signed and the card is unreadable. */
  fun part(handle: String): Part? = HANDLE.matchEntire(handle)?.groupValues?.get(1)?.let {
    if (it == "password") Part.PASSWORD else Part.USERNAME
  }

  /** The grant's sites as this phone signed them (`step.sites_signed`): planque relays them and can't widen them. */
  data class SitesSigned(val sites: List<String>, val sig: String)

  /** A sensitive site this phone allowed for the grant (`step.sensitive_allowed`), with its `tafs-chrome/sensitive/v1` signature. */
  data class Allowance(val site: String, val sig: String)

  /**
   * A held sealed fill, as the card reads it: the held step's own fields, copied byte for byte into what's signed, and what the
   * phone checks before it signs. [expiresAt] is the hold's end (unix ms).
   */
  data class Fill(
    val chromeId: String,
    val op: String,
    val sealed: String,
    val origin: String,
    val fieldRef: String,
    val readId: String,
    val grant: String,
    val sitesSigned: SitesSigned?,
    val sensitiveAllowed: List<Allowance> = emptyList(),
    val expiresAt: Long,
  )

  /** Why the phone won't sign a release, whatever planque says: the card is then drawn as an unreadable hold, and says why. */
  enum class Refusal {
    /** The hold has no op, read or field to name. */
    INCOMPLETE,
    /** The handle isn't exactly `sec_<id>:password` or `:username`. */
    HANDLE,
    /** The Chrome isn't one this phone is paired with. */
    CHROME,
    /** The hold has expired: a late yes signs nothing. */
    EXPIRED,
    /** The origin isn't `https://host` (an optional port, no path, no user). */
    ORIGIN,
    /** The origin isn't within the sites this phone signed for the grant, or that signature doesn't hold. */
    NOT_SIGNED,
    /** A sensitive site with no allowance of this phone's own for it. */
    SENSITIVE,
  }

  /**
   * Whether the phone may sign a release for [fill] (null) or why not. [pairedChromes] are the Chromes this phone is paired with,
   * [approvalKeyDER] its approval key's public key, [now] in unix ms. Every check is the phone's own, never planque's word.
   */
  fun refusal(fill: Fill, pairedChromes: Set<String>, approvalKeyDER: ByteArray, now: Long): Refusal? {
    if (fill.op.isEmpty() || fill.readId.isEmpty() || fill.fieldRef.isEmpty()) return Refusal.INCOMPLETE
    if (part(fill.sealed) == null) return Refusal.HANDLE
    if (fill.chromeId.isEmpty() || fill.chromeId !in pairedChromes) return Refusal.CHROME
    if (now >= fill.expiresAt) return Refusal.EXPIRED
    val host = httpsHost(fill.origin) ?: return Refusal.ORIGIN
    val signed = fill.sitesSigned ?: return Refusal.NOT_SIGNED
    if (signed.sites.none { within(host, it) }) return Refusal.NOT_SIGNED
    if (!ChromePairing.verifiesRaw(approvalKeyDER, ChromePairing.sitesBytes(fill.chromeId, fill.grant, signed.sites), signed.sig)) {
      return Refusal.NOT_SIGNED
    }
    if (ChromePairing.isSensitive(host)) {
      val allowed = fill.sensitiveAllowed.any { a ->
        within(host, a.site) && ChromePairing.verifiesRaw(approvalKeyDER, ChromePairing.sensitiveBytes(fill.chromeId, fill.grant, a.site), a.sig)
      }
      if (!allowed) return Refusal.SENSITIVE
    }
    return null
  }

  /** The release that goes in the hold's answer (`release`), for planque to put on the step. */
  data class Release(val chromeId: String, val op: String, val origin: String, val nonce: String, val exp: Long, val sig: String)

  /** The bytes the approval key signs: label ‖ 0x00 ‖ JCS of the eight fields (vectors/chrome-release.json `signed_bytes`). */
  fun bytes(chromeId: String, op: String, sealed: String, origin: String, fieldRef: String, readId: String, nonce: String, exp: Long): ByteArray =
    LABEL.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + Jcs.canonical(
      buildJsonObject {
        put("chrome_id", JsonPrimitive(chromeId))
        put("op", JsonPrimitive(op))
        put("sealed", JsonPrimitive(sealed))
        put("origin", JsonPrimitive(origin))
        put("field_ref", JsonPrimitive(fieldRef))
        put("read_id", JsonPrimitive(readId))
        put("nonce", JsonPrimitive(nonce))
        put("exp", JsonPrimitive(exp))
      },
    ).toByteArray(Charsets.UTF_8)

  /** A nonce of the phone's own: 16 random bytes, unpadded base64url, made when the owner allows and never reused. */
  fun newNonce(): String = ChromePairing.base64Url(ByteArray(16).also { random.nextBytes(it) })

  /**
   * The release for [fill], made at [now]: a new nonce, `exp` 60 s on, and [sign]'s signature (the approval key's DER, base64,
   * behind the fingerprint) as raw r‖s. Refused (null) when [refusal] refuses, so a release is never made for a fill the card
   * couldn't draw.
   */
  suspend fun release(
    fill: Fill,
    pairedChromes: Set<String>,
    approvalKeyDER: ByteArray,
    now: Long,
    nonce: String = newNonce(),
    sign: suspend (built: () -> ByteArray) -> String,
  ): Release? {
    if (refusal(fill, pairedChromes, approvalKeyDER, now) != null) return null
    val exp = now + VALID_MS
    val der = sign { bytes(fill.chromeId, fill.op, fill.sealed, fill.origin, fill.fieldRef, fill.readId, nonce, exp) }
    val sig = ChromePairing.rawSignature(Base64.getMimeDecoder().decode(der)) ?: return null
    return Release(fill.chromeId, fill.op, fill.origin, nonce, exp, sig)
  }

  /** [origin]'s host when it's exactly `https://host` or `https://host:port`, lower case; null otherwise. */
  internal fun httpsHost(origin: String): String? = runCatching {
    val u = URI(origin)
    if (u.scheme != "https" || u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) return null
    if (!u.rawPath.isNullOrEmpty()) return null
    val host = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    host.takeIf { origin == "https://$host" + (if (u.port >= 0) ":${u.port}" else "") }
  }.getOrNull()

  /** Whether [host] is [site] or one of its subdomains, as the extension reads a run's sites. */
  internal fun within(host: String, site: String): Boolean = host == site || host.endsWith(".$site")

  private val random = SecureRandom()
}
