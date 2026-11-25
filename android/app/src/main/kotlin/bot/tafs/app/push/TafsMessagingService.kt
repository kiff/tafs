package bot.tafs.app.push

import bot.tafs.app.TafsApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * What tafs cloud sends this phone through Firebase: an alert becomes a notification, and the answer to a held call takes its
 * notification away; an ask from the Wear OS watch that changed, or its Inbox's marks, go on to the watch. A message that isn't one of those is ignored.
 */
class TafsMessagingService : FirebaseMessagingService() {
  override fun onMessageReceived(message: RemoteMessage) {
    val notifier = AlertNotifier(applicationContext)
    when (val alert = AlertPayload.parse(message.data)) {
      is Alert.Show -> {
        notifier.show(alert)
        // The brief's alert, or an urgent one, with the app up: the brief is read again.
        if (alert.brief) (application as TafsApplication).changeArrived(Alert.Changed(bot.tafs.app.app.BriefRoutes.BRIEF_CHANGE, null))
      }
      is Alert.Clear -> notifier.clear(alert.tag)
      // Nothing to show: the app, if it's up, reads again what changed.
      is Alert.Changed -> (application as TafsApplication).changeArrived(alert)
      // The watch's ask, or its Inbox's marks, changed: read and kept for the watch by a job of its own (docs/watch-wear.md,
      // Delivery), since Firebase gives this callback only a few seconds and a phone may have to restore its session first.
      is Alert.WatchAsk -> bot.tafs.app.watch.WatchResultsWork.ask(applicationContext, alert.askId)
      Alert.WatchInbox -> bot.tafs.app.watch.WatchResultsWork.inbox(applicationContext)
      is Alert.TafChat -> bot.tafs.app.watch.WatchResultsWork.tafChat(applicationContext, alert.tafId)
      null -> Unit
    }
  }

  /** Firebase gave this phone a new token: tafs cloud is told it again, now if the app is up, else at its next start. */
  override fun onNewToken(token: String) {
    (application as TafsApplication).pushTokenArrived(token)
  }
}
