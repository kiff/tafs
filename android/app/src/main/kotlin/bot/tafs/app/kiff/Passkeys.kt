package bot.tafs.app.kiff

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One of the account's passkeys, as KIFF lists them (`GET /v1/me/passkeys`). */
@Serializable
data class Passkey(
  /** Null for a computer's own passkey: it goes with its device, and has no id of its own to remove. */
  val id: String? = null,
  val name: String = "",
  /** Unknown (null) until its first approval reads the backup-state flag. */
  val synced: Boolean? = null,
  @SerialName("created_at") val createdAt: String? = null,
  /** The computer it belongs to, for a computer's own passkey. */
  @SerialName("device_id") val deviceId: String? = null,
) {
  /** Only an account passkey with an id can be removed from here: a computer's own goes with its device. */
  val removable: Boolean get() = id != null && deviceId == null

  companion object {
    /**
     * Whether removing [passkey] leaves no passkey that can approve. Every passkey KIFF lists approves, a computer's own included,
     * so Remove warns only when none other is left (`settings.devices.passkey_remove_last`), as on Windows and iOS.
     */
    fun removesLast(passkey: Passkey, among: List<Passkey>): Boolean = among.none { !it.isSameAs(passkey) }
  }

  /** The same passkey, by what names it in KIFF: its id, or a computer's own by its computer, whatever its name says now. */
  fun isSameAs(other: Passkey): Boolean = when {
    id != null -> id == other.id
    deviceId != null -> deviceId == other.deviceId
    else -> this == other
  }
}

@Serializable
internal data class PasskeyList(val passkeys: List<Passkey> = emptyList())

@Serializable
internal data class PasskeyBody(val passkey: Passkey)

/**
 * A kiff.dev passkey made on this phone and registered with KIFF, with proof of possession: the creation's response as
 * WebAuthn gives it, which KIFF reads the credential from (the phone parses nothing of it). KIFF's contract for
 * `POST /v1/me/passkeys`; the shared test vector is `vectors/passkey-registration.json`.
 */
object PasskeyRegistration {
  const val RP_ID = "kiff.dev"
  const val RP_NAME = "KIFF"
  const val MESSAGE_V1 = "kiff.passkey.register.v1"

  /** KIFF's bounds on a passkey's name. */
  const val NAME_MAX = 64

  private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

  /** What the challenge covers: `kiff.passkey.register.v1\n<tenant>\n<subject>\n<issued_at>`. */
  fun message(tenant: String, subject: String, issuedAt: String): String = listOf(MESSAGE_V1, tenant, subject, issuedAt).joinToString("\n")

  /** The creation's challenge: base64url of the message's SHA-256. KIFF keeps no challenge, so this binds it. */
  fun challenge(tenant: String, subject: String, issuedAt: String): String =
    base64Url(MessageDigest.getInstance("SHA-256").digest(message(tenant, subject, issuedAt).toByteArray()))

  /**
   * The creation's options, as Credential Manager takes them (WebAuthn's JSON): rp kiff.dev, the person's subject as the
   * user's id and their email as its name, ES256 only, user verification required, a discoverable key preferred, and no
   * attestation, as KIFF asks.
   */
  fun createOptions(tenant: String, subject: String, email: String, issuedAt: String): JsonObject = buildJsonObject {
    put("challenge", challenge(tenant, subject, issuedAt))
    putJsonObject("rp") {
      put("id", RP_ID)
      put("name", RP_NAME)
    }
    putJsonObject("user") {
      put("id", base64Url(subject.toByteArray()))
      put("name", email)
      put("displayName", email)
    }
    putJsonArray("pubKeyCredParams") {
      addJsonObject {
        put("type", "public-key")
        put("alg", -7)
      }
    }
    putJsonObject("authenticatorSelection") {
      put("residentKey", "preferred")
      put("userVerification", "required")
    }
    put("attestation", "none")
  }

  /**
   * A name KIFF takes: trimmed of spaces and newlines at both ends, then 1 to 64 Unicode code points. Null for one it
   * refuses (empty, longer, or with a control character inside: below U+0020, or U+007F): never cut to fit, since a cut name
   * isn't the one the person gave.
   */
  fun validName(name: String): String? = name.trim { it.isWhitespace() || it == '\u0085' }.takeIf { kept ->
    kept.isNotEmpty() && kept.codePointCount(0, kept.length) <= NAME_MAX && kept.none { it < ' ' || it == '\u007f' }
  }

  /**
   * Whether KIFF takes a passkey from this person: an account's owners and admins only (KIFF answers a member `403
   * admins_only`). Read before Android is asked to make one, so a member's tap leaves no passkey on the phone that KIFF
   * then refuses. KIFF still decides.
   */
  fun mayAdd(role: String?): Boolean = role == "owner" || role == "admin"

  /** The passkey's name: the phone's model as KIFF takes it, or "Android phone" for a model it would refuse. */
  fun name(model: String?): String = model?.let(::validName) ?: "Android phone"

  /** When the challenge is made, as KIFF reads it: RFC 3339 in UTC, to the second. KIFF takes it within 10 minutes. */
  fun issuedAt(now: java.time.Instant): String = java.time.format.DateTimeFormatter.ISO_INSTANT.format(now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

  /**
   * KIFF's registration body from Credential Manager's response (its `registrationResponseJson`): the name, the time the
   * challenge was made with, and the response's `clientDataJSON` and `attestationObject` as they came. Never the earlier
   * body's `credential_id` or `public_key` too: KIFF refuses both kinds at once.
   */
  fun registerBody(name: String, issuedAt: String, registrationResponseJson: String): JsonObject {
    val response = KiffClient.json.parseToJsonElement(registrationResponseJson).jsonObject["response"]?.jsonObject
      ?: throw IllegalArgumentException("the passkey's response has no response")
    fun field(key: String) = response[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
      ?: throw IllegalArgumentException("the passkey's response has no $key")
    return buildJsonObject {
      put("name", name)
      put("issued_at", issuedAt)
      put("client_data_json", field("clientDataJSON"))
      put("attestation_object", field("attestationObject"))
    }
  }
}
