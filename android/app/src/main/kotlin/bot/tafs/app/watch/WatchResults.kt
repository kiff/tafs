package bot.tafs.app.watch

import android.content.Context
import bot.tafs.app.TafsApplication
import bot.tafs.watchlink.KeptAsk
import bot.tafs.watchlink.KeptMarks
import bot.tafs.watchlink.WatchLink
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Where the phone keeps something for the watch: a Data Layer item, delivered when the watch is next in reach. */
fun interface KeptPut {
  suspend fun put(path: String, data: ByteArray)
}

/** Takes back what the phone kept for the watch: the item at [path], or every item under it when [prefix]. */
fun interface KeptDrop {
  suspend fun drop(path: String, prefix: Boolean)
}

/**
 * Signed out on the phone (docs/watch-wear.md, "What the watch keeps"): what the phone kept for the watch of the account is taken
 * back (an ask's state, the Inbox's marks, a taf's chat), and [WatchLink.KEPT_SIGNED_OUT] is put with the mark the phone dropped,
 * so a watch out of reach now forgets the account when it's next in reach, without asking. Each step is tried on its own.
 */
class WatchSignedOut(
  private val keep: KeptPut,
  private val drop: KeptDrop,
  private val now: () -> Long = System::currentTimeMillis,
) {
  suspend fun tell(mark: String?) {
    runCatching { drop.drop(WatchLink.KEPT_ASK, prefix = true) }
    runCatching { drop.drop(WatchLink.KEPT_MARKS, prefix = false) }
    runCatching { drop.drop(WatchLink.KEPT_TAF_CHAT, prefix = true) }
    runCatching { drop.drop(KeptTafsJson.path, prefix = false) }
    runCatching { drop.drop(WatchLink.KEPT_NEEDS_YOU, prefix = false) }
    runCatching { keep.put(WatchLink.KEPT_SIGNED_OUT, WatchLink.encodeKept(bot.tafs.watchlink.KeptSignedOut(now(), mark?.takeIf(WatchLink::isMark)))) }
  }

  companion object {
    fun of(context: Context): WatchSignedOut = DataLayerKeep(context).let { WatchSignedOut(it, it) }
  }
}

/**
 * Passes what changed for the Wear OS watch on to it (docs/watch-wear.md, Delivery): tafs cloud's push says only which ask
 * changed (`watch_ask`), or that the Inbox's marks did (`watch_inbox`), so the phone reads it with its own session and keeps it
 * for the watch as a Data Layer item ([WatchLink.KEPT]), as tafs cloud words it. The Data Layer delivers it when the watch is
 * next in reach, its app open or not, and the watch deletes it once kept. Nothing is shown on the phone.
 *
 * Run by [WatchResultsWork], outside Firebase's few seconds, so a phone that has to restore its session first still passes the
 * answer on. Signed out, or an ask tafs cloud doesn't give this person, puts nothing.
 */
class WatchResults(
  /** One of the watch's asks, by its id, read from tafs cloud; null when the phone isn't signed in or tafs cloud has none. */
  private val readAsk: suspend (String) -> bot.tafs.app.kiff.WatchAsk?,
  /** The Inbox's marks as tafs cloud keeps them; null when the phone isn't signed in. */
  private val readMarks: suspend () -> bot.tafs.app.kiff.WatchInboxMarks?,
  private val keep: KeptPut,
  private val now: () -> Long = System::currentTimeMillis,
  /** A taf's chat by its id in tafs cloud: the phone's id for the taf and its messages, as the chat view reads them; null for none. */
  private val readChat: suspend (String) -> Pair<String, List<bot.tafs.app.model.ChatMessage>>? = { null },
  /** The taf chats the watch has opened, by the phone's id ([WatchChats.openedIds]): only those are kept for it. */
  private val opened: () -> Set<String> = { emptySet() },
  private val zone: () -> String? = { null },
  /** The phone's account mark now ([bot.tafs.app.app.AppModel.watchMark]); none in a test that doesn't look. */
  private val mark: suspend () -> String? = { null },
  /**
   * Needs you read again, as the item to keep for the watch ([WatchNeedsYou.changed]): null when the phone isn't signed in, the
   * watch hasn't used tafs in this sign-in, or Needs you is as last put.
   */
  private val readNeedsYou: suspend () -> Pair<String, ByteArray>? = { null },
) {
  /** [read], with the account mark it was read under: none when the sign-in changed while it was read, so nothing is put. */
  private suspend fun <T> marked(read: suspend () -> T?): Pair<T, String?>? {
    val before = mark()
    val value = read() ?: return null
    val after = mark()
    return if (before == after) value to after else null
  }

  /** Reads [askId] and keeps it for the watch. Whether it was put. */
  suspend fun ask(askId: String): Boolean {
    // A message to a taf too: it lands in that taf's chat on the watch, open or kept, never in its Inbox (docs/watch-wear.md).
    val (ask, mark) = marked { readAsk(askId) } ?: return false
    val line = WatchRelay.lineOf(ask)
    keep.put(WatchLink.keptAskPath(line.id), WatchLink.encodeKept(KeptAsk(now(), line, mark)))
    return true
  }

  /**
   * A taf's chat changed (tafs cloud's `taf_chat` push, by its id there): for a chat the watch has opened, reads it and keeps its
   * last 30 messages for the watch (`kept/taf-chat/{id}`). One the watch never opened, or a taf this phone doesn't list: nothing.
   */
  suspend fun tafChat(cloudId: String): Boolean {
    val (read, mark) = marked { readChat(cloudId) } ?: return false
    val (tafId, messages) = read
    if (tafId !in opened()) return false
    val path = WatchLink.keptChatPath(tafId) ?: return false
    keep.put(path, WatchLink.encodeKept(bot.tafs.watchlink.KeptChat(now(), tafId, WatchRelay.chatLines(messages, bot.tafs.watchlink.ChatLine.KEPT), zone(), mark)))
    return true
  }

  /**
   * A held call came or went (its alert, or its answer, finding 2): Needs you read again and kept for the watch, its app open or not,
   * so the wrist hears of it with the phone's app in the background too. Whether it was put.
   */
  suspend fun needsYou(): Boolean {
    val (path, data) = readNeedsYou() ?: return false
    keep.put(path, data)
    return true
  }

  /** Reads the Inbox's marks and keeps them for the watch. Whether they were put. */
  suspend fun inbox(): Boolean {
    val (marks, mark) = marked { readMarks() } ?: return false
    keep.put(WatchLink.KEPT_MARKS, WatchLink.encodeKept(KeptMarks(now(), marks.read, marks.dismissed, mark)))
    return true
  }

  companion object {
    /** The real one: the app's one model, its session restored for the watch with no screen, and the Data Layer. */
    fun of(app: TafsApplication): WatchResults = WatchResults(
      readAsk = { id -> app.restoredModel(onScreen = false).watchAskById(id) },
      readMarks = { app.restoredModel(onScreen = false).watchInbox() },
      keep = DataLayerKeep(app),
      readChat = { cloudId ->
        val model = app.restoredModel(onScreen = false)
        model.state.value.tafs.firstOrNull { it.cloudId == cloudId }?.id?.let { tafId -> model.watchTafChat(tafId)?.let { tafId to it } }
      },
      opened = { app.watchChats.openedIds() },
      zone = { bot.tafs.app.model.zone.id },
      mark = { app.restoredModel(onScreen = false).watchMark() },
      readNeedsYou = {
        val model = app.restoredModel(onScreen = false)
        // Only a watch that has used tafs in this sign-in is told: no read for a phone with none.
        if (!model.state.value.watchServed) null
        else {
          model.refresh()
          app.watchNeedsYou.changed(model.state.value)
        }
      },
    )
  }
}

/** [KeptPut] on the Data Layer: urgent, so it's delivered at once when the watch is in reach rather than batched. */
class DataLayerKeep(private val context: Context) : KeptPut, KeptDrop {
  override suspend fun put(path: String, data: ByteArray) {
    Wearable.getDataClient(context).putDataItem(PutDataRequest.create(path).setData(data).setUrgent()).await()
  }

  /** Every node's item at [path] (`wear:` with no node): the phone's own, which the Data Layer then takes off the watch too. */
  override suspend fun drop(path: String, prefix: Boolean) {
    val uri = android.net.Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()
    val filter = if (prefix) com.google.android.gms.wearable.DataClient.FILTER_PREFIX else com.google.android.gms.wearable.DataClient.FILTER_LITERAL
    Wearable.getDataClient(context).deleteDataItems(uri, filter).await()
  }
}
