package bot.tafs.app.watch

import bot.tafs.app.TafsApplication
import bot.tafs.watchlink.WatchLink
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Passes an ask's new state to the Wear OS watch (docs/watch-wear.md, Ask tafs): tafs cloud's push says only which ask changed,
 * so the phone reads it with its own session and sends it, as tafs cloud words it, to the watches running tafs
 * ([WatchLink.WATCH_CAPABILITY]). Nothing is shown on the phone, and nothing is kept: a watch out of reach reads its asks when
 * it's next opened.
 */
class WatchResults(private val app: TafsApplication, private val timeoutMs: Long = 8_000) {
  suspend fun forward(askId: String) {
    try {
      withTimeoutOrNull(timeoutMs) {
        val model = app.restoredModel(onScreen = false)
        // The one ask the push named, read by its id (another person's, or one tafs cloud no longer has, is 404: nothing sent).
        val line = WatchRelay.lineOf(model.watchAskById(askId) ?: return@withTimeoutOrNull)
        val watches = Wearable.getCapabilityClient(app).getCapability(WatchLink.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
        val messages = Wearable.getMessageClient(app)
        for (watch in watches) runCatching { messages.sendMessage(watch.id, WatchLink.ASK_RESULT, WatchLink.encodeResult(line)).await() }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // tafs cloud or the Data Layer didn't answer: the watch reads its asks when it's next opened.
    }
  }
}
