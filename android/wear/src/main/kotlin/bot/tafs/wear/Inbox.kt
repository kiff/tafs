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

/** [InboxStore] in the watch's private preferences (`tafs.inbox`), as one JSON list: each line's id, kind, taf, words, time and read. */
class PreferencesInboxStore(context: android.content.Context) : InboxStore {
  private val prefs = context.getSharedPreferences("tafs.inbox", android.content.Context.MODE_PRIVATE)

  override fun read(): List<InboxItem> = runCatching { decode(prefs.getString(ITEMS, null) ?: return emptyList()) }.getOrDefault(emptyList())

  override fun write(items: List<InboxItem>) {
    prefs.edit().putString(ITEMS, encode(items)).apply()
  }

  override fun dismissed(): Set<String> =
    runCatching { JSONArray(prefs.getString(DISMISSED, null) ?: return emptySet()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())

  override fun writeDismissed(ids: Set<String>) {
    prefs.edit().putString(DISMISSED, JSONArray(ids.toList()).toString()).apply()
  }

  override fun clear() {
    prefs.edit().clear().apply()
  }

  companion object {
    private const val ITEMS = "items"
    private const val DISMISSED = "dismissed"

    fun encode(items: List<InboxItem>): String = JSONArray().apply {
      for (i in items) put(JSONObject().put("id", i.id).put("kind", i.kind.name).put("title", i.title).put("line", i.line).put("at", i.at).put("read", i.read))
    }.toString()

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
