package bot.tafs.wear

import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.NotAccepted
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
  /** The watch's own id for a new ask: random, so tafs cloud can tell a retry from a new ask. */
  private val newClientId: () -> String = ::randomClientId,
  /** Which ended asks were opened, for the process's life only: never written to disk. */
  private val seen: MutableSet<String> = SeenAsks.ids,
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

  data class NeedsYouRead(val count: Int, val words: String, val calls: List<HeldLine>) {
    /** Nothing waits for the person: no count and no held call. The screen shows the all-clear above the words. */
    val allClear: Boolean get() = count == 0 && calls.isEmpty()
  }

  data class TafsRead(val tafs: List<TafLine>, val allHalted: Boolean)

  /** What came of a Refuse or a Halt, shown over the screen until the next one. */
  sealed interface Outcome {
    /** It went through, in the phone's words ("Refused", "All your tafs halted"). */
    data class Done(val words: String) : Outcome

    /** A Halt sent, until the phone answers with tafs cloud's word: [taf] is the taf's name, null for every taf. */
    data class Halting(val taf: String?) : Outcome

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

  /**
   * Halts one taf through the phone. Nothing is sent from a watch without its lock, or locked. It shows "Halting…" until the
   * phone answers, and never "halted" before tafs cloud says so.
   */
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
    if (request is WatchRequest.Halt) {
      val name = request.tafId?.let { id -> ((_shown.value as? Shown.Read)?.tafs?.tafs?.firstOrNull { it.tafId == id })?.name }
      _outcome.value = Outcome.Halting(if (request.tafId == null) null else name ?: "")
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

  fun dismissOutcome() {
    _outcome.value = null
  }


  // MARK: Ask tafs (docs/watch-wear.md)

  /** The microphone's screen, from the tap to tafs cloud's receipt. */
  sealed interface Voice {
    /** Listening: what's been heard so far, shown as it comes. [replyTo] is the ask a Reply continues. */
    data class Listening(val heard: String, val replyTo: String?) : Voice

    /** Sending the words, as text, through the phone. */
    data class Sending(val text: String, val clientId: String, val replyTo: String?) : Voice

    /** tafs cloud took it: its receipt, then the taf it went to and its state as they change. */
    data class Sent(val ask: AskLine) : Voice

    /** No receipt: it may have arrived. Retry sends the same words with the same id, so nothing is started twice. */
    data class Unsure(val text: String, val clientId: String, val replyTo: String?) : Voice

    /** Not sent, in the phone's words (no AI consent, asking not on yet, the phone signed out…); null: Open on phone. */
    data class NotSent(val words: String?) : Voice

    /** Nothing heard, or the speech input failed: tap to try again. */
    data object HeardNothing : Voice

    /** Not sent: this watch has no screen lock, or is locked. */
    data object NeedsLock : Voice
  }

  private val _voice = MutableStateFlow<Voice?>(null)
  val voice: StateFlow<Voice?> = _voice.asStateFlow()

  private val _asks = MutableStateFlow<Map<String, AskLine>>(emptyMap())

  /** The watch's asks as tafs cloud last said, by id: in memory only. */
  val asks: StateFlow<Map<String, AskLine>> = _asks.asStateFlow()

  private val _result = MutableStateFlow<AskLine?>(null)

  /** The result card open now: an ended ask, its taf and its line, with Hear and Reply. */
  val result: StateFlow<AskLine?> = _result.asStateFlow()

  /** How long each step took, by the ask's client id, for the internal builds' readout; nothing is sent anywhere. */
  data class Timing(val listenMs: Long?, val receiptMs: Long?, val resultMs: Long?)

  private val tapAt = mutableMapOf<String?, Long>()
  private var listenMs: Long? = null
  private var heardAt: Long? = null
  private val timings = mutableMapOf<String, Timing>()
  private val receivedAt = mutableMapOf<String, Long>()

  fun timing(clientId: String): Timing? = timings[clientId]

  /**
   * The microphone tapped (or Reply, with [replyTo]): from a watch with a screen lock, unlocked. An ask is an instruction to a
   * taf, so, as Refuse and Halt, never from a watch anyone could pick up. Returns whether to start listening.
   */
  fun listen(replyTo: String? = null): Boolean {
    if (!unlocked()) {
      _voice.value = Voice.NeedsLock
      return false
    }
    _result.value = null
    tapAt.clear()
    tapAt[replyTo] = now()
    listenMs = null
    heardAt = null
    _voice.value = Voice.Listening("", replyTo)
    return true
  }

  /** The speech input is ready for words: the listen time ends here. */
  fun readyToHear() {
    val listening = _voice.value as? Voice.Listening ?: return
    listenMs = tapAt[listening.replyTo]?.let { now() - it }
  }

  /** What's been heard so far, shown as it comes. */
  fun partial(text: String) {
    val listening = _voice.value as? Voice.Listening ?: return
    _voice.value = listening.copy(heard = text)
  }

  /** Listening can't start (the microphone not allowed): why, in the watch's words. */
  fun notListening(words: String) {
    if (_voice.value is Voice.Listening) _voice.value = Voice.NotSent(words)
  }

  /** The speech input ended with nothing, or failed. */
  fun heardNothing() {
    if (_voice.value is Voice.Listening) _voice.value = Voice.HeardNothing
  }

  /** The person spoke and stopped: what was heard goes at once, with nothing to confirm. */
  suspend fun heard(text: String) {
    val listening = _voice.value as? Voice.Listening ?: return
    val words = text.trim()
    if (words.isEmpty()) return heardNothing()
    heardAt = now()
    send(Voice.Sending(words.take(bot.tafs.watchlink.WatchLink.MAX_ASK_TEXT), newClientId(), listening.replyTo))
  }

  /** Retry, from "Not sure it arrived": the same words, the same id. */
  suspend fun retry() {
    val unsure = _voice.value as? Voice.Unsure ?: return
    if (!unlocked()) {
      _voice.value = Voice.NeedsLock
      return
    }
    send(Voice.Sending(unsure.text, unsure.clientId, unsure.replyTo))
  }

  private suspend fun send(sending: Voice.Sending) {
    _voice.value = sending
    val asked = phone.ask(WatchRequest.Ask(now(), sending.clientId, sending.text, sending.replyTo))
    // Only while this ask is still the one shown: a cancel meanwhile isn't undone by its answer.
    if (_voice.value != sending) return
    _voice.value = when (asked) {
      is Asked.Answered -> when (val reply = asked.reply) {
        is WatchReply.AskTaken -> {
          val at = now()
          receivedAt[sending.clientId] = at
          timings[sending.clientId] = Timing(listenMs, heardAt?.let { at - it }, null)
          took(reply.ask)
          Voice.Sent(reply.ask)
        }
        WatchReply.Unsure -> Voice.Unsure(sending.text, sending.clientId, sending.replyTo)
        is WatchReply.Failed -> Voice.NotSent(reply.words)
        WatchReply.SignedOut -> Voice.NotSent(Words.Watch.signInOnPhone)
        // A phone with an older tafs doesn't know the ask.
        is WatchReply.Refused -> Voice.NotSent(if (reply.reason == NotAccepted.UNKNOWN_PATH) Words.Watch.updatePhone else null)
        else -> Voice.NotSent(null)
      }
      // No answer in the watch's time: the phone may have sent it.
      Asked.Unreachable -> Voice.Unsure(sending.text, sending.clientId, sending.replyTo)
      Asked.NotUnderstood -> Voice.NotSent(null)
    }
  }

  /** Done with the microphone's screen: back to the microphone. Nothing heard is kept. */
  fun closeVoice() {
    _voice.value = null
  }

  /**
   * An ask's new state, from the phone's message or a read: kept in memory, shown on the screen it belongs to. An ended ask opens
   * its result card when it's the one just sent.
   */
  fun took(ask: AskLine) {
    val before = _asks.value[ask.id]
    // An older state never replaces a newer one (two messages crossing).
    if (before != null && before.updated > ask.updated) return
    _asks.value = _asks.value + (ask.id to ask)
    val at = now()
    if (ask.ended) receivedAt[ask.clientId]?.let { t -> timings[ask.clientId] = (timings[ask.clientId] ?: Timing(null, null, null)).copy(resultMs = at - t) }
    when (val v = _voice.value) {
      is Voice.Sent -> if (v.ask.id == ask.id) {
        if (ask.ended) {
          _voice.value = null
          open(ask)
        } else _voice.value = Voice.Sent(ask)
      }
      else -> Unit
    }
    if (_result.value?.id == ask.id) _result.value = ask
  }

  /** Opens an ask's result card (from the home screen or its notification). */
  fun open(ask: AskLine) {
    seen += ask.id
    _result.value = ask
  }

  fun closeResult() {
    _result.value = null
  }

  /** The asks at work, newest first, for the home screen. */
  fun working(): List<AskLine> = _asks.value.values.filter { !it.ended }.sortedByDescending { it.updated }

  /** Ended asks not opened yet, newest first. */
  fun ready(): List<AskLine> = _asks.value.values.filter { it.ended && it.id !in seen }.sortedByDescending { it.updated }

  /** Reads the watch's asks from the phone: those of the last day, as tafs cloud has them. A failed read leaves what's shown. */
  suspend fun readAsks() {
    val reply = (phone.ask(WatchRequest.Asks(now())) as? Asked.Answered)?.reply as? WatchReply.AskList ?: return
    for (ask in reply.asks) took(ask)
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
    _voice.value = null
    _asks.value = emptyMap()
    _result.value = null
  }

  private fun show(shown: Shown) {
    _shown.value = shown
  }
}

/** A new ask's id: `w_` and 24 random hex digits. */
fun randomClientId(): String {
  val bytes = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
  return "w_" + bytes.joinToString("") { "%02x".format(it) }
}

/** Which ended asks were opened, for the watch app's process only: "ready" on the home screen means not opened yet. */
object SeenAsks {
  val ids: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
}
