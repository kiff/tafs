package bot.tafs.app.catalog

import bot.tafs.app.kiff.HttpRequest
import bot.tafs.app.kiff.Jcs
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SecretStore
import bot.tafs.app.kiff.SigningTime
import bot.tafs.app.kiff.Transport
import bot.tafs.app.model.Words
import bot.tafs.app.signing.Authenticator
import bot.tafs.app.signing.CatalogKeys
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One catalog revision as the connection server reads it (`GET /v1/catalog/entries/{id}`): no credential, no secret. */
@Serializable
data class CatalogRevision(
  val id: String,
  /** The entry, with its id: what the digest covers. */
  val entry: JsonElement,
  /** The JCS SHA-256 of [entry], lowercase hex: what a device signs. */
  val digest: String,
  /** draft, reviewed, published or retired. */
  val state: String = "",
  /** Each tool as the connection server reads the entry's pinned description, outside the digest. */
  val described: CatalogDescribed? = null,
)

/** The entry's tools as read from its description, or why they can't be. A device draws read or write from this alone. */
@Serializable
data class CatalogDescribed(val from: String = "", val why: String? = null, val tools: List<CatalogTool> = emptyList())

@Serializable
data class CatalogTool(
  val tool: String,
  val method: String? = null,
  val path: String? = null,
  @SerialName("read_only") val readOnly: Boolean = false,
  @SerialName("final_arguments") val finalArguments: List<CatalogFinal> = emptyList(),
  val why: String? = null,
)

/** An argument that can make a write final, and the value it's pinned to. */
@Serializable
data class CatalogFinal(val argument: String, val undo: JsonElement? = null)

/** The operator sheet: enrolling the catalog key, or one revision opened by its id, and what came of an action on it. */
data class CatalogSheet(
  val enrolling: Boolean = false,
  val id: String = "",
  val revision: CatalogRevision? = null,
  /** The phone's own JCS SHA-256 of the entry equals the connection server's digest: only then is anything signed. */
  val matches: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
  val done: String? = null,
)

data class CatalogState(
  /** The operator id typed once at enrolment; none until then. */
  val operator: String? = null,
  /** The catalog key's public half, base64 SPKI DER, shown to be sent to whoever enrols operators. */
  val publicKey: String? = null,
  val sheet: CatalogSheet? = null,
) {
  /** Enrolled: an operator id and a key on this phone. The Settings row shows only then. */
  val enrolled: Boolean get() = !operator.isNullOrBlank() && !publicKey.isNullOrBlank()
}

/**
 * The catalog operator on this phone (docs/android-app.md, "Catalog operator"; the connection server's docs/connections.md, "The
 * catalog kept as data"). It opens one revision by its id, draws what the connection server reads of it, and publishes or
 * retires it with the catalog key, behind the fingerprint, only when the phone's own digest of the entry is the server's.
 * It holds no credential: an operator's API credential drafts and reviews elsewhere; this phone only signs.
 */
class CatalogOperator(
  private val transport: Transport,
  /** The connection server's base URL, as its signed message names it. */
  private val base: String,
  private val keys: CatalogKeys,
  private val authenticator: Authenticator,
  private val secrets: SecretStore,
  private val signingTime: SigningTime = SigningTime(),
  private val random: SecureRandom = SecureRandom(),
) {
  private val _state = MutableStateFlow(CatalogState())
  val state: StateFlow<CatalogState> = _state

  /** Reads what this phone holds: the operator id, and the key's public half when one is made. */
  fun restore() {
    val operator = secrets.load(OPERATOR)
    val key = runCatching { keys.publicKeyDER }.getOrDefault(ByteArray(0)).takeIf { it.isNotEmpty() }?.let { Base64.getEncoder().encodeToString(it) }
    _state.update { it.copy(operator = operator, publicKey = key) }
  }

  /** `tafs://catalog/enrol`: the sheet that makes the key and keeps the operator id. */
  fun openEnrol() = _state.update { it.copy(sheet = CatalogSheet(enrolling = true)) }

  /** The Settings row: open a revision by its id. */
  fun openSheet() = _state.update { it.copy(sheet = CatalogSheet()) }

  fun close() = _state.update { it.copy(sheet = null) }

  /** Makes the catalog key (kept if made already) and keeps [operatorId]; then its public key is shown to be sent. */
  fun enrol(operatorId: String): Boolean {
    val id = operatorId.trim()
    if (id.isEmpty()) return false
    return try {
      keys.make()
      secrets.save(id, OPERATOR)
      restore()
      true
    } catch (e: Exception) {
      _state.update { s -> s.copy(sheet = s.sheet?.copy(error = e.message ?: Words.Common.notDoneUnconfirmed)) }
      false
    }
  }

  /** Reads revision [id] from the connection server, and checks its digest here before anything is offered. */
  suspend fun open(id: String): Boolean {
    val name = id.trim()
    if (name.isEmpty()) return false
    _state.update { it.copy(sheet = CatalogSheet(id = name, busy = true)) }
    return try {
      val response = transport.send(HttpRequest("GET", base.trimEnd('/') + "/v1/catalog/entries/" + escaped(name), mapOf("Accept" to "application/json")))
      if (response.status !in 200..299) {
        _state.update { s -> s.copy(sheet = s.sheet?.takeIf { it.id == name }?.copy(busy = false, error = said(response.body, response.status))) }
        return false
      }
      val revision = KiffClient.json.decodeFromString(CatalogRevision.serializer(), String(response.body))
      val matches = revision.id == name && digestOf(revision.entry) == revision.digest
      _state.update { s -> s.copy(sheet = s.sheet?.takeIf { it.id == name }?.copy(revision = revision, matches = matches, busy = false)) }
      matches
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _state.update { s -> s.copy(sheet = s.sheet?.takeIf { it.id == name }?.copy(busy = false, error = e.message ?: Words.Common.notDoneUnconfirmed)) }
      false
    }
  }

  /**
   * Publishes (a reviewed revision) or retires (a published one) the open revision: the catalog key signs, behind the
   * fingerprint, the connection server's message for it, with a nonce of its own and the time taken once the person approved.
   * Never when the phone's digest isn't the server's.
   */
  suspend fun act(action: String): Boolean {
    val open = _state.value.sheet ?: return false
    val revision = open.revision ?: return false
    val operator = _state.value.operator ?: return false
    if (!open.matches || open.busy) return false
    if (action != expectedAction(revision.state)) return false
    _state.update { s -> s.copy(sheet = s.sheet?.copy(busy = true, error = null, done = null)) }
    val nonce = nonce()
    var signedAt = ""
    val signature = try {
      val sig = keys.signature()
      val reason = if (action == PUBLISH) Words.Catalog.publishReason(id = revision.id) else Words.Catalog.retireReason(id = revision.id)
      if (!authenticator.authenticate(reason, sig)) {
        _state.update { s -> s.copy(sheet = s.sheet?.copy(busy = false, error = bot.tafs.app.signing.DeviceSigner.NOT_RECOGNIZED)) }
        return false
      }
      signedAt = signingTime.next()
      sig.update(message(base, action, revision.id, revision.digest, nonce, signedAt))
      Base64.getEncoder().encodeToString(sig.sign())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _state.update { s -> s.copy(sheet = s.sheet?.copy(busy = false, error = e.message ?: Words.Common.notDoneUnconfirmed)) }
      return false
    }
    val body = JsonObject(
      mapOf(
        "operator" to JsonPrimitive(operator),
        "digest" to JsonPrimitive(revision.digest),
        "nonce" to JsonPrimitive(nonce),
        "signed_at" to JsonPrimitive(signedAt),
        "signature" to JsonPrimitive(signature),
      ),
    )
    return try {
      val response = transport.send(
        HttpRequest(
          "POST", base.trimEnd('/') + "/v1/catalog/entries/" + escaped(revision.id) + "/" + action,
          mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
          KiffClient.json.encodeToString(JsonObject.serializer(), body).toByteArray(),
        ),
      )
      if (response.status !in 200..299) {
        _state.update { s -> s.copy(sheet = s.sheet?.copy(busy = false, error = said(response.body, response.status))) }
        return false
      }
      val words = if (action == PUBLISH) Words.Catalog.publishedNow(id = revision.id) else Words.Catalog.retiredNow(id = revision.id)
      open(revision.id)
      _state.update { s -> s.copy(sheet = s.sheet?.copy(done = words)) }
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _state.update { s -> s.copy(sheet = s.sheet?.copy(busy = false, error = e.message ?: Words.Common.notDoneUnconfirmed)) }
      false
    }
  }

  private fun nonce(): String = ByteArray(24).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

  /** The connection server's words for a refusal, as it said them; its status when it said none. */
  private fun said(body: ByteArray, status: Int): String =
    runCatching { KiffClient.json.parseToJsonElement(String(body)).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
      ?.takeIf { it.isNotBlank() } ?: Words.Error.connectionAnswered(status = status.toString())

  private fun escaped(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

  companion object {
    const val PUBLISH = "publish"
    const val RETIRE = "retire"
    const val OPERATOR = "catalog.operator"

    /** What a revision in [state] may have done to it: a reviewed one is published, a published one retired; nothing else. */
    fun expectedAction(state: String): String? = when (state) {
      "reviewed" -> PUBLISH
      "published" -> RETIRE
      else -> null
    }

    /** The JCS SHA-256 of [entry], lowercase hex, as the connection server computes its digest. */
    fun digestOf(entry: JsonElement): String =
      MessageDigest.getInstance("SHA-256").digest(Jcs.canonical(entry).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** What the catalog key signs (`planque.catalog.v1`): the server's base, the action, the revision, its digest, a nonce and the time. */
    fun message(base: String, action: String, id: String, digest: String, nonce: String, signedAt: String): ByteArray =
      listOf("planque.catalog.v1", base.trimEnd('/'), action, id, digest, nonce, signedAt).joinToString("\n").toByteArray(Charsets.UTF_8)
  }
}
