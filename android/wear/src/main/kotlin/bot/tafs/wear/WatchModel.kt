package bot.tafs.wear

import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the watch reaches the phone: one live request and its answer, or nothing when the phone can't be reached. */
fun interface PhoneLink {
  /** The phone's reply; [Asked.Unreachable] when no phone with tafs answered in time. */
  suspend fun ask(request: WatchRequest): Asked
}

sealed interface Asked {
  data class Answered(val reply: WatchReply) : Asked

  /** No phone with tafs is connected, or it didn't answer in time. */
  data object Unreachable : Asked

  /** The phone answered with something this watch doesn't understand (a newer phone): it says Open on phone, never guesses. */
  data object NotUnderstood : Asked
}

/**
 * What the watch shows, read from the phone each time it's opened. Kept in memory only (docs/watch-wear.md): nothing of the
 * list, the tafs or their words is written to the watch's storage, and a new read replaces it whole.
 */
class WatchModel(private val phone: PhoneLink, private val now: () -> Long = System::currentTimeMillis) {
  sealed interface Shown {
    /** Asking the phone, with nothing to show yet. */
    data object Asking : Shown

    /** The phone's Needs you and Your tafs, in its words. */
    data class Read(val needsYou: NeedsYouRead, val tafs: TafsRead?) : Shown

    /** The phone isn't signed in to tafs. */
    data object SignedOut : Shown

    /** No phone answered: keep it close and try again. */
    data object PhoneUnreachable : Shown

    /** The phone said something this watch can't draw, or refused the read: Open on phone. */
    data object OpenOnPhone : Shown
  }

  data class NeedsYouRead(val count: Int, val words: String, val calls: List<HeldLine>)

  data class TafsRead(val tafs: List<TafLine>, val allHalted: Boolean)

  private val _shown = MutableStateFlow<Shown>(Shown.Asking)
  val shown: StateFlow<Shown> = _shown.asStateFlow()

  /** Reads Needs you, then Your tafs, from the phone. Your tafs not answering leaves Needs you shown, without the tafs. */
  suspend fun refresh() {
    if (_shown.value !is Shown.Read) _shown.value = Shown.Asking
    val needsYou = when (val asked = phone.ask(WatchRequest.NeedsYou(now()))) {
      is Asked.Answered -> when (val reply = asked.reply) {
        is WatchReply.NeedsYou -> NeedsYouRead(reply.count, reply.words, reply.calls)
        is WatchReply.SignedOut -> return show(Shown.SignedOut)
        else -> return show(Shown.OpenOnPhone)
      }
      Asked.Unreachable -> return show(Shown.PhoneUnreachable)
      Asked.NotUnderstood -> return show(Shown.OpenOnPhone)
    }
    val tafs = (phone.ask(WatchRequest.Tafs(now())) as? Asked.Answered)?.let { it.reply as? WatchReply.Tafs }?.let { TafsRead(it.tafs, it.allHalted) }
    show(Shown.Read(needsYou, tafs))
  }

  /** What the watch keeps once it's put away or the phone signs out: nothing. */
  fun forget() {
    _shown.value = Shown.Asking
  }

  private fun show(shown: Shown) {
    _shown.value = shown
  }
}
