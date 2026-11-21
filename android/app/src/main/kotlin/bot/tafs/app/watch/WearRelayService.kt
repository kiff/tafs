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
      // Not connected now is answered as a path this phone doesn't serve: the watch's words are the same (Open on phone), and
      // a new reason would be one an older watch can't read. In logs, UNKNOWN_PATH from here means "not a connected node".
      val reply = if (!connected) bot.tafs.watchlink.WatchReply.Refused(bot.tafs.watchlink.NotAccepted.UNKNOWN_PATH)
      else runCatching {
        WatchRelay(model = { app.restoredModel(onScreen = false) }, floor = floor, voice = voice(), transcriber = OnDeviceTranscriber(applicationContext))
          .answer(path, request)
      }
        .getOrElse { bot.tafs.watchlink.WatchReply.Failed(bot.tafs.app.model.Words.Watch.openOnPhone) }
      answer.trySetResult(WatchLink.encodeReply(reply))
    }.invokeOnCompletion {
      // Cancelled before it answered (the service went away, even before the work began): the watch hears Open on phone,
      // never silence until its own limit.
      answer.trySetResult(failed())
    }
    return answer.task
  }

  /**
   * A recording from the watch (row 44, A): the only audio that comes over the Data Layer, from a connected watch running this
   * app, on its own channel path. Kept in the app's private cache until the watch asks for its text ([VoiceInbox]).
   */
  override fun onChannelOpened(channel: com.google.android.gms.wearable.ChannelClient.Channel) {
    val path = channel.path
    if (!path.startsWith(WatchLink.VOICE)) return
    val client = Wearable.getChannelClient(this)
    val id = path.removePrefix(WatchLink.VOICE)
    scope.launch(Dispatchers.IO) {
      try {
        val connected = runCatching { Wearable.getNodeClient(this@WearRelayService).connectedNodes.await().any { it.id == channel.nodeId } }.getOrDefault(false)
        if (connected && WatchLink.isVoiceId(id)) client.getInputStream(channel).await().use { voice().receive(id, it) }
      } finally {
        runCatching { client.close(channel) }
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    // Recordings a killed process left behind: older than 2 minutes, they go now.
    runCatching { voice().sweep() }
  }

  private fun voice(): VoiceInbox = inbox ?: synchronized(WearRelayService::class.java) {
    inbox ?: VoiceInbox(java.io.File(cacheDir, "watch-voice")).also { inbox = it }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private companion object {
    /** One for the process, across the service's restarts: the watch's reads of tafs cloud and KIFF are at least 10 s apart. */
    val floor = RefreshFloor()

    /** The watch's recordings waiting for their text, for the process: a channel and its request may reach two service instances. */
    @Volatile var inbox: VoiceInbox? = null
  }
}
