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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

/**
 * The signed entry's own words, read from [CatalogRevision.entry]: what the digest covers, so what the operator reads is what
 * the key signs. Read or write is never taken from here: that's [CatalogDescribed].
 */
data class CatalogEntryView(
  val id: String?,
  val vendor: String,
  val title: String,
  val kind: String,
  /** Where a key entry's key is sent (`api.base`), and how (`api.auth`: `header` or `query`, and its name). */
  val base: String?,
  val authIn: String?,
  val authName: String?,
  /** An MCP entry's server. */
  val server: String?,
  /** The description a key entry pins (`api.url`), and its SHA-256. */
  val descriptionUrl: String?,
  val descriptionSha256: String?,
  val tools: List<CatalogEntryTool>,
) {
  val isKey: Boolean get() = kind == "api_key"
}

/** One of the entry's tools: its name and title, the words an owner reads, its own label, and what it pins. */
data class CatalogEntryTool(
  val tool: String,
  val name: String?,
  val title: String?,
  val readOnly: Boolean,
  val fixed: Map<String, JsonElement>,
)

/** Where the signed entry and the connection server's reading of it don't agree. Any one of them leaves nothing to sign. */
sealed interface CatalogDisagreement {
  /** The server reads a tool the entry doesn't have. */
  data class NotInEntry(val tool: String) : CatalogDisagreement

  /** The entry has a tool the server doesn't read. */
  data class NotDescribed(val tool: String) : CatalogDisagreement

  /** The entry labels a tool read-only, and its description reads a write. */
  data class LabelDiffers(val tool: String) : CatalogDisagreement

  /** An argument that can make a write final, left unpinned or pinned to another value. [value] is what it must be. */
  data class LeftOpen(val tool: String, val argument: String, val value: String) : CatalogDisagreement

  /** The server couldn't read a key entry's description, or one of its tools: its own why is drawn. */
  data object Unread : CatalogDisagreement
}

/** The operator sheet: enrolling the catalog key, or one revision opened by its id, and what came of an action on it. */
data class CatalogSheet(
  val enrolling: Boolean = false,
  val id: String = "",
  val revision: CatalogRevision? = null,
  /** The entry as signed, or none when it can't be read. */
  val entry: CatalogEntryView? = null,
  /**
   * The phone's own JCS SHA-256 of the entry equals the connection server's digest, and both the answer's id and the entry's
   * are the id asked for: only then is anything signed.
   */
  val matches: Boolean = false,
  /** Where the entry and the server's reading don't agree: nothing is signed while there's one. */
  val disagreements: List<CatalogDisagreement> = emptyList(),
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
      val entry = entryOf(revision.entry)
      val matches = entry != null && revision.id == name && entry.id == name && digestOf(revision.entry) == revision.digest
      val disagreements = entry?.let { disagreements(it, revision.described) }.orEmpty()
      _state.update { s ->
        s.copy(sheet = s.sheet?.takeIf { it.id == name }?.copy(revision = revision, entry = entry, matches = matches, disagreements = disagreements, busy = false))
      }
      matches && disagreements.isEmpty()
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
    if (!open.matches || open.disagreements.isNotEmpty() || open.busy) return false
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

    /** The entry's own fields, as signed; none when it isn't an object. */
    fun entryOf(entry: JsonElement): CatalogEntryView? {
      val o = entry as? JsonObject ?: return null
      fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
      val api = o["api"] as? JsonObject
      val auth = api?.get("auth") as? JsonObject
      val tools = (o["tools"] as? JsonArray).orEmpty().mapNotNull { t ->
        val tool = t as? JsonObject ?: return@mapNotNull null
        CatalogEntryTool(
          tool = tool.text("tool") ?: return@mapNotNull null,
          name = tool.text("name"),
          title = tool.text("title"),
          readOnly = (tool["read_only"] as? JsonPrimitive)?.content == "true",
          fixed = (tool["fixed_arguments"] as? JsonObject).orEmpty(),
        )
      }
      return CatalogEntryView(
        id = o.text("id"),
        vendor = o.text("vendor").orEmpty(),
        title = o.text("title").orEmpty(),
        kind = o.text("kind").orEmpty(),
        base = api?.text("base"),
        authIn = auth?.text("in"),
        authName = auth?.text("name"),
        server = o.text("server"),
        descriptionUrl = api?.text("url"),
        descriptionSha256 = o.text("description_sha256"),
        tools = tools,
      )
    }

    /**
     * Where a key entry and the server's reading of its pinned description don't agree: the two tool lists, a read-only label
     * on a write, and each argument that can make a write final, which the entry must pin to the undoable value (the server
     * refuses to publish otherwise). An MCP entry's tools are listed by its server once connected: nothing to compare.
     */
    fun disagreements(entry: CatalogEntryView, described: CatalogDescribed?): List<CatalogDisagreement> {
      if (!entry.isKey) return emptyList()
      if (described == null || (described.why != null && described.tools.isEmpty())) return listOf(CatalogDisagreement.Unread)
      val out = mutableListOf<CatalogDisagreement>()
      val read = described.tools.associateBy { it.tool }
      for (t in entry.tools) {
        val d = read[t.tool]
        if (d == null) {
          out += CatalogDisagreement.NotDescribed(t.tool)
          continue
        }
        if (d.why != null) out += CatalogDisagreement.Unread
        if (t.readOnly && !d.readOnly) out += CatalogDisagreement.LabelDiffers(t.tool)
        for (f in d.finalArguments) {
          if (!pinned(t, f.argument, f.undo)) out += CatalogDisagreement.LeftOpen(t.tool, f.argument, Jcs.canonical(f.undo ?: JsonNull))
        }
      }
      val inEntry = entry.tools.map { it.tool }.toSet()
      described.tools.filter { it.tool !in inEntry }.forEach { out += CatalogDisagreement.NotInEntry(it.tool) }
      return out.distinct()
    }

    /** Whether the entry pins [argument] of [tool] to the value that keeps it undoable, compared as JCS. */
    fun pinned(tool: CatalogEntryTool, argument: String, undo: JsonElement?): Boolean =
      tool.fixed[argument]?.let { Jcs.canonical(it) } == Jcs.canonical(undo ?: JsonNull)

    /** The JCS SHA-256 of [entry], lowercase hex, as the connection server computes its digest. */
    fun digestOf(entry: JsonElement): String =
      MessageDigest.getInstance("SHA-256").digest(Jcs.canonical(entry).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** What the catalog key signs (`planque.catalog.v1`): the server's base, the action, the revision, its digest, a nonce and the time. */
    fun message(base: String, action: String, id: String, digest: String, nonce: String, signedAt: String): ByteArray =
      listOf("planque.catalog.v1", base.trimEnd('/'), action, id, digest, nonce, signedAt).joinToString("\n").toByteArray(Charsets.UTF_8)
  }
}
