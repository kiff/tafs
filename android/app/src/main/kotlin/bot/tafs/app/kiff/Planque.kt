package bot.tafs.app.kiff

import bot.tafs.app.model.Words
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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

  /** planque's refusal: its status, its words ([message]) and its [code], when it sends one (`vendor_refused_client`…). */
  /** planque refused: its status, its reason ([message], for the code), its [code], and [words], its line for the person, when sent. */
  class Server(val status: Int, message: String, val code: String? = null, val words: String? = null) : PlanqueError(message)

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
  /** A browser grant's limits as planque holds them. The grant's digest covers them, so what the owner was shown is what is read here. */
  val browser: GrantBrowser? = null,
) {
  /** A grant that uses the owner's own paired Chrome: approved only with this phone's signature over its sites (`chrome_sites_sig`). */
  val usesOwnChrome: Boolean get() = browser?.driver == GrantBrowser.EXTENSION
}

/** A browser grant's limits: which browser drives it, the paired Chrome an extension grant uses, and every site it may reach. */
@Serializable
data class GrantBrowser(val driver: String? = null, val chrome: String? = null, val sites: List<String>? = null) {
  companion object {
    /** The driver of a grant that uses the owner's own Chrome; none for planque's hosted browser. */
    const val EXTENSION = "extension"
  }
}

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

/** A connection's health, planque's: [state] working, paused, signed_out, unfinished, waiting or ended; [ok] only while it works. */
@Serializable
data class GrantHealth(val state: String = "", val ok: Boolean = false, val words: String = "", val at: String? = null)

/**
 * One thing the person can do with a connection, as planque offers it: [words] on the button, [how] the call it is (`approve`, a
 * sign-in again on itself; `key`, a new key; `delete`, the signed removal), and for a removal, [stops], what stops, asked once more.
 */
@Serializable
data class GrantAction(val id: String = "", val words: String = "", val how: String = "", val stops: String? = null) {
  companion object { val KNOWN = setOf("approve", "key", "delete") }
}

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
  /**
   * How planque signs it in again once signed out: `fresh`, a new sign-in that can't be compared with the old account and
   * brings back every connection signed out on it (`connect-fresh`); else, or absent, on the same account.
   */
  val reconnect: String? = null,
  /** The bot a bot connection's token is, once given, as planque shows it: its @username names it. */
  val telegram: BotView? = null,
  /** Whether [digest] is the hash of the request as received: the app shows Approve only then. Set by the client, never read from planque. */
  @Transient val digestMatches: Boolean = false,
  /** planque answered connected to this phone's approval, before its list says so. Never read, only set. */
  @Transient val connectedNow: Boolean = false,
  /** planque's name for the connection ("Gmail · name@example.com"): never an id. None from a planque before it. */
  val title: String? = null,
  /** The tafs on it, by their names, as planque reads them from the request; empty for an unfinished one. */
  @SerialName("used_by") val usedBy: List<String>? = null,
  @SerialName("health") val healthRead: JsonElement? = null,
  @SerialName("actions") val actionsRead: JsonElement? = null,
) {
  /** How it is, in planque's words ("Working · last used 00:32"); read leniently, so a shape this build doesn't know is none. */
  val health: GrantHealth? get() = healthRead?.let { runCatching { KiffClient.json.decodeFromJsonElement(GrantHealth.serializer(), it) }.getOrNull() }

  /**
   * What the person can do with it, as planque offers it, in its order: only these are drawn. Null from a planque before it, and
   * then the app's own row stands in. An action whose `how` this build doesn't know is left out.
   */
  val actions: List<GrantAction>? get() = actionsRead?.let { e ->
    runCatching { (e as kotlinx.serialization.json.JsonArray).mapNotNull { a -> runCatching { KiffClient.json.decodeFromJsonElement(GrantAction.serializer(), a) }.getOrNull() } }.getOrNull()
      ?.filter { it.words.isNotBlank() && it.how in GrantAction.KNOWN }
  }

  /** The vendor, as the owner knows it: its name, else its server's host. */
  val vendor: String get() = vendorName ?: request.server

  /** The host a key is used with: the vendor's server, without its scheme or path (as iOS's `host(of:)`). */
  val keyHost: String get() = runCatching { java.net.URI(request.server).host }.getOrNull() ?: request.server

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
   * A request not answered yet: it's cancelled, not disconnected, since nothing was connected. Not one that holds a sign-in
   * already, nor one this phone connected since (as iOS reads it).
   */
  val pendingRequest: Boolean get() = status == "pending" && !holdsSignIn && !connectedNow

  /** Ended with Cancel rather than Remove access: a sign-in that never came back, or a request not answered yet. */
  val cancellable: Boolean get() = waitingSignIn || pendingRequest

  /**
   * planque draws this connection's row (its health and its actions): the list shows it as sent, and a tap opens its detail,
   * where its actions are. Without health words, the row is the app's own, with Cancel or Disconnect (as iOS reads it), so a
   * request nobody answered is never a row with nothing to end it.
   */
  val rowFromPlanque: Boolean get() = !health?.words.isNullOrEmpty() && actions != null

  /**
   * A managed provider's connection, connected: planque can't sign it in again in place (it can't tell which account a new
   * sign-in at the provider is), so it's disconnected and connected again instead.
   */
  val signsInThroughProvider: Boolean get() = request.connector != null && connected && !takesKey

  /** Sign in again is a new sign-in (`connect-fresh`), never a reconnect planque would refuse. */
  val reconnectsFresh: Boolean get() = reconnect == "fresh"

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
  /**
   * With `signed_in`: the sign-in is already held at the provider, and this is the one-use code the approving device confirms
   * with (`/confirm`), as with the browser's. No second sign-in.
   */
  val confirm: String? = null,
)

/** planque's answer to an enrolment: the account, and the phone key's fingerprint as planque shows it in its record. */
@Serializable
/** planque's enrolment. [kept]: asked with no connector key, the account's enrolled one still stands, so nothing was written. */
data class Enrolment(val tenant: String = "", val device: String = "", val kept: Boolean = false)

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
   * device's key goes too, for a planque from before that, which enrols one phone by its key. With no [connectorKey], it only
   * asks whether the enrolled key still stands: planque answers [Enrolment.kept], or refuses (`needs_key`) and writes nothing.
   */
  suspend fun enrol(deviceKey: ByteArray, connectorKey: String? = null): Enrolment {
    val key = "device_key" to java.util.Base64.getEncoder().encodeToString(deviceKey)
    return decode(raw("POST", "enrolment", if (connectorKey == null) body(key) else body(key, "connector_key" to connectorKey)))
  }

  /**
   * What a grant's digest covers, as its `digest_v` says (docs/android-app.md › Connect apps). Without one, the request alone (v1).
   * With `digest_v: 2`, the request with the words the person reads and their language, `{"lang", "request", "text"}`, so the phone
   * proves the words it shows, in that language, are the ones signed. Null for a version this app doesn't know, or a v2 grant
   * without its words or language: nothing is covered, so nothing matches and nothing is signed.
   */
  internal fun digestCovers(raw: JsonObject): JsonElement? {
    val request = raw["request"] ?: return null
    return when (val v = raw["digest_v"]) {
      null -> request
      else -> {
        if ((v as? JsonPrimitive)?.content != "2") return null
        val lang = (raw["lang"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() } ?: return null
        val text = (raw["text"] as? JsonPrimitive)?.takeIf { it.isString } ?: return null
        JsonObject(mapOf("lang" to lang, "request" to request, "text" to text))
      }
    }
  }

  /**
   * Every grant for the account, each with its digest checked against its request. [signedBy] signs this read alone, leaving
   * [signer] as it is: a refresh that runs while a confirmed step waits on the vendor's page must not end that step's signer.
   */
  suspend fun grants(signedBy: ((ByteArray) -> String)? = null): List<Grant> {
    val data = raw("GET", "grants", null, signedBy)
    val grants = KiffClient.json.decodeFromString<Grants>(String(data)).grants
    // The grant as planque sent it, numbers as written, so the digest is planque's.
    val raws = runCatching { KiffClient.json.parseToJsonElement(String(data)).jsonObject["grants"]?.jsonArray }.getOrNull().orEmpty()
    val paired = pairedRaws(grants.map { it.id }, raws)
    return grants.mapIndexed { i, grant ->
      val raw = paired[i]
      val covered = raw?.let(::digestCovers)
      val matches = covered != null && runCatching { sha256Hex(Jcs.canonical(covered).toByteArray()) == grant.digest }.getOrDefault(false)
      if (!matches) logMismatch(grant.digest, grant.status, raw?.get("digest_v"), if (raw == null) unpairedCause(grants.map { it.id }, raws, i) else mismatchCause(raw, covered))
      grant.copy(digestMatches = matches)
    }
  }

  /** The digests whose mismatch was logged: each one once, however often the grants are read. Never logged themselves. */
  private val mismatchesLogged = mutableSetOf<String>()

  /**
   * One logcat line for a grant whose request doesn't hash to its digest: the phone shows "doesn't match" and signs nothing, and
   * the line says so where diagnostics are read. Its status and `digest_v` only: no id, digest or words.
   */
  private fun logMismatch(digest: String, status: String, digestV: JsonElement?, cause: String) {
    if (!synchronized(mismatchesLogged) { mismatchesLogged.add(digest) }) return
    runCatching { android.util.Log.w(LOG_TAG, mismatchLine(status, digestV, cause)) }
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
  suspend fun approve(grant: Grant, words: ProfileWords? = null, chromeSitesSig: String? = null): Approved = approveAt(grant, words, "approve", chromeSitesSig)

  /**
   * A new sign-in for a signed-out connection planque can't sign in again on its old account (`reconnect: fresh`): the first
   * step in place of [approve], with the same body, signature and answer. Its confirm brings back every connection signed out
   * on that account.
   */
  suspend fun connectFresh(grant: Grant, words: ProfileWords? = null, chromeSitesSig: String? = null): Approved =
    approveAt(grant, words, "connect-fresh", chromeSitesSig)

  /**
   * The approve body. [chromeSitesSig], for a grant that uses the owner's own Chrome, is this phone's signature over its sites
   * (`tafs-chrome/sites/v1`, raw r‖s): planque approves such a grant only with it. Added by the phone, never by tafs cloud.
   */
  private suspend fun approveAt(grant: Grant, words: ProfileWords?, step: String, chromeSitesSig: String? = null): Approved {
    if (!grant.digestMatches) throw PlanqueError.DigestMismatch
    val fields = buildMap {
      put("digest", kotlinx.serialization.json.JsonPrimitive(grant.digest))
      words?.let { put("kiff_profile", it.approval()) }
      chromeSitesSig?.let { put("chrome_sites_sig", kotlinx.serialization.json.JsonPrimitive(it)) }
    }
    val body = KiffClient.json.encodeToString(JsonObject.serializer(), JsonObject(fields)).toByteArray()
    return decode(raw("POST", "grants/${escaped(grant.id)}/$step", body))
  }

  /**
   * Approves [grantId] with the body tafs cloud built for it (kiff/tafs#413): sent as it is, signed by the device. The phone
   * checks nothing of it; tafs cloud read the grant and decided.
   */
  suspend fun approveAsAsked(grantId: String, body: kotlinx.serialization.json.JsonElement, chromeSitesSig: String? = null): Approved =
    decode(raw("POST", "grants/${escaped(grantId)}/approve", KiffClient.json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), withSites(body, chromeSitesSig)).toByteArray()))

  /** [body] with this phone's `chrome_sites_sig` added when it has one: tafs cloud builds the rest, never this field. */
  internal fun withSites(body: kotlinx.serialization.json.JsonElement, chromeSitesSig: String?): kotlinx.serialization.json.JsonElement =
    if (chromeSitesSig == null) body else JsonObject(body.jsonObject + ("chrome_sites_sig" to kotlinx.serialization.json.JsonPrimitive(chromeSitesSig)))

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

  /** The key route's body, exactly as it's sent: the digest shown, the key's SHA-256, KIFF's words for its profile. */
  fun keyBody(grant: Grant, key: String, words: ProfileWords? = null): ByteArray {
    val fields = buildMap {
      put("digest", kotlinx.serialization.json.JsonPrimitive(grant.digest))
      put("key_sha256", kotlinx.serialization.json.JsonPrimitive(sha256Hex(key.trim().toByteArray())))
      words?.let { put("kiff_profile", it.approval()) }
    }
    return KiffClient.json.encodeToString(JsonObject.serializer(), JsonObject(fields)).toByteArray()
  }

  private fun keyUri(account: String, grantId: String) = "/v1/accounts/${escaped(account)}/grants/${escaped(grantId)}/key"

  /** planque's own message for giving [grant] its key at [signedAt] (`planque.request.v1`), whose SHA-256 goes into `kiff.requests.v1`. */
  fun keyMessage(grant: Grant, key: String, words: ProfileWords?, signedAt: String): ByteArray {
    val account = tenant ?: throw PlanqueError.NotSigning
    return message(baseUrl, account, "POST", keyUri(account, grant.id), keyBody(grant, key, words), signedAt)
  }

  /**
   * Gives [grant] its key signed together with another request (`kiff.requests.v1`, docs/secure-connection.md): [signature] is
   * the approval key's over both requests' hashes, [with] the other request's message hash. planque rebuilds this request's
   * message and has KIFF check the one signature over both. The key goes in its header alone, as for [giveKey].
   */
  suspend fun giveKeyTogether(grant: Grant, key: String, words: ProfileWords?, signedAt: String, signature: String, with: String): Grant {
    if (!grant.digestMatches) throw PlanqueError.DigestMismatch
    val account = tenant ?: throw PlanqueError.NotSigning
    val device = deviceId ?: deviceLoader?.invoke()?.also { if (tenant == account) deviceId = it } ?: throw PlanqueError.NoDevice
    val sent = linkedMapOf(
      "Accept" to "application/json",
      "User-Agent" to "tafs-android",
      "Accept-Language" to KiffClient.screenLanguage(),
      "Content-Type" to "application/json",
      "Planque-Device-Signature" to "t=$signedAt,v3=$signature,with=sha256:$with,d=$device",
      VENDOR_KEY to key.trim(),
    )
    val response = transport.send(HttpRequest("POST", baseUrl.trimEnd('/') + keyUri(account, grant.id), sent, keyBody(grant, key, words)))
    if (response.status !in 200..299) {
      val fields = runCatching { KiffClient.json.parseToJsonElement(String(response.body)).jsonObject }.getOrNull()
      val said = runCatching { fields?.get("error")?.jsonPrimitive?.content }.getOrNull()
      val code = runCatching { fields?.get("code")?.jsonPrimitive?.content }.getOrNull()
      val words = runCatching { (fields?.get("words") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
      throw PlanqueError.Server(response.status, said ?: Words.Error.connectionAnswered(status = response.status.toString()), code, words)
    }
    return decode(response.body)
  }

  private fun approveUri(account: String, grantId: String) = "/v1/accounts/${escaped(account)}/grants/${escaped(grantId)}/approve"

  /** planque's own message for approving [grantId] with [body] at [signedAt] (`planque.request.v1`), for `kiff.requests.v1`. */
  fun approveMessage(grantId: String, body: ByteArray, signedAt: String): ByteArray {
    val account = tenant ?: throw PlanqueError.NotSigning
    return message(baseUrl, account, "POST", approveUri(account, grantId), body, signedAt)
  }

  /**
   * Approves [grantId] with [body], exactly as tafs cloud built it, signed together with another request (`kiff.requests.v1`,
   * `tools_and_cards`, docs/secure-connection.md): [signature] is the approval key's over both requests' hashes, [with] the
   * other's message hash. planque rebuilds this request's message and has KIFF check the one signature over both.
   */
  suspend fun approveTogether(grantId: String, body: ByteArray, signedAt: String, signature: String, with: String): Approved {
    val account = tenant ?: throw PlanqueError.NotSigning
    val device = deviceId ?: deviceLoader?.invoke()?.also { if (tenant == account) deviceId = it } ?: throw PlanqueError.NoDevice
    val sent = linkedMapOf(
      "Accept" to "application/json",
      "User-Agent" to "tafs-android",
      "Accept-Language" to KiffClient.screenLanguage(),
      "Content-Type" to "application/json",
      "Planque-Device-Signature" to "t=$signedAt,v3=$signature,with=sha256:$with,d=$device",
    )
    val response = transport.send(HttpRequest("POST", baseUrl.trimEnd('/') + approveUri(account, grantId), sent, body))
    if (response.status !in 200..299) {
      val fields = runCatching { KiffClient.json.parseToJsonElement(String(response.body)).jsonObject }.getOrNull()
      val code = runCatching { fields?.get("code")?.jsonPrimitive?.content }.getOrNull()
      // planque's `error` is for the log: the person reads planque's `words` when sent, else a plain line by status (ErrorWords).
      val words = runCatching { (fields?.get("words") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
      throw PlanqueError.Server(response.status, Words.Error.connectionAnswered(status = response.status.toString()), code, words)
    }
    return decode(response.body)
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

  // Chrome (docs/chrome.md, "Pairing"), as iOS's PlanqueClient.

  /** The Chromes paired to the account. planque answers 404 while it doesn't offer them, and the app then shows nothing. */
  suspend fun chromes(): List<Chrome> = decode<ChromeList>(raw("GET", "chromes", null)).chromes

  /** Opens a pairing: its id, and the four characters of the code planque looks it up by. */
  suspend fun openChromePairing(): ChromePairingOpened = decode(raw("POST", "chromes/pairings", "{}".toByteArray()))

  /** A pairing as planque has it: `waiting_for_chrome`, `waiting_for_phone` with the extension's offer, or `confirmed`. 404 once expired. */
  suspend fun chromePairing(id: String): ChromePairingState = ChromePairingState.read(raw("GET", "chromes/pairings/${escaped(id)}", null))

  /** Confirms the pairing: the confirmation the approval key signed, with that signature as raw r‖s. */
  suspend fun confirmChromePairing(id: String, c: ChromePairing.Confirmation, phoneSig: String) {
    val body = KiffClient.json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
      "chrome_key_sha256" to JsonPrimitive(c.chromeKeySha256), "name" to JsonPrimitive(c.name), "phone_key" to JsonPrimitive(c.phoneKey),
      "phone_proof" to JsonPrimitive(c.phoneProof), "phone_sig" to JsonPrimitive(phoneSig),
    ))).toByteArray()
    raw("POST", "chromes/pairings/${escaped(id)}/confirm", body)
  }

  /**
   * The picture kept for a Chrome step, signed by this device as the Chromes list is, as iOS's `chromeScreenshot(step:)`. null
   * when none is kept: planque answers 404 for a step with none, one past its 30 days, and while it keeps none. A picture that
   * doesn't check out ([ChromeScreenshot.read]) is an error, never shown.
   */
  suspend fun chromeScreenshot(step: String): ChromeScreenshot? {
    val bytes = try {
      raw("GET", "chromes/screenshots/${escaped(step)}", null)
    } catch (e: PlanqueError.Server) {
      if (e.status == 404) return null
      throw e
    }
    return ChromeScreenshot.read(bytes) ?: throw PlanqueError.Server(0, "bad_picture")
  }

  /** Unpairs a Chrome: planque deletes its key at once. */
  suspend fun removeChrome(id: String) {
    raw("DELETE", "chromes/${escaped(id)}", null)
  }

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
      // The person's language, as tafs cloud gets it: planque writes the words they approve in it, and says which (`lang`).
      "Accept-Language" to KiffClient.screenLanguage(),
      // The person's time zone, as tafs cloud gets it: the times in planque's words are in it ("last used 00:32").
      "X-Time-Zone" to java.time.ZoneId.systemDefault().id,
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
        // The app's own words: planque's `error` is for the log, never the person.
        throw PlanqueError.WindowRequired(Words.Error.approvalWindowEnded)
      }
      val code = runCatching { fields?.get("code")?.jsonPrimitive?.content }.getOrNull()
      val words = runCatching { (fields?.get("words") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
      throw PlanqueError.Server(response.status, said ?: Words.Error.connectionAnswered(status = response.status.toString()), code, words)
    }
    return response.body
  }

  companion object {
    /** The header an API-key connection's key goes to planque in, once. */
    const val VENDOR_KEY = "Planque-Vendor-Key"

    /** Logcat's tag for planque's reads. */
    const val LOG_TAG = "TafsPlanque"

    /**
     * The mismatch's logcat line: which way it failed ([mismatchCause]), the grant's status and its `digest_v` (1 when it has
     * none), nothing that names it.
     */
    fun mismatchLine(status: String, digestV: JsonElement?, cause: String): String {
      val v = (digestV as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }?.take(8) ?: if (digestV == null) "1" else "unknown"
      val st = status.takeIf { it.isNotEmpty() && it.all { c -> c.isLetter() || c == '_' } }?.take(32) ?: "unknown"
      return "grant digest doesn't match: $cause; status $st, digest_v $v"
    }

    /**
     * Each decoded grant's entry as planque sent it, by its place in the list, never looked up by id: with two entries under one
     * id, a lookup would check the second grant (shown as its own request) against the first's bytes, and its Approve would sign
     * the first's digest. An entry whose id isn't its grant's, an id that repeats, or a list whose length differs gives none, so
     * nothing there matches and nothing is signed.
     */
    fun pairedRaws(ids: List<String>, raws: List<JsonElement>): List<JsonObject?> {
      if (raws.size != ids.size) return ids.map { null }
      val repeated = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
      return ids.mapIndexed { i, id ->
        (raws[i] as? JsonObject)?.takeIf { id !in repeated && (it["id"] as? JsonPrimitive)?.content == id }
      }
    }

    /** Why a grant has no entry to check ([pairedRaws]), in a few fixed words. */
    fun unpairedCause(ids: List<String>, raws: List<JsonElement>, i: Int): String = when {
      raws.size != ids.size -> "a list of another length"
      ids.count { it == ids[i] } > 1 -> "an id repeated in the list"
      else -> "not in the list as sent"
    }

    /**
     * Why a grant's digest didn't match, in a few fixed words: planque's list didn't hold the grant as sent, or no request;
     * a version or a v2 grant's words or language the phone can't cover; or what it covers hashed to another digest.
     */
    fun mismatchCause(raw: JsonObject?, covered: JsonElement?): String = when {
      raw == null -> "not in the list as sent"
      raw["request"] == null -> "no request"
      covered == null -> if ((raw["digest_v"] as? JsonPrimitive)?.content == "2") "v2 without its words or language" else "a digest_v the app doesn't know"
      else -> "the hash differs"
    }

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

/** A Chrome paired to the account, as planque lists it: the owner's name for it, and what its extension reported. */
@Serializable
data class Chrome(
  @SerialName("chrome_id") val id: String,
  val name: String,
  val browser: String? = null,
  val os: String? = null,
  @SerialName("paired_at") val pairedAt: String? = null,
)

@Serializable
private data class ChromeList(val chromes: List<Chrome> = emptyList())

/**
 * The picture a Chrome kept just before a taf's step acted (docs/chrome.md, "Screenshots of a run's acts"), as iOS's
 * `ChromeScreenshot`: the step's id at the Chrome, when it was taken, and the WebP bytes, whose hash is checked against
 * planque's before they're shown.
 */
class ChromeScreenshot(val step: String, val takenAt: java.time.Instant, val image: ByteArray) {
  companion object {
    /** The most a picture may weigh: planque keeps none over 120 KB. */
    const val LARGEST = 120 * 1024

    /**
     * planque's answer, or null when it isn't a picture to show: not the shape, not base64url, too big, not WebP, or bytes
     * whose SHA-256 isn't the one planque kept.
     */
    fun read(body: ByteArray): ChromeScreenshot? {
      val o = runCatching { KiffClient.json.parseToJsonElement(String(body)).jsonObject }.getOrNull() ?: return null
      fun text(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
      val step = text("step") ?: return null
      val sha = text("sha256")?.lowercase() ?: return null
      val bytes = text("image")?.let { ChromePairing.base64UrlDecoded(it) } ?: return null
      if (bytes.size < 12 || bytes.size > LARGEST) return null
      if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP") return null
      val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
      if (hash != sha) return null
      val at = text("taken_at")?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return null
      return ChromeScreenshot(step, at, bytes)
    }
  }
}

/** A pairing this phone opened. */
@Serializable
data class ChromePairingOpened(val pairing: String, val lookup: String)

/**
 * What an extension sent for a pairing: its key (SPKI DER, base64url), what it says it is, its KIFF device's `kiff.pair.v1` offer
 * as its text (empty when it sent none), and its proof of both under the code's secret.
 */
data class ChromeOffer(
  val key: String,
  val version: String = "",
  val browser: String = "",
  val os: String = "",
  val proof: String,
  val kiffOffer: String = "",
)

/** A pairing's state, with the extension's offer once it sent one. */
data class ChromePairingState(val status: String, val offer: ChromeOffer? = null) {
  companion object {
    fun read(bytes: ByteArray): ChromePairingState {
      val o = KiffClient.json.parseToJsonElement(String(bytes)).jsonObject
      fun field(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
      val key = field("key")
      val proof = field("proof")
      val offer = if (key != null && proof != null) {
        ChromeOffer(key, field("version").orEmpty(), field("browser").orEmpty(), field("os").orEmpty(), proof, field("kiff_offer").orEmpty())
      } else {
        null
      }
      return ChromePairingState(field("status").orEmpty(), offer)
    }
  }
}
