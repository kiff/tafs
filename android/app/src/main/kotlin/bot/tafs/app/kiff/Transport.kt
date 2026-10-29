package bot.tafs.app.kiff

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One HTTP request, as the clients make it. */
class HttpRequest(
  val method: String,
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  val body: ByteArray? = null,
)

class HttpResponse(val status: Int, val body: ByteArray)

/** The network, as the clients see it: tests answer for it, the app reaches it with [UrlConnectionTransport]. */
interface Transport {
  /** Throws [IOException] when the network fails: no answer at all. */
  suspend fun send(request: HttpRequest): HttpResponse
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
}
