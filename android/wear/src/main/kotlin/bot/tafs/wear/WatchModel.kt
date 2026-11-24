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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import bot.tafs.watchlink.InboxMarkIds
import bot.tafs.watchlink.Amounts

/**
 * Your brief on the watch (docs/watch-wear.md, Your brief): off. With it off the watch asks and shows exactly what it did before;
 * on, it asks the phone for the brief too and shows it in Needs you's place. It turns on with the owner's yes, as this one line.
 */
internal const val BRIEF_ON_WATCH = false

/** How long the phone may fail to answer while the last read stays on screen, before it's called unreachable (row 44, D). */
internal const val UNREACHABLE_AFTER_MS = 30_000L

/** How the watch reaches the phone: one live request and its answer, or nothing when the phone can't be reached. */
fun interface PhoneLink {
  /** The phone's reply; [Asked.Unreachable] when no phone with tafs answered in time. */
  suspend fun ask(request: WatchRequest): Asked

  /**
   * A recording the watch's recognizer gave up on, to the phone over its own channel (row 44, A): the only audio that leaves the
   * watch, and only to the paired phone, which turns it into text there ([WatchRequest.Transcribe]). Whether it all went.
   */
  suspend fun sendVoice(id: String, file: java.io.File): Boolean = false
}

/** The longest a recording for the phone is kept on the watch, whatever happens: 2 minutes (row 44, A). */
internal const val VOICE_KEEP_MS = 2 * 60 * 1000L

/**
 * Why the phone gave no answer, so the wrist says which, never "not in reach" for all of them (row 44, D; the Apple Watch's
 * `PhoneFailure`, tafs#1632, in the same lines).
 */
enum class LinkFailure {
  /** No phone with tafs is in reach: none found after the second look, or the link says it isn't connected. */
  NotReachable,

  /** The phone was asked and didn't answer within the limit. */
  NoAnswer,

  /** The paired phone is there, but tafs isn't on it (or isn't one that answers the watch). */
  NoApp,

  /** The link itself failed, or the reply couldn't be read. */
  Link;

  /** What the watch says, with Try again under it. */
  val line: String get() = when (this) {
    NotReachable -> Words.Watch.phoneNotReachable
    NoAnswer -> Words.Watch.phoneNoAnswer
    NoApp -> Words.Watch.phoneNoApp
    Link -> Words.Watch.phoneLinkFailed
  }
}

sealed interface Asked {
  data class Answered(val reply: WatchReply) : Asked

  /** No phone with tafs answered, and [why]. */
  data class Unreachable(val why: LinkFailure = LinkFailure.NotReachable) : Asked

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
  /**
   * Whether the watch has a screen lock at all (locked or not): a message kept for later stays only on one that does, as the
   * Inbox does (row 8, as the Apple Watch's). True in a test that doesn't look at it.
   */
  private val secure: () -> Boolean = { true },
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
  /** v2's Inbox, kept on the watch until dismissed ([InboxStore]); in memory only in a test that doesn't pass one. */
  private val inboxStore: InboxStore? = null,
  /** v2's Sign out on this watch alone (AccountScreens.kt): whether it's signed out here; none in v1, which can't sign out. */
  private val signIn: WatchSignIn? = null,
  /** What the watch keeps so it opens at once (row 44, D, [WatchCache]); none in a test that doesn't use it. */
  private val cache: WatchCache? = null,
) {
  sealed interface Shown {
    /** Asking the phone, with nothing to show yet. */
    data object Asking : Shown

    /**
     * The phone's Needs you and Your tafs, in its words; and its brief, shown in Needs you's place, when the switch is on and the
     * account has one.
     */
    data class Read(
      val needsYou: NeedsYouRead,
      val tafs: TafsRead?,
      val brief: WatchReply.Brief? = null,
      /** What's shown is the last read (kept, or from before a short drop), and a fresh one is on its way (row 44, D). */
      val updating: Boolean = false,
    ) : Shown {
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
    data class PhoneUnreachable(val why: LinkFailure = LinkFailure.NotReachable) : Shown

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

    data class PhoneUnreachable(val why: LinkFailure = LinkFailure.NotReachable) : Outcome

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
    if (_signedOutHere.value) return
    if (!unlocked()) {
      _outcome.value = Outcome.NeedsLock
      return
    }
    if (request is WatchRequest.Halt) {
      val name = request.tafId?.let { id -> ((_shown.value as? Shown.Read)?.tafs?.tafs?.firstOrNull { it.tafId == id })?.name }
      _outcome.value = Outcome.Halting(if (request.tafId == null) null else name ?: "")
    }
    val asked = phone.ask(request)
    // Signed out on this watch meanwhile: its answer is dropped, never shown after Sign in.
    if (_signedOutHere.value) return
    _outcome.value = outcomeOf(asked)
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
    is Asked.Unreachable -> Outcome.PhoneUnreachable(asked.why)
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

    data class PhoneUnreachable(val why: LinkFailure = LinkFailure.NotReachable) : TafRead

    /** A phone with an older tafs, which doesn't know the page: update it. */
    data object UpdatePhone : TafRead

    /** The phone couldn't give it, in its words; null: Open on phone. */
    data class Failed(val words: String?) : TafRead
  }

  /**
   * The taf whose page is open: its id, and its line in Your tafs when it was opened, for its name and whether it's halted until
   * the phone's page comes and says them itself.
   */
  data class OpenTaf(val tafId: String, val listed: TafLine?, val read: TafRead, val chat: TafChat = TafChat()) {
    val name: String get() = (read as? TafRead.Read)?.page?.name ?: listed?.name.orEmpty()
    val halted: Boolean get() = (read as? TafRead.Read)?.page?.halted ?: listed?.halted ?: false
    /** Its schedule is paused: a message still goes, and it answers it now (the owner's 2A). */
    val paused: Boolean get() = !halted && listed?.paused == true
  }

  /**
   * A taf's chat while it's open (docs/watch-wear.md, chatting with a taf): its last turns as the phone shows them, this visit's
   * turns with the taf's answers, and the answers read in full. In memory only: gone when the chat closes, never in the Inbox.
   */
  data class TafChat(val history: History = History.Reading, val turns: List<TafTurn> = emptyList(), val whole: Map<String, Whole> = emptyMap()) {
    sealed interface History {
      data object Reading : History
      data class Read(val lines: List<bot.tafs.watchlink.ChatLine>) : History
      /** The phone couldn't give them: the chat starts from this visit. */
      data object NotRead : History
    }
  }

  /** One message sent from this chat: the words, when, and the taf's request as tafs cloud says it. */
  data class TafTurn(val said: String, val at: Long, val ask: AskLine)

  /** What's under a taf's answer: Read more, the read under way, the rest on the phone, or nothing once it's whole. */
  enum class Foot { ReadMore, Reading, OnPhone }

  /** Read more on an answer: being read, read whole, or the rest is on the phone. */
  sealed interface Whole {
    data object Reading : Whole
    data class Read(val text: String) : Whole
    data object OnPhone : Whole
  }

  private val _taf = MutableStateFlow<OpenTaf?>(null)

  /** The taf's page open now, from the Tafs page; in memory only, as the rest. */
  val taf: StateFlow<OpenTaf?> = _taf.asStateFlow()

  /** A taf tapped on the Tafs page: its page, read from the phone. */
  suspend fun openTaf(tafId: String) {
    if (_signedOutHere.value) return
    val listed = (_shown.value as? Shown.Read)?.tafs?.tafs?.firstOrNull { it.tafId == tafId }
    _taf.value = OpenTaf(tafId, listed, TafRead.Asking)
    if (tafChatOn) readTafChat(tafId)
    readTaf(tafId)
  }

  /** The last turns of a taf's chat, read from the phone; nothing of them is kept once the chat closes. */
  private suspend fun readTafChat(tafId: String) {
    val history = when (val asked = phone.ask(WatchRequest.TafChat(now(), tafId))) {
      is Asked.Answered -> (asked.reply as? WatchReply.TafChat)?.also { if (it.zone != null) WatchChatTime.use(it.zone) }
        ?.let { TafChat.History.Read(it.lines) } ?: TafChat.History.NotRead
      else -> TafChat.History.NotRead
    }
    _taf.update { it?.takeIf { open -> open.tafId == tafId }?.let { open -> open.copy(chat = open.chat.copy(history = history)) } ?: it }
  }

  /** The asks sent from a taf's chat this session: never Coach's, never in the Inbox. */
  private val tafAsks = mutableSetOf<String>()

  /** Read more on a taf's answer: the whole of it, read from tafs cloud only now, shown in place and kept nowhere. */
  suspend fun readMore(askId: String) {
    val open = _taf.value ?: return
    if (open.chat.whole[askId] is Whole.Read) return
    setWhole(askId, Whole.Reading)
    val whole = when (val asked = phone.ask(WatchRequest.AskFull(now(), askId))) {
      is Asked.Answered -> (asked.reply as? WatchReply.AskFull)?.let { Whole.Read(it.text) } ?: Whole.OnPhone
      else -> Whole.OnPhone
    }
    setWhole(askId, whole)
  }

  private fun setWhole(askId: String, whole: Whole) {
    _taf.update { open -> open?.takeIf { o -> o.chat.turns.any { t -> t.ask.id == askId } }?.let { o -> o.copy(chat = o.chat.copy(whole = o.chat.whole + (askId to whole))) } ?: open }
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
      is Asked.Unreachable -> TafRead.PhoneUnreachable(asked.why)
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
    if (_signedOutHere.value) return
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
    val each = chosen.map { taf -> taf.name to outcomeOf(phone.ask(WatchRequest.Halt(now(), taf.tafId))) }
    if (_signedOutHere.value) return
    _outcome.value = Outcome.Several(each)
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
    data class Review(
      val text: String,
      val replyTo: String?,
      val audio: VoiceAudio? = null,
      /** Chatting with a taf: the phone's taf the words go to, and its name for the title; null for Coach. */
      val tafId: String? = null,
      val tafName: String? = null,
    ) : Voice

    /** v2: deleted from the review; nothing was sent. */
    data object Deleted : Voice

    /** Sending the words, as text, through the phone. */
    data class Sending(val text: String, val clientId: String, val replyTo: String?, val tafId: String? = null) : Voice

    /** tafs cloud took it: its receipt, then the taf it went to and its state as they change. */
    data class Sent(val ask: AskLine) : Voice

    /** No receipt: it may have arrived. Retry sends the same words with the same id, so nothing is started twice. */
    data class Unsure(val text: String, val clientId: String, val replyTo: String?, val tafId: String? = null) : Voice

    /** Not sent, in the phone's words (no AI consent, asking not on yet, the phone signed out…); null: Open on phone. */
    data class NotSent(val words: String?) : Voice

    /** Nothing heard, or the speech input failed: tap to try again. */
    data object HeardNothing : Voice

    /** The watch's recognizer gave up: the phone is turning the recording into text, on the phone itself (row 44, A). */
    data class Transcribing(val before: String, val replyTo: String?) : Voice

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

  /** What the person said from this watch, by its ask's id, with when (W4, Ask Coach as one conversation). Memory only. */
  data class Said(val text: String, val at: Long)

  private val _said = MutableStateFlow<Map<String, Said>>(emptyMap())
  val said: StateFlow<Map<String, Said>> = _said.asStateFlow()

  /**
   * Today's conversation with Coach from this watch, oldest first: each thing said and its ask as the phone relays it (W4).
   * In memory only, as the asks are: forgotten when the app is put away and on sign-out, so nothing new is kept on the watch
   * (docs/watch-wear.md, "What the watch keeps"). Until tafs cloud keeps the watch's conversation, it's what this watch said.
   */
  fun thread(): List<Pair<Said, AskLine>> =
    _said.value.mapNotNull { (id, said) -> _asks.value[id]?.let { said to it } }.sortedBy { it.first.at }

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
  /** Asks this watch sent whose answer hasn't come yet, in memory only: their answer opens by itself when it ends (W3). */
  private val awaiting = mutableSetOf<String>()

  fun timing(clientId: String): Timing? = timings[clientId]

  /**
   * The microphone tapped (or Reply, with [replyTo]): from a watch with a screen lock, unlocked. An ask is an instruction to a
   * taf, so, as Refuse and Halt, never from a watch anyone could pick up. Returns whether to start listening.
   */
  fun listen(replyTo: String? = null): Boolean {
    if (_signedOutHere.value) return false
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
  fun readyToRecord(replyTo: String? = null, tafId: String? = null) {
    if (_signedOutHere.value) return
    messaging = tafId
    if (!unlocked()) {
      voiceNow = Voice.NeedsLock
      return
    }
    _result.value = null
    voiceNow = Voice.Ready(replyTo)
  }

  /**
   * One tap to talk (row 44): the start's screen ([readyToRecord]: its taf, the watch's lock) and listening at once, so the
   * screen opens already listening. Returns whether to start the recognizer: not on a locked watch or one without a screen lock,
   * which get their line, as before.
   */
  fun talk(replyTo: String? = null, tafId: String? = null): Boolean {
    readyToRecord(replyTo, tafId)
    return _voice.value is Voice.Ready && listen(replyTo)
  }

  /**
   * The taf the words being recorded go to, set at every start ([readyToRecord], [review]): Coach's starts set it to null, a
   * taf's chat to its id, so words never go to a taf by a start that wasn't its own.
   */
  private var messaging: String? = null

  /** The switch for chatting with a taf, as [reviewFirst]: off, a taf's page opens as before and no chat is read. */
  var tafChatOn: Boolean = WATCH_TAF_CHAT

  private fun reviewOf(text: String, replyTo: String?, audio: VoiceAudio? = null): Voice.Review {
    val taf = messaging?.let { id -> _taf.value?.takeIf { it.tafId == id } }
    return Voice.Review(text.take(bot.tafs.watchlink.WatchLink.MAX_ASK_TEXT), replyTo, audio, tafId = taf?.tafId, tafName = taf?.name)
  }

  /** v2: words typed or a quick reply picked, to check before Send, as words heard are. Blank is nothing. */
  fun review(text: String, replyTo: String? = null, tafId: String? = null) {
    val words = text.trim()
    if (words.isEmpty() || _signedOutHere.value) return
    messaging = tafId
    if (!unlocked()) {
      voiceNow = Voice.NeedsLock
      return
    }
    _result.value = null
    voiceNow = reviewOf(words, replyTo)
  }

  /** v2: Send, from the review. The watch is checked again: it may have locked while the words were read. */
  suspend fun sendReviewed() {
    val review = _voice.value as? Voice.Review ?: return
    if (!unlocked()) {
      voiceNow = Voice.NeedsLock
      return
    }
    send(Voice.Sending(review.text, newClientId(), review.replyTo, review.tafId))
  }

  /** v2: Re-record, from the review: the words are dropped and the microphone is ready again, for the same chat. */
  fun rerecord() {
    val review = _voice.value as? Voice.Review ?: return
    readyToRecord(review.replyTo, review.tafId)
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
      voiceNow = reviewOf(words, listening.replyTo, audio)
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
    send(Voice.Sending(unsure.text, unsure.clientId, unsure.replyTo, unsure.tafId))
  }

  private suspend fun send(sending: Voice.Sending) {
    voiceNow = sending
    val asked = phone.ask(WatchRequest.Ask(now(), sending.clientId, sending.text, sending.replyTo, tafId = sending.tafId))
    // Only while this ask is still the one shown: a cancel meanwhile isn't undone by its answer.
    if (_voice.value != sending) return
    // A taf's chat: the turn shows in that chat, back from the microphone's screen; nothing of it goes to Coach's conversation or
    // the Inbox.
    val taken = ((asked as? Asked.Answered)?.reply as? WatchReply.AskTaken)?.ask
    if (sending.tafId != null && taken != null) {
      tafAsks += taken.id
      val turn = TafTurn(sending.text, now(), taken)
      _taf.update { open -> open?.takeIf { it.tafId == sending.tafId }?.let { it.copy(chat = it.chat.copy(turns = it.chat.turns + turn)) } ?: open }
      voiceNow = null
      return
    }
    voiceNow = when (asked) {
      is Asked.Answered -> when (val reply = asked.reply) {
        is WatchReply.AskTaken -> {
          val at = now()
          receivedAt[sending.clientId] = at
          timings[sending.clientId] = Timing(listenMs, heardAt?.let { at - it }, null)
          awaiting += reply.ask.id
          _said.value = _said.value + (reply.ask.id to Said(sending.text, at))
          took(reply.ask)
          Voice.Sent(reply.ask)
        }
        WatchReply.Unsure -> Voice.Unsure(sending.text, sending.clientId, sending.replyTo, sending.tafId)
        is WatchReply.Failed -> Voice.NotSent(reply.words)
        WatchReply.SignedOut -> Voice.NotSent(Words.Watch.signInOnPhone)
        // A phone with an older tafs doesn't know the ask.
        is WatchReply.Refused -> Voice.NotSent(if (reply.reason == NotAccepted.UNKNOWN_PATH) Words.Watch.updatePhone else null)
        else -> Voice.NotSent(null)
      }
      // No answer in the watch's time: the phone may have sent it.
      is Asked.Unreachable -> Voice.Unsure(sending.text, sending.clientId, sending.replyTo, sending.tafId)
      Asked.NotUnderstood -> Voice.NotSent(null)
    }
  }

  /** Done with the microphone's screen: back to the microphone. Nothing heard is kept. */
  fun closeVoice() {
    // Cancel while the phone transcribes: the recording goes now, and the text that comes after is dropped.
    phoneVoice?.delete()
    phoneVoice = null
    voiceNow = null
  }

  /** The recording on its way to the phone, deleted on Cancel. */
  private var phoneVoice: VoiceAudio? = null

  /**
   * The watch's recognizer gave up partway and the watch recorded the rest ([audio]); [before] is what it heard first (row 44, A).
   * The recording goes to the phone, which turns it into text on the phone itself; the text joins [before] and goes to the review,
   * as words heard do. The recording is deleted once the text is back, on Cancel, and after [VOICE_KEEP_MS] at most. With no
   * on-device recognizer on the phone, what was heard before stands, or the watch says so: nothing goes to a server.
   */
  suspend fun transcribeOnPhone(audio: KeptAudio, before: String, language: String) {
    val listening = _voice.value as? Voice.Listening ?: return audio.delete()
    phoneVoice = audio
    voiceNow = Voice.Transcribing(before, listening.replyTo)
    val id = newClientId()
    val reply = try {
      kotlinx.coroutines.withTimeoutOrNull(VOICE_KEEP_MS) {
        if (!phone.sendVoice(id, audio.file)) null
        else (phone.ask(WatchRequest.Transcribe(now(), id, language)) as? Asked.Answered)?.reply
      }
    } finally {
      audio.delete()
      if (phoneVoice === audio) phoneVoice = null
    }
    // Cancelled, or another start, meanwhile: nothing more.
    val transcribing = _voice.value as? Voice.Transcribing ?: return
    voiceNow = Voice.Listening(before, transcribing.replyTo)
    when (reply) {
      is WatchReply.Transcribed -> heard(listOf(before, reply.text).filter { it.isNotBlank() }.joinToString(" "))
      WatchReply.NoOfflineSpeech -> if (before.isNotBlank()) heard(before) else voiceNow = Voice.NotSent(Words.Watch.noOfflineSpeech)
      else -> if (before.isNotBlank()) heard(before) else heardNothing()
    }
  }

  /**
   * An ask's new state, from the phone's message or a read: kept in memory, shown on the screen it belongs to. An ended ask opens
   * its result card when it's the one just sent.
   */
  fun took(ask: AskLine) {
    if (_signedOutHere.value) return
    if (ask.tafChat || ask.id in tafAsks) {
      // A taf's chat: its open chat's turn, if it's there; never Coach's asks, the Inbox or a result card.
      _taf.update { open ->
        open?.takeIf { o -> o.chat.turns.any { it.ask.id == ask.id && it.ask.updated <= ask.updated } }
          ?.let { o -> o.copy(chat = o.chat.copy(turns = o.chat.turns.map { if (it.ask.id == ask.id) it.copy(ask = ask) else it })) } ?: open
      }
      return
    }
    val before = _asks.value[ask.id]
    // An older state never replaces a newer one (two messages crossing).
    if (before != null && before.updated > ask.updated) return
    _asks.value = _asks.value + (ask.id to ask)
    // An ended ask is a line in the Inbox: Coach's or a taf's answer, kept until dismissed.
    // Kept on the watch, so with no amount (docs/watch-wear.md, "What the watch keeps"): the result shows whole while it's open.
    if (ask.ended) inboxPut(InboxItem(InboxItem.askId(ask.id), InboxItem.Kind.ASK, ask.tafName, Amounts.strip(ask.line ?: ask.words), now()))
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
    // v2 (W3, the owner's Watch 5 test: the answer came while the app was elsewhere, and they didn't know where it was): an ask
    // sent from this watch that ends while the app is open opens its answer by itself, unless the person is busy with a new
    // message or another answer; then it waits in Coach's home and the Inbox, unread.
    if (ask.ended && awaiting.remove(ask.id) && reviewFirst && _result.value == null && _voice.value == null) open(ask)
  }

  /** Opens an ask's result card (from the home screen or its notification). */
  fun open(ask: AskLine) {
    if (_signedOutHere.value) return
    seen += ask.id
    markRead(InboxItem.askId(ask.id))
    _result.value = ask
  }

  // MARK: The Inbox (v2, docs/watch-wear.md): kept on the watch until dismissed; cleared on sign-out and when the watch is locked.

  private val _inbox = MutableStateFlow(inboxStore?.read().orEmpty().sortedByDescending { it.at })

  /** The Inbox, newest first. */
  val inbox: StateFlow<List<InboxItem>> = _inbox.asStateFlow()

  private val _inboxKept = MutableStateFlow(false)

  /**
   * Whether the Inbox can be kept on this watch now: v2, unlocked, with a screen lock. When it can't, nothing is stored and the
   * screen shows what the phone answered now (Needs you's calls, the results ready), so a call that needs the person is never
   * hidden behind "Nothing new".
   */
  val inboxKept: StateFlow<Boolean> = _inboxKept.asStateFlow()

  /** How many lines of the Inbox aren't read yet: Coach's home says so. */
  fun unreadCount(): Int = _inbox.value.count { !it.read }

  /** Lines taken away (Dismiss, Clear read) that the phone still lists: a read doesn't bring them back. */
  private var dismissed: Set<String> = inboxStore?.dismissed().orEmpty()

  /** A new line, unread; one already there keeps its place and whether it was read, with the newest words. */
  private fun inboxPut(item: InboxItem) {
    // v1 keeps no Inbox; a locked watch, or one with no screen lock, adds nothing to it.
    if (inboxStore == null && !reviewFirst) return
    if (!unlocked()) return
    if (item.id in dismissed) return
    val before = _inbox.value.firstOrNull { it.id == item.id }
    if (before != null && before.line == item.line && before.title == item.title) return
    val next = if (before != null) _inbox.value.map { if (it.id == item.id) it.copy(title = item.title, line = item.line) else it } else listOf(item) + _inbox.value
    saveInbox(next)
  }

  /** Opened: read, and read on the person's other watches once [sendMarks] sends it (row 21). */
  fun markRead(id: String) {
    val line = _inbox.value.firstOrNull { it.id == id && !it.read } ?: return
    saveInbox(_inbox.value.map { if (it.id == id) it.copy(read = true) else it })
    unsent.mark(read = listOf(line.ref))
  }

  /** Dismiss: gone from the Inbox, and it stays gone while the phone still lists it; gone on the person's other watches too. */
  fun dismiss(id: String) {
    val line = _inbox.value.firstOrNull { it.id == id } ?: return
    setDismissed(dismissed + id)
    saveInbox(_inbox.value.filterNot { it.id == id })
    unsent.mark(dismissed = listOf(line.ref))
  }

  /** Clear read: every line read is gone, and stays gone; unread ones stay. */
  fun clearRead() {
    val read = _inbox.value.filter { it.read }
    if (read.isEmpty()) return
    setDismissed(dismissed + read.map { it.id })
    saveInbox(_inbox.value.filterNot { it.read })
    unsent.mark(dismissed = read.map { it.ref })
  }

  // MARK: In step across the person's watches (row 21, docs/watch-wear.md): tafs cloud keeps the lines' ids read and dismissed.

  /** Marks made on this watch, not yet sent: in memory only, so one that doesn't arrive changes nothing here. */
  private class Unsent {
    private val read = mutableListOf<String>()
    private val dismissed = mutableListOf<String>()

    @Synchronized fun mark(read: List<String> = emptyList(), dismissed: List<String> = emptyList()) {
      this.read += read
      this.dismissed += dismissed
    }

    @Synchronized fun take(): Pair<List<String>, List<String>> = (read.toList() to dismissed.toList()).also { read.clear(); dismissed.clear() }
  }

  private val unsent = Unsent()
  private val marking = kotlinx.coroutines.sync.Mutex()

  /** The Inbox is kept here now (v2, unlocked, a screen lock): only then are marks read or sent. */
  private fun inboxHere(): Boolean = (inboxStore != null || reviewFirst) && unlocked() && !_signedOutHere.value

  /**
   * Sends the marks made here (opened, Dismiss, Clear read) through the phone, by id alone, in chunks of at most
   * [InboxMarkIds.LIMIT], one after another. One that doesn't arrive (no answer, an older phone) changes nothing on this watch,
   * which keeps its own; it isn't sent again.
   */
  suspend fun sendMarks() = marking.withLock {
    val (read, gone) = unsent.take()
    if (!inboxHere()) return@withLock
    for (chunk in InboxMarkIds.checked(read).chunked(InboxMarkIds.LIMIT)) phone.ask(WatchRequest.InboxMark(now(), read = chunk))
    for (chunk in InboxMarkIds.checked(gone).chunked(InboxMarkIds.LIMIT)) phone.ask(WatchRequest.InboxMark(now(), dismissed = chunk))
  }

  /**
   * After Needs you and the asks are read: sends this watch's marks, then reads the marks tafs cloud keeps, through the phone, and
   * takes them in. With no Inbox kept here nothing is read; a phone from before the marks, or no answer, leaves the lines as they are.
   */
  suspend fun syncInbox() {
    if (!inboxHere()) return
    sendMarks()
    val marks = (phone.ask(WatchRequest.InboxMarks(now())) as? Asked.Answered)?.reply as? WatchReply.InboxMarks ?: return
    if (!inboxHere()) return
    takeMarks(marks)
  }

  /**
   * The marks from tafs cloud: a line dismissed on any watch goes, and stays away while the phone lists it (its id kept with the
   * ids taken away, pruned as theirs are); a line read on any watch is read here. Nothing is ever brought back.
   */
  private fun takeMarks(marks: WatchReply.InboxMarks) {
    val gone = marks.dismissed.toSet()
    val read = marks.read.toSet()
    val away = _inbox.value.filter { it.ref in gone }
    if (away.isNotEmpty()) setDismissed(dismissed + away.map { it.id })
    val next = _inbox.value.filterNot { it.ref in gone }.map { if (!it.read && it.ref in read) it.copy(read = true) else it }
    if (next != _inbox.value) saveInbox(next)
  }

  /** The phone's list of [kind] now: a line taken away that it no longer lists can't come back, so its id goes. */
  private fun pruneDismissed(kind: String, listed: Set<String>) {
    val kept = dismissed.filterTo(mutableSetOf()) { !it.startsWith(kind) || it in listed }
    if (kept.size != dismissed.size) setDismissed(kept)
  }

  private fun setDismissed(ids: Set<String>) {
    dismissed = ids
    inboxStore?.writeDismissed(ids)
  }

  /** Signed out (on the phone, or on this watch): nothing of the Inbox stays on the watch, the ids taken away included. */
  fun clearInbox() {
    inboxStore?.clear()
    dismissed = emptySet()
    _inbox.value = emptyList()
  }

  /**
   * Locked, or no screen lock: the lines go (no taf, words or time stays), but the ids taken away stay, bare, so a line dismissed
   * before the lock doesn't come back unread after it.
   */
  private fun clearInboxLines() {
    inboxStore?.write(emptyList())
    _inbox.value = emptyList()
  }

  /** Put away while locked (or with no screen lock): the Inbox's lines are cleared now, not at the next start. */
  fun clearInboxIfLocked() {
    if (!unlocked()) clearInboxLines()
    checkLock()
  }

  /**
   * The app opened or was put away: on a watch with no screen lock, a message kept for later is deleted, unsent, as the Inbox's
   * lines are, so the watch keeps nothing without one. A watch that's only locked keeps it, since the person asked for it to
   * be sent.
   */
  fun checkLock() {
    if (_kept.value != null && !secure()) deleteKept()
  }

  private fun saveInbox(items: List<InboxItem>) {
    _inbox.value = items
    inboxStore?.write(items)
  }

  /** The ask behind an Inbox line, as this watch knows it now or as the line kept it: its result card opens from either. */
  fun askOf(item: InboxItem): AskLine =
    _asks.value[item.ref] ?: AskLine(item.ref, "", "done", tafName = item.title, line = item.line, words = item.line, updated = "")

  fun closeResult() {
    _result.value = null
  }

  /** The asks at work, newest first, for the home screen. */
  fun working(): List<AskLine> = _asks.value.values.filter { !it.ended }.sortedByDescending { it.updated }

  /** Ended asks not opened yet, newest first. */
  fun ready(): List<AskLine> = _asks.value.values.filter { it.ended && it.id !in seen }.sortedByDescending { it.updated }

  /** Reads the watch's asks from the phone: those of the last day, as tafs cloud has them. A failed read leaves what's shown. */
  suspend fun readAsks() {
    if (_signedOutHere.value) return
    val reply = (phone.ask(WatchRequest.Asks(now())) as? Asked.Answered)?.reply as? WatchReply.AskList ?: return
    for (ask in reply.asks) took(ask)
    pruneDismissed("ask:", reply.asks.map { InboxItem.askId(it.id) }.toSet())
  }

  private val _shown = MutableStateFlow<Shown>(Shown.Asking)
  val shown: StateFlow<Shown> = _shown.asStateFlow()

  /** Reads Needs you, then Your brief (with the switch on), then Your tafs, from the phone. Your tafs not answering leaves Needs you shown, without the tafs. */
  suspend fun refresh() {
    // Signed out on this watch: nothing is read until the person signs in on it again, and an answer that lands after is dropped.
    if (_signedOutHere.value) return
    // A locked watch, or one with no screen lock, keeps no Inbox: as the tile's count, nothing shows off the wrist.
    val kept = unlocked()
    if (!kept) {
      clearInboxLines()
      cache?.clear()
    }
    // Nor, with no screen lock at all, a message kept for later.
    checkLock()
    _inboxKept.value = kept && (inboxStore != null || reviewFirst)
    // Opens at once on what was kept, marked as updating, while the phone is asked (row 44, D).
    if (_shown.value !is Shown.Read) {
      _shown.value = cache?.takeIf { kept }?.load()?.let { c ->
        // The count alone: Needs you's line comes with the phone's read.
        Shown.Read(NeedsYouRead(c.count, "", emptyList()), c.tafs?.let { TafsRead(it, c.allHalted) }, updating = true)
      } ?: Shown.Asking
    } else _shown.value = (_shown.value as Shown.Read).copy(updating = true)
    val asked = phone.ask(WatchRequest.NeedsYou(now()))
    if (_signedOutHere.value) return
    val needsYou = when (asked) {
      is Asked.Answered -> when (val reply = asked.reply) {
        is WatchReply.NeedsYou -> NeedsYouRead(reply.count, reply.words, reply.calls).also {
          tile?.saw(reply.count, now())
          // Each held call is a line in the Inbox, kept until dismissed.
          for (call in reply.calls) inboxPut(InboxItem(InboxItem.heldId(call.holdId), InboxItem.Kind.HELD, call.taf, call.wants, now()))
          pruneDismissed("held:", reply.calls.map { InboxItem.heldId(it.holdId) }.toSet())
        }
        is WatchReply.SignedOut -> {
          tile?.signedOut()
          // Signed out: nothing of the account stays on the watch, a kept message and a recording included, nor its zone.
          WatchChatTime.use(null)
          cache?.clear()
          deleteKept()
          clearInbox()
          if (_voice.value is Voice.Review) voiceNow = null
          // Nor what an open screen read: a taf's page, the halt pick, the calendar, Account.
          _taf.value = null
          _haltPick.value = null
          closeCalendar()
          _account.value = null
          return show(Shown.SignedOut)
        }
        else -> return show(Shown.OpenOnPhone)
      }
      is Asked.Unreachable -> return unreachable(asked.why)
      Asked.NotUnderstood -> return show(Shown.OpenOnPhone)
    }
    failingSince = null
    // Your brief, only with the switch on: none, or a phone that can't say (an older one), and Needs you stays, as today.
    val brief = if (briefOn) (phone.ask(WatchRequest.Brief(now())) as? Asked.Answered)?.reply as? WatchReply.Brief else null
    val tafs = (phone.ask(WatchRequest.Tafs(now())) as? Asked.Answered)?.let { it.reply as? WatchReply.Tafs }
      ?.also { if (it.zone != null) WatchChatTime.use(it.zone) }?.let { TafsRead(it.tafs, it.allHalted) }
    if (_signedOutHere.value) return
    if (kept && unlocked()) cache?.save(needsYou, tafs)
    show(Shown.Read(needsYou, tafs, brief))
    // The phone answered: a message kept for later goes now.
    sendKept()
  }

  /** Since when the phone hasn't answered, while the last read is still shown. */
  private var failingSince: Long? = null

  /**
   * No phone answered: a short drop keeps the last read on screen, marked as updating; only after [UNREACHABLE_AFTER_MS] of
   * failing is the phone called unreachable (row 44, D: "a short disconnect must not flash 'Asking your phone'").
   */
  private fun unreachable(why: LinkFailure = LinkFailure.NotReachable) {
    val shown = _shown.value
    if (shown is Shown.Read) {
      val since = failingSince ?: now().also { failingSince = it }
      if (now() - since < UNREACHABLE_AFTER_MS) return show(shown.copy(updating = true))
    }
    show(Shown.PhoneUnreachable(why))
  }

  // MARK: Keep for later (v2, docs/watch-wear.md)

  /** A message kept on the watch to send when the phone is back: its words, its ask id and the chat it continues. Text only. */
  data class Draft(val text: String, val clientId: String, val replyTo: String?)

  private val _kept = MutableStateFlow(drafts?.read()?.takeIf { secure() } ?: run { if (!secure()) drafts?.clear(); null })

  /** The message kept for later, or none. Kept on disk ([DraftStore]) until it's sent, deleted, or the phone signs out. */
  val kept: StateFlow<Draft?> = _kept.asStateFlow()

  /**
   * Keep for later, from "Not sure it arrived": the words are kept with their id, and sent at the next read that reaches the phone.
   * The same id, so tafs cloud starts it at most once if the first send did arrive. One at a time: a newer one replaces it.
   */
  fun keepForLater() {
    val unsure = _voice.value as? Voice.Unsure ?: return
    // A message to a taf is never kept on the watch (nothing of a taf's chat is), and a kept message is sent as Coach's:
    // Retry or Cancel only.
    if (unsure.tafId != null) return
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
    _said.value = emptyMap()
    _result.value = null
    _taf.value = null
    _haltPick.value = null
    closeCalendar()
    _account.value = null
  }

  private fun show(shown: Shown) {
    _shown.value = shown
  }

  // MARK: A calendar day, Account, and Sign out on this watch (v2 step vi, AccountScreens.kt)

  /** A read for one of v2's own screens: asking, the phone's answer in its words, or why there's none. */
  sealed interface Reading<out T> {
    data object Asking : Reading<Nothing>

    data class Read<T>(val value: T) : Reading<T>

    data object SignedOut : Reading<Nothing>

    data class PhoneUnreachable(val why: LinkFailure = LinkFailure.NotReachable) : Reading<Nothing>

    /** An answer this watch can't draw (an older phone, a failed read): Open on phone, never a guess. */
    data object OpenOnPhone : Reading<Nothing>
  }

  /** A calendar day as tafs cloud said it, and whether it's the account's today: the day the phone answered when asked for none. */
  data class DayRead(val day: WatchReply.CalendarDay, val today: Boolean)

  private val _calendar = MutableStateFlow<Reading<DayRead>?>(null)

  /** The calendar day on screen, in memory only; none until it's opened. */
  val calendar: StateFlow<Reading<DayRead>?> = _calendar.asStateFlow()

  private val _account = MutableStateFlow<Reading<WatchReply.AccountPage>?>(null)

  /** Who is signed in and the plan's line, read each time Account is shown; in memory only. */
  val account: StateFlow<Reading<WatchReply.AccountPage>?> = _account.asStateFlow()

  /** The account's today as the phone last answered it, so a day the person moved to says Today only when it is. */
  private var today: String? = null
  private var calendarAsk = 0

  /** Reads the calendar's [day] (`YYYY-MM-DD`, in the account's zone), or today with none. Only the last day asked is shown. */
  suspend fun readCalendar(day: String? = null) {
    if (_signedOutHere.value) return
    val ask = ++calendarAsk
    _calendar.value = Reading.Asking
    val read = read<WatchReply.CalendarDay>(WatchRequest.Calendar(now(), day))
    // Closed, another day asked, or signed out here meanwhile: this answer is dropped.
    if (ask != calendarAsk || _signedOutHere.value) return
    if (day == null && read is Reading.Read) today = read.value.day
    _calendar.value = when (read) {
      is Reading.Read -> Reading.Read(DayRead(read.value, read.value.day == today))
      Reading.Asking -> Reading.Asking
      Reading.SignedOut -> Reading.SignedOut
      is Reading.PhoneUnreachable -> read
      Reading.OpenOnPhone -> Reading.OpenOnPhone
    }
  }

  /** Done with the calendar: what it showed is forgotten, and an answer still to come is dropped. */
  fun closeCalendar() {
    calendarAsk++
    _calendar.value = null
  }

  /** Reads Account's lines from the phone; what's shown stays until the answer replaces it. */
  suspend fun readAccount() {
    if (_signedOutHere.value) return
    if (_account.value !is Reading.Read) _account.value = Reading.Asking
    val read = read<WatchReply.AccountPage>(WatchRequest.Account(now()))
    if (!_signedOutHere.value) _account.value = read
  }

  private suspend inline fun <reified T : WatchReply> read(request: WatchRequest): Reading<T> = when (val asked = phone.ask(request)) {
    is Asked.Answered -> when (val reply = asked.reply) {
      is T -> Reading.Read(reply)
      WatchReply.SignedOut -> Reading.SignedOut
      else -> Reading.OpenOnPhone
    }
    is Asked.Unreachable -> Reading.PhoneUnreachable(asked.why)
    Asked.NotUnderstood -> Reading.OpenOnPhone
  }

  private val _signedOutHere = MutableStateFlow(signIn?.signedOut() == true)

  /** Signed out on this watch alone: it shows that and Sign in, and reads and sends nothing, until it's signed in here again. */
  val signedOutHere: StateFlow<Boolean> = _signedOutHere.asStateFlow()

  /**
   * Sign out, on this watch alone: the phone stays signed in. The watch forgets everything it holds: what it showed, the asks, a
   * review and its recording, the message kept for later and the tile's count. The flag stays on the watch until Sign in, here.
   */
  fun signOutHere() {
    cache?.clear()
    signIn?.signOut()
    _signedOutHere.value = true
    forget()
    deleteKept()
    clearInbox()
    tile?.signedOut()
    seen.clear()
    timings.clear()
    receivedAt.clear()
    awaiting.clear()
    today = null
  }

  /** Sign in, on this watch again: the phone is still signed in, so the watch only reads it again (the caller refreshes). */
  fun signInHere() {
    signIn?.signIn()
    _signedOutHere.value = false
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
