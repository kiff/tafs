package bot.tafs.app.push

import bot.tafs.app.model.Words
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import bot.tafs.app.MainActivity
import bot.tafs.app.R
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Shows and takes away the notifications alerts become. */
class AlertNotifier(private val context: Context) {
  init {
    if (Build.VERSION.SDK_INT >= 26) {
      val manager = context.getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(NotificationChannel(HOLDS, Words.Alert.needsYou, NotificationManager.IMPORTANCE_HIGH).apply {
        description = Words.Push.Channel.holdsDescription
      })
      manager.createNotificationChannel(NotificationChannel(URGENT, Words.Brief.urgent, NotificationManager.IMPORTANCE_HIGH).apply {
        description = Words.Push.Channel.urgentDescription
      })
      // Your brief is ready sounds (19 Nov, the owner's phone: a quiet brief went unseen). Android never raises a channel's
      // importance once made, so it's a new channel, and the old quiet one goes, with the settings it had.
      manager.deleteNotificationChannel(OLD_BRIEFS)
      manager.createNotificationChannel(NotificationChannel(BRIEFS, Words.Push.Channel.briefs, BRIEFS_IMPORTANCE).apply {
        description = Words.Push.Channel.briefsDescription
      })
      manager.createNotificationChannel(NotificationChannel(QUIET, Words.Push.Channel.other, NotificationManager.IMPORTANCE_LOW).apply {
        description = Words.Push.Channel.otherDescription
      })
    }
  }

  fun show(alert: Alert.Show) {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    val open = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra(EXTRA_OPEN, alert.open)
    }
    val intent = PendingIntent.getActivity(context, alert.tag.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = NotificationCompat.Builder(context, idOf(alert.channel))
      .setSmallIcon(R.drawable.ic_bell)
      .setContentTitle(alert.title)
      .setContentText(alert.body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
      .setContentIntent(intent)
      .setAutoCancel(true)
      .setCategory(if (interrupts(alert.channel)) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
      .setPriority(if (interrupts(alert.channel)) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
      .apply { alert.expiresAt?.let { setTimeoutAfter(maxOf(1L, it.toEpochMilli() - System.currentTimeMillis())) } }
      // Locked, the phone and any watch it bridges to show only the public version: tafs cloud's words can name an amount, a
      // merchant or a person, so they wait for the unlock. Every channel, as iOS's hidden previews.
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(lockedVersion(alert.locked()))
      .build()
    // A later alert for the same call replaces this one.
    manager.notify(alert.tag, 0, notification)
  }

  /** What a locked phone or watch shows instead of [show]'s notification: [Alert.Show.locked]'s words, and nothing to open. */
  private fun lockedVersion(locked: Alert.Show) =
    NotificationCompat.Builder(context, idOf(locked.channel))
      .setSmallIcon(R.drawable.ic_bell)
      .setContentTitle(locked.title)
      .setCategory(if (interrupts(locked.channel)) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
      .build()

  fun clear(tag: String) {
    NotificationManagerCompat.from(context).cancel(tag, 0)
  }

  companion object {
    const val HOLDS = "holds"
    const val URGENT = "urgent"
    const val BRIEFS = "brief"

    /** Your brief's first channel, quiet (Android 118 to 125): deleted, since its importance can't be raised. */
    const val OLD_BRIEFS = "briefs"

    /** Your brief is ready: the phone's notification sound, in the shade, never popping up over what's on screen. */
    const val BRIEFS_IMPORTANCE = NotificationManager.IMPORTANCE_DEFAULT
    const val QUIET = "quiet"

    /** The channel's id on the phone: Needs you's and the quiet one keep the ids they've always had, and so their settings. */
    fun idOf(channel: AlertChannel): String = when (channel) {
      AlertChannel.NEEDS_YOU -> HOLDS
      AlertChannel.URGENT -> URGENT
      AlertChannel.BRIEFS -> BRIEFS
      AlertChannel.OTHER -> QUIET
    }

    /** Whether a channel's alerts interrupt: Needs you's and Urgent do; Briefs and the rest don't. */
    fun interrupts(channel: AlertChannel): Boolean = channel == AlertChannel.NEEDS_YOU || channel == AlertChannel.URGENT

    /** Where a tapped alert goes: the intent's extra. */
    const val EXTRA_OPEN = "bot.tafs.app.open"
  }
}

/** Firebase and the notification permission, as the phone has them. */
class FirebasePush(private val context: Context) : PushPlatform {
  /** Set by the screen on top: asks the person for the notification permission and answers what they said. */
  @Volatile var askPermission: (suspend () -> Boolean)? = null

  override val available: Boolean
    get() = runCatching { FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null }.getOrDefault(false)

  override suspend fun allowed(): Boolean {
    if (NotificationManagerCompat.from(context).areNotificationsEnabled() &&
      (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    ) return true
    if (Build.VERSION.SDK_INT < 33) return false
    return askPermission?.invoke() ?: false
  }

  override suspend fun token(): String? {
    if (!available) return null
    return suspendCancellableCoroutine { cont ->
      FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        if (cont.isActive) cont.resume(task.result?.takeIf { task.isSuccessful && it.isNotBlank() })
      }
    }
  }
}
