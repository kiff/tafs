package bot.tafs.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone link as the watch measured it, for the internal timing builds only (docs/watch-wear.md, "Measuring the link"; row 44,
 * D0): each ask's request, the time to find the phone (the capability lookup), the time from sending to the reply, the reply's
 * size, and how it ended. So "your phone isn't reachable" can be told apart: no phone found, a timeout, or the Data Layer's own
 * error. Nothing in it is shown or kept outside a timing build, and it holds no words of a reply: only its size.
 */
object LinkTrace {
  data class Trace(val request: String, val lookupMs: Long, val sendMs: Long?, val bytes: Int?, val outcome: String) {
    /** One faint line under a page: "tafs 0.4s + 1.9s · 3.2 kB", or "tafs ✕ no phone (0.1s)". */
    val line: String get() = if (outcome == OK) "$request ${s(lookupMs)} + ${sendMs?.let(::s) ?: "–"} · ${bytes?.let(::kb) ?: "–"}"
      else "$request ✕ $outcome (${s(lookupMs + (sendMs ?: 0))})"
  }

  const val OK = "ok"
  const val NO_PHONE = "no phone"
  const val TIMEOUT = "timeout"
  const val UNREADABLE = "unreadable"

  private val last = MutableStateFlow<Trace?>(null)
  val latest: StateFlow<Trace?> get() = last

  fun record(trace: Trace) { last.value = trace }

  /** A request's name for the line: its path's last part ("tafs/watch/v1/needs-you" → "needs-you"). */
  fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

  private fun s(ms: Long) = "%.1fs".format(java.util.Locale.ROOT, ms / 1000.0)
  private fun kb(bytes: Int) = if (bytes < 1024) "$bytes B" else "%.1f kB".format(java.util.Locale.ROOT, bytes / 1024.0)
}
