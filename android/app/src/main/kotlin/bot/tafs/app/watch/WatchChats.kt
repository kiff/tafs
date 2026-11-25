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
 * opened while this process runs ([opened]), each change to the phone's own thread of it (a message sent here, the taf's answer
 * as the phone follows the chat) is put for the watch as one Data Layer item, its last [ChatLine.KEPT] messages
 * (`kept/taf-chat/{id}`), so a chat open there moves at once and one kept there stays the phone's.
 *
 * Only while the phone holds the thread: tafs cloud sends no push when a taf's chat gains a message the watch didn't send, so a
 * phone whose app isn't running tells the watch nothing of it until the chat is next opened there.
 */
class WatchChats(
  private val keep: KeptPut,
  private val now: () -> Long = System::currentTimeMillis,
  private val zone: () -> String? = { null },
) {
  private val watched = java.util.Collections.synchronizedSet(mutableSetOf<String>())
  private val sent = mutableMapOf<String, List<ChatLine>>()

  /** The watch read [tafId]'s chat: from now on its changes are kept for the watch. */
  fun opened(tafId: String) {
    watched += tafId
  }

  /** The items to put for [chats] (the phone's threads by taf): one per watched taf whose lines changed since the last put. */
  @Synchronized fun changed(chats: Map<String, List<ChatMessage>>): List<Pair<String, ByteArray>> =
    watched.toList().mapNotNull { tafId ->
      val lines = WatchRelay.chatLines(chats[tafId] ?: return@mapNotNull null, ChatLine.KEPT)
      if (sent[tafId] == lines) return@mapNotNull null
      sent[tafId] = lines
      val path = WatchLink.keptChatPath(tafId) ?: return@mapNotNull null
      path to WatchLink.encodeKept(KeptChat(now(), tafId, lines, zone()))
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
    /** The app's model's threads, as they change. */
    fun of(state: Flow<bot.tafs.app.app.AppState>): Flow<Map<String, List<ChatMessage>>> = state.map { it.chats }
  }
}
