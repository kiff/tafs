package bot.tafs.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import bot.tafs.watchlink.HeldLine

/**
 * Held calls as the phone pushes them (`kept/needs-you`, docs/watch-wear.md, Delivery), one place for the open app ([WatchModel])
 * and the listener that keeps what the phone sends while it's closed ([KeptResults]). What the watch keeps of one is its taf and
 * what it wants, with no amount, as the Privacy page says; the amount and the reason come from the live read when it's opened.
 */
object HeldCalls {
  /**
   * The Inbox with [calls] in it: each held call a line (new ones unread, first), and a held line the phone no longer lists (answered,
   * refused or run out, on any device) gone. Lines taken away ([dismissed]) never come back. Null when nothing changes.
   */
  fun inbox(items: List<InboxItem>, dismissed: Set<String>, calls: List<HeldLine>, at: Long): List<InboxItem>? {
    val listed = live(calls, at).map { InboxItem.heldId(it.holdId) }.toSet()
    var next = items.filterNot { it.kind == InboxItem.Kind.HELD && (it.id !in listed || ended(it, at)) }
    for (call in live(calls, at)) InboxLines.put(next, dismissed, line(call, at))?.let { next = it }
    return next.takeIf { it != items }
  }

  /** A held call's Inbox line: its taf and what it wants, with no amount in it ([bot.tafs.watchlink.Amounts]), and its end. */
  fun line(call: HeldLine, at: Long): InboxItem =
    InboxItem(InboxItem.heldId(call.holdId), InboxItem.Kind.HELD, call.taf, bot.tafs.watchlink.Amounts.strip(call.wants).trim(), at, endsAt = call.endsAt)

  /** The calls whose hold hasn't ended at [now], on the watch's own clock (row 29): an ended one is never drawn or counted. */
  fun live(calls: List<HeldLine>, now: Long): List<HeldLine> = calls.filter { (it.endsAt ?: Long.MAX_VALUE) > now }

  private fun ended(item: InboxItem, now: Long) = (item.endsAt ?: Long.MAX_VALUE) <= now

  /** The Inbox without held lines whose hold has ended at [now]; null when none has. */
  fun dropEnded(items: List<InboxItem>, now: Long): List<InboxItem>? =
    items.filterNot { it.kind == InboxItem.Kind.HELD && ended(it, now) }.takeIf { it.size != items.size }

  internal const val CHANNEL = "held"

  /** The notification's tag for a held call: one per call, never two. */
  internal fun tag(holdId: String) = "held:$holdId"

  /** The ids of [calls] the watch hasn't seen yet: none of them is in the Inbox [before], nor taken away from it. */
  fun fresh(before: List<InboxItem>, dismissed: Set<String>, calls: List<HeldLine>): Set<String> =
    calls.map { it.holdId }.filterTo(mutableSetOf()) { id -> InboxItem.heldId(id).let { it !in dismissed && before.none { line -> line.id == it } } }

  /**
   * The watch's own alert for a held call: one per call [fresh] to this watch (so one swiped away doesn't come back with the next
   * push), and gone when the phone no longer lists it. Locked or with no screen lock, when nothing is kept ([fresh] null), one alert
   * says only how many held calls need you, and buzzes only when that number goes up. Opened, the app reads Needs you from the
   * phone, where Refuse (behind the watch's lock) and Approve on your phone are. The watch never approves.
   */
  fun alert(context: Context, all: List<HeldLine>, fresh: Set<String>?, now: Long = System.currentTimeMillis()) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val calls = live(all, now)
    val listed = calls.map { tag(it.holdId) }.toSet()
    val active = runCatching { manager.activeNotifications.toList() }.getOrDefault(emptyList())
    for (shown in active) if (shown.tag?.startsWith("held:") == true && shown.tag !in listed) runCatching { manager.cancel(shown.tag, ID) }
    if (calls.isEmpty()) {
      runCatching { manager.cancel(COUNT_TAG, ID) }
      return
    }
    manager.createNotificationChannel(NotificationChannel(CHANNEL, Words.Watch.heldChannel, NotificationManager.IMPORTANCE_HIGH))
    val open = PendingIntent.getActivity(
      context, CHANNEL.hashCode(),
      Intent(context, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val name = context.getString(R.string.app_name)
    // What a locked watch shows: how many held calls need you, never the taf or what it wants.
    fun count(): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(name)
      .setContentText(Words.Watch.heldCalls(calls.size)).setContentIntent(open).setAutoCancel(true)
    if (fresh == null) {
      val before = active.firstOrNull { it.tag == COUNT_TAG }?.notification?.extras?.getInt(EXTRA_COUNT) ?: 0
      // Gone by itself once the last of them ends.
      val last = calls.mapNotNull { it.endsAt }.takeIf { it.size == calls.size }?.maxOrNull()
      val alert = count().setPriority(NotificationCompat.PRIORITY_HIGH).setOnlyAlertOnce(calls.size <= before)
        .apply { last?.let { setTimeoutAfter((it - now).coerceAtLeast(1)) } }
        .setExtras(android.os.Bundle().apply { putInt(EXTRA_COUNT, calls.size) }).build()
      runCatching { manager.notify(COUNT_TAG, ID, alert) }
      return
    }
    for (call in calls.filter { it.holdId in fresh }) {
      val text = bot.tafs.watchlink.Amounts.strip(call.wants).trim()
      val shown = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(call.taf)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(count().build())
        .setOnlyAlertOnce(true)
        .setContentIntent(open)
        .setAutoCancel(true)
        // Gone by itself when the hold ends, with no word from the phone (row 29).
        .apply { call.endsAt?.let { setTimeoutAfter((it - now).coerceAtLeast(1)) } }
        .build()
      runCatching { manager.notify(tag(call.holdId), ID, shown) }
    }
  }

  private const val COUNT_TAG = "held-count"
  private const val EXTRA_COUNT = "bot.tafs.wear.HELD_COUNT"
  private const val ID = 2
}
