package bot.tafs.app.kiff

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** planque's refusal, or the reason nothing was sent. */
sealed class PlanqueError(message: String) : Exception(message) {
  /** No signer is set: this step needs the owner's confirmation first. */
  data object NotSigning : PlanqueError("This step needs your confirmation first.")

  /** This device's id in KIFF couldn't be read: planque takes no signature without it, so nothing is sent. */
  data object NoDevice : PlanqueError("Couldn't reach KIFF to name this device. Check the connection and try again.")

  /** The grant's digest isn't the hash of the request shown: nothing is approved. */
  data object DigestMismatch : PlanqueError("This request doesn't match what it asks for. Nothing was approved.")

  class Server(val status: Int, message: String) : PlanqueError(message)
}

/** One tool of a grant's request. */
@Serializable
data class GrantTool(
  val tool: String,
  val name: String? = null,
  /** What it does, in the owner's words, as Setup asked for it. */
  val title: String? = null,
  @SerialName("read_only") val readOnly: Boolean? = null,
  /** Setup's own read, on Setup's reader Card, never the taf's. */
  @SerialName("setup_reads") val setupReads: Boolean? = null,
) {
  /** One of the taf's own tools, not Setup's read. */
  val tafs: Boolean get() = setupReads != true

  /** Its name in KIFF and at the relay: the Card's action. */
  val action: String get() = name ?: tool

  /** What the owner reads for it: its title, else its action in words. */
  val words: String get() = KiffMapping.name(action, title)
}

@Serializable
data class GrantRequest(val server: String = "", val agent: String = "", val tools: List<GrantTool> = emptyList())

@Serializable
data class GrantEvent(
  /** RFC 3339, as planque writes it (Go's nanoseconds included). */
  val at: String = "",
  /** In the owner's words, as planque writes it: the device's name, never an id. */
  val what: String = "",
)

/** A connection planque keeps for the account, or one waiting for the owner. */
@Serializable
data class Grant(
  val id: String,
  /** pending, approved, signed_in, listed, connected, failed or disconnected. */
  val status: String = "",
  /** What the owner reads before approving: each tool in words. */
  val text: String = "",
  /** Each tool's exact terms, one line each, shown under Details; the phone signs the request they're built from. */
  val terms: List<String>? = null,
  val digest: String = "",
  val request: GrantRequest = GrantRequest(),
  val account: String? = null,
  val tools: List<String>? = null,
  val reason: String? = null,
  val events: List<GrantEvent> = emptyList(),
  /** planque holds a confirmed sign-in for it: approving the tools connects them with no second sign-in. */
  @SerialName("signed_in") val signedIn: Boolean? = null,
  @SerialName("held_until") val heldUntil: String? = null,
  /** The vendor's name, as planque gives it: what its server calls itself. */
  @SerialName("vendor") val vendorName: String? = null,
  /** Whether [digest] is the hash of the request as received: the app shows Approve only then. Set by the client, never read from planque. */
  @Transient val digestMatches: Boolean = false,
  /** planque answered connected to this phone's approval, before its list says so. Never read, only set. */
  @Transient val connectedNow: Boolean = false,
) {
  /** The vendor, as the owner knows it: its name, else its server's host. */
  val vendor: String get() = vendorName ?: request.server

  val connected: Boolean get() = status == "connected" || connectedNow

  /** Signed in and planque listed the vendor's tools for Setup to choose from; nothing is in KIFF yet. */
  val listed: Boolean get() = status == "listed"

  /** A sign-in planque holds that no taf uses yet: the owner can see and end it. */
  val holdsSignIn: Boolean get() = listed || (status == "pending" && signedIn == true)

  /** This grant as planque's answer to an approval left it. */
  fun marked(connected: Boolean): Grant = copy(connectedNow = connected)
}

@Serializable
internal data class Grants(val grants: List<Grant> = emptyList())

/** planque's answer to an approval. */
@Serializable
data class Approved(
  val status: String = "",
  /** The vendor's own sign-in page, to open in a browser. None when planque already holds the sign-in. */
  @SerialName("sign_in_url") val signInUrl: String? = null,
  @SerialName("expires_at") val expiresAt: String? = null,
)

/** planque's answer to an enrolment: the account, and the phone key's fingerprint as planque shows it in its record. */
@Serializable
data class Enrolment(val tenant: String = "", val device: String = "")

/** What a finished vendor sign-in hands back: `tafs://planque/signed-in?tenant&grant&confirm`. */
data class SignedIn(val tenant: String, val grant: String, val code: String)

/**
 * planque (kiff/planque), which keeps a taf's vendor sign-ins so they never reach the phone, tafs cloud, the agent or KIFF. The
 * phone approves each grant, confirms each vendor sign-in and disconnects. Every call carries the phone key's signature
 * (`Planque-Device-Signature`), so planque acts only on what this device's owner signed: it asks KIFF whether the device signed.
 */
class PlanqueClient(
  private val transport: Transport,
  val baseUrl: String = "https://planque.kiff.dev",
  private val signingTime: SigningTime = SigningTime(),
) {
  /** The account the phone signed in to KIFF with. */
  var tenant: String? = null

  /** Signs one request: set while a step the owner confirmed runs, and for reads, which ask for nothing. */
  var signer: ((ByteArray) -> String)? = null

  /** This device's id in KIFF, sent with each signature (`d=`) so planque asks KIFF whether the device signed. */
  var deviceId: String? = null

  /** Reads [deviceId] from KIFF when it isn't known yet, right before a signature. */
  var deviceLoader: (suspend () -> String?)? = null

  /**
   * Hands planque the KIFF connector key it keeps to register tools. Signed with [deviceId], any of the owner's devices may. The
   * device's key goes too, for a planque from before that, which enrols one phone by its key.
   */
  suspend fun enrol(deviceKey: ByteArray, connectorKey: String): Enrolment =
    decode(raw("POST", "enrolment", body("device_key" to java.util.Base64.getEncoder().encodeToString(deviceKey), "connector_key" to connectorKey)))

  /**
   * Every grant for the account, each with its digest checked against its request. [signedBy] signs this read alone, leaving
   * [signer] as it is: a refresh that runs while a confirmed step waits on the vendor's page must not end that step's signer.
   */
  suspend fun grants(signedBy: ((ByteArray) -> String)? = null): List<Grant> {
    val data = raw("GET", "grants", null, signedBy)
    val grants = KiffClient.json.decodeFromString<Grants>(String(data)).grants
    // The request as planque sent it, numbers as written, so the digest is planque's.
    val raws = runCatching { KiffClient.json.parseToJsonElement(String(data)).jsonObject["grants"]?.jsonArray }.getOrNull().orEmpty()
    return grants.map { grant ->
      val request = raws.firstOrNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content == grant.id }?.jsonObject?.get("request")
      val matches = request != null && runCatching { sha256Hex(Jcs.canonical(request).toByteArray()) == grant.digest }.getOrDefault(false)
      grant.copy(digestMatches = matches)
    }
  }

  /** Approves [grant] as shown: its digest is signed. Refused here when the digest isn't the hash of the request the owner was shown. */
  suspend fun approve(grant: Grant): Approved {
    if (!grant.digestMatches) throw PlanqueError.DigestMismatch
    return decode(raw("POST", "grants/${escaped(grant.id)}/approve", body("digest" to grant.digest)))
  }

  /** Confirms the vendor sign-in with the code it handed back to the app. Only the phone that approved can, within 15 minutes; then planque registers the tools in KIFF. */
  suspend fun confirm(grantId: String, code: String): Grant =
    decode(raw("POST", "grants/${escaped(grantId)}/confirm", body("confirm" to code)))

  /** Removes the grant's tools from KIFF and revokes its token at the vendor. */
  suspend fun disconnect(grantId: String): Grant = decode(raw("DELETE", "grants/${escaped(grantId)}", null))

  /** Forgets the account (kiff/tafs#185): every connection disconnected and its tokens revoked. Safe to ask again. */
  suspend fun eraseAccount() {
    raw("DELETE", "", null)
  }

  private inline fun <reified T> decode(bytes: ByteArray): T = KiffClient.json.decodeFromString(String(bytes))

  private fun body(vararg fields: Pair<String, String>): ByteArray =
    KiffClient.json.encodeToString(JsonObject.serializer(), JsonObject(fields.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) })).toByteArray()

  private fun escaped(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

  private suspend fun raw(method: String, path: String, body: ByteArray?, override: ((ByteArray) -> String)? = null): ByteArray {
    val account = tenant
    val sign = override ?: signer
    if (account == null || sign == null) throw PlanqueError.NotSigning
    // The account itself when path is empty (erasing it), else one of its routes.
    val uri = "/v1/accounts/${escaped(account)}" + if (path.isEmpty()) "" else "/$path"
    val signedAt = signingTime.next()
    val message = message(baseUrl, account, method, uri, body ?: ByteArray(0), signedAt)
    // planque asks KIFF whether this device signed: never a key alone.
    if (deviceId == null) deviceId = deviceLoader?.invoke()
    val device = deviceId ?: throw PlanqueError.NoDevice
    val headers = linkedMapOf(
      "Accept" to "application/json",
      "User-Agent" to "tafs-android",
      "Planque-Device-Signature" to "t=$signedAt,v1=${sign(message)},d=$device",
    )
    if (body != null) headers["Content-Type"] = "application/json"
    val response = transport.send(HttpRequest(method, baseUrl.trimEnd('/') + uri, headers, body))
    if (response.status !in 200..299) {
      val said = runCatching { KiffClient.json.parseToJsonElement(String(response.body)).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
      throw PlanqueError.Server(response.status, said ?: "The connection answered ${response.status}")
    }
    return response.body
  }

  companion object {
    /** What the phone signs (planque's `device.Message`): seven lines, no trailing newline. */
    fun message(base: String, tenant: String, method: String, uri: String, body: ByteArray, signedAt: String): ByteArray =
      listOf("planque.request.v1", base.trimEnd('/'), tenant, method, uri, sha256Hex(body), signedAt).joinToString("\n").toByteArray()

    /** What a vendor sign-in handed back, or null for a link that isn't one. */
    fun signedIn(url: String): SignedIn? {
      val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
      if (uri.scheme != "tafs" || uri.host != "planque" || uri.path != "/signed-in") return null
      val query = (uri.rawQuery ?: return null).split("&").mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i) to java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
      }.toMap()
      fun value(name: String) = query[name]?.takeIf { it.isNotEmpty() }
      return SignedIn(value("tenant") ?: return null, value("grant") ?: return null, value("confirm") ?: return null)
    }

    internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}

private fun sha256Hex(bytes: ByteArray): String = PlanqueClient.sha256Hex(bytes)
