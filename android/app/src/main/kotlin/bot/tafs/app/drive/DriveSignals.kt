package bot.tafs.app.drive

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The app's events Coach can wait on (docs/drive.md, `wait`): `screen_shown`, `sheet_opened`, `sheet_closed`, `refreshed`,
 * `prompt_shown`, `prompt_done`, `notification_shown`. The app says each as it happens; nothing is kept.
 */
object DriveSignals {
  private val events = MutableSharedFlow<String>(extraBufferCapacity = 64)
  val flow: SharedFlow<String> = events.asSharedFlow()

  fun emit(event: String) {
    events.tryEmit(event)
  }

  /** The first [event] within [timeoutMs]: true when it came. */
  suspend fun await(event: String, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) { flow.first { it == event } } != null
}

/**
 * What Coach may read of the app's recent trouble (docs/drive.md, `diagnostics`): its errors and refusals in its own words, and
 * failed requests by route template and status, never a body, header, query or token. The newest 30 of each, in memory only.
 */
object DriveDiagnostics {
  private const val KEPT = 30
  private val errors = ArrayDeque<Pair<String, String>>()
  private val requests = ArrayDeque<Triple<String, String, Int>>()

  /** The app said [words] to the person: an error or a refusal. */
  @Synchronized
  fun error(words: String, at: String = java.time.Instant.now().toString()) {
    if (words.isBlank()) return
    errors.addLast(at to words.take(300))
    while (errors.size > KEPT) errors.removeFirst()
  }

  /** A request failed with [status]: kept by its route's template only. */
  @Synchronized
  fun failed(method: String, url: String, status: Int, at: String = java.time.Instant.now().toString()) {
    requests.addLast(Triple(at, "$method ${route(url)}", status))
    while (requests.size > KEPT) requests.removeFirst()
  }

  /**
   * A path as its template: the query dropped, and every part that names something (an id, a session, a name) as `{id}`. Only
   * the route's own words stay ("/v1/tafs/{id}/work").
   */
  fun route(url: String): String {
    val path = url.substringAfter("://", url).let { if (url.contains("://")) "/" + it.substringAfter('/', "") else it }
      .substringBefore('?').substringBefore('#')
    return path.split('/').joinToString("/") { part ->
      if (part.isEmpty() || part.matches(Regex("v[0-9]+|[a-z]+(-[a-z]+)*"))) part else "{id}"
    }
  }

  @Synchronized
  fun read(app: JsonObject): JsonElement = buildJsonObject {
    put("app", app)
    put("errors", buildJsonArray { errors.forEach { (at, w) -> add(buildJsonObject { put("at", at); put("words", w) }) } })
    put("requests", buildJsonArray { requests.forEach { (at, r, s) -> add(buildJsonObject { put("at", at); put("route", r); put("status", s) }) } })
  }

  @Synchronized
  fun clear() {
    errors.clear()
    requests.clear()
  }
}
