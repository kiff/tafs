package bot.tafs.wear

import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * How the watch reaches the phone (row 44, D; docs/watch-wear.md, "Reaching the phone"), so a phone that's there isn't called
 * unreachable:
 * - **The phone it reached last** is asked directly, with no capability lookup first; a lookup is made only when there's none
 *   or that phone failed.
 * - **No phone found** (common in the first seconds after the app starts, before the Data Layer has its nodes) is looked for
 *   once more after [settleMs].
 * - **A read whose send failed** is sent once more to a phone found afresh. A write (Refuse, Halt, an ask, Coach) never is: a
 *   failed send may still have arrived, and the person decides whether to try again.
 *
 * Each try's outcome is said ([Tried]), so the timing line names the reason. The caller bounds the whole with its own timeout.
 */
internal class PhoneRoute(
  private val lookup: suspend () -> String?,
  private val send: suspend (node: String, path: String, data: ByteArray) -> ByteArray,
  private val settleMs: Long = 500,
) {
  @Volatile private var known: String? = null

  /** The phone to send a recording to: the one reached last, or one found now. */
  suspend fun node(): String? = known ?: lookup()?.also { known = it }

  sealed interface Tried {
    data class Reply(val bytes: ByteArray, val looked: Boolean, val retried: Boolean) : Tried
    data class NoPhone(val retried: Boolean) : Tried
    data class Failed(val error: Exception, val retried: Boolean) : Tried
  }

  /** [onFound] is told when a phone is known, so the time to find it can be measured apart from the send. */
  suspend fun ask(request: WatchRequest, path: String, data: ByteArray, onFound: () -> Unit = {}): Tried {
    var looked = false
    var retried = false
    suspend fun find(): String? {
      looked = true
      return lookup() ?: run {
        retried = true
        delay(settleMs)
        lookup()
      }
    }
    val first = known ?: find() ?: return Tried.NoPhone(retried)
    onFound()
    return try {
      Tried.Reply(send(first, path, data), looked, retried).also { known = first }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      known = null
      // A read goes once more to a phone found afresh; a write never is sent twice.
      if (!isRead(request)) return Tried.Failed(e, retried)
      val again = lookup() ?: return Tried.Failed(e, true)
      try {
        Tried.Reply(send(again, path, data), looked = true, retried = true).also { known = again }
      } catch (e2: CancellationException) {
        throw e2
      } catch (e2: Exception) {
        Tried.Failed(e2, true)
      }
    }
  }

  companion object {
    /**
     * Asks that only read: safe to send twice. Refuse, Halt, an ask and a Coach message act, so they're sent once. Every type is
     * named, with no default: a new request won't compile until someone decides whether it reads or acts.
     */
    fun isRead(request: WatchRequest): Boolean = when (request) {
      // Transcribe too: the phone deletes the recording once it's read, so a second send finds none.
      // An Inbox mark changes what tafs cloud keeps, so it is sent once, as on the Apple Watch.
      is WatchRequest.Refuse, is WatchRequest.Halt, is WatchRequest.Ask, is WatchRequest.Coach, is WatchRequest.Transcribe,
      is WatchRequest.InboxMark -> false
      is WatchRequest.NeedsYou, is WatchRequest.Tafs, is WatchRequest.Asks, is WatchRequest.Brief, is WatchRequest.Taf,
      is WatchRequest.TafChat, is WatchRequest.AskFull, is WatchRequest.Calendar, is WatchRequest.Account, is WatchRequest.InboxMarks -> true
    }
  }
}
