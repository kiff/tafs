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
 * result is ready, never the taf or its line. What's kept of it is [KeptResults]'s.
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
      // Only when an amount was taken out: a double space or a trailing ":" alone changes nothing said (#1445).
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
 * What the phone keeps for the watch, arriving with the app closed or open (docs/watch-wear.md, Delivery): an ask's new state goes
 * into Coach's conversation and the Inbox kept on the watch, and is notified once it has ended; the Inbox's marks are taken in.
 * With the app on screen, its model takes it instead, so there's one writer at a time. Nothing is kept on a watch with no screen
 * lock, locked, or signed out here: then an ended ask is only notified, as before.
 */
object KeptResults {
  /** The open app's model, while it's on screen: the Inbox's marks. */
  @Volatile var marks: ((bot.tafs.watchlink.WatchReply.InboxMarks) -> Unit)? = null

  /** A kept ask: into the stores (the app closed) or the app's model (open), then notified as [AskResults.arrived] does. */
  fun ask(context: Context, ask: AskLine, stores: Stores = Stores.of(context), now: Long = System.currentTimeMillis()) {
    if (AskResults.shown == null && !stores.signedOut && stores.secure) {
      // Whichever kept thread holds it, Coach's or a taf's.
      stores.thread?.let { thread -> KeptThreads.took(KeptThreads.pruned(thread.read(), now), ask)?.let { thread.write(KeptThreads.pruned(it, now)) } }
      stores.inbox?.let { inbox -> InboxLines.of(ask, now)?.let { InboxLines.put(inbox.read(), inbox.dismissed(), it) }?.let(inbox::write) }
    }
    AskResults.arrived(context, ask)
  }

  /** The open app's model, while it's on screen: a taf's chat as the phone holds it now. */
  @Volatile var chats: ((String, List<bot.tafs.watchlink.ChatLine>) -> Unit)? = null

  /**
   * A taf's chat as the phone holds it now, pushed as it changed there: into the open app's chat at once, or, closed, into its
   * kept thread when the watch keeps one. Nothing is notified: the phone's own messages and a taf's answers to them are the phone's.
   */
  fun chat(tafId: String, lines: List<bot.tafs.watchlink.ChatLine>, stores: Stores, now: Long = System.currentTimeMillis()) {
    chats?.let { return it(tafId, lines) }
    val thread = stores.thread ?: return
    if (stores.signedOut || !stores.secure) return
    KeptThreads.chat(KeptThreads.pruned(thread.read(), now), tafId, lines)?.let { thread.write(KeptThreads.pruned(it, now)) }
  }

  /** The open app's model, while it's on screen: the phone signed out ([WatchModel.phoneSignedOut]). */
  @Volatile var signedOut: (() -> Unit)? = null

  /**
   * The phone signed out ([WatchLink.KEPT_SIGNED_OUT], from the paired phone only): everything of the account goes, the open app's
   * model or, closed, the stores, without asking. One that names another sign-in than the one held is late, and wipes nothing.
   */
  fun signedOut(context: Context, item: bot.tafs.watchlink.KeptSignedOut, stores: Stores) {
    if (!AccountMark.signedOutWipes(stores.marks?.read(), item.mark)) return
    signedOut?.let { return it() }
    stores.wipe()
    runCatching { context.getSystemService(NotificationManager::class.java)?.cancelAll() }
  }

  /**
   * One kept item from the phone, by its [path]: the app open or closed. Only what the sign-in the watch holds kept for it is taken
   * in ([AccountMark.keptTaken]); the signed-out item only from the paired phone, connected now ([fromPhone]), as its replies are.
   * Present when the watch comes back on after being off, it's taken the same way then.
   */
  fun kept(context: Context, path: String, bytes: ByteArray?, stores: Stores, fromPhone: () -> Boolean) {
    val held = stores.marks?.read()
    when {
      path == WatchLink.KEPT_SIGNED_OUT -> if (fromPhone()) bytes?.let(WatchLink::decodeKeptSignedOut)?.let { signedOut(context, it, stores) }
      WatchLink.keptAskId(path) != null -> bytes?.let(WatchLink::decodeKeptAsk)?.takeIf { AccountMark.keptTaken(held, it.mark) }?.ask?.takeIf { it.id == WatchLink.keptAskId(path) }?.let { ask(context, it, stores) }
      path == WatchLink.KEPT_MARKS -> bytes?.let(WatchLink::decodeKeptMarks)?.takeIf { AccountMark.keptTaken(held, it.mark) }?.let { marks(bot.tafs.watchlink.WatchReply.InboxMarks(it.read, it.dismissed), stores) }
      WatchLink.keptChatTaf(path) != null -> bytes?.let(WatchLink::decodeKeptChat)?.takeIf { it.tafId == WatchLink.keptChatTaf(path) && AccountMark.keptTaken(held, it.mark) }?.let { chat(it.tafId, it.lines, stores) }
    }
  }

  /** Kept marks: into the Inbox kept on the watch (the app closed) or the app's model (open). */
  fun marks(marks: bot.tafs.watchlink.WatchReply.InboxMarks, stores: Stores) {
    KeptResults.marks?.let { return it(marks) }
    val inbox = stores.inbox ?: return
    if (stores.signedOut || !stores.secure) return
    val (next, away) = InboxLines.marked(inbox.read(), inbox.dismissed(), marks.read.toSet(), marks.dismissed.toSet())
    inbox.write(next)
    inbox.writeDismissed(away)
  }

  /**
   * Where the watch keeps things, and whether it may: v2's stores, on a watch with a screen lock, unlocked, not signed out here.
   * The rest is what a sign-out takes with it ([wipe]): the cache, a message kept for later, the tile's count, the mark, and
   * [others] (recordings).
   */
  class Stores(
    val thread: ThreadStore?,
    val inbox: InboxStore?,
    val secure: Boolean,
    val signedOut: Boolean,
    val marks: MarkStore? = null,
    val cache: CacheStore? = null,
    val drafts: DraftStore? = null,
    val tile: TileCount.Store? = null,
    val others: () -> Unit = {},
  ) {
    /** Everything of the account the watch keeps, gone: the app closed, so nothing in memory is left to clear. */
    fun wipe() {
      thread?.clear()
      inbox?.clear()
      cache?.clear()
      drafts?.clear()
      tile?.clear()
      marks?.clear()
      runCatching { others() }
    }

    companion object {
      fun of(context: Context): Stores = Stores(
        thread = if (WATCH_V2 && KEEP_THREADS) KeptThreadStore.of(context) else null,
        inbox = if (WATCH_V2) KeptInboxStore.of(context) else null,
        marks = PreferencesMarkStore(context),
        cache = if (WATCH_V2) KeystoreCacheStore(context) else null,
        drafts = if (WATCH_V2) PreferencesDraftStore(context) else null,
        tile = PreferencesTileStore(context),
        others = { VoiceFiles.dir(context).deleteRecursively() },
        // The Inbox's own rule: kept only on a watch with a screen lock, unlocked now. A locked watch gets the notification's
        // locked words only, and the app takes the answer in at its next open, from the phone's read.
        secure = context.getSystemService(android.app.KeyguardManager::class.java)?.let { it.isDeviceSecure && !it.isDeviceLocked } == true,
        signedOut = PreferencesWatchSignIn(context).signedOut(),
      )
    }
  }
}

/**
 * Where the phone's results reach the watch. Its kept items ([WatchLink.KEPT], `DATA_CHANGED`), which the Data Layer delivers when
 * the watch is next in reach, its app open or not, and which the watch deletes once kept; and, from a phone before them, its live
 * message ([WatchLink.ASK_RESULT]), only from a node connected now. The Data Layer joins only tafs on the paired phone, signed
 * with the same key.
 */
class AskResultService : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != WatchLink.ASK_RESULT) return
    val connected = runCatching { runBlocking { Wearable.getNodeClient(this@AskResultService).connectedNodes.await().any { it.id == event.sourceNodeId } } }.getOrDefault(false)
    if (!connected) return
    val ask = WatchLink.decodeResult(event.data) ?: return
    AskResults.arrived(this, ask)
  }

  /** Whether a kept item was put by a phone connected to this watch now (its uri's node), as a request's reply must come from one. */
  private fun fromPhone(uri: android.net.Uri): Boolean {
    val node = uri.host ?: return false
    return runCatching { runBlocking { Wearable.getNodeClient(this@AskResultService).connectedNodes.await().any { it.id == node } } }.getOrDefault(false)
  }

  override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
    val data = Wearable.getDataClient(this)
    // Read while the buffer is open: it's released when this returns.
    val changed = events.filter { it.type == com.google.android.gms.wearable.DataEvent.TYPE_CHANGED }
      .map { it.dataItem.uri to it.dataItem.data }
    for ((uri, bytes) in changed) {
      val path = uri.path ?: continue
      if (!path.startsWith(WatchLink.KEPT)) continue
      KeptResults.kept(this, path, bytes, KeptResults.Stores.of(this)) { fromPhone(uri) }
      // Kept now: the item goes, on the watch and the phone, so nothing waits in the Data Layer once it's here.
      runCatching { data.deleteDataItems(uri) }
    }
  }
}
