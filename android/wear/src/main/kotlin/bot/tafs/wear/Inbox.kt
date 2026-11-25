package bot.tafs.wear

import org.json.JSONArray
import org.json.JSONObject

/**
 * One line of v2's Inbox (docs/watch-wear.md, Version 2, step iv): a result of the person's ask (Coach answered, a taf did it), or
 * a held call that needs them. Kept on the watch until dismissed, with whether it was read; cleared on sign-out and whenever the
 * watch is locked or has no screen lock, as the tile's count is. Read and dismissed stay on this watch until tafs cloud keeps them
 * in step with the phone (server piece c).
 */
data class InboxItem(
  /** `ask:<id>` or `held:<id>`: one line per ask or held call, never two. */
  val id: String,
  val kind: Kind,
  /** The taf, as the phone named it; none for an ask no taf took. */
  val title: String?,
  /** The result's line, or what the held call wants, in the phone's words. */
  val line: String,
  /** When the watch first saw it, for the order: newest first. */
  val at: Long,
  val read: Boolean = false,
) {
  enum class Kind { ASK, HELD }

  /** The ask's or the held call's own id. */
  val ref: String get() = id.substringAfter(':')

  companion object {
    fun askId(askId: String) = "ask:$askId"
    fun heldId(holdId: String) = "held:$holdId"
  }
}

/**
 * The Inbox's rules, one place for the open app ([WatchModel]) and the listener that keeps what the phone sends while it's closed
 * ([KeptResults]).
 */
object InboxLines {
  /**
   * [item] put in [items]: a new line, unread, first; one already there keeps its place and whether it was read, with the newest
   * words. Null when nothing changes: a line taken away ([dismissed]) never comes back.
   */
  fun put(items: List<InboxItem>, dismissed: Set<String>, item: InboxItem): List<InboxItem>? {
    if (item.id in dismissed) return null
    val before = items.firstOrNull { it.id == item.id }
    if (before != null && before.line == item.line && before.title == item.title) return null
    return if (before != null) items.map { if (it.id == item.id) it.copy(title = item.title, line = item.line) else it } else listOf(item) + items
  }

  /** An ended ask's line, as the watch keeps it: no amount ([bot.tafs.watchlink.Amounts]); none for a message to a taf. */
  fun of(ask: bot.tafs.watchlink.AskLine, at: Long): InboxItem? =
    if (!ask.ended || ask.tafChat) null
    else InboxItem(InboxItem.askId(ask.id), InboxItem.Kind.ASK, ask.tafName, Plain.of(bot.tafs.watchlink.Amounts.strip(ask.line ?: ask.words)), at)

  /**
   * The marks from tafs cloud (row 21): the lines dismissed on any watch go, and their ids join [dismissed]; the lines read on any
   * watch are read here. Nothing is ever brought back.
   */
  fun marked(items: List<InboxItem>, dismissed: Set<String>, read: Set<String>, gone: Set<String>): Pair<List<InboxItem>, Set<String>> {
    val away = items.filter { it.ref in gone }.map { it.id }
    val next = items.filterNot { it.ref in gone }.map { if (!it.read && it.ref in read) it.copy(read = true) else it }
    return next to (dismissed + away)
  }
}

/**
 * Where the Inbox is kept: the watch's private preferences, or a list in a test. [dismissed] are the ids taken away (Dismiss, Clear
 * read) that the phone still lists, so a read doesn't bring them back; each goes once the phone no longer lists it.
 */
interface InboxStore {
  fun read(): List<InboxItem>
  fun write(items: List<InboxItem>)
  fun dismissed(): Set<String> = emptySet()
  fun writeDismissed(ids: Set<String>) = Unit
  fun clear()
}

/**
 * [InboxStore] in the app's private files (`watch-inbox.bin`), encrypted at rest as the cache is ([KeystoreCacheStore]: AES-GCM
 * under the watch's Keystore key): one JSON object, the lines (each line's id, kind, taf, words, time and read) and the ids taken
 * away. What won't decrypt reads as an empty Inbox and is cleared. An Inbox kept in plain preferences by 0.4.7 and before
 * (`tafs.inbox`, [legacy]) is read once, written encrypted, and the plain copy cleared.
 */
class KeptInboxStore(private val text: EncryptedText, legacy: android.content.SharedPreferences? = null) : InboxStore {
  init {
    if (legacy != null && (legacy.contains(ITEMS) || legacy.contains(DISMISSED))) {
      val items = runCatching { decode(legacy.getString(ITEMS, null) ?: "[]") }.getOrDefault(emptyList())
      val gone = runCatching { ids(legacy.getString(DISMISSED, null) ?: "[]") }.getOrDefault(emptySet())
      save(items, gone)
      legacy.edit().clear().commit()
    }
  }

  private fun load(): Pair<List<InboxItem>, Set<String>> = runCatching {
    val o = JSONObject(text.read() ?: return emptyList<InboxItem>() to emptySet())
    decode(o.optJSONArray(ITEMS)?.toString() ?: "[]") to ids(o.optJSONArray(DISMISSED)?.toString() ?: "[]")
  }.getOrElse {
    text.clear()
    emptyList<InboxItem>() to emptySet()
  }

  private fun save(items: List<InboxItem>, gone: Set<String>) {
    text.write(JSONObject().put(ITEMS, JSONArray(encode(items))).put(DISMISSED, JSONArray(gone.toList())).toString())
  }

  override fun read(): List<InboxItem> = load().first

  override fun write(items: List<InboxItem>) = save(items, load().second)

  override fun dismissed(): Set<String> = load().second

  override fun writeDismissed(ids: Set<String>) = save(load().first, ids)

  override fun clear() = text.clear()

  companion object {
    private const val ITEMS = "items"
    private const val DISMISSED = "dismissed"

    fun encode(items: List<InboxItem>): String = JSONArray().apply {
      for (i in items) put(JSONObject().put("id", i.id).put("kind", i.kind.name).put("title", i.title).put("line", i.line).put("at", i.at).put("read", i.read))
    }.toString()

    /** The real one: encrypted under the watch's Keystore key, taking in 0.4.7's plain `tafs.inbox` once. */
    fun of(context: android.content.Context): KeptInboxStore =
      KeptInboxStore(EncryptedText(KeystoreCacheStore(context, "watch-inbox.bin")), context.getSharedPreferences("tafs.inbox", android.content.Context.MODE_PRIVATE))

    private fun ids(text: String): Set<String> = JSONArray(text).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }

    fun decode(text: String): List<InboxItem> {
      val a = JSONArray(text)
      return (0 until a.length()).mapNotNull { n ->
        val o = a.getJSONObject(n)
        val kind = runCatching { InboxItem.Kind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
        InboxItem(o.getString("id"), kind, o.optString("title").takeIf { o.has("title") && !o.isNull("title") }, o.getString("line"), o.getLong("at"), o.optBoolean("read"))
      }
    }
  }
}
