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
class WatchModel(
  private val phone: PhoneLink,
  private val now: () -> Long = System::currentTimeMillis,
  /**
   * Whether this watch may ask the phone to refuse or halt: a screen lock is set and the watch is unlocked now
   * (`KeyguardManager`). The phone signs what the watch asks, with no prompt of its own, so the watch's unlock is the confirmation.
   */
  private val unlocked: () -> Boolean = { false },
  /** Needs you's count for the tile, the only thing written to disk; none in a test that doesn't look at it. */
  private val tile: TileCount? = null,
) {
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

  /** What came of a Refuse or a Halt, shown over the screen until the next one. */
  sealed interface Outcome {
    /** It went through, in the phone's words ("Refused", "All your tafs halted"). */
    data class Done(val words: String) : Outcome

    /** The call was answered already, or is gone. */
    data object AlreadyAnswered : Outcome

    /** It didn't go through, in the phone's words: Open on phone. */
    data class NotDone(val words: String?) : Outcome

    /** The watch has no screen lock, or is locked: nothing was sent. */
    data object NeedsLock : Outcome

    data object PhoneUnreachable : Outcome
  }

  private val _outcome = MutableStateFlow<Outcome?>(null)
  val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

  /** Refuses one held call, through the phone. Nothing is sent from a watch without its lock, or locked. */
  suspend fun refuse(holdId: String) = act(WatchRequest.Refuse(now(), holdId))

  /** Halts one taf through the phone. Nothing is sent from a watch without its lock, or locked. */
  suspend fun halt(tafId: String) = act(WatchRequest.Halt(now(), tafId))

  private val _confirmingHaltAll = MutableStateFlow(false)

  /** Halt all is asked first, on the watch, before anything goes to the phone. */
  val confirmingHaltAll: StateFlow<Boolean> = _confirmingHaltAll.asStateFlow()

  /** Halt all tapped: the watch asks to confirm, and sends nothing yet. */
  fun askHaltAll() {
    _confirmingHaltAll.value = true
  }

  fun cancelHaltAll() {
    _confirmingHaltAll.value = false
  }

  /** Halt all confirmed: every taf, through the phone. Nothing is sent unless it was asked first. */
  suspend fun confirmHaltAll() {
    if (!_confirmingHaltAll.value) return
    _confirmingHaltAll.value = false
    act(WatchRequest.Halt(now(), null))
  }

  private suspend fun act(request: WatchRequest) {
    if (!unlocked()) {
      _outcome.value = Outcome.NeedsLock
      return
    }
    _outcome.value = when (val asked = phone.ask(request)) {
      // Shown only once the phone has KIFF's or tafs cloud's answer: that's when its reply comes.
      is Asked.Answered -> when (val reply = asked.reply) {
        is WatchReply.Done -> Outcome.Done(reply.words)
        WatchReply.AlreadyAnswered -> Outcome.AlreadyAnswered
        is WatchReply.Failed -> Outcome.NotDone(reply.words)
        else -> Outcome.NotDone(null)
      }
      Asked.Unreachable -> Outcome.PhoneUnreachable
      Asked.NotUnderstood -> Outcome.NotDone(null)
    }
    // The lists as they stand now.
    refresh()
  }

  /** Coach from the wrist: the words heard, shown before they're sent; then Coach's answer. */
  sealed interface Talk {
    /** What the watch's speech input heard, shown to the person before anything is sent. */
    data class Heard(val text: String) : Talk

    data object Sending : Talk

    /** Coach's answer, short; [long] when the rest (a card, more words) is on the phone. */
    data class Answered(val text: String, val long: Boolean) : Talk

    /** Not sent, in the phone's words (no AI consent yet, Coach not started on the phone…): Open on phone. */
    data class NotSent(val words: String?) : Talk
  }

  private val _talk = MutableStateFlow<Talk?>(null)
  val talk: StateFlow<Talk?> = _talk.asStateFlow()

  /** The speech input's text: shown, never sent until the person says Send. Nothing heard is kept but this text. */
  fun heard(text: String) {
    _talk.value = text.trim().takeIf { it.isNotEmpty() }?.let { Talk.Heard(it) }
  }

  /** Sends what was heard to Coach, through the phone, as text. */
  suspend fun sendToCoach() {
    val heard = _talk.value as? Talk.Heard ?: return
    _talk.value = Talk.Sending
    _talk.value = when (val asked = phone.ask(WatchRequest.Coach(now(), heard.text))) {
      is Asked.Answered -> when (val reply = asked.reply) {
        is WatchReply.Coach -> Talk.Answered(reply.text, reply.long)
        is WatchReply.Failed -> Talk.NotSent(reply.words)
        else -> Talk.NotSent(null)
      }
      Asked.Unreachable -> Talk.NotSent(null)
      Asked.NotUnderstood -> Talk.NotSent(null)
    }
  }

  /** Done with Coach: the words and the answer are forgotten. */
  fun closeTalk() {
    _talk.value = null
  }

  fun dismissOutcome() {
    _outcome.value = null
  }

  private val _shown = MutableStateFlow<Shown>(Shown.Asking)
  val shown: StateFlow<Shown> = _shown.asStateFlow()

  /** Reads Needs you, then Your tafs, from the phone. Your tafs not answering leaves Needs you shown, without the tafs. */
  suspend fun refresh() {
    if (_shown.value !is Shown.Read) _shown.value = Shown.Asking
    val needsYou = when (val asked = phone.ask(WatchRequest.NeedsYou(now()))) {
      is Asked.Answered -> when (val reply = asked.reply) {
        is WatchReply.NeedsYou -> NeedsYouRead(reply.count, reply.words, reply.calls).also { tile?.saw(reply.count, now()) }
        is WatchReply.SignedOut -> {
          tile?.signedOut()
          return show(Shown.SignedOut)
        }
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
    _outcome.value = null
    _confirmingHaltAll.value = false
    _talk.value = null
  }

  private fun show(shown: Shown) {
    _shown.value = shown
  }
}
