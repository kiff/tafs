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
  private val channels = Wearable.getChannelClient(context)
  private val nodes = Wearable.getNodeClient(context)
  private val route = PhoneRoute(
    lookup = {
      capabilities.getCapability(WatchLink.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
        .nodes.let { nodes -> nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() }?.id
    },
    send = { node, path, data -> messages.sendRequest(node, path, data).await() },
  )

  override suspend fun ask(request: WatchRequest): Asked = ask(request, null)

  override suspend fun ask(request: WatchRequest, mark: String?): Asked {
    val (path, data) = WatchLink.encode(request, mark)
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
      // Turning a recording into text takes the phone longer than a read.
      withTimeoutOrNull(if (request is WatchRequest.Transcribe) TRANSCRIBE_MS else timeoutMs) {
        // The phone reached last first, a second look when none is found, a read sent once more (PhoneRoute).
        when (val tried = route.ask(request, path, data) { lookupMs = android.os.SystemClock.elapsedRealtime() - started }) {
          is PhoneRoute.Tried.NoPhone -> {
            outcome = LinkTrace.NO_PHONE + if (tried.retried) " ↻" else ""
            Asked.Unreachable(noPhone(paired = paired(), everOffered = everOffered()))
          }
          is PhoneRoute.Tried.Failed -> {
            outcome = "error: ${tried.error.javaClass.simpleName}" + if (tried.retried) " ↻" else ""
            Asked.Unreachable(failureOf(tried.error))
          }
          is PhoneRoute.Tried.Reply -> {
            sendMs = android.os.SystemClock.elapsedRealtime() - started - (lookupMs ?: 0L)
            bytes = tried.bytes.size
            val decoded = WatchLink.decodeMarked(tried.bytes)
            outcome = (if (decoded != null) LinkTrace.OK else LinkTrace.UNREADABLE)
            if (decoded != null && tried.retried) outcome = LinkTrace.OK_RETRIED
            // With the phone's account mark: the model checks it before anything of the reply is used.
            decoded?.let { Asked.Answered(it.reply, it.mark) } ?: Asked.NotUnderstood
          }
        }
      } ?: Asked.Unreachable(LinkFailure.NoAnswer)
    } catch (e: CancellationException) {
      // The screen closed or the ask was cancelled: not the link's failure, so not recorded as a timeout.
      outcome = LinkTrace.CANCELLED
      throw e
    } catch (e: Exception) {
      // The Data Layer's own failures (no connection, the phone app not answering): the same to the person.
      outcome = "error: ${e.javaClass.simpleName}"
      Asked.Unreachable(failureOf(e))
    } finally {
      trace()
    }
  }

  /** A phone is paired and connected now, whether or not tafs is on it. */
  private suspend fun paired(): Boolean = runCatching { nodes.connectedNodes.await().isNotEmpty() }.getOrDefault(false)

  /**
   * A phone ever offered tafs's capability to this watch (`FILTER_ALL`, reachable now or not). Unsure counts as yes, so a watch
   * never says tafs is missing on a guess.
   */
  private suspend fun everOffered(): Boolean =
    runCatching { capabilities.getCapability(WatchLink.PHONE_CAPABILITY, CapabilityClient.FILTER_ALL).await().nodes.isNotEmpty() }.getOrDefault(true)

  /** A recording to the phone over its own channel (row 44, A); the phone closes it once it has read the whole. */
  override suspend fun sendVoice(id: String, file: java.io.File): Boolean = runCatching {
    kotlinx.coroutines.withTimeout(TRANSCRIBE_MS) {
      val node = route.node() ?: return@withTimeout false
      val channel = channels.openChannel(node, WatchLink.VOICE + id).await()
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        channels.getOutputStream(channel).await().use { out -> file.inputStream().use { it.copyTo(out) } }
      }
      true
    }
  }.getOrDefault(false)

  private companion object {
    const val TRANSCRIBE_MS = 30_000L
  }
}

/**
 * No phone with tafs was found (row 44, D): tafs isn't on it only when a phone is paired and connected and none ever offered
 * tafs's capability; the first seconds after start, before the capability shows, read as not in reach, never as missing.
 */
internal fun noPhone(paired: Boolean, everOffered: Boolean): LinkFailure =
  if (paired && !everOffered) LinkFailure.NoApp else LinkFailure.NotReachable

/** The Data Layer's own failure, as the wrist says it: its "not connected" is the phone out of reach, any other the link's. */
internal fun failureOf(error: Exception): LinkFailure =
  if ((error as? com.google.android.gms.common.api.ApiException)?.statusCode == com.google.android.gms.wearable.WearableStatusCodes.TARGET_NODE_NOT_CONNECTED) {
    LinkFailure.NotReachable
  } else {
    LinkFailure.Link
  }
