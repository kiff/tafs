package bot.tafs.wear

import android.content.Context
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchRequest
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone, over the Wearable Data Layer: a live request (`MessageClient.sendRequest`) to the connected phone that declares
 * [WatchLink.PHONE_CAPABILITY]. Never a `DataItem`, so nothing queued while the phone was away lands later. The Data Layer
 * joins only apps with the same package and signing key, so only tafs on the paired phone can answer.
 */
class DataLayerPhone(context: Context, private val timeoutMs: Long = 10_000) : PhoneLink {
  private val capabilities = Wearable.getCapabilityClient(context)
  private val messages = Wearable.getMessageClient(context)

  override suspend fun ask(request: WatchRequest): Asked {
    val (path, data) = WatchLink.encode(request)
    // Measured for the internal timing builds only (LinkTrace): the lookup, the send and how it ended, never the reply's words.
    val started = android.os.SystemClock.elapsedRealtime()
    var lookupMs = 0L
    var sendMs: Long? = null
    var bytes: Int? = null
    var outcome = LinkTrace.TIMEOUT
    val trace = { if (BuildConfig.TIMING) LinkTrace.record(LinkTrace.Trace(LinkTrace.nameOf(path), lookupMs, sendMs, bytes, outcome)) }
    return try {
      withTimeoutOrNull(timeoutMs) {
        val phone = capabilities.getCapability(WatchLink.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
          .nodes.let { nodes -> nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() }
        lookupMs = android.os.SystemClock.elapsedRealtime() - started
        if (phone == null) { outcome = LinkTrace.NO_PHONE; return@withTimeoutOrNull Asked.Unreachable }
        val reply = messages.sendRequest(phone.id, path, data).await()
        sendMs = android.os.SystemClock.elapsedRealtime() - started - lookupMs
        bytes = reply.size
        val decoded = WatchLink.decodeReply(reply)
        outcome = if (decoded != null) LinkTrace.OK else LinkTrace.UNREADABLE
        decoded?.let { Asked.Answered(it) } ?: Asked.NotUnderstood
      } ?: Asked.Unreachable.also { if (lookupMs == 0L) lookupMs = android.os.SystemClock.elapsedRealtime() - started }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // The Data Layer's own failures (no connection, the phone app not answering): the same to the person.
      outcome = "error: ${e.javaClass.simpleName}"
      Asked.Unreachable
    } finally {
      trace()
    }
  }
}
