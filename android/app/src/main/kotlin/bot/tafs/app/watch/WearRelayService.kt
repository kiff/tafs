package bot.tafs.app.watch

import bot.tafs.app.TafsApplication
import bot.tafs.watchlink.WatchLink
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Where the Wear OS watch's requests reach the phone (docs/watch-wear.md): live `MessageClient` requests only, which the Data
 * Layer delivers from a paired watch running this app, signed with the same key. [WatchRelay] checks each one before anything
 * is read, and answers with the app's one model, its session restored if the app wasn't running.
 */
class WearRelayService : WearableListenerService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
    // Only our paths: anything else isn't ours to answer.
    if (!path.startsWith(WatchLink.PREFIX)) return null
    val app = application as TafsApplication
    val answer = TaskCompletionSource<ByteArray>()
    val failed = { WatchLink.encodeReply(bot.tafs.watchlink.WatchReply.Failed(bot.tafs.app.model.Words.Watch.openOnPhone)) }
    scope.launch {
      // Only a watch connected to this phone now: the Data Layer names the node it came from, and it must be one of them.
      val connected = runCatching { Wearable.getNodeClient(this@WearRelayService).connectedNodes.await().any { it.id == nodeId } }.getOrDefault(false)
      val reply = if (!connected) bot.tafs.watchlink.WatchReply.Refused(bot.tafs.watchlink.NotAccepted.UNKNOWN_PATH)
      else runCatching { WatchRelay(model = { app.restoredModel(onScreen = false) }, floor = floor).answer(path, request) }
        .getOrElse { bot.tafs.watchlink.WatchReply.Failed(bot.tafs.app.model.Words.Watch.openOnPhone) }
      answer.trySetResult(WatchLink.encodeReply(reply))
    }.invokeOnCompletion {
      // Cancelled before it answered (the service went away, even before the work began): the watch hears Open on phone,
      // never silence until its own limit.
      answer.trySetResult(failed())
    }
    return answer.task
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private companion object {
    /** One for the process, across the service's restarts: the watch's reads of tafs cloud and KIFF are at least 10 s apart. */
    val floor = RefreshFloor()
  }
}
