package bot.tafs.app.kiff

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Signing in once, inside tafs (row 39, docs/android-app.md "Onboarding"): KIFF's `/oauth/authorize` in a Custom Tab, with
 * PKCE, then `POST /v1/auth/app/token` with the device's keys, as `/v1/auth/device/start` takes them today. Behind
 * [ONBOARDING_ON], off: with it off, nothing here is reached and today's device-code sign-in is the only one.
 *
 * Only what the app must do itself is here: make the verifier and its S256 challenge, ask with a fresh `state`, and take back
 * only our own redirect carrying that same state. KIFF decides the rest (who the person is, the account, the device's session).
 */
object AppSignIn {
  /** The tafs apps' one client at KIFF. */
  const val CLIENT_ID = "tafs-app"

  /** The redirect KIFF sends the code to: a private-use scheme, the Play package's own name (RFC 8252 §7.1), matched exactly. */
  const val REDIRECT = "dev.kiff.tafs:/oauth"

  /** The languages KIFF's sign-in page speaks; another is left out, and the page uses its own. */
  private val LANGUAGES = setOf("en", "de", "fr", "es", "it", "pt", "nl", "pl")

  /** One sign-in's secrets: the verifier never leaves the app until the token request, and the state is checked on return. */
  class Attempt internal constructor(val verifier: String, val state: String) {
    val challenge: String get() = challengeOf(verifier)
  }

  /** A new attempt: 32 random bytes each, base64url without padding (a 43-character verifier, RFC 7636 §4.1). */
  fun attempt(random: SecureRandom = SecureRandom()): Attempt = Attempt(token(random), token(random))

  /** S256: base64url(SHA-256(the verifier's ASCII)), without padding (RFC 7636 §4.2). */
  fun challengeOf(verifier: String): String =
    b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

  /** KIFF's sign-in page for [attempt], in [lang] when KIFF speaks it, with [email] filled in when the person gave one. */
  fun authorizeUrl(base: String, attempt: Attempt, lang: String, email: String? = null): String {
    val q = buildList {
      add("response_type" to "code")
      add("client_id" to CLIENT_ID)
      add("redirect_uri" to REDIRECT)
      add("code_challenge" to attempt.challenge)
      add("code_challenge_method" to "S256")
      add("state" to attempt.state)
      lang.substringBefore('-').lowercase().takeIf { it in LANGUAGES }?.let { add("ui_locales" to it) }
      email?.trim()?.takeIf { it.isNotEmpty() }?.let { add("login_hint" to it) }
    }
    return "${base.trimEnd('/')}/oauth/authorize?" + q.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
  }

  /** What a link back to the app says. */
  sealed interface Answer {
    /** KIFF's code, for the token request. */
    data class Code(val code: String) : Answer
    /** KIFF's own refusal: `access_denied` when the person closed or declined, `unsupported_client` with the switch off. */
    data class Refused(val error: String) : Answer
    /** Our redirect, but not this attempt's state: never used, whatever it carries. */
    data object WrongState : Answer
    /** Not our redirect at all. */
    data object NotOurs : Answer
  }

  /** Reads [link] for [attempt]: only [REDIRECT] exactly, and only with the attempt's own state, gives a code. */
  fun answer(link: String, attempt: Attempt): Answer {
    val uri = runCatching { URI(link) }.getOrNull() ?: return Answer.NotOurs
    if (uri.scheme != "dev.kiff.tafs" || uri.rawAuthority != null || uri.rawPath != "/oauth" || uri.rawFragment != null) return Answer.NotOurs
    val q = query(uri.rawQuery)
    // The state first: a link with another attempt's state, or none, says nothing about this one, not even a refusal.
    if (!MessageDigest.isEqual((q["state"] ?: "").toByteArray(), attempt.state.toByteArray())) return Answer.WrongState
    q["error"]?.let { return Answer.Refused(it) }
    val code = q["code"]?.takeIf { it.isNotEmpty() } ?: return Answer.Refused("invalid_request")
    return Answer.Code(code)
  }

  private fun query(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in raw.split('&')) {
      val k = URLDecoder.decode(part.substringBefore('='), "UTF-8")
      // A name given twice is ambiguous: neither is taken.
      if (k in out) { out[k] = ""; continue }
      out[k] = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
    }
    return out
  }

  private fun token(random: SecureRandom): String = ByteArray(32).also(random::nextBytes).let(::b64)
  private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** Row 39's sign-in and first run (welcome tick, three cards, first Home). Off until the end-to-end test with the owner. */
const val ONBOARDING_ON = false
