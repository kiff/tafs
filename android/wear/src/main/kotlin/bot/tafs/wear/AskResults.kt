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
  internal const val CHANNEL = "replies"
  private const val OLD_CHANNEL = "results"

  fun arrived(context: Context, ask: AskLine) {
    val app = shown
    if (app != null) return app(ask)
    // Signed out on this watch (v2's Account): nothing is shown of the account until it's signed in here again.
    if (PreferencesWatchSignIn(context).signedOut()) return
    if (ask.ended) notify(context, ask)
  }

  /**
   * The alert's words: the answer with no amount in it (watchlink's `Amounts.strip`, the rule the Inbox keeps), since a
   * notification stays in the watch's list until it's dismissed; when something was taken out, "The rest is on your phone."
   * follows, as on the Apple Watch. Opened, the answer shows whole, from memory.
   */
  internal fun alertText(ask: AskLine): String {
    val whole = ask.line ?: ask.words
    val kept = bot.tafs.watchlink.Amounts.strip(whole)
    return when {
      kept.isBlank() -> Words.Watch.moreOnPhone
      // Only when an amount was taken out: a double space or a trailing ":" alone changes nothing said (qa-kiff, #1445).
      bot.tafs.watchlink.Amounts.hasAmount(whole) -> kept + "\n" + Words.Watch.moreOnPhone
      else -> whole.trim()
    }
  }

  internal fun notify(context: Context, ask: AskLine) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    // High importance, so an answer peeks on the face and buzzes, and stays in the list until it's opened or swiped away (W3:
    // at the default level it came and went unseen). A channel's importance can't be raised once made, so it's a new channel,
    // and the old one goes, so Settings lists one.
    manager.deleteNotificationChannel(OLD_CHANNEL)
    manager.createNotificationChannel(NotificationChannel(CHANNEL, Words.Watch.resultsChannel, NotificationManager.IMPORTANCE_HIGH))
    val open = PendingIntent.getActivity(
      context, ask.id.hashCode(),
      Intent(context, WatchActivity::class.java).putExtra(EXTRA_ASK, WatchLink.encodeResult(ask)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val name = context.getString(R.string.app_name)
    // What a locked watch shows: that a result is ready, never the taf or its line.
    val locked = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(name).setContentText(Words.Watch.resultReady).build()
    val text = alertText(ask)
    val shown = NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(ask.tafName ?: name)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
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
