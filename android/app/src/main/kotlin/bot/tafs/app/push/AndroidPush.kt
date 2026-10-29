package bot.tafs.app.push

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
      manager.createNotificationChannel(NotificationChannel(HOLDS, "A taf needs you", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "A call is held for your answer. Nothing is sent until you approve it."
      })
      manager.createNotificationChannel(NotificationChannel(QUIET, "Other alerts", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Requests for tasks and their answers, with no sound."
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
    val notification = NotificationCompat.Builder(context, if (alert.loud) HOLDS else QUIET)
      .setSmallIcon(R.drawable.ic_bell)
      .setContentTitle(alert.title)
      .setContentText(alert.body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
      .setContentIntent(intent)
      .setAutoCancel(true)
      .setCategory(if (alert.loud) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
      .setPriority(if (alert.loud) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
      .apply { alert.expiresAt?.let { setTimeoutAfter(maxOf(1L, it.toEpochMilli() - System.currentTimeMillis())) } }
      .build()
    // A later alert for the same call replaces this one.
    manager.notify(alert.tag, 0, notification)
  }

  fun clear(tag: String) {
    NotificationManagerCompat.from(context).cancel(tag, 0)
  }

  companion object {
    const val HOLDS = "holds"
    const val QUIET = "quiet"

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
