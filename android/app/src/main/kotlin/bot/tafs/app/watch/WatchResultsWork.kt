package bot.tafs.app.watch

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import bot.tafs.app.R
import bot.tafs.app.TafsApplication
import bot.tafs.app.model.Words
import bot.tafs.app.push.AlertChannel
import bot.tafs.app.push.AlertNotifier

/**
 * The phone's job for a `watch_ask` or `watch_inbox` push (docs/watch-wear.md, Delivery): an expedited one-time job, one chain
 * per ask (a second push for the same ask runs after the first, so its last state is the one kept). It runs outside Firebase's
 * few seconds, so a phone that must restore its session first still keeps the answer for the watch. It ends once the item is put; a read that fails is
 * tried again by WorkManager, a few times, with its own backoff.
 */
class WatchResultsWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val results = WatchResults.of(applicationContext as TafsApplication)
    return try {
      val askId = inputData.getString(ASK_ID)
      if (askId != null) results.ask(askId) else results.inbox()
      Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (_: Exception) {
      // tafs cloud or the Data Layer didn't answer: again later, at most a few times; then the watch reads it when it opens.
      if (runAttemptCount < MAX_TRIES) Result.retry() else Result.success()
    }
  }

  /** Android 11 runs an expedited job as a foreground service, which needs a notification: the quiet channel, the app's name. */
  override suspend fun getForegroundInfo(): ForegroundInfo {
    AlertNotifier(applicationContext)
    val notification = NotificationCompat.Builder(applicationContext, AlertNotifier.idOf(AlertChannel.OTHER))
      .setSmallIcon(R.drawable.ic_bell)
      .setContentTitle(Words.App.name)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setSilent(true)
      .build()
    return ForegroundInfo(NOTIFICATION_ID, notification)
  }

  companion object {
    private const val ASK_ID = "ask_id"
    private const val MAX_TRIES = 3
    private const val NOTIFICATION_ID = 0x7a75

    /** An ask from the watch changed: its job, after any for the same ask still to run. */
    fun ask(context: Context, askId: String) = enqueue(context, "watch-ask:$askId", askId)

    /** The Inbox's marks changed: its job, after any still to run. */
    fun inbox(context: Context) = enqueue(context, "watch-inbox", null)

    private fun enqueue(context: Context, name: String, askId: String?) {
      val request = OneTimeWorkRequestBuilder<WatchResultsWork>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(if (askId != null) workDataOf(ASK_ID to askId) else workDataOf())
        .build()
      WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
  }
}
