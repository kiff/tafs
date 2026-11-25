package bot.tafs.wear

import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.ChatLine
import bot.tafs.watchlink.WatchLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * The chats kept on the watch (docs/watch-wear.md, "What the watch keeps"), as the Apple Watch keeps them: the last [MESSAGES_MAX]
 * messages of each of up to [THREADS_MAX] threads opened on the watch, Coach's ([COACH_THREAD]) and each taf's
 * ([tafThread]), text only with no amount ([bot.tafs.watchlink.Amounts]). Gone on either sign-out, on a watch with no screen
 * lock, and for a thread not opened in [UNOPENED_MS]. Behind [KEEP_THREADS] until the owner's yes and the Privacy page's line.
 */
data class KeptTurn(val said: WatchModel.Said, val ask: AskLine)

/**
 * One kept thread: when it was last opened on the watch, the phone's lines of it (a taf's chat, as `taf_chat` gives them), and the
 * turns sent from this watch with their asks (Coach's conversation, or a taf's messages sent since its lines were read).
 */
data class KeptThread(val openedAt: Long, val lines: List<ChatLine> = emptyList(), val turns: List<KeptTurn> = emptyList())

/**
 * Whether the watch keeps its chats on disk: on only in a test version ("-test") until the owner's yes and the Privacy page's
 * line (wear/build.gradle.kts). Off, they live in memory while the app runs; the rest of delivery is the same.
 */
internal val KEEP_THREADS: Boolean = BuildConfig.KEEP_THREADS

/** The most messages kept of one thread: the newest. A turn sent from the watch is two, its words and its answer. */
internal const val MESSAGES_MAX = 30

/** The most threads kept: the ones opened last. */
internal const val THREADS_MAX = 20

/** A thread not opened on the watch for this long goes. */
internal const val UNOPENED_MS = 30L * 24 * 60 * 60 * 1000

/** Coach's conversation's key. */
internal const val COACH_THREAD = "coach"

/** A taf's chat's key, by the phone's id for it. */
internal fun tafThread(tafId: String) = "taf:$tafId"

/** Where the chats are kept: the watch's private preferences, or a map in a test. */
interface ThreadStore {
  fun read(): Map<String, KeptThread>
  fun write(threads: Map<String, KeptThread>)
  fun clear()
}

/** The rules for what's kept, one place for the open app ([WatchModel]) and the listener ([KeptResults]). */
object KeptThreads {
  /**
   * What may stay: threads opened within [UNOPENED_MS] of [now], the [THREADS_MAX] opened last, each its newest [MESSAGES_MAX]
   * messages, with no amount.
   */
  fun pruned(threads: Map<String, KeptThread>, now: Long): Map<String, KeptThread> =
    threads.filterValues { now - it.openedAt in 0 until UNOPENED_MS }
      .entries.sortedByDescending { it.value.openedAt }.take(THREADS_MAX)
      .associate { (key, thread) -> key to trimmed(thread) }

  /** [thread]'s newest [MESSAGES_MAX] messages: its oldest lines go first, then its oldest turns. */
  fun trimmed(thread: KeptThread): KeptThread {
    val turns = thread.turns.sortedBy { it.said.at }.takeLast(MESSAGES_MAX / 2).map(::stripped)
    val lines = thread.lines.takeLast((MESSAGES_MAX - turns.size * 2).coerceAtLeast(0)).map { it.copy(text = bot.tafs.watchlink.Amounts.strip(it.text)) }
    return thread.copy(lines = lines, turns = turns)
  }

  private fun stripped(t: KeptTurn) =
    t.copy(ask = t.ask.copy(line = t.ask.line?.let(bot.tafs.watchlink.Amounts::strip), words = bot.tafs.watchlink.Amounts.strip(t.ask.words)))

  /** [ask] in whichever kept thread has it, when it's not older than what's kept; null when nothing changes. */
  fun took(threads: Map<String, KeptThread>, ask: AskLine): Map<String, KeptThread>? {
    val (key, thread) = threads.entries.firstOrNull { e -> e.value.turns.any { it.ask.id == ask.id } } ?: return null
    val before = thread.turns.first { it.ask.id == ask.id }
    if (before.ask.updated > ask.updated) return null
    return threads + (key to thread.copy(turns = thread.turns.map { if (it.ask.id == ask.id) it.copy(ask = ask) else it }))
  }

  /** A taf's chat as the phone holds it now, into its kept thread when the watch keeps one; null when it doesn't. */
  fun chat(threads: Map<String, KeptThread>, tafId: String, lines: List<ChatLine>): Map<String, KeptThread>? {
    val key = tafThread(tafId)
    val thread = threads[key] ?: return null
    return threads + (key to withLines(thread, lines))
  }

  /** [thread] with the phone's [lines]: the watch's own turns the lines already hold (sent before the newest line) go. */
  fun withLines(thread: KeptThread, lines: List<ChatLine>): KeptThread {
    val newest = lines.mapNotNull { WatchChatTime.instantOf(it.at)?.toEpochMilli() }.maxOrNull()
    return thread.copy(lines = lines, turns = if (newest == null) thread.turns else thread.turns.filter { it.said.at > newest })
  }
}

/** [ThreadStore] in the watch's private preferences (`tafs.threads`), as one JSON object by thread. */
class PreferencesThreadStore(context: android.content.Context) : ThreadStore {
  private val prefs = context.getSharedPreferences("tafs.threads", android.content.Context.MODE_PRIVATE)

  override fun read(): Map<String, KeptThread> = runCatching { decode(prefs.getString(THREADS, null) ?: return emptyMap()) }.getOrDefault(emptyMap())

  override fun write(threads: Map<String, KeptThread>) {
    prefs.edit().putString(THREADS, encode(threads)).apply()
  }

  override fun clear() {
    prefs.edit().clear().apply()
  }

  companion object {
    private const val THREADS = "threads"

    fun encode(threads: Map<String, KeptThread>): String = JSONObject().apply {
      for ((key, t) in threads) {
        put(key, JSONObject()
          .put("opened_at", t.openedAt)
          .put("lines", JSONArray().apply {
            for (l in t.lines) put(JSONObject().put("id", l.id).put("mine", l.mine).put("at", l.at).put("text", l.text).put("more", l.more))
          })
          .put("turns", JSONArray().apply {
            for (turn in t.turns) put(JSONObject().put("said", turn.said.text).put("at", turn.said.at).put("ask", String(WatchLink.encodeResult(turn.ask))))
          }))
      }
    }.toString()

    fun decode(text: String): Map<String, KeptThread> {
      val o = JSONObject(text)
      return o.keys().asSequence().mapNotNull { key ->
        runCatching {
          val t = o.getJSONObject(key)
          val lines = t.getJSONArray("lines").let { a ->
            (0 until a.length()).map { n -> a.getJSONObject(n).let { ChatLine(it.getString("id"), it.getBoolean("mine"), it.getString("at"), it.getString("text"), it.optBoolean("more")) } }
          }
          val turns = t.getJSONArray("turns").let { a ->
            (0 until a.length()).mapNotNull { n ->
              val turn = a.getJSONObject(n)
              val ask = WatchLink.decodeResult(turn.getString("ask").encodeToByteArray()) ?: return@mapNotNull null
              KeptTurn(WatchModel.Said(turn.getString("said"), turn.getLong("at")), ask)
            }
          }
          key to KeptThread(t.getLong("opened_at"), lines, turns)
        }.getOrNull()
      }.toMap()
    }
  }
}
