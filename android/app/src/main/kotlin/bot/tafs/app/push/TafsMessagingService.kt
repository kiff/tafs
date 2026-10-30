package bot.tafs.app.push

import bot.tafs.app.TafsApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * What tafs cloud sends this phone through Firebase: an alert becomes a notification, and the answer to a held call takes its
 * notification away. A message that isn't one of those is ignored.
 */
class TafsMessagingService : FirebaseMessagingService() {
  override fun onMessageReceived(message: RemoteMessage) {
    val notifier = AlertNotifier(applicationContext)
    when (val alert = AlertPayload.parse(message.data)) {
      is Alert.Show -> notifier.show(alert)
      is Alert.Clear -> notifier.clear(alert.tag)
      null -> Unit
    }
  }

  /** Firebase gave this phone a new token: tafs cloud is told it again, now if the app is up, else at its next start. */
  override fun onNewToken(token: String) {
    (application as TafsApplication).pushTokenArrived(token)
  }
}
