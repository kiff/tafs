package bot.tafs.app.kiff

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** One HTTP request, as the clients make it. */
class HttpRequest(
  val method: String,
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  val body: ByteArray? = null,
)

class HttpResponse(val status: Int, val body: ByteArray)

/** An answer that streams: its status, and its body line by line as it comes; on a refusal, the body whole. */
class StreamedResponse(val status: Int, val lines: Flow<String>, val refusal: ByteArray = ByteArray(0))

/** The network, as the clients see it: tests answer for it, the app reaches it with [UrlConnectionTransport]. */
interface Transport {
  /** Throws [IOException] when the network fails: no answer at all. */
  suspend fun send(request: HttpRequest): HttpResponse

  /**
   * Opens an answer that streams (a chat's run, docs/chat.md). By default the whole answer is read and then given line by line,
   * which is what a test's answer is.
   */
  suspend fun stream(request: HttpRequest): StreamedResponse {
    val r = send(request)
    if (r.status != 200) return StreamedResponse(r.status, emptyFlow(), r.body)
    return StreamedResponse(r.status, String(r.body).lineSequence().asFlow())
  }
}

/** HttpURLConnection on the IO dispatcher: no library for what is a handful of JSON calls. */
class UrlConnectionTransport(
  private val connectTimeoutMs: Int = 15_000,
  private val readTimeoutMs: Int = 60_000,
) : Transport {
  override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
    val connection = URL(request.url).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = request.method
      connection.connectTimeout = connectTimeoutMs
      connection.readTimeout = readTimeoutMs
      connection.instanceFollowRedirects = false
      request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
      request.body?.let {
        connection.doOutput = true
        connection.outputStream.use { out -> out.write(it) }
      }
      val status = connection.responseCode
      val stream = if (status in 200..399) connection.inputStream else connection.errorStream
      HttpResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
    } finally {
      connection.disconnect()
    }
  }

  /** A run can think for minutes between two words: the read waits up to [streamReadTimeoutMs], and the lines come as they arrive. */
  override suspend fun stream(request: HttpRequest): StreamedResponse = withContext(Dispatchers.IO) {
    val connection = URL(request.url).openConnection() as HttpURLConnection
    connection.requestMethod = request.method
    connection.connectTimeout = connectTimeoutMs
    connection.readTimeout = STREAM_READ_TIMEOUT_MS
    connection.instanceFollowRedirects = false
    request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
    try {
      request.body?.let {
        connection.doOutput = true
        connection.outputStream.use { out -> out.write(it) }
      }
      val status = connection.responseCode
      if (status != 200) {
        val body = (connection.errorStream ?: runCatching { connection.inputStream }.getOrNull())?.use { it.readBytes() } ?: ByteArray(0)
        connection.disconnect()
        return@withContext StreamedResponse(status, emptyFlow(), body)
      }
      val lines = flow {
        try {
          connection.inputStream.bufferedReader().use { reader ->
            while (true) emit(reader.readLine() ?: break)
          }
        } finally {
          connection.disconnect()
        }
      }.flowOn(Dispatchers.IO)
      StreamedResponse(status, lines)
    } catch (e: Exception) {
      connection.disconnect()
      throw e
    }
  }

  private companion object {
    const val STREAM_READ_TIMEOUT_MS = 900_000
  }
}
