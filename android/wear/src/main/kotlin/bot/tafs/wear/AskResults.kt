package bot.tafs.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.WatchLink
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/**
 * An ask's new state from the phone (docs/watch-wear.md, Ask tafs): drawn at once when the app is open; otherwise, once it has
 * ended, the watch's own notification, which opens its result card with Hear and Reply. Locked, the notification says only that a
 * result is ready, never the taf or its line. Nothing of it is written to the watch's storage.
 */
object AskResults {
  /** The open app's model, while it's on screen. */
  @Volatile var shown: ((AskLine) -> Unit)? = null

  const val EXTRA_ASK = "bot.tafs.wear.ASK"
  private const val CHANNEL = "results"

  fun arrived(context: Context, ask: AskLine) {
    val app = shown
    if (app != null) return app(ask)
    if (ask.ended) notify(context, ask)
  }

  private fun notify(context: Context, ask: AskLine) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(NotificationChannel(CHANNEL, Words.Watch.resultsChannel, NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(
      context, ask.id.hashCode(),
      Intent(context, WatchActivity::class.java).putExtra(EXTRA_ASK, WatchLink.encodeResult(ask)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val name = context.getString(R.string.app_name)
    // What a locked watch shows: that a result is ready, never the taf or its line.
    val locked = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(name).setContentText(Words.Watch.resultReady).build()
    val shown = NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(ask.tafName ?: name)
      .setContentText(ask.line ?: ask.words)
      .setStyle(NotificationCompat.BigTextStyle().bigText(ask.line ?: ask.words))
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(locked)
      .setContentIntent(open)
      .setAutoCancel(true)
      .build()
    runCatching { manager.notify(ask.id, 1, shown) }
  }
}

/**
 * Where the phone's message about an ask reaches the watch: only [WatchLink.ASK_RESULT], only from a node connected now, which the
 * Data Layer joins only for tafs on the paired phone, signed with the same key.
 */
class AskResultService : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != WatchLink.ASK_RESULT) return
    val connected = runCatching { runBlocking { Wearable.getNodeClient(this@AskResultService).connectedNodes.await().any { it.id == event.sourceNodeId } } }.getOrDefault(false)
    if (!connected) return
    val ask = WatchLink.decodeResult(event.data) ?: return
    AskResults.arrived(this, ask)
  }
}
