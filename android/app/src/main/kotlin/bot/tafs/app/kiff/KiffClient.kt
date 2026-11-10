package bot.tafs.app.kiff

import bot.tafs.app.model.Words
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed class KiffError(message: String?) : Exception(message) {
  data object AuthorizationPending : KiffError(null) { override val message: String get() = Words.Error.signInPending }
  data object SlowDown : KiffError(null) { override val message: String get() = Words.Error.slowDown }
  data object Expired : KiffError(null) { override val message: String get() = Words.Error.signInCodeExpired }
  data object Unauthorized : KiffError(null) { override val message: String get() = Words.Error.signedOut }

  /**
   * [reason] is KIFF's machine-readable why, when it gives one ("card_revised"); [code] its `code` where a route names one
   * there instead, and [words] KIFF's own words for a person, where it sends them (its `words`, or a refusal's `message`).
   */
  class Server(val status: Int, message: String, val reason: String? = null, val code: String? = null, val words: String? = null) : KiffError(message) {
    /** Whether KIFF refused with [why], whichever field the route puts it in. */
    fun refused(why: String): Boolean = reason == why || code == why || message == why
  }

  /**
   * KIFF takes this act only signed by the session's approval key, and this phone doesn't hold it
   * (kiff/tafs#233): approving waits for a new sign-in.
   */
  data object ApprovalKeyRequired : KiffError(null) { override val message: String get() = KiffClient.APPROVAL_KEY_LOST }

  /**
   * Refused inside an approval window (kiff/tafs#494): [code] is KIFF's, `critical_key_required` (the act takes the approval key
   * every time) or `window_required` (the window has ended). Thrown only where no approval key can sign it again.
   */
  class WindowRefused(val code: String, message: String) : KiffError(message)

  /** The session has an approval key already: KIFF's fingerprint of it. */
  class ApprovalKeyExists(val fingerprint: String?) : KiffError(null) { override val message: String get() = Words.Error.approvalKeyExists }
}

@Serializable
data class DeviceStart(
  @SerialName("device_code") val deviceCode: String,
  @SerialName("user_code") val userCode: String,
  @SerialName("verification_uri") val verificationUri: String,
  @SerialName("verification_uri_complete") val verificationUriComplete: String,
  @SerialName("expires_in") val expiresIn: Int,
  val interval: Int,
)

@Serializable
data class DeviceToken(
  @SerialName("access_token") val accessToken: String,
  @SerialName("expires_in") val expiresIn: Int,
  val tenant: String,
  val subject: String,
)

/** The KIFF account a sign-in is in (kiff/tafs#73): its name and slug, and the person's email when KIFF says it. */
@Serializable
data class Account(
  @SerialName("tenant_id") val tenantId: String,
  @SerialName("tenant_slug") val slug: String,
  @SerialName("tenant_display_name") val displayName: String? = null,
  val email: String? = null,
  /** The account's time zone, which ends a Card's day. */
  @SerialName("time_zone") val timeZone: String? = null,
  /** What this person may do on the account ("cards.manage", …); null from a KIFF that doesn't say. */
  val capabilities: List<String>? = null,
) {
  /** The account as the owner knows it: its name, else its slug. */
  val name: String get() = displayName?.takeIf { it.isNotEmpty() } ?: slug
}

/**
 * An approval window as KIFF answers it (kiff/tafs#494): one fingerprint opened it, and inside it a routine approval is a tap,
 * signed by the device key with the window's id. KIFF alone says whether it is open; [words] are KIFF's for the next tap.
 */
@Serializable
data class ApprovalWindow(
  val id: String,
  val expires: String = "",
  val cap: String = "",
  val open: Boolean = false,
  val words: String? = null,
)

/** `GET /v1/me/window`: the open window, if any, and KIFF's words for the next approval. */
@Serializable
data class WindowRead(val window: ApprovalWindow? = null, val words: String? = null)

/** A device signed in as this person on the account. */
@Serializable
data class Device(
  val id: String,
  val name: String? = null,
  @SerialName("last_used_at") val lastUsedAt: String? = null,
  @SerialName("last_seen_at") val lastSeenAt: String? = null,
  @SerialName("this_device") val thisDevice: Boolean = false,
)

/**
 * A group of the account's people: its manager sees the group's work on the tafs it uses; its members use them. Everyone is
 * the group KIFF made of the account's people when groups arrived, so no one lost a taf.
 */
@Serializable
data class Group(
  val id: String,
  val name: String,
  val everyone: Boolean? = null,
  val members: List<GroupMember> = emptyList(),
  /** The KIFF agents the group is shared. */
  val agents: List<String> = emptyList(),
)

@Serializable
data class GroupMember(val subject: String, /** manager or member. */ val role: String) {
  val isManager: Boolean get() = role == "manager"
}

/** One of the account's people, as KIFF lists them. KIFF names them by email. */
@Serializable
data class Person(val subject: String, val role: String = "", val status: String = "", val email: String? = null, val you: Boolean? = null) {
  val active: Boolean get() = status == "active"

  /** What the app shows: their email, else KIFF's id for them. */
  val label: String get() = email?.takeIf { it.isNotEmpty() } ?: subject
}

/** Who a taf is shared with: a group, or one person. */
@Serializable
data class Share(val group: String? = null, val subject: String? = null)

@Serializable
internal data class GroupsResponse(val groups: List<Group> = emptyList())

@Serializable
internal data class PeopleResponse(val members: List<Person> = emptyList())

@Serializable
internal data class SharesResponse(val grants: List<Share> = emptyList())

/** One of the account's tool connections in KIFF's gateway: its name (a held call's action), the server it reaches, and whether it is the account's control server. */
@Serializable
data class Connection(val name: String, val url: String? = null, val control: Boolean? = null)

@Serializable
private class FingerprintBody(val fingerprint: String? = null)

@Serializable
private class DevicesBody(val devices: List<Device> = emptyList())

/**
 * The KIFF API as the phone uses it: device sign-in (RFC 034-B), the account, its devices, and the
 * signed requests KIFF takes only from a phone that holds the session's key. tafs is a KIFF
 * customer like any other: it only uses the public API.
 */
class KiffClient(
  private val transport: Transport,
  val baseUrl: String = "https://api.kiff.dev",
  /** Where a person answers in KIFF's own app. */
  val webUrl: String = "https://app.kiff.dev",
  /** KIFF's MCP gateway, which lists the account's connected tools. */
  val gatewayUrl: String = "https://mcp.kiff.dev",
  var token: String? = null,
  /**
   * The language the app's lines are in, so KIFF writes what it says to the owner in it, at its source (kiff/tafs#75): read
   * at each request, as Android resolved it ([bot.tafs.app.model.Res.language]), never the phone's own setting.
   */
  private val acceptLanguage: () -> String = { screenLanguage() },
  private val signingTime: SigningTime = SigningTime(),
) {
  /** The account the phone signed in to, for signed setup calls. */
  var tenant: String? = null

  /** Signs a setup call for a phone session bound to its key. */
  var setupSigner: ((ByteArray) -> String)? = null

  /**
   * Signs, with the approval key, a call that grants authority ([grantsAuthority]), for a session
   * that registered one (kiff/tafs#233): it asks for a fingerprint for that one request, then signs
   * the message [built] after it, with its time. Null
   * otherwise, and the setup signer signs it as before.
   */
  var approvalSigner: (suspend (built: () -> ByteArray) -> String)? = null

  /** Called when KIFF answers that an act needs the approval key this phone doesn't hold. */
  var approvalKeyRequired: (() -> Unit)? = null

  /**
   * The approval window this phone holds at KIFF (kiff/tafs#494), as KIFF last answered it. While it is held, a call the approval
   * key would sign goes with the device key and the window's id, a tap; KIFF decides whether it takes it. A refusal drops back to
   * the approval key, and `window_required` forgets the window.
   */
  @Volatile var window: ApprovalWindow? = null

  /** The open window, if any, with KIFF's words for the next approval. */
  suspend fun readWindow(): WindowRead = call("GET", WINDOW)

  /**
   * Opens an approval window with the approval key, behind its fingerprint: KIFF's `kiff.window.v1`, to [expires] at most five
   * minutes ahead, with a fresh [nonce]; KIFF takes the signer's own session and device. The window is held once KIFF answers it.
   */
  suspend fun openWindow(expires: String, nonce: String): ApprovalWindow {
    val body = buildJsonObject {
      put("message", WINDOW_MESSAGE)
      put("expires", expires)
      put("nonce", nonce)
    }
    val opened = json.decodeFromString<WindowRead>(String(sendJson("POST", WINDOW, body))).window ?: throw KiffError.Server(0, Words.Error.kiffOpenedNoWindow)
    window = opened
    return opened
  }

  /** Closes the held window, signed by the device key ([sign]): the app went to the background or the screen locked. Forgotten either way. */
  suspend fun closeWindow(sign: (ByteArray) -> String) {
    val held = window ?: return
    window = null
    sendJson("POST", "$WINDOW/${escaped(held.id)}/close", JsonObject(emptyMap()), signedBy = sign)
  }

  /** The held call's page in KIFF, where it can be answered today. */
  /** The held call's page in KIFF, in the language the app's lines resolved to (`lang`); the browser's is KIFF's fallback. */
  fun answerPage(exceptionId: String, language: String = bot.tafs.app.model.Res.language): String =
    "$webUrl/needs-you/$exceptionId?lang=$language"

  // MARK: Device sign-in

  /**
   * Starts signing in. [publicKey] (SPKI DER) is the phone's signing key: KIFF shows its fingerprint
   * when the owner approves in their browser, and binds it to this session. [approvalPublicKey] is
   * the key that signs what grants authority (kiff/tafs#233). [platform] names the device in KIFF's
   * device list ("android").
   */
  suspend fun startDeviceSignIn(
    publicKey: ByteArray? = null,
    approvalPublicKey: ByteArray? = null,
    deviceName: String? = null,
    platform: String? = null,
  ): DeviceStart {
    val body = buildMap {
      publicKey?.let { put("public_key", base64(it)) }
      approvalPublicKey?.let { put("approval_public_key", base64(it)) }
      deviceName?.let { put("device_name", it) }
      platform?.let { put("platform", it) }
    }
    return call("POST", "/v1/auth/device/start", body, authorized = false)
  }

  /**
   * Registers the approval key for a session that signed in without one (kiff/tafs#233), signed by
   * the current key with [signer], and answers KIFF's fingerprint of the key it now holds. KIFF takes
   * one per session: a session that has one throws [KiffError.ApprovalKeyExists] with its
   * fingerprint, and replacing it means signing in again.
   */
  suspend fun registerApprovalKey(publicKey: ByteArray, signer: (ByteArray) -> String): String? {
    return call<FingerprintBody>("POST", "/v1/me/device/approval-key", mapOf("public_key" to base64(publicKey)), signedBy = signer).fingerprint
  }

  /** One poll. Throws [KiffError.AuthorizationPending] until the person approves. */
  suspend fun pollDeviceToken(deviceCode: String): DeviceToken = pollToken(deviceCode)

  // MARK: Approving again

  /**
   * Asks KIFF to make [approvalPublicKey] this session's approval key in place of the one it holds, once the person approves it in
   * their browser: no new session, so the phone's alerts and list stay. [proof] signs, with the new key behind its fingerprint, that
   * the phone holds it (`kiff.approval-key-relink.v1`); [signer] signs the request with the device key ([deviceKey], SPKI DER), which
   * names the session. Answers a code to approve, as a sign-in does; a new one cancels the last.
   */
  suspend fun startRelink(
    approvalPublicKey: ByteArray,
    deviceKey: ByteArray,
    proof: suspend (built: () -> ByteArray) -> String,
    signer: (ByteArray) -> String,
  ): DeviceStart {
    val account = tenant ?: throw KiffError.Unauthorized
    var signedAt = ""
    // The time is taken once the fingerprint was given.
    val signature = proof {
      signedAt = signingTime.next()
      relinkMessage(account, deviceKey, approvalPublicKey, signedAt)
    }
    val body = mapOf("approval_public_key" to base64(approvalPublicKey), "signed_at" to signedAt, "signature" to signature)
    return call("POST", RELINK, body, signedBy = signer)
  }

  /**
   * One poll of a re-link. Throws [KiffError.AuthorizationPending] until the person approves; then answers KIFF's fingerprint of
   * the key it holds now. A sign-in's token here would be KIFF's mistake, and isn't taken as one.
   */
  suspend fun pollRelink(deviceCode: String): String? {
    val answer = pollToken<Relinked>(deviceCode)
    if (answer.status != "relinked") throw KiffError.Server(200, Words.Error.kiffNoRelink)
    return answer.fingerprint
  }

  @Serializable
  private data class Relinked(val status: String = "", val fingerprint: String? = null)

  private suspend inline fun <reified T> pollToken(deviceCode: String): T {
    try {
      return call("POST", "/v1/auth/device/token", mapOf("device_code" to deviceCode), authorized = false)
    } catch (e: KiffError.Server) {
      if (e.status != 400) throw e
      throw when (e.message) {
        "authorization_pending" -> KiffError.AuthorizationPending
        "slow_down" -> KiffError.SlowDown
        "expired_token" -> KiffError.Expired
        else -> e
      }
    }
  }

  suspend fun signOut() {
    send("POST", "/v1/auth/device/logout", mapOf<String, String>())
  }

  suspend fun me(): Account = call("GET", "/v1/me")

  /**
   * Sends a Google Play purchase to KIFF for this account (KIFF's side: tracked privately): KIFF verifies the token with Google, records
   * it and acknowledges it. Safe to send again. [requestId] is the member's request it answers, when it does.
   */
  suspend fun recordGooglePurchase(product: String, purchaseToken: String, requestId: String? = null) {
    send("POST", "/v1/me/billing/google/purchases", buildMap {
      put("product", product)
      put("purchase_token", purchaseToken)
      requestId?.let { put("request_id", it) }
    })
  }

  /** Sets the account's time zone, which ends a Card's day. KIFF takes it only signed by this phone: set [setupSigner] first. */
  suspend fun setTimeZone(zone: String) {
    send("PUT", "/v1/me/time-zone", mapOf("time_zone" to zone))
  }

  /**
   * This device's id in KIFF (`dev_…`), from the person's device list: planque asks KIFF about the
   * device by it. Null when KIFF lists this session without a device yet.
   */
  suspend fun thisDeviceId(): String? = devices().firstOrNull { it.thisDevice && it.id.startsWith("dev_") }?.id

  /** The devices signed in as this person on the account. */
  suspend fun devices(): List<Device> {
    return call<DevicesBody>("GET", "/v1/me/devices").devices
  }

  // MARK: Held calls and activity

  /** The calls KIFF holds for the owner's answer. */
  suspend fun heldCalls(): List<CardException> = call<CardExceptions>("GET", "/v1/me/card-exceptions?status=held").exceptions

  /** One exception as KIFF has it now, read again before it is answered. */
  suspend fun cardException(id: String): CardException = call("GET", "/v1/me/card-exceptions/${escaped(id)}")

  /** The money calls whose outcome KIFF can't prove, newest first, for someone who answers held calls. */
  suspend fun didThisHappen(): List<UnsureCall> = call<UnsureCalls>("GET", "/v1/me/did-this-happen").calls

  /** The owner's answer to "Did this happen?", signed by the approval key whichever it is. The first answer stands. */
  suspend fun answerDidThisHappen(proposal: String, happened: Boolean) {
    sendJson("POST", "$DID_THIS_HAPPEN/${escaped(proposal)}", JsonObject(mapOf("happened" to JsonPrimitive(happened))))
  }

  /**
   * "Not sure, help me check": decides nothing, so it's signed by the device key alone ([sign]), window or not. KIFF marks the
   * call as being checked by this person for a day.
   */
  suspend fun checkDidThisHappen(proposal: String, sign: (ByteArray) -> String) {
    sendJson("POST", "$DID_THIS_HAPPEN/${escaped(proposal)}/check", JsonObject(emptyMap()), signedBy = sign)
  }

  /** The account's latest calls, for Activity and the Record. */
  suspend fun activity(limit: Int = 100): List<ActivityCall> = call<ActivityCalls>("GET", "/v1/me/activity?limit=$limit").calls

  /** The phone's signature on an answer, as KIFF checks it. */
  class AnswerSignature(val signedAt: String, val value: String)

  private fun answerBody(nextStep: String?, signature: AnswerSignature?): JsonObject = buildJsonObject {
    nextStep?.let { put("next_step", it) }
    signature?.let {
      put("signature", buildJsonObject {
        put("signed_at", it.signedAt)
        put("value", it.value)
      })
    }
  }

  /** Approves a held call once. KIFF counts it only once it verifies [signature] against this phone's key. */
  suspend fun approveOnce(id: String, signature: AnswerSignature? = null) {
    sendJson("POST", "/v1/me/card-exceptions/${escaped(id)}/approve-once", answerBody(null, signature))
  }

  /** Refuses a held call; [nextStep] is one of the Card's next steps ("return first"). */
  suspend fun reject(id: String, nextStep: String? = null, signature: AnswerSignature? = null) {
    sendJson("POST", "/v1/me/card-exceptions/${escaped(id)}/reject", answerBody(nextStep, signature))
  }

  /**
   * Gives the held call's Card the extra room this call needs, until that total resets, and lets the
   * call go on (kiff/tafs#211, 6.13): KIFF's change-card, signed as an answer. Only for a call over a
   * total kept across calls; it needs Card management.
   */
  suspend fun changeCard(id: String, signature: AnswerSignature? = null) {
    sendJson("POST", "/v1/me/card-exceptions/${escaped(id)}/change-card", answerBody(null, signature))
  }

  // MARK: Drafts and access

  /** A draft as it is now: has someone confirmed it since it was submitted? */
  suspend fun draft(id: String): AgentDraft = call("GET", "/v1/me/agent-drafts/${escaped(id)}")

  /**
   * Confirms a draft another agent submitted, exactly the [version] the owner was shown: KIFF issues its Cards. A
   * signed setup call, by the approval key where the session has one: set [setupSigner] first.
   */
  suspend fun confirmDraft(id: String, version: String): AgentDraft =
    call("POST", "/v1/me/agent-drafts/${escaped(id)}/confirm", mapOf("version" to version))

  /**
   * As [confirmDraft], naming Coach's held version_install this draft's version answers: KIFF approves that hold once under the
   * same signature, so one fingerprint covers both, and says so in the draft's `hold`. A KIFF that doesn't take [hold] ignores it
   * and confirms the draft alone: the hold is then answered as any other.
   */
  suspend fun confirmDraft(id: String, version: String, hold: HoldAnswered): AgentDraft {
    val body = buildJsonObject {
      put("version", version)
      put("hold", buildJsonObject {
        put("id", hold.id)
        put("accounting_version", hold.accountingVersion)
      })
    }
    val bytes = sendJson("POST", "/v1/me/agent-drafts/${escaped(id)}/confirm", body)
    return json.decodeFromString(if (bytes.isEmpty()) "{}" else String(bytes))
  }

  /** Coach's held call a draft's confirm answers: its KIFF id, at the version it was shown. */
  class HoldAnswered(val id: String, val accountingVersion: Int)

  /**
   * Confirms a version's draft as a **trial**: KIFF issues its Cards as trial Cards of their own, with their own
   * counters, and the live Cards stay as they are. The draft's status becomes "trying". A signed setup call, as any confirm.
   */
  suspend fun confirmDraftAsTrial(id: String, version: String): AgentDraft =
    call("POST", "/v1/me/agent-drafts/${escaped(id)}/confirm", mapOf("version" to version, "as" to "trial"))

  /**
   * Go back: KIFF ends the draft's trial, so its Cards stop at once. A signed setup call: it takes authority away, so it
   * needs no confirmation. A trial that had ended already answers 404 or 409, which counts as ended.
   */
  suspend fun endTrial(draftId: String) {
    try {
      send("DELETE", "/v1/me/agent-drafts/${escaped(draftId)}/trial")
    } catch (e: KiffError.Server) {
      if (e.status != 404 && e.status != 409) throw e
    }
  }

  /**
   * Points the account's held-call alerts at [url], replacing the target there before (KIFF shows the new signing secret only
   * this once). A signed setup call: the owner confirms it first, so set [setupSigner].
   */
  suspend fun registerAlertTarget(url: String): AlertTarget = call("POST", "/v1/me/alert-targets", mapOf("url" to url))

  // MARK: Starting Coach

  /**
   * A draft for an agent that holds no tools (Coach). From the phone, signed, it is confirmed at once, and tafs cloud then
   * claims the agent's key with the claim secret it shows this once.
   */
  suspend fun submitNoToolsDraft(agentId: String, label: String): AgentDraft {
    val body = buildJsonObject {
      put("agent_id", agentId)
      put("label", label)
      put("no_tools", true)
    }
    return json.decodeFromString(String(sendJson("POST", "/v1/me/agent-drafts", body)))
  }

  /**
   * The key planque keeps to register a taf's tools in KIFF: it connects tools and does nothing else. Minted from the phone
   * with a signed setup call; the plaintext is shown this once and goes straight to planque, never to storage.
   */
  suspend fun mintConnectorKey(): String {
    val body = buildJsonObject {
      put("label", "planque")
      put("roles", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("connector")) })
    }
    return json.parseToJsonElement(String(sendJson("POST", "/v1/keys", body))).jsonObject["plaintext"]?.jsonPrimitive?.contentOrNull
      ?: throw KiffError.Server(502, Words.Error.kiffNoKey)
  }

  /** The account's tool connections in KIFF's gateway. */
  suspend fun connections(): List<Connection> {
    val root = json.parseToJsonElement(String(sendJson("GET", "/v1/connections", null, gateway = true)).ifEmpty { "[]" })
    val list = (root as? JsonObject)?.get("connections") ?: root
    return runCatching { json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Connection.serializer()), list) }.getOrDefault(emptyList())
  }

  /**
   * Gives Coach its tools: KIFF's batch of connections as tafs cloud sent it (`tools.connect.body`), sent as it is. A signed setup
   * call to the gateway; a name already connected the same way is left as it is, so sending it again is safe.
   */
  suspend fun connectBatch(body: JsonObject) {
    sendJson("POST", "/v1/connections/batch", body, gateway = true)
  }

  /** Submits a draft exactly as tafs cloud sent it (Coach's Cards, `tools.card.body`): from the phone, signed by the approval key, it is issued at once. */
  suspend fun submitDraft(body: JsonObject) {
    sendJson("POST", "/v1/me/agent-drafts", body)
  }

  /** What this person may do with each of the account's agents, by KIFF agent. */
  suspend fun myAccess(): Map<String, Access> = call<AccessResponse>("GET", "/v1/me/access").agents

  // MARK: Groups and sharing
  // Each change is the person's act behind their confirmation: set [setupSigner] first (signsSetup lists the routes).

  suspend fun groups(): List<Group> = call<GroupsResponse>("GET", "/v1/groups").groups

  suspend fun people(): List<Person> = call<PeopleResponse>("GET", "/v1/tenant/members").members

  suspend fun createGroup(name: String) {
    send("POST", "/v1/groups", mapOf("name" to name))
  }

  suspend fun renameGroup(id: String, name: String) {
    send("PATCH", "/v1/groups/" + escaped(id), mapOf("name" to name))
  }

  suspend fun deleteGroup(id: String) {
    send("DELETE", "/v1/groups/" + escaped(id), mapOf())
  }

  /** Adds someone to a group, or changes their role in it: manager or member. */
  suspend fun setGroupMember(group: String, subject: String, role: String) {
    send("PUT", "/v1/groups/${escaped(group)}/members/${escaped(subject)}", mapOf("role" to role))
  }

  suspend fun removeGroupMember(group: String, subject: String) {
    send("DELETE", "/v1/groups/${escaped(group)}/members/${escaped(subject)}", mapOf())
  }

  /** Who a taf's KIFF agent is shared with. */
  suspend fun shares(agent: String): List<Share> = call<SharesResponse>("GET", "/v1/agents/${escaped(agent)}/grants").grants

  suspend fun share(agent: String, with: Share) {
    send("POST", "/v1/agents/${escaped(agent)}/grants", with.group?.let { mapOf("group" to it) } ?: with.subject?.let { mapOf("subject" to it) } ?: mapOf())
  }

  suspend fun unshare(agent: String, from: Share) {
    val query = from.group?.let { "group=" + escaped(it) } ?: from.subject?.let { "subject=" + escaped(it) } ?: ""
    send("DELETE", "/v1/agents/${escaped(agent)}/grants?$query", mapOf())
  }

  // MARK: Usage and halting

  /** KIFF's usage read: the plan, its period, the store, who bills it, and requests. */
  suspend fun usage(): Usage = call("GET", "/v1/me/usage")

  /**
   * Resumes a halted agent in KIFF, or with none the whole account. KIFF takes it only signed by this phone,
   * after the owner's confirmation: set [setupSigner] first.
   */
  suspend fun resume(agent: String?) {
    send("POST", agent?.let { "/v1/agents/${escaped(it)}/resume" } ?: "/v1/resume", mapOf<String, String>())
  }

  /** KIFF's receipt for a call, as KIFF wrote it: its bytes are what [ReceiptCheck] checks, never decoded and written again. */
  suspend fun receipt(traceId: String): ByteArray = send("GET", "/v1/receipts/${escaped(traceId)}")

  /**
   * KIFF's words for a connection's [profile] (KIFF's profile contract, `kiff.words.v1`), with this person's session: the words the owner reads before
   * approving, checked against their digests here ([ProfileWords.checked]).
   */
  suspend fun profileWords(profile: JsonObject): ProfileWords {
    val answer = json.parseToJsonElement(String(sendJson("POST", "/v1/profiles/words", profile))).jsonObject
    return ProfileWords.checked(profile, answer)
  }

  /**
   * Raises the spend limit by one step from [fromCents], the limit the balance read gave, signed: the tafs go on at once. KIFF
   * refuses (403) without `billing.manage`, a replayed signature, and a limit that changed meanwhile (409 `limit_changed`), so one
   * tap adds one step; and past its ceiling (409 `limit_ceiling`).
   */
  suspend fun raiseLimit(fromCents: Long) {
    sendJson("PUT", USAGE_LIMIT, JsonObject(mapOf("raise" to JsonPrimitive(true), "from_cents" to JsonPrimitive(fromCents))))
  }

  /** Asks the account's admins for a pack, with a reason; with no [pack], for a raise of the spend limit. */
  suspend fun askForPack(pack: String?, reason: String): PackRequest =
    call("POST", "/v1/me/pack-requests", if (pack == null) mapOf("kind" to PackRequest.RAISE, "reason" to reason) else mapOf("pack" to pack, "reason" to reason))

  /** Takes back this person's request. */
  suspend fun withdrawRequest(id: String): PackRequest = call("POST", "/v1/me/pack-requests/${escaped(id)}/withdraw", mapOf())

  /** Declines a member's request, with a note: a signed call from a phone. */
  suspend fun declineRequest(id: String, note: String): PackRequest = call("POST", "/v1/me/pack-requests/${escaped(id)}/decline", mapOf("note" to note))

  /** Revokes one of a taf's Cards: no new action under it goes through. It takes authority away, so it isn't signed. */
  suspend fun revoke(cardId: String) {
    send("POST", "/v1/me/mandates/${escaped(cardId)}/revoke", mapOf<String, String>())
  }

  /** Signs a device out of the account: it no longer approves, and its sessions are over. */
  suspend fun removeDevice(id: String) {
    send("DELETE", "/v1/me/devices/" + escaped(id))
  }

  /**
   * Deletes the account in KIFF (kiff/tafs#185): its keys stop at once, and KIFF erases its data after its grace window.
   * KIFF takes it only signed by this phone, after the owner's confirmation: set [setupSigner] first. It ends this
   * phone's session too, so it comes last.
   */
  suspend fun deleteAccount() {
    send("POST", "/v1/me/delete", mapOf<String, String>())
  }

  // MARK: Requests

  private suspend inline fun <reified T> call(
    method: String,
    path: String,
    body: Map<String, String>? = null,
    authorized: Boolean = true,
    noinline signedBy: ((ByteArray) -> String)? = null,
  ): T {
    val bytes = send(method, path, body, authorized, signedBy)
    return json.decodeFromString(if (bytes.isEmpty()) "{}" else String(bytes))
  }

  /** Sends one request with a body of text fields. [signedBy] signs this one request, whatever [setupSigner] holds. */
  internal suspend fun send(
    method: String,
    path: String,
    body: Map<String, String>? = null,
    authorized: Boolean = true,
    signedBy: ((ByteArray) -> String)? = null,
  ): ByteArray = sendJson(method, path, body?.let { JsonObject(it.mapValues { (_, v) -> JsonPrimitive(v) }) }, authorized, signedBy)

  /** Sends one request with a JSON body, which may nest. [gateway] sends it to KIFF's MCP gateway rather than the API. */
  internal suspend fun sendJson(
    method: String,
    path: String,
    body: JsonObject?,
    authorized: Boolean = true,
    signedBy: ((ByteArray) -> String)? = null,
    gateway: Boolean = false,
    inWindow: Boolean = true,
  ): ByteArray {
    val url = (if (gateway) gatewayUrl else baseUrl) + path
    val uri = try { URI.create(url) } catch (_: IllegalArgumentException) { throw KiffError.Server(0, Words.Error.badPath) }
    val headers = linkedMapOf(
      "Accept" to "application/json",
      "User-Agent" to "tafs-android",
      "Accept-Language" to acceptLanguage(),
    )
    val payload = body?.let { json.encodeToString(JsonObject.serializer(), it).toByteArray() }
    if (payload != null) headers["Content-Type"] = "application/json"

    // The gateway takes a control-server connection from a phone only signed (kiff/tafs#206).
    val signed = if (gateway) path == "/v1/connections" || path == "/v1/connections/batch" else signsSetup(path) || path == "/v1/me/device/approval-key" || path == RELINK
    // What grants authority is signed by the approval key, once the session has one, each request
    // behind its own fingerprint; KIFF picks the key by route, so the header is the same (kiff/tafs#233).
    val plain = (signedBy ?: setupSigner)?.let { s -> val signs: suspend (() -> ByteArray) -> String = { built -> s(built()) }; signs }
    val authority = signedBy == null && plain != null && !gateway && grantsAuthority(method, path)
    // Inside a held approval window, what the approval key would sign goes with the device key and the window's id: a tap
    // (kiff/tafs#494). Opening a window is never signed in one.
    val held = window?.id?.takeIf { inWindow && authority && path != WINDOW }
    val signer = if (held != null) plain else if (authority) approvalSigner ?: plain else plain
    val account = tenant
    if (method in WRITES && signed && signer != null && account != null) {
      val requestUri = uri.path + (uri.rawQuery?.let { "?$it" } ?: "")
      // The time is taken once the person approved: after the fingerprint, for the approval key.
      var signedAt = ""
      val value = signer {
        signedAt = signingTime.next()
        if (held != null) requestMessageWindow(account, method, requestUri, payload ?: ByteArray(0), signedAt, held)
        else requestMessage(account, method, requestUri, payload ?: ByteArray(0), signedAt)
      }
      headers["KIFF-Device-Signature"] = if (held != null) "t=$signedAt,v2=$value,w=$held" else "t=$signedAt,v1=$value"
    }
    if (authorized) {
      val bearer = token ?: throw KiffError.Unauthorized
      headers["Authorization"] = "Bearer $bearer"
    }

    val response = transport.send(HttpRequest(method, url, headers, payload))
    val status = response.status
    if (status !in 200..299) bot.tafs.app.drive.DriveDiagnostics.failed(method, path, status)
    if (status == 401) throw KiffError.Unauthorized
    if (status !in 200..299) {
      val fields = errorFields(response.body)
      // Refused in the window: the act takes the fingerprint every time, or the window ended. The approval key signs it
      // again, once, behind its fingerprint; an ended window is forgotten.
      val refused = fields["error"]
      if (status == 403 && held != null && (refused == CRITICAL_KEY_REQUIRED || refused == WINDOW_REQUIRED)) {
        if (refused == WINDOW_REQUIRED && window?.id == held) window = null
        if (approvalSigner == null) throw KiffError.WindowRefused(refused, fields["detail"] ?: refused)
        return sendJson(method, path, body, authorized, signedBy, gateway, inWindow = false)
      }
      if (status == 403 && (fields["error"] == "approval_key_required" || fields["reason"] == "approval_key_required")) {
        approvalKeyRequired?.invoke()
        throw KiffError.ApprovalKeyRequired
      }
      if (status == 409 && path == "/v1/me/device/approval-key") throw KiffError.ApprovalKeyExists(fields["fingerprint"])
      throw KiffError.Server(status, fields["error"] ?: Words.Error.kiffAnswered(status = status.toString()), fields["reason"], fields["code"], fields["words"] ?: fields["message"])
    }
    return response.body
  }

  /** The strings of an error body: `error`, `reason`, `fingerprint`. Anything else is ignored. */
  private fun errorFields(body: ByteArray): Map<String, String> = runCatching {
    json.parseToJsonElement(String(body)).jsonObject.mapNotNull { (key, value) ->
      (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { key to it }
    }.toMap()
  }.getOrDefault(emptyMap())

  private fun escaped(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

  companion object {
    /** What the person reads when this phone can't approve until they sign in again. */
    val APPROVAL_KEY_LOST: String get() = Words.Approval.keyLost

    private val WRITES = setOf("POST", "PUT", "PATCH", "DELETE")

    /** Approve again: a new approval key for this session, approved in the browser. */
    const val RELINK = "/v1/me/device/relink"

    internal val json = Json { ignoreUnknownKeys = true }

    private fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    /**
     * The calls KIFF takes from a phone's session only with its signature: the draft that starts a
     * taf, the alert target that tells tafs cloud when a call is held, the connector key planque
     * keeps, the account's time zone, deleting the account (kiff/tafs#185), and raising the spend limit.
     */
    val SIGNED_SETUP_PATHS = setOf("/v1/me/agent-drafts", "/v1/me/alert-targets", "/v1/keys", "/v1/me/time-zone", "/v1/me/delete", USAGE_LIMIT)

    /** KIFF's "Did this happen?": each answer is a POST under it, signed by the approval key. */
    const val DID_THIS_HAPPEN = "/v1/me/did-this-happen"

    /** KIFF's spend limit: Raise is a PUT here, signed by the device key (KIFF's phone-signed routes, not the approval key's). */
    const val USAGE_LIMIT = "/v1/me/usage/limit"

    /**
     * Whether KIFF takes [path] from a phone only with its signature: the paths above, confirming a
     * draft someone else submitted, resuming a halt, declining a request for more tasks, and the
     * changes to groups and sharing.
     */
    fun signsSetup(path: String): Boolean {
      if (path in SIGNED_SETUP_PATHS) return true
      // Opening an approval window, and closing it (kiff/tafs#494).
      if (path == WINDOW || (path.startsWith("$WINDOW/") && path.endsWith("/close"))) return true
      if (path.startsWith("$DID_THIS_HAPPEN/")) return true
      val parts = path.split("/")
      // Groups and sharing: each change is the person's act behind their confirmation. A group,
      // renamed or deleted (/v1/groups/{id}), or its people (/v1/groups/{id}/members/{subject});
      // who a taf is shared with (/v1/agents/{id}/grants, with ?group= or ?subject= on a removal).
      if (path == "/v1/groups") return true
      if (path.startsWith("/v1/groups/") && (parts.size == 4 || (parts.size == 6 && parts[4] == "members")) && parts[3].isNotEmpty()) return true
      val bare = path.split("?", limit = 2).first()
      val bareParts = bare.split("/")
      if (bareParts.size == 5 && bare.startsWith("/v1/agents/") && bareParts[4] == "grants" && bareParts[3].isNotEmpty()) return true
      if (parts.size == 6 && path.startsWith("/v1/me/pack-requests/") && parts[5] == "decline" && parts[4].isNotEmpty()) return true
      // Resuming a halted agent, or the whole account: it gives authority back.
      if (path == "/v1/resume" || (parts.size == 5 && path.startsWith("/v1/agents/") && parts[4] == "resume" && parts[3].isNotEmpty())) return true
      // Confirming a draft, and ending its trial (kiff/tafs#247): KIFF takes both only signed.
      return parts.size == 6 && path.startsWith("/v1/me/agent-drafts/") && (parts[5] == "confirm" || parts[5] == "trial") && parts[4].isNotEmpty()
    }

    /**
     * Whether KIFF takes [method] on [path] only signed by the approval key, once the session has one
     * (kiff/tafs#233): submitting a draft, confirming one, plain or as a trial, sharing a taf, and
     * resuming a halt. Taking authority away (ending a trial, unsharing) is the current key.
     */
    fun grantsAuthority(method: String, path: String): Boolean {
      if (method != "POST") return false
      // Opening an approval window stands for a fingerprint: only the approval key opens one (kiff/tafs#494).
      if (path == WINDOW) return true
      // "Did this happen?": No gives the Card its room back, and KIFF takes either answer only from the approval key.
      // "Not sure" decides nothing: the device key takes it.
      if (path.startsWith("$DID_THIS_HAPPEN/") && path.length > DID_THIS_HAPPEN.length + 1 && !path.endsWith("/check")) return true
      if (path == "/v1/me/agent-drafts") return true
      val parts = path.split("/")
      // Sharing a taf gives people its use. Taking it away is the current key.
      if (parts.size == 5 && path.startsWith("/v1/agents/") && parts[4] == "grants" && parts[3].isNotEmpty()) return true
      if (path == "/v1/resume" || (parts.size == 5 && path.startsWith("/v1/agents/") && parts[4] == "resume" && parts[3].isNotEmpty())) return true
      return parts.size == 6 && path.startsWith("/v1/me/agent-drafts/") && parts[5] == "confirm" && parts[4].isNotEmpty()
    }

    /** KIFF's approval window (kiff/tafs#494): read, opened with the approval key, closed with the device key. */
    const val WINDOW = "/v1/me/window"

    /** What opening a window signs, as its body's `message`. */
    const val WINDOW_MESSAGE = "kiff.window.v1"

    /** KIFF's refusals inside a window: the act takes the approval key every time, or the window has ended. */
    const val CRITICAL_KEY_REQUIRED = "critical_key_required"
    const val WINDOW_REQUIRED = "window_required"

    /**
     * What the device key signs inside an approval window: `kiff.request.v1`'s lines under `kiff.request.v2`, with the window's id
     * last, so the signature can't be moved to another window (KIFF's `RequestMessageWindow`).
     */
    fun requestMessageWindow(tenant: String, method: String, uri: String, body: ByteArray, signedAt: String, window: String): ByteArray =
      listOf("kiff.request.v2", tenant, method, uri, sha256Hex(body), signedAt, window).joinToString("\n").toByteArray()

    /** What the phone signs for a setup call: KIFF's signed request message, `kiff.request.v1`. */
    fun requestMessage(tenant: String, method: String, uri: String, body: ByteArray, signedAt: String): ByteArray =
      listOf("kiff.request.v1", tenant, method, uri, sha256Hex(body), signedAt).joinToString("\n").toByteArray()

    /**
     * What the new approval key signs to ask for re-linking, `kiff.approval-key-relink.v1`: the account, the device key that names
     * the session and the new key, each as the SHA-256 of its SPKI DER in lowercase hex, and the time.
     */
    fun relinkMessage(tenant: String, deviceKey: ByteArray, approvalKey: ByteArray, signedAt: String): ByteArray =
      listOf("kiff.approval-key-relink.v1", tenant, sha256Hex(deviceKey), sha256Hex(approvalKey), signedAt).joinToString("\n").toByteArray()

    /** What the phone signs to answer a held call: KIFF's signed answer message, `kiff.answer.v1`, one field per line. */
    fun answerMessage(
      tenant: String,
      exceptionId: String,
      answer: String,
      nextStep: String?,
      note: String? = null,
      accountingVersion: Int,
      signedAt: String,
    ): ByteArray {
      val noteHash = note?.takeIf { it.isNotEmpty() }?.let { sha256Hex(it.toByteArray()) } ?: ""
      return listOf("kiff.answer.v1", tenant, exceptionId, answer, nextStep ?: "", noteHash, accountingVersion.toString(), signedAt)
        .joinToString("\n").toByteArray()
    }

    /**
     * A key's fingerprint as KIFF shows it: the first 8 bytes of the SHA-256 of its SPKI DER, in hex,
     * in groups of four ("ab12 cd34 ef56 7890").
     */
    fun fingerprint(der: ByteArray): String =
      sha256Hex(der).take(16).chunked(4).joinToString(" ")

    /** Whether [fingerprint], as KIFF wrote it, is [der]'s. */
    fun sameKey(fingerprint: String?, der: ByteArray): Boolean {
      if (fingerprint == null) return false
      return fingerprint.lowercase().filter { it.isDigit() || it in 'a'..'f' } == fingerprint(der).filter { it.isDigit() || it in 'a'..'f' }
    }

    /**
     * The languages to ask KIFF for, most preferred first, so KIFF writes what it says to the owner (a
     * held call's reason, a Record line's) in the app's language at its source, never translated by the
     * app (kiff/tafs#75). English is the last fallback.
     */
    /** `Accept-Language` for the language the app's lines resolved to, English after it: "de, en;q=0.9". */
    fun screenLanguage(): String = acceptLanguage(listOf(bot.tafs.app.model.Res.language))

    fun acceptLanguage(preferred: List<String>): String {
      val tags = preferred.take(4).map { it.replace('_', '-') }.toMutableList()
      if (tags.none { it == "en" || it.startsWith("en-") }) tags += "en"
      return tags.mapIndexed { i, tag ->
        if (i == 0) tag else "$tag;q=${String.format(Locale.ROOT, "%.1f", maxOf(0.1, 1.0 - i * 0.1))}"
      }.joinToString(", ")
    }

    private fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
