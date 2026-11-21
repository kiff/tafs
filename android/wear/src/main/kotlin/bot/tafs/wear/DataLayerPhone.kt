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
  private val route = PhoneRoute(
    lookup = {
      capabilities.getCapability(WatchLink.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
        .nodes.let { nodes -> nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() }?.id
    },
    send = { node, path, data -> messages.sendRequest(node, path, data).await() },
  )

  override suspend fun ask(request: WatchRequest): Asked {
    val (path, data) = WatchLink.encode(request)
    // Measured for the internal timing builds only (LinkTrace): the lookup, the send and how it ended, never the reply's words.
    val started = android.os.SystemClock.elapsedRealtime()
    var lookupMs: Long? = null
    var sendMs: Long? = null
    var bytes: Int? = null
    var outcome = LinkTrace.TIMEOUT
    val trace = {
      if (BuildConfig.TIMING) {
        // Whatever ended it, the time goes where it was spent: still looking for the phone, or waiting on its reply.
        val spent = android.os.SystemClock.elapsedRealtime() - started
        val lookup = lookupMs ?: spent
        LinkTrace.record(LinkTrace.Trace(LinkTrace.nameOf(path), lookup, if (lookupMs == null) null else sendMs ?: (spent - lookup), bytes, outcome))
      }
    }
    return try {
      withTimeoutOrNull(timeoutMs) {
        // The phone reached last first, a second look when none is found, a read sent once more (PhoneRoute).
        when (val tried = route.ask(request, path, data) { lookupMs = android.os.SystemClock.elapsedRealtime() - started }) {
          is PhoneRoute.Tried.NoPhone -> {
            outcome = LinkTrace.NO_PHONE + if (tried.retried) " ↻" else ""
            Asked.Unreachable
          }
          is PhoneRoute.Tried.Failed -> {
            outcome = "error: ${tried.error.javaClass.simpleName}" + if (tried.retried) " ↻" else ""
            Asked.Unreachable
          }
          is PhoneRoute.Tried.Reply -> {
            sendMs = android.os.SystemClock.elapsedRealtime() - started - (lookupMs ?: 0L)
            bytes = tried.bytes.size
            val decoded = WatchLink.decodeReply(tried.bytes)
            outcome = (if (decoded != null) LinkTrace.OK else LinkTrace.UNREADABLE)
            if (decoded != null && tried.retried) outcome = LinkTrace.OK_RETRIED
            decoded?.let { Asked.Answered(it) } ?: Asked.NotUnderstood
          }
        }
      } ?: Asked.Unreachable
    } catch (e: CancellationException) {
      // The screen closed or the ask was cancelled: not the link's failure, so not recorded as a timeout.
      outcome = LinkTrace.CANCELLED
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
