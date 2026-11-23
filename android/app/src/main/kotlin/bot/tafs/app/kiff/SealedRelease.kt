package bot.tafs.app.kiff

import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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

  /**
   * A held sealed fill, as the card reads it: the block's `release_fields`, copied byte for byte into what's signed. [expiresAt]
   * is the hold's end (unix ms). Nothing else of the block decides whether it's signed: the sites come from this phone's own
   * signed grants ([refusal]).
   */
  @Serializable
  data class Fill(
    val chromeId: String,
    val op: String,
    val sealed: String,
    val origin: String,
    val fieldRef: String,
    val readId: String,
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
    /** The origin isn't within the sites this phone signed for any of the account's connections on that Chrome. */
    NOT_SIGNED,
    /**
     * A sensitive site (a bank, wallet, password manager, payment, health or government account): this phone keeps no
     * `tafs-chrome/sensitive/v1` allowance of its own to check one against, so it never signs a release for one.
     */
    SENSITIVE,
  }

  /**
   * Whether the phone may sign a release for [fill] (null) or why not. [pairedChromes] are the Chromes this phone is paired with,
   * [grants] the account's connections as planque lists them, [approvalKeyDER] its approval key's public key, [now] in unix ms.
   * Every check is the phone's own, never planque's word: the origin must be within the sites this phone signed, with
   * [approvalKeyDER], for one of [grants] on that same Chrome ([ChromePairing.signedSites]). A list planque widened, another
   * key's signature, or a connection on another Chrome counts for nothing.
   */
  fun refusal(fill: Fill, pairedChromes: Set<String>, grants: List<Grant>, approvalKeyDER: ByteArray, now: Long): Refusal? {
    if (fill.op.isEmpty() || fill.readId.isEmpty() || fill.fieldRef.isEmpty()) return Refusal.INCOMPLETE
    if (part(fill.sealed) == null) return Refusal.HANDLE
    if (fill.chromeId.isEmpty() || fill.chromeId !in pairedChromes) return Refusal.CHROME
    if (now >= fill.expiresAt) return Refusal.EXPIRED
    val host = httpsHost(fill.origin) ?: return Refusal.ORIGIN
    val signed = grants.filter { it.request.browser?.chrome == fill.chromeId }.mapNotNull { ChromePairing.signedSites(it, approvalKeyDER) }
    if (signed.none { sites -> sites.any { within(host, it) } }) return Refusal.NOT_SIGNED
    if (ChromePairing.isSensitive(host)) return Refusal.SENSITIVE
    return null
  }

  /** The release that goes in the hold's answer (`release`), for planque to put on the step. */
  data class Release(val chromeId: String, val op: String, val origin: String, val nonce: String, val exp: Long, val sig: String) {
    /** As the answer carries it: `{chrome_id, op, origin, nonce, exp, sig}`. */
    fun json(): JsonObject = buildJsonObject {
      put("chrome_id", JsonPrimitive(chromeId))
      put("op", JsonPrimitive(op))
      put("origin", JsonPrimitive(origin))
      put("nonce", JsonPrimitive(nonce))
      put("exp", JsonPrimitive(exp))
      put("sig", JsonPrimitive(sig))
    }
  }

  /** Why the card can't allow [refusal], in the card's words. A late hold says nothing here: the card says it expired. */
  fun refusalWords(refusal: Refusal): String? = when (refusal) {
    Refusal.INCOMPLETE, Refusal.HANDLE -> bot.tafs.app.model.Words.Chrome.releaseUnreadable
    Refusal.CHROME -> bot.tafs.app.model.Words.Chrome.releaseOtherChrome
    Refusal.EXPIRED -> null
    Refusal.ORIGIN -> bot.tafs.app.model.Words.Chrome.releaseNotHttps
    Refusal.NOT_SIGNED -> bot.tafs.app.model.Words.Chrome.releaseNotConnected
    Refusal.SENSITIVE -> bot.tafs.app.model.Words.Chrome.releaseSensitive
  }

  /** What the release's fingerprint prompt says it signs: which part, for which site. */
  fun prompt(part: Part, site: String): String =
    if (part == Part.PASSWORD) bot.tafs.app.model.Words.Chrome.releasePromptPassword(site = site)
    else bot.tafs.app.model.Words.Chrome.releasePromptUsername(site = site)

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
   * The release for [fill], checked at [now]: a new nonce, `exp` 60 s after the owner allowed ([clock], read once the prompt
   * succeeded), and [sign]'s signature (the approval key's DER, base64, behind the fingerprint) as raw r‖s. Refused (null) when
   * [refusal] refuses, so a release is never made for a fill the card couldn't draw.
   */
  suspend fun release(
    fill: Fill,
    pairedChromes: Set<String>,
    grants: List<Grant>,
    approvalKeyDER: ByteArray,
    now: Long,
    nonce: String = newNonce(),
    clock: () -> Long = { now },
    sign: suspend (built: () -> ByteArray) -> String,
  ): Release? {
    if (refusal(fill, pairedChromes, grants, approvalKeyDER, now) != null) return null
    var exp = 0L
    val der = sign {
      exp = clock() + VALID_MS
      bytes(fill.chromeId, fill.op, fill.sealed, fill.origin, fill.fieldRef, fill.readId, nonce, exp)
    }
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
