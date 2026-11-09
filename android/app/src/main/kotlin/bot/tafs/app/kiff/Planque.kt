package bot.tafs.app.kiff

import bot.tafs.app.model.Words
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** planque's refusal, or the reason nothing was sent. */
sealed class PlanqueError(message: String?) : Exception(message) {
  /** No signer is set: this step needs the owner's confirmation first. */
  data object NotSigning : PlanqueError(null) { override val message: String get() = Words.Error.needsConfirmationFirst }

  /** This device's id in KIFF couldn't be read: planque takes no signature without it, so nothing is sent. */
  data object NoDevice : PlanqueError(null) { override val message: String get() = Words.Error.noDeviceName }

  /** The grant's digest isn't the hash of the request shown: nothing is approved. */
  data object DigestMismatch : PlanqueError(null) { override val message: String get() = Words.Error.requestMismatch }

  class Server(val status: Int, message: String) : PlanqueError(message)

  /** Signed in an approval window that has ended (kiff/tafs#494): nothing was done; a new window, then the same call again. */
  class WindowRequired(message: String) : PlanqueError(message)
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
data class GrantRequest(
  val server: String = "",
  val agent: String = "",
  val tools: List<GrantTool> = emptyList(),
  /** `api_key` for a vendor reached with a key the owner gives; none for one signed in to. */
  val kind: String? = null,
  /**
   * The connection's `kiff.profile.v1` (KIFF's profile contract), when planque sends one: inside the request, so the grant's digest the phone
   * checks covers it. The phone asks KIFF for its words and signs both digests on approval.
   */
  val profile: kotlinx.serialization.json.JsonObject? = null,
  /** A managed provider's connection: which provider and toolkit it is (`{provider, toolkit}`). */
  val connector: GrantConnector? = null,
)

/** The catalog entry of a managed Slack bot connection. */
const val SLACK_BOT_ENTRY = "connector:composio:slackbot"

/** A managed provider's connection, as planque names it: `composio`, and its toolkit, such as `slackbot`. */
@Serializable
data class GrantConnector(val provider: String? = null, val toolkit: String? = null)

/** A Slack channel the taf's Slack bot can see, for a Slack door: its ID, its name, and whether it's private. */
@Serializable
data class SlackChannel(val id: String, val name: String = "", val private: Boolean = false)

/** planque's live sign-in view for a browser connection: its one-time URL, when it lapses, and its words. */
@Serializable
data class SiteSignInView(val view: String, val expires: String? = null, val words: String? = null)

/** The bot a bot connection's token is, as planque shows it. */
@Serializable
data class BotView(val id: Long = 0, val username: String = "")

@Serializable
private data class SlackChannels(val channels: List<SlackChannel> = emptyList())

/** A trigger planque subscribed for a grant: its id, to remove it again. */
@Serializable
data class GrantTrigger(val id: String? = null, val trigger: String? = null) {
  val key: String? get() = id ?: trigger
}

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
  /**
   * pending, approved, signed_in, listed, connected, failed, disconnected, or needs_reconnect: connected once, then signed out
   * by its vendor, so it waits for a real sign-in again.
   */
  val status: String = "",
  /** planque's words for the status, shown as they are: "Signed out by Stripe · sign in again". */
  @SerialName("status_words") val statusWords: String? = null,
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
  /** The catalog entry it was connected from, such as `connector:composio:slackbot`. */
  val entry: String? = null,
  /** The name of the taf it was made for, as planque keeps it: what a removed taf's connection is still called. */
  @SerialName("agent_name") val agentName: String? = null,
  /** The connection's provider, as planque names it; a bot door lists the connections whose provider it names. */
  val provider: String? = null,
  /** The bot a bot connection's token is, once given, as planque shows it: its @username names it. */
  val telegram: BotView? = null,
  /** Whether [digest] is the hash of the request as received: the app shows Approve only then. Set by the client, never read from planque. */
  @Transient val digestMatches: Boolean = false,
  /** planque answered connected to this phone's approval, before its list says so. Never read, only set. */
  @Transient val connectedNow: Boolean = false,
) {
  /** The vendor, as the owner knows it: its name, else its server's host. */
  val vendor: String get() = vendorName ?: request.server

  /** A Slack bot connection, which a Slack door listens through: by planque's entry, else its connector's toolkit, never a vendor name. */
  val isSlackBot: Boolean get() = entry == SLACK_BOT_ENTRY || request.connector?.toolkit == "slackbot"

  /** Whether planque names [p] as its provider, at the top or on its connector: a bot door's connections, by the provider tafs cloud names, never a vendor name. */
  fun isFrom(p: String): Boolean = provider == p || request.connector?.provider == p

  /** A bot connection's @username, when planque gives it. */
  val botName: String? get() = telegram?.username?.takeIf { it.isNotBlank() }?.let { "@" + it.removePrefix("@") }

  val connected: Boolean get() = status == "connected" || connectedNow

  /** Its vendor signed it out: the owner signs in again, and it's connected only once planque says so. */
  val needsReconnect: Boolean get() = status == "needs_reconnect"

  /**
   * Approved, and waiting on a sign-in at the vendor that hasn't come back: the owner may cancel it, which ends the grant, and
   * connect again (planque's disconnect takes a grant in any state).
   */
  val waitingSignIn: Boolean get() = status == "approved" && signedIn != true && !connectedNow

  /**
   * A managed provider's connection, connected: planque can't sign it in again in place (it can't tell which account a new
   * sign-in at the provider is), so it's disconnected and connected again instead.
   */
  val signsInThroughProvider: Boolean get() = request.connector != null && connected && !takesKey

  /** Reached with a key the owner gives (planque's `api_key`), not a sign-in: it's fixed by a new key. */
  val takesKey: Boolean get() = request.kind == "api_key"

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

  /**
   * The approval window [signer] signs in (kiff/tafs#494): set only while a step that takes one runs, so its calls go as a tap
   * (`v2`, with the window's id); planque asks KIFF whether it is open. Null signs as before.
   */
  @Volatile var window: String? = null

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

  /** One read of the account's grants, forwarded by tafs cloud; it grants and signs nothing there. */
  suspend fun grantsReadProof(sign: (ByteArray) -> String): String {
    val account = tenant ?: throw PlanqueError.NotSigning
    val uri = "/v1/accounts/${escaped(account)}/grants"
    val at = signingTime.next()
    val device = deviceId ?: deviceLoader?.invoke()?.also { if (tenant == account) deviceId = it } ?: throw PlanqueError.NoDevice
    return "t=$at,v1=${sign(message(baseUrl, account, "GET", uri, ByteArray(0), at))},d=$device"
  }

  /**
   * Approves [grant] as shown: its digest is signed. Refused here when the digest isn't the hash of the request the owner was
   * shown. With [words], KIFF's words for its profile that the owner read, the body carries `kiff_profile` (KIFF's profile contract), so the
   * same signature covers the profile and the words.
   */
  suspend fun approve(grant: Grant, words: ProfileWords? = null): Approved {
    if (!grant.digestMatches) throw PlanqueError.DigestMismatch
    val fields = buildMap {
      put("digest", kotlinx.serialization.json.JsonPrimitive(grant.digest))
      words?.let { put("kiff_profile", it.approval()) }
    }
    val body = KiffClient.json.encodeToString(JsonObject.serializer(), JsonObject(fields)).toByteArray()
    return decode(raw("POST", "grants/${escaped(grant.id)}/approve", body))
  }

  /**
   * Approves [grantId] with the body tafs cloud built for it (kiff/tafs#413): sent as it is, signed by the device. The phone
   * checks nothing of it; tafs cloud read the grant and decided.
   */
  suspend fun approveAsAsked(grantId: String, body: kotlinx.serialization.json.JsonElement): Approved =
    decode(raw("POST", "grants/${escaped(grantId)}/approve", KiffClient.json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), body).toByteArray()))

  /**
   * Gives an API-key connection its key, as shown, with the same checks as approving: the body carries the digest the owner
   * approved, the key's SHA-256 and `kiff_profile` when it has one, and is signed; the key itself goes once, in
   * `Planque-Vendor-Key`, to planque, which keeps it. Neither the phone nor tafs cloud keeps it.
   */
  suspend fun giveKey(grant: Grant, key: String, words: ProfileWords? = null): Grant {
    if (!grant.digestMatches) throw PlanqueError.DigestMismatch
    val trimmed = key.trim()
    val fields = buildMap {
      put("digest", kotlinx.serialization.json.JsonPrimitive(grant.digest))
      put("key_sha256", kotlinx.serialization.json.JsonPrimitive(sha256Hex(trimmed.toByteArray())))
      words?.let { put("kiff_profile", it.approval()) }
    }
    val body = KiffClient.json.encodeToString(JsonObject.serializer(), JsonObject(fields)).toByteArray()
    return decode(raw("POST", "grants/${escaped(grant.id)}/key", body, headers = mapOf(VENDOR_KEY to trimmed)))
  }

  /** Confirms the vendor sign-in with the code it handed back to the app. Only the phone that approved can, within 15 minutes; then planque registers the tools in KIFF. */
  suspend fun confirm(grantId: String, code: String): Grant =
    decode(raw("POST", "grants/${escaped(grantId)}/confirm", body("confirm" to code)))

  /** The channels a Slack bot connection can see (at most 200, no message content), for a Slack door. A signed read. */
  /**
   * Opens a browser connection's live sign-in view (kiff/planque#86, step 4), signed by this device: a one-time URL good for ten
   * minutes, which the app opens in a web view and nothing more, and the words to show over it.
   */
  suspend fun siteSignIn(grantId: String, signedBy: ((ByteArray) -> String)? = null): SiteSignInView =
    decode(raw("POST", "grants/${escaped(grantId)}/sign-in", "{}".toByteArray(), signedBy))

  suspend fun slackChannels(grantId: String, signedBy: ((ByteArray) -> String)? = null): List<SlackChannel> {
    val data = raw("GET", "grants/${escaped(grantId)}/channels", null, signedBy)
    // A list, or an object holding one.
    val json = KiffClient.json.parseToJsonElement(String(data))
    return if (json is kotlinx.serialization.json.JsonArray) KiffClient.json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(SlackChannel.serializer()), json)
    else KiffClient.json.decodeFromJsonElement(SlackChannels.serializer(), json).channels
  }

  /**
   * Subscribes a Slack bot connection's [slug] to [channels], signed by this device: planque builds the provider's configuration
   * from these channels alone and pins the trigger's definition. Without it, a Slack door hears nothing.
   */
  suspend fun subscribe(grantId: String, account: String, slug: String, channels: List<String>): GrantTrigger {
    val body = KiffClient.json.encodeToString(
      JsonObject.serializer(),
      JsonObject(mapOf(
        "account" to kotlinx.serialization.json.JsonPrimitive(account),
        "slug" to kotlinx.serialization.json.JsonPrimitive(slug),
        "channels" to kotlinx.serialization.json.JsonArray(channels.map { kotlinx.serialization.json.JsonPrimitive(it) }),
      )),
    ).toByteArray()
    return decode(raw("POST", "grants/${escaped(grantId)}/triggers", body))
  }

  /**
   * Subscribes a connection to an app's event, [slug], with the owner's filters [config] as tafs cloud gave them, signed by
   * this device: planque checks them against the event's own schema and subscribes exactly those.
   */
  suspend fun subscribeEvent(grantId: String, account: String, slug: String, config: JsonObject): GrantTrigger {
    val body = KiffClient.json.encodeToString(
      JsonObject.serializer(),
      JsonObject(mapOf(
        "account" to kotlinx.serialization.json.JsonPrimitive(account),
        "slug" to kotlinx.serialization.json.JsonPrimitive(slug),
        "config" to config,
      )),
    ).toByteArray()
    return decode(raw("POST", "grants/${escaped(grantId)}/triggers", body))
  }

  /** Removes a trigger planque subscribed, signed by this device: its delivery stops first, then the provider's listener. */
  suspend fun unsubscribe(grantId: String, trigger: String, account: String) {
    raw("DELETE", "grants/${escaped(grantId)}/triggers/${escaped(trigger)}", body("account" to account))
  }

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

  private suspend fun raw(method: String, path: String, body: ByteArray?, override: ((ByteArray) -> String)? = null, headers: Map<String, String> = emptyMap()): ByteArray {
    val account = tenant
    val sign = override ?: signer
    if (account == null || sign == null) throw PlanqueError.NotSigning
    // The account itself when path is empty (erasing it), else one of its routes.
    val uri = "/v1/accounts/${escaped(account)}" + if (path.isEmpty()) "" else "/$path"
    val signedAt = signingTime.next()
    // Inside an approval window, the device key signs with the window's id; only the calls of a step that takes one.
    val held = window.takeIf { override == null }
    val message = messageWindow(baseUrl, account, method, uri, body ?: ByteArray(0), signedAt, held)
    // planque asks KIFF whether this device signed: never a key alone. One read while the account changed is kept only if the
    // account is still this request's: kept otherwise, it would name the last account's device to the next.
    val device = deviceId ?: deviceLoader?.invoke()?.also { if (tenant == account) deviceId = it } ?: throw PlanqueError.NoDevice
    val sent = linkedMapOf(
      "Accept" to "application/json",
      "User-Agent" to "tafs-android",
      "Planque-Device-Signature" to if (held != null) "t=$signedAt,v2=${sign(message)},w=$held,d=$device" else "t=$signedAt,v1=${sign(message)},d=$device",
    )
    if (body != null) sent["Content-Type"] = "application/json"
    sent.putAll(headers)
    val response = transport.send(HttpRequest(method, baseUrl.trimEnd('/') + uri, sent, body))
    if (response.status !in 200..299) {
      val fields = runCatching { KiffClient.json.parseToJsonElement(String(response.body)).jsonObject }.getOrNull()
      val said = runCatching { fields?.get("error")?.jsonPrimitive?.content }.getOrNull()
      if (response.status == 403 && held != null && runCatching { fields?.get("code")?.jsonPrimitive?.content }.getOrNull() == KiffClient.WINDOW_REQUIRED) {
        if (window == held) window = null
        throw PlanqueError.WindowRequired(said ?: Words.Error.approvalWindowEnded)
      }
      throw PlanqueError.Server(response.status, said ?: Words.Error.connectionAnswered(status = response.status.toString()))
    }
    return response.body
  }

  companion object {
    /** The header an API-key connection's key goes to planque in, once. */
    const val VENDOR_KEY = "Planque-Vendor-Key"

    /**
     * What the phone signs inside an approval window (planque's `device.MessageWindow`): [message]'s lines under
     * `planque.request.v2`, with the window's id last. With no window, [message].
     */
    fun messageWindow(base: String, tenant: String, method: String, uri: String, body: ByteArray, signedAt: String, window: String?): ByteArray =
      if (window == null) message(base, tenant, method, uri, body, signedAt)
      else listOf("planque.request.v2", base.trimEnd('/'), tenant, method, uri, sha256Hex(body), signedAt, window).joinToString("\n").toByteArray()

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
