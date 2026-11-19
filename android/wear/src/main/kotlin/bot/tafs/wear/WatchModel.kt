package bot.tafs.wear

import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.DecisionLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.NotAccepted
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Your brief on the watch (docs/watch-wear.md, Your brief): off. With it off the watch asks and shows exactly what it did before;
 * on, it asks the phone for the brief too and shows it in Needs you's place. It turns on with the owner's yes, as this one line.
 */
internal const val BRIEF_ON_WATCH = false

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
  /** Whether the watch asks for Your brief and shows it ([BRIEF_ON_WATCH]); a test passes it rather than flipping the switch. */
  private val briefOn: Boolean = BRIEF_ON_WATCH,
  /**
   * Wear v2's microphone (docs/watch-wear.md, Version 2): Start, then Stop or the 30 s limit, then the words to check, sent only on
   * Send. Off (v1), what's heard goes the moment the person stops. A test passes it rather than flipping [WATCH_V2].
   */
  private val reviewFirst: Boolean = WATCH_V2,
  /** v2's Keep for later: one message, text only, kept on the watch until it's sent or deleted; none in a test that doesn't use it. */
  private val drafts: DraftStore? = null,
) {
  sealed interface Shown {
    /** Asking the phone, with nothing to show yet. */
    data object Asking : Shown

    /**
     * The phone's Needs you and Your tafs, in its words; and its brief, shown in Needs you's place, when the switch is on and the
     * account has one.
     */
    data class Read(val needsYou: NeedsYouRead, val tafs: TafsRead?, val brief: WatchReply.Brief? = null) : Shown {
      /**
       * The hold [decision] may be refused by, from the wrist: only a held call Needs you's own read lists as refusable, matched by
       * its hold id, so the brief offers Refuse exactly where Needs you does. Null for any other: it's Open on phone.
       */
      fun refusable(decision: DecisionLine): String? =
        decision.holdId?.takeIf { id -> needsYou.calls.any { it.holdId == id && it.refusable } }
    }

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

    /** v2: several tafs halted one after the other, each taf's name with what came of its Halt. */
    data class Several(val each: List<Pair<String, Outcome>>) : Outcome {
      /** Every one went through. */
      val done: Boolean get() = each.all { it.second is Done }
    }
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
    _outcome.value = outcomeOf(phone.ask(request))
    // The lists as they stand now, and the taf's page open.
    refresh()
    rereadTaf()
  }

  /** What came of a Refuse or a Halt: shown only once the phone has KIFF's or tafs cloud's answer, which is when its reply comes. */
  private fun outcomeOf(asked: Asked): Outcome = when (asked) {
    is Asked.Answered -> when (val reply = asked.reply) {
      is WatchReply.Done -> Outcome.Done(reply.words)
      WatchReply.AlreadyAnswered -> Outcome.AlreadyAnswered
      is WatchReply.Failed -> Outcome.NotDone(reply.words)
      else -> Outcome.NotDone(null)
    }
    Asked.Unreachable -> Outcome.PhoneUnreachable
    Asked.NotUnderstood -> Outcome.NotDone(null)
  }

  fun dismissOutcome() {
    _outcome.value = null
  }


  // MARK: A taf's page, and choosing which to halt (v2, step v; docs/watch-wear.md)

  /** A taf's page as the phone answered it (`taf`), or why there's none, as v1 says it. */
  sealed interface TafRead {
    data object Asking : TafRead

    data class Read(val page: WatchReply.TafPage) : TafRead

    data object SignedOut : TafRead

    data object PhoneUnreachable : TafRead

    /** A phone with an older tafs, which doesn't know the page: update it. */
    data object UpdatePhone : TafRead

    /** The phone couldn't give it, in its words; null: Open on phone. */
    data class Failed(val words: String?) : TafRead
  }

  /**
   * The taf whose page is open: its id, and its line in Your tafs when it was opened, for its name and whether it's halted until
   * the phone's page comes and says them itself.
   */
  data class OpenTaf(val tafId: String, val listed: TafLine?, val read: TafRead) {
    val name: String get() = (read as? TafRead.Read)?.page?.name ?: listed?.name.orEmpty()
    val halted: Boolean get() = (read as? TafRead.Read)?.page?.halted ?: listed?.halted ?: false
  }

  private val _taf = MutableStateFlow<OpenTaf?>(null)

  /** The taf's page open now, from the Tafs page; in memory only, as the rest. */
  val taf: StateFlow<OpenTaf?> = _taf.asStateFlow()

  /** A taf tapped on the Tafs page: its page, read from the phone. */
  suspend fun openTaf(tafId: String) {
    val listed = (_shown.value as? Shown.Read)?.tafs?.tafs?.firstOrNull { it.tafId == tafId }
    _taf.value = OpenTaf(tafId, listed, TafRead.Asking)
    readTaf(tafId)
  }

  fun closeTaf() {
    _taf.value = null
  }

  private suspend fun rereadTaf() {
    _taf.value?.let { readTaf(it.tafId) }
  }

  private suspend fun readTaf(tafId: String) {
    val read = when (val asked = phone.ask(WatchRequest.Taf(now(), tafId))) {
      is Asked.Answered -> when (val reply = asked.reply) {
        // Another taf's page is never drawn as this one's.
        is WatchReply.TafPage -> if (reply.tafId == tafId) TafRead.Read(reply) else TafRead.Failed(null)
        WatchReply.SignedOut -> TafRead.SignedOut
        is WatchReply.Failed -> TafRead.Failed(reply.words)
        is WatchReply.Refused -> if (reply.reason == NotAccepted.UNKNOWN_PATH) TafRead.UpdatePhone else TafRead.Failed(null)
        else -> TafRead.Failed(null)
      }
      Asked.Unreachable -> TafRead.PhoneUnreachable
      Asked.NotUnderstood -> TafRead.Failed(null)
    }
    // Only while this taf is still the one open: closed, or another opened meanwhile, its answer is dropped.
    val open = _taf.value?.takeIf { it.tafId == tafId } ?: return
    _taf.value = open.copy(read = read)
  }

  /**
   * Choose which to halt, from the menu's Halt: the tafs offered (every one not halted already), the ones picked, and whether it's
   * being asked once more.
   */
  data class HaltPick(val offered: List<TafLine>, val picked: Set<String> = emptySet(), val confirming: Boolean = false) {
    /** Every taf offered is picked: it's Halt all, one request for every taf. */
    val all: Boolean get() = offered.isNotEmpty() && offered.all { it.tafId in picked }

    /** The tafs picked, in Your tafs' order. */
    val chosen: List<TafLine> get() = offered.filter { it.tafId in picked }
  }

  private val _haltPick = MutableStateFlow<HaltPick?>(null)

  /** Choose which to halt, while it's open. Nothing is sent before it's confirmed. */
  val haltPick: StateFlow<HaltPick?> = _haltPick.asStateFlow()

  /** The menu's Halt: every taf not halted, none picked yet. With none running, or every taf halted, nothing opens. */
  fun pickToHalt() {
    val read = (_shown.value as? Shown.Read)?.tafs ?: return
    val offered = read.tafs.filter { !it.halted }
    if (read.allHalted || offered.isEmpty()) return
    _haltPick.value = HaltPick(offered)
  }

  /** One taf's switch: on, or off again. */
  fun togglePick(tafId: String) {
    val pick = _haltPick.value?.takeIf { !it.confirming } ?: return
    if (pick.offered.none { it.tafId == tafId }) return
    _haltPick.value = pick.copy(picked = if (tafId in pick.picked) pick.picked - tafId else pick.picked + tafId)
  }

  /** All your tafs: every taf offered on, or, with every one on already, all off. */
  fun togglePickAll() {
    val pick = _haltPick.value?.takeIf { !it.confirming } ?: return
    _haltPick.value = pick.copy(picked = if (pick.all) emptySet() else pick.offered.map { it.tafId }.toSet())
  }

  /** Halt now · N: asked once more, on the watch. With none picked, nothing. */
  fun askPicked() {
    val pick = _haltPick.value ?: return
    if (pick.chosen.isNotEmpty()) _haltPick.value = pick.copy(confirming = true)
  }

  /** Cancel: from the question back to the choice, and from the choice back to the menu. Nothing is sent. */
  fun cancelPick() {
    val pick = _haltPick.value ?: return
    _haltPick.value = if (pick.confirming) pick.copy(confirming = false) else null
  }

  /**
   * Halt now, confirmed: every taf, with one Halt all, when every taf offered is picked; else a Halt for each taf picked, one after
   * the other, through the phone, with Halting shown until the last answers. Nothing is sent unless it was asked once more, nor
   * from a watch without its lock, or locked.
   */
  suspend fun haltPicked() {
    val pick = _haltPick.value?.takeIf { it.confirming } ?: return
    val chosen = pick.chosen
    _haltPick.value = null
    when {
      chosen.isEmpty() -> return
      pick.all -> return act(WatchRequest.Halt(now(), null))
      chosen.size == 1 -> return act(WatchRequest.Halt(now(), chosen.single().tafId))
    }
    if (!unlocked()) {
      _outcome.value = Outcome.NeedsLock
      return
    }
    _outcome.value = Outcome.Halting(chosen.joinToString(", ") { it.name })
    // Each to the end, one after the other: one that doesn't go through never keeps the next from halting.
    _outcome.value = Outcome.Several(chosen.map { taf -> taf.name to outcomeOf(phone.ask(WatchRequest.Halt(now(), taf.tafId))) })
    refresh()
    rereadTaf()
  }

  // MARK: Ask tafs (docs/watch-wear.md)

  /** The microphone's screen, from the tap to tafs cloud's receipt. */
  sealed interface Voice {
    /** v2: the microphone's screen, before Start. [replyTo] is the ask a Reply continues. */
    data class Ready(val replyTo: String?) : Voice

    /**
     * Listening: what's been heard so far, shown as it comes. [replyTo] is the ask a Reply continues; [since], when listening
     * began, for v2's timer and its 30 s limit.
     */
    data class Listening(val heard: String, val replyTo: String?, val since: Long? = null) : Voice

    /** v2: the words heard, typed or picked, to check before Send. Nothing has left the watch. */
    data class Review(val text: String, val replyTo: String?, val audio: VoiceAudio? = null) : Voice

    /** v2: deleted from the review; nothing was sent. */
    data object Deleted : Voice

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

  /**
   * Every change of the microphone's screen: a review's recording is erased the moment the screen leaves it, whatever it goes
   * to (Send, Delete, Re-record, Cancel, a lock, put away, signed out). It never outlives the words it was kept beside.
   */
  private var voiceNow: Voice?
    get() = _voice.value
    set(value) {
      val kept = (_voice.value as? Voice.Review)?.audio
      _voice.value = value
      if (kept != null && (value as? Voice.Review)?.audio !== kept) kept.delete()
    }

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
      voiceNow = Voice.NeedsLock
      return false
    }
    _result.value = null
    tapAt.clear()
    tapAt[replyTo] = now()
    listenMs = null
    heardAt = null
    voiceNow = Voice.Listening("", replyTo, since = now())
    return true
  }

  /**
   * v2: the microphone tapped (or Reply, with [replyTo]): its screen, ready for Start. From an unlocked watch with a screen lock
   * only, as [listen].
   */
  fun readyToRecord(replyTo: String? = null) {
    if (!unlocked()) {
      voiceNow = Voice.NeedsLock
      return
    }
    _result.value = null
    voiceNow = Voice.Ready(replyTo)
  }

  /** v2: words typed or a quick reply picked, to check before Send, as words heard are. Blank is nothing. */
  fun review(text: String, replyTo: String? = null) {
    val words = text.trim()
    if (words.isEmpty()) return
    if (!unlocked()) {
      voiceNow = Voice.NeedsLock
      return
    }
    _result.value = null
    voiceNow = Voice.Review(words.take(bot.tafs.watchlink.WatchLink.MAX_ASK_TEXT), replyTo)
  }

  /** v2: Send, from the review. The watch is checked again: it may have locked while the words were read. */
  suspend fun sendReviewed() {
    val review = _voice.value as? Voice.Review ?: return
    if (!unlocked()) {
      voiceNow = Voice.NeedsLock
      return
    }
    send(Voice.Sending(review.text, newClientId(), review.replyTo))
  }

  /** v2: Re-record, from the review: the words are dropped and the microphone is ready again, for the same chat. */
  fun rerecord() {
    val review = _voice.value as? Voice.Review ?: return
    readyToRecord(review.replyTo)
  }

  /** v2: Delete, from the review: the words are dropped, and nothing was sent. */
  fun deleteReviewed() {
    if (_voice.value is Voice.Review) voiceNow = Voice.Deleted
  }

  /** The speech input is ready for words: the listen time ends here. */
  fun readyToHear() {
    val listening = _voice.value as? Voice.Listening ?: return
    listenMs = tapAt[listening.replyTo]?.let { now() - it }
  }

  /** What's been heard so far, shown as it comes. */
  fun partial(text: String) {
    val listening = _voice.value as? Voice.Listening ?: return
    voiceNow = listening.copy(heard = text)
  }

  /** Listening can't start (the microphone not allowed): why, in the watch's words. */
  fun notListening(words: String) {
    if (_voice.value is Voice.Listening) voiceNow = Voice.NotSent(words)
  }

  /** The speech input ended with nothing, or failed. */
  fun heardNothing() {
    if (_voice.value is Voice.Listening) voiceNow = Voice.HeardNothing
  }

  /** The person spoke and stopped: in v1 what was heard goes at once, with nothing to confirm; in v2, to the review first. */
  suspend fun heard(text: String, audio: VoiceAudio? = null) {
    val listening = _voice.value as? Voice.Listening
    val words = text.trim()
    if (listening == null || words.isEmpty() || !reviewFirst) {
      // Nothing to keep it beside, or v1, which keeps no recording.
      audio?.delete()
    }
    if (listening == null) return
    if (words.isEmpty()) return heardNothing()
    heardAt = now()
    if (reviewFirst) {
      voiceNow = Voice.Review(words.take(bot.tafs.watchlink.WatchLink.MAX_ASK_TEXT), listening.replyTo, audio)
      return
    }
    send(Voice.Sending(words.take(bot.tafs.watchlink.WatchLink.MAX_ASK_TEXT), newClientId(), listening.replyTo))
  }

  /** Retry, from "Not sure it arrived": the same words, the same id. */
  suspend fun retry() {
    val unsure = _voice.value as? Voice.Unsure ?: return
    if (!unlocked()) {
      voiceNow = Voice.NeedsLock
      return
    }
    send(Voice.Sending(unsure.text, unsure.clientId, unsure.replyTo))
  }

  private suspend fun send(sending: Voice.Sending) {
    voiceNow = sending
    val asked = phone.ask(WatchRequest.Ask(now(), sending.clientId, sending.text, sending.replyTo))
    // Only while this ask is still the one shown: a cancel meanwhile isn't undone by its answer.
    if (_voice.value != sending) return
    voiceNow = when (asked) {
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
    voiceNow = null
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
          voiceNow = null
          open(ask)
        } else voiceNow = Voice.Sent(ask)
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

  /** Reads Needs you, then Your brief (with the switch on), then Your tafs, from the phone. Your tafs not answering leaves Needs you shown, without the tafs. */
  suspend fun refresh() {
    if (_shown.value !is Shown.Read) _shown.value = Shown.Asking
    val needsYou = when (val asked = phone.ask(WatchRequest.NeedsYou(now()))) {
      is Asked.Answered -> when (val reply = asked.reply) {
        is WatchReply.NeedsYou -> NeedsYouRead(reply.count, reply.words, reply.calls).also { tile?.saw(reply.count, now()) }
        is WatchReply.SignedOut -> {
          tile?.signedOut()
          // Signed out: nothing of the account stays on the watch, a kept message and a recording included.
          deleteKept()
          if (_voice.value is Voice.Review) voiceNow = null
          return show(Shown.SignedOut)
        }
        else -> return show(Shown.OpenOnPhone)
      }
      Asked.Unreachable -> return show(Shown.PhoneUnreachable)
      Asked.NotUnderstood -> return show(Shown.OpenOnPhone)
    }
    // Your brief, only with the switch on: none, or a phone that can't say (an older one), and Needs you stays, as today.
    val brief = if (briefOn) (phone.ask(WatchRequest.Brief(now())) as? Asked.Answered)?.reply as? WatchReply.Brief else null
    val tafs = (phone.ask(WatchRequest.Tafs(now())) as? Asked.Answered)?.let { it.reply as? WatchReply.Tafs }?.let { TafsRead(it.tafs, it.allHalted) }
    show(Shown.Read(needsYou, tafs, brief))
    // The phone answered: a message kept for later goes now.
    sendKept()
  }

  // MARK: Keep for later (v2, docs/watch-wear.md)

  /** A message kept on the watch to send when the phone is back: its words, its ask id and the chat it continues. Text only. */
  data class Draft(val text: String, val clientId: String, val replyTo: String?)

  private val _kept = MutableStateFlow(drafts?.read())

  /** The message kept for later, or none. Kept on disk ([DraftStore]) until it's sent, deleted, or the phone signs out. */
  val kept: StateFlow<Draft?> = _kept.asStateFlow()

  /**
   * Keep for later, from "Not sure it arrived": the words are kept with their id, and sent at the next read that reaches the phone.
   * The same id, so tafs cloud starts it at most once if the first send did arrive. One at a time: a newer one replaces it.
   */
  fun keepForLater() {
    val unsure = _voice.value as? Voice.Unsure ?: return
    val draft = Draft(unsure.text, unsure.clientId, unsure.replyTo)
    drafts?.write(draft)
    _kept.value = draft
    voiceNow = null
  }

  /** Delete what was kept: nothing is sent. */
  fun deleteKept() {
    drafts?.clear()
    _kept.value = null
  }

  /**
   * Sends what was kept, from an unlocked watch only, as any ask. Taken, it's gone from the watch and shows as an ask at work;
   * anything else, it stays for the next read, never sent twice under two ids.
   */
  suspend fun sendKept() {
    val draft = _kept.value ?: return
    if (!unlocked()) return
    val asked = phone.ask(WatchRequest.Ask(now(), draft.clientId, draft.text, draft.replyTo))
    if (_kept.value != draft) return
    val taken = ((asked as? Asked.Answered)?.reply as? WatchReply.AskTaken)?.ask ?: return
    deleteKept()
    took(taken)
  }

  /** What the watch keeps once it's put away or the phone signs out: nothing. */
  fun forget() {
    _shown.value = Shown.Asking
    _outcome.value = null
    _confirmingHaltAll.value = false
    voiceNow = null
    _asks.value = emptyMap()
    _result.value = null
    _taf.value = null
    _haltPick.value = null
  }

  private fun show(shown: Shown) {
    _shown.value = shown
  }
}

/** Where v2 keeps a message for later: the watch's own preferences, or a map in a test. Text only, never audio. */
interface DraftStore {
  fun read(): WatchModel.Draft?
  fun write(draft: WatchModel.Draft)
  fun clear()
}

/** [DraftStore] in the watch's private preferences: the words, their ask id and the chat they continue, nothing else. */
class PreferencesDraftStore(context: android.content.Context) : DraftStore {
  private val prefs = context.getSharedPreferences("tafs.draft", android.content.Context.MODE_PRIVATE)

  override fun read(): WatchModel.Draft? {
    val text = prefs.getString(TEXT, null) ?: return null
    val id = prefs.getString(ID, null) ?: return null
    return WatchModel.Draft(text, id, prefs.getString(REPLY_TO, null))
  }

  override fun write(draft: WatchModel.Draft) {
    prefs.edit().clear().putString(TEXT, draft.text).putString(ID, draft.clientId).apply {
      draft.replyTo?.let { putString(REPLY_TO, it) }
    }.apply()
  }

  override fun clear() {
    prefs.edit().clear().apply()
  }

  private companion object {
    const val TEXT = "text"
    const val ID = "client_id"
    const val REPLY_TO = "reply_to"
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
