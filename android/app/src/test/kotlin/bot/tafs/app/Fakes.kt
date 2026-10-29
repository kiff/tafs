package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.SampleStore
import bot.tafs.app.kiff.HttpRequest
import bot.tafs.app.kiff.HttpResponse
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SecretStore
import bot.tafs.app.kiff.TafsCloud
import bot.tafs.app.kiff.Transport
import bot.tafs.app.signing.ApprovalSigning
import bot.tafs.app.signing.Authenticator
import bot.tafs.app.signing.DeviceSigner
import bot.tafs.app.signing.SoftwareKeys
import java.io.IOException
import java.net.URI
import java.time.Instant

/** The person at the prompt: confirms or doesn't, and remembers what they were asked. */
class FakeAuthenticator(var confirms: Boolean = true, var available: Boolean = true) : Authenticator {
  val asked = mutableListOf<String>()

  /** What a fingerprint was asked for, to unlock the approval key for one signature. */
  val fingerprints = mutableListOf<String>()

  override val canAuthenticate: Boolean get() = available

  override suspend fun authenticate(reason: String): Boolean {
    asked += reason
    return confirms
  }

  override suspend fun authenticate(reason: String, signature: java.security.Signature): Boolean {
    asked += reason
    fingerprints += reason
    return confirms
  }
}

/**
 * KIFF as the tests need it: each "METHOD /path" is answered in turn by what was queued for it, the
 * last answer again once the queue is spent; a request nothing was queued for is a 404. Every request
 * made is kept.
 */
class FakeTransport : Transport {
  /** Kept from any thread: a model's own background work may be sending while a test reads. */
  val requests: MutableList<HttpRequest> = java.util.Collections.synchronizedList(mutableListOf())
  private val queued = mutableMapOf<String, MutableList<Any>>()
  private val used = mutableMapOf<String, Int>()

  fun answer(key: String, status: Int, body: String = "{}") = queue(key, HttpResponse(status, body.toByteArray()))

  /** The network failing: no answer at all. */
  fun drop(key: String) = queue(key, IOException("The network dropped"))

  private fun queue(key: String, what: Any) = synchronized(this) {
    queued.getOrPut(key) { mutableListOf() } += what
  }

  fun requestsTo(key: String): List<HttpRequest> = synchronized(requests) { requests.filter { key(it) == key } }

  private fun key(r: HttpRequest) = "${r.method} ${URI.create(r.url).path}"

  override suspend fun send(request: HttpRequest): HttpResponse {
    val next = synchronized(this) {
      requests += request
      val key = key(request)
      val list = queued[key] ?: return HttpResponse(404, """{"error":"not_found"}""".toByteArray())
      val i = used.getOrDefault(key, 0)
      used[key] = i + 1
      list[minOf(i, list.size - 1)]
    }
    return when (next) {
      is IOException -> throw next
      else -> next as HttpResponse
    }
  }
}

fun softwareSigner(
  auth: FakeAuthenticator = FakeAuthenticator(),
  clock: () -> Instant = { Instant.parse("2026-10-28T09:00:00Z") },
): ApprovalSigning = DeviceSigner(SoftwareKeys(), auth)

/** A model whose owner confirms (or not, as [auth] says) with software keys, on the sample unless [start] is false. */
fun testModel(
  auth: FakeAuthenticator = FakeAuthenticator(),
  store: SampleStore? = null,
  clock: () -> Instant = { Instant.parse("2026-10-28T09:00:00Z") },
  sampleReplyDelayMs: Long = 0,
  transport: FakeTransport = FakeTransport(),
  secrets: SecretStore = InMemorySecretStore(),
  signer: ApprovalSigning = softwareSigner(auth, clock),
  start: Boolean = true,
  /** Where a chat is followed while a run is at work: a test's own background scope, on virtual time. */
  scope: kotlinx.coroutines.CoroutineScope? = null,
  push: bot.tafs.app.push.PushPlatform = bot.tafs.app.push.NoPush,
  vendorSignIn: bot.tafs.app.kiff.VendorSignIn = bot.tafs.app.kiff.NoVendorSignIn,
) = AppModel(
  store, signer, KiffClient(transport), TafsCloud(transport), secrets,
  clock = clock, sampleReplyDelayMs = sampleReplyDelayMs, followScope = scope, push = push,
  planque = bot.tafs.app.kiff.PlanqueClient(transport), vendorSignIn = vendorSignIn,
).also { if (start) it.startSample() }
