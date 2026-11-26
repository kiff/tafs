package bot.tafs.app.watch

import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.watchlink.HeldLog
import bot.tafs.watchlink.KeptNeedsYou
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Needs you, kept for the watch as it changes on this phone (docs/watch-wear.md, Delivery): when a call is held, answered, refused
 * or runs out, the phone puts the count, its words and each held call's taf and what it wants (never an amount or the reason, as the
 * Privacy page says) as one urgent Data Layer item (`kept/needs-you`), with the account mark. So a held call reaches the wrist at once, its count, its line at the top of the
 * Inbox, Refuse behind the watch's lock and Approve on your phone, without the watch asking first. The watch never approves.
 * Only once the watch has used tafs in this sign-in ([AppState.watchServed]), only on a change (compared with the last put), never
 * on a timer.
 */
class WatchNeedsYou(
  private val keep: KeptPut,
  /** The phone's account mark now ([bot.tafs.app.app.AppModel.watchMark]). */
  private val mark: () -> String? = { null },
  private val now: () -> Long = System::currentTimeMillis,
) {
  private var sent: WatchReply.NeedsYou? = null

  /** The item to put for [state], or null: nothing read yet, signed out, the watch not served, or Needs you as last put. */
  @Synchronized fun changed(state: AppState): Pair<String, ByteArray>? {
    if (state.mode != Mode.kiff || !state.listLoaded || !state.watchServed) {
      log(HeldLog.skipped(HeldLog.PHONE_PUT_NOTHING, HeldLog.NOT_NOW, "signed_in" to (state.mode == Mode.kiff), "read" to state.listLoaded, "watch_served" to state.watchServed, "held" to state.held.size))
      return null
    }
    val needsYou = WatchRelay.needsYou(state)
    if (needsYou == sent) return null
    sent = needsYou
    // What the watch may keep, as the Privacy page says: the count, and per held call its taf's name and what it wants with the
    // amount taken out (the line's `wants` already is). Never KIFF's amount or the reason: those come from the live read when the
    // person opens the call, shown while unlocked and never kept.
    val calls = needsYou.calls.map { it.copy(amount = null, reason = null) }
    val kept = KeptNeedsYou(now(), needsYou.count, needsYou.words, calls, mark()?.takeIf(WatchLink::isMark))
    log(HeldLog.line(HeldLog.PHONE_PUT, kept.count, calls, kept.putAt, "mark" to (kept.mark != null)))
    return WatchLink.KEPT_NEEDS_YOU to WatchLink.encodeKept(kept)
  }

  /** Signed out on the phone: the next sign-in's Needs you is put whole, whatever was put before. */
  @Synchronized fun forget() {
    sent = null
  }

  /** Follows the phone's state in [scope] (the app's), putting each change of Needs you for the watch. */
  fun follow(states: Flow<AppState>, scope: CoroutineScope) {
    scope.launch {
      states.map { it }
        .distinctUntilChanged { a, b ->
          a.held == b.held && a.needsYouCount == b.needsYouCount && a.needsYouWords == b.needsYouWords && a.tafs == b.tafs &&
            a.listLoaded == b.listLoaded && a.mode == b.mode && a.watchServed == b.watchServed
        }
        .collect { state ->
          changed(state)?.let { (path, data) ->
            runCatching { keep.put(path, data) }.onFailure { log(HeldLog.skipped(HeldLog.PHONE_PUT_FAILED, it.javaClass.simpleName)) }
          }
        }
    }
  }

  companion object {
    /** One line about held calls in logcat ([HeldLog]): counts and seconds only. */
    fun log(line: String) {
      runCatching { android.util.Log.i(HeldLog.TAG, line) }
    }
  }
}
