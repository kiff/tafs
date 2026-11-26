package bot.tafs.app.watch

import bot.tafs.app.model.ChatMessage
import bot.tafs.watchlink.ChatLine
import bot.tafs.watchlink.KeptChat
import bot.tafs.watchlink.WatchLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A taf's chat, kept for the watch as it changes on this phone (docs/watch-wear.md, Delivery): for each taf whose chat the watch
 * opened ([opened], remembered across restarts by id alone), each change to the phone's own thread of it (a message sent here, the taf's answer
 * as the phone follows the chat) is put for the watch as one Data Layer item, its last [ChatLine.KEPT] messages
 * (`kept/taf-chat/{id}`), so a chat open there moves at once and one kept there stays the phone's.
 *
 * While the phone's app isn't running, tafs cloud's `taf_chat` push does the same through [WatchResultsWork] ([WatchResults.tafChat]).
 */
class WatchChats(
  private val keep: KeptPut,
  private val now: () -> Long = System::currentTimeMillis,
  private val zone: () -> String? = { null },
  /** Where the taf ids the watch opened are kept across the phone's restarts (ids only); in memory in a test. */
  private val store: OpenedStore = OpenedStore.Memory(),
  /** The phone's account mark now ([bot.tafs.app.app.AppModel.watchMark]), on each kept chat; none in a test that doesn't look. */
  private val mark: () -> String? = { null },
) {
  private val watched = java.util.Collections.synchronizedSet(store.read().toMutableSet())
  private val sent = mutableMapOf<String, List<ChatLine>>()

  /** The taf ids the watch opened: the phone's own ids, nothing of the chats. */
  interface OpenedStore {
    fun read(): Set<String>
    fun write(ids: Set<String>)

    class Memory : OpenedStore {
      private var ids: Set<String> = emptySet()
      override fun read() = ids
      override fun write(ids: Set<String>) { this.ids = ids }
    }

    /** In the phone's private preferences (`tafs.watch_chats`): ids only, at most [MAX], the opened last kept. */
    class Preferences(context: android.content.Context) : OpenedStore {
      private val prefs = context.getSharedPreferences("tafs.watch_chats", android.content.Context.MODE_PRIVATE)
      override fun read(): Set<String> = prefs.getString(IDS, null)?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty()
      override fun write(ids: Set<String>) {
        prefs.edit().putString(IDS, ids.toList().takeLast(MAX).joinToString("\n")).apply()
      }

      private companion object {
        const val IDS = "ids"
      }
    }
  }

  /** The watch read [tafId]'s chat: from now on its changes are kept for the watch, across the phone's restarts. */
  fun opened(tafId: String) {
    if (watched.add(tafId)) store.write(watched.toSet())
  }

  /** The taf chats the watch has opened, by the phone's ids. */
  fun openedIds(): Set<String> = watched.toSet()

  /**
   * The items to put for [chats] (the phone's threads by taf): one per watched taf whose lines changed since the last put. A chat
   * the watch was given that has since left the phone (a conversation deleted, or a new one begun in its place) is put empty,
   * so the watch's copy goes with it; one never given (the phone not yet read) puts nothing.
   */
  @Synchronized fun changed(chats: Map<String, List<ChatMessage>>): List<Pair<String, ByteArray>> =
    watched.toList().mapNotNull { tafId ->
      val thread = chats[tafId]
      if (thread == null && sent[tafId].isNullOrEmpty()) return@mapNotNull null
      val lines = if (thread == null) emptyList() else WatchRelay.chatLines(thread, ChatLine.KEPT)
      if (sent[tafId] == lines) return@mapNotNull null
      sent[tafId] = lines
      val path = WatchLink.keptChatPath(tafId) ?: return@mapNotNull null
      path to WatchLink.encodeKept(KeptChat(now(), tafId, lines, zone(), mark()))
    }

  /** Signed out on the phone: the chats the watch opened are the account's, so they're forgotten with it. */
  @Synchronized fun forget() {
    watched.clear()
    sent.clear()
    store.write(emptySet())
  }

  /** Follows the phone's threads in [scope] (the app's), putting each change for the watch. */
  fun follow(chats: Flow<Map<String, List<ChatMessage>>>, scope: CoroutineScope) {
    scope.launch {
      chats.distinctUntilChanged().collect { threads ->
        for ((path, data) in changed(threads)) runCatching { keep.put(path, data) }
      }
    }
  }

  companion object {
    /** The most taf chats remembered as opened on the watch: as many as it keeps. */
    const val MAX = 20

    /** The app's model's threads, as they change. */
    fun of(state: Flow<bot.tafs.app.app.AppState>): Flow<Map<String, List<ChatMessage>>> = state.map { it.chats }
  }
}
