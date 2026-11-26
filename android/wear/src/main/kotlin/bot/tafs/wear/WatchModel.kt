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

/**
 * Your brief on the watch (docs/watch-wear.md, Your brief): off. With it off the watch asks and shows exactly what it did before;
 * on, it asks the phone for the brief too and shows it in Needs you's place. It turns on with the owner's yes, as this one line.
 */
internal const val BRIEF_ON_WATCH = false

/** How long the phone may fail to answer while the last read stays on screen, before it's called unreachable (row 44, D). */
internal const val UNREACHABLE_AFTER_MS = 30_000L

/**
 * The waits between the watch's own tries when the phone doesn't answer a read in time (docs/watch-wear.md, Delivery): a first
 * slow answer (a phone restoring its session) is tried again by itself, never by the person's Try again.
 */
internal val RETRY_BACKOFF_MS = listOf(2_000L, 4_000L, 8_000L)

/** The waits before Your tafs is read again after a read that missed (row 27): three quiet tries, then the kept list stays. */
internal val TAFS_RETRY_MS = listOf(2_000L, 5_000L, 10_000L)

/** A send with no receipt is sent again by itself after each of these (row 30, call 6)… */
internal val SEND_RETRY_MS = listOf(2_000L, 5_000L, 10_000L)

/** …then this often… */
internal const val SEND_RETRY_EVERY_MS = 30_000L

/** …for at most this long, while its linking screen shows: then "Not sure it arrived", with Retry and Keep for later. */
internal const val SEND_RETRY_FOR_MS = 10 * 60 * 1000L

/** While a taf's chat on screen is answering, its asks are read again this often (row 13)… */
internal const val ANSWER_READ_EVERY_MS = 5_000L

/** …for at most this long: then it stops, and the answer still lands when the phone pushes it. */
internal const val ANSWER_FOLLOW_MS = 120_000L

/** With nothing kept to show, the watch goes on trying this often while it's open, behind its linking screen. */
internal const val LINKING_RETRY_MS = 15_000L

/** A taf's page read again behind its linking line at most this many times, after [RETRY_BACKOFF_MS] (10 minutes in all). */
internal const val LINKING_TRIES = 40

/** How the watch reaches the phone: one live request and its answer, or nothing when the phone can't be reached. */
fun interface PhoneLink {
  /** The phone's reply; [Asked.Unreachable] when no phone with tafs answered in time. */
  suspend fun ask(request: WatchRequest): Asked

  /**
   * [ask], with the account [mark] the watch holds, for a request that carries the person's words or acts
   * ([bot.tafs.watchlink.WatchLink.carriesMark]): a phone on another sign-in answers signed out and does nothing with it.
   */
  suspend fun ask(request: WatchRequest, mark: String?): Asked = ask(request)

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
  /** The phone's reply, and its account mark ([bot.tafs.watchlink.WatchLink.MARK_KEY]); none from a phone before marks. */
  data class Answered(val reply: WatchReply, val mark: String? = null) : Asked

  /** No phone with tafs answered, and [why]. */
  data class Unreachable(val why: LinkFailure = LinkFailure.NotReachable) : Asked

  /** The phone answered with something this watch doesn't understand (a newer phone): it says Open on phone, never guesses. */
  data object NotUnderstood : Asked
}

/**
 * What the watch shows, read from the phone each time it's opened. What it keeps (docs/watch-wear.md, "What the watch keeps"):
 * Coach's conversation ([ThreadStore]), the Inbox ([InboxStore]), the cache ([WatchCache]) and a message kept for later; the
 * rest is in memory, and a new read replaces it whole.
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
  /**
   * The chats kept on the watch, Coach's and each taf's opened here ([ThreadStore], [KeptThreads]); in memory only in a test that
   * doesn't pass one, in v1, and with [KEEP_THREADS] off.
   */
  private val thread: ThreadStore? = null,
  /**
   * The phone's account mark the watch last saw ([MarkStore], [AccountMark]); none in a test that doesn't look, which behaves as a
   * watch before marks.
   */
  private val marks: MarkStore? = null,
  /** Everything of an account went (a change of sign-in, a sign-out): the watch's own notifications and recordings go too. */
  private val wiped: () -> Unit = {},
) {
  /**
   * Whether what the watch keeps may be drawn yet (docs/watch-wear.md, "What the watch keeps"). Holding a mark, nothing kept is
   * drawn until a reply this process carried that same mark: not one frame of an account the phone may have left. Holding none
   * (a phone before marks, or a first open), as before.
   */
  private var verified = marks?.read() == null

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
      /** The watch's own tries all went unanswered: what's shown stays, with this small line and Try again (never a screen). */
      val unreachable: LinkFailure? = null,
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
    val asked = ask(request)
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
   * A taf's chat while it's open (docs/watch-wear.md, chatting with a taf): its last turns as the phone shows them, the turns sent
   * from this watch with the taf's answers, and the answers read in full. Kept as Coach's is ([KeptThreads]), the whole answers
   * read with Read more excepted; never in the Inbox.
   */
  data class TafChat(
    val history: History = History.Reading,
    val turns: List<TafTurn> = emptyList(),
    val whole: Map<String, Whole> = emptyMap(),
    /** Its answer was followed for [ANSWER_FOLLOW_MS] and hasn't come: it still lands when the phone pushes it, and Open on phone shows. */
    val waitedOut: Boolean = false,
  ) {
    /** A message sent from here that the taf is still answering (not held: that's the phone's). */
    val answering: Boolean get() = turns.any { !it.ask.ended && !it.ask.held }

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

  /** The taf's page open now, from the Tafs page, with its chat (kept on the watch as Coach's is, with [KEEP_THREADS] on). */
  val taf: StateFlow<OpenTaf?> = _taf.asStateFlow()

  /**
   * A taf tapped on the Tafs page: its page, read from the phone; its chat opens at once on what the watch keeps of it, newest at
   * the bottom, and is read again behind it.
   */
  suspend fun openTaf(tafId: String) {
    if (_signedOutHere.value) return
    val listed = (_shown.value as? Shown.Read)?.tafs?.tafs?.firstOrNull { it.tafId == tafId }
    val kept = keptRead()[tafThread(tafId)]
    val chat = kept?.let { k ->
      tafAsks += k.turns.map { it.ask.id } + k.sent
      TafChat(history = if (k.lines.isEmpty()) TafChat.History.Reading else TafChat.History.Read(k.lines), turns = k.turns.map { TafTurn(it.said.text, it.said.at, it.ask) })
    } ?: TafChat()
    _taf.value = OpenTaf(tafId, listed, TafRead.Asking, chat)
    if (tafChatOn) {
      // Opened here: it's kept (with KEEP_THREADS on), and stays while it's opened within 30 days.
      keptWrite { all -> all + (tafThread(tafId) to (all[tafThread(tafId)] ?: KeptThread(now())).copy(openedAt = now())) }
      readTafChat(tafId)
    }
    readTaf(tafId)
  }

  /**
   * The last turns of a taf's chat, read from the phone. What the watch kept stays shown if the phone can't answer; once it does,
   * the watch's own turns its lines already hold go, and the chat is kept again.
   */
  private suspend fun readTafChat(tafId: String) {
    val read = when (val asked = ask(WatchRequest.TafChat(now(), tafId))) {
      is Asked.Answered -> (asked.reply as? WatchReply.TafChat)?.also { if (it.zone != null) WatchChatTime.use(it.zone) }
      else -> null
    }
    if (read != null) return tafChatChanged(tafId, read.lines)
    _taf.update { open ->
      open?.takeIf { it.tafId == tafId && it.chat.history !is TafChat.History.Read }?.let { it.copy(chat = it.chat.copy(history = TafChat.History.NotRead)) } ?: open
    }
  }

  /**
   * A taf's chat as the phone holds it now: from a read, or pushed by the phone as it changes (a kept item, while the chat is
   * open here). The open chat moves at once; the kept copy follows.
   */
  fun tafChatChanged(tafId: String, lines: List<bot.tafs.watchlink.ChatLine>) {
    if (_signedOutHere.value || !verified) return
    _taf.update { open ->
      open?.takeIf { it.tafId == tafId }?.let {
        val kept = KeptThreads.withLines(KeptThread(0, turns = it.chat.turns.map { t -> KeptTurn(Said(t.said, t.at), t.ask) }), lines)
        it.copy(chat = it.chat.copy(history = TafChat.History.Read(lines), turns = kept.turns.map { t -> TafTurn(t.said.text, t.said.at, t.ask) }))
      } ?: open
    }
    keptWrite { all -> KeptThreads.chat(all, tafId, lines) ?: all }
  }

  /** Keeps the open taf's chat as it shows now. */
  private fun saveTaf(tafId: String) {
    val open = _taf.value?.takeIf { it.tafId == tafId } ?: return
    keptWrite { all ->
      val before = all[tafThread(tafId)] ?: KeptThread(now())
      all + (tafThread(tafId) to KeptThread(now(), lines = (open.chat.history as? TafChat.History.Read)?.lines.orEmpty(),
        turns = open.chat.turns.map { KeptTurn(Said(it.said, it.at), it.ask) }, sent = before.sent).sending(open.chat.turns.map { it.ask.id }))
    }
  }

  /** The asks sent from a taf's chat this session: never Coach's, never in the Inbox. */
  private val tafAsks = mutableSetOf<String>()

  /**
   * While a taf's chat on screen shows "{taf} is answering": the watch's asks are read again through the phone every [every], so
   * the answer lands even if the phone's push didn't come (row 13). It stops at once when nothing is being answered, when the chat
   * closes, or after [limit], with Open on phone beside the answering line; the answer still lands when the phone pushes it. The
   * screen runs it only while the chat is on it, so a watch put away reads nothing.
   */
  suspend fun followAnswer(tafId: String, every: Long = ANSWER_READ_EVERY_MS, limit: Long = ANSWER_FOLLOW_MS, wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }) {
    var waited = 0L
    while (_taf.value?.takeIf { it.tafId == tafId }?.chat?.answering == true) {
      if (waited >= limit) {
        _taf.update { open -> open?.takeIf { it.tafId == tafId }?.let { it.copy(chat = it.chat.copy(waitedOut = true)) } ?: open }
        return
      }
      wait(every)
      waited += every
      if (_signedOutHere.value || _taf.value?.tafId != tafId) return
      readAsks()
    }
  }

  /** Read more on a taf's answer: the whole of it, read from tafs cloud only now, shown in place and kept nowhere. */
  suspend fun readMore(askId: String) {
    val open = _taf.value ?: return
    if (open.chat.whole[askId] is Whole.Read) return
    setWhole(askId, Whole.Reading)
    val whole = when (val asked = ask(WatchRequest.AskFull(now(), askId))) {
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

  /** The taf's page read again (its calm linking line tries by itself while it shows). */
  suspend fun rereadTaf() {
    _taf.value?.let { readTaf(it.tafId) }
  }

  private suspend fun readTaf(tafId: String) {
    val read = when (val asked = ask(WatchRequest.Taf(now(), tafId))) {
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
    val each = chosen.map { taf -> taf.name to outcomeOf(ask(WatchRequest.Halt(now(), taf.tafId))) }
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
      /** Why the words came back here unsent, in the phone's words (row 13): they're kept, never dropped behind Open on phone. */
      val notice: String? = null,
    ) : Voice

    /** v2: deleted from the review; nothing was sent. */
    data object Deleted : Voice

    /** Sending the words, as text, through the phone. */
    data class Sending(val text: String, val clientId: String, val replyTo: String?, val tafId: String? = null) : Voice

    /** tafs cloud took it: its receipt, then the taf it went to and its state as they change. */
    data class Sent(val ask: AskLine) : Voice

    /**
     * No receipt yet, the phone out of reach or slow (row 30, call 6): one calm linking screen while the watch sends the same words
     * with the same id again by itself ([keepTrying]), so nothing is started twice; it ends on its own when the phone answers.
     */
    data class Linking(val text: String, val clientId: String, val replyTo: String?, val tafId: String? = null) : Voice

    /** No receipt after the watch's own tries: it may have arrived. Retry sends the same words with the same id, so nothing is started twice. */
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

  /** Coach's conversation as kept on the watch when the app opened: only on a watch with a screen lock, else it goes. */
  private val keptAtOpen: List<KeptTurn> =
    if (!secure()) { thread?.clear(); emptyList() } else if (!verified) emptyList() else keptRead()[COACH_THREAD]?.turns.orEmpty()

  private val _asks = MutableStateFlow(keptAtOpen.associate { it.ask.id to it.ask })

  /** The watch's asks as tafs cloud last said, by id: the kept conversation's, then each read's. */
  val asks: StateFlow<Map<String, AskLine>> = _asks.asStateFlow()

  /** What the person said from this watch, by its ask's id, with when (W4, Ask Coach as one conversation). */
  data class Said(val text: String, val at: Long)

  private val _said = MutableStateFlow(keptAtOpen.associate { it.ask.id to it.said })
  val said: StateFlow<Map<String, Said>> = _said.asStateFlow()

  /**
   * Coach's conversation from this watch, oldest first: each thing said and its ask as the phone relays it (W4). One
   * conversation across opens: kept on the watch ([ThreadStore]) on a watch with a screen lock, and in memory for the app's life
   * on one without; put away, it stays; gone on either sign-out (docs/watch-wear.md, "What the watch keeps").
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
  /** The chats kept on the watch, as they may stay now ([KeptThreads.pruned]): none on a watch with no screen lock. */
  private fun keptRead(): Map<String, KeptThread> =
    if (thread == null || !secure()) emptyMap() else KeptThreads.pruned(thread.read(), now())

  /** Changes what's kept, on a watch with a screen lock, signed in here; what may no longer stay goes as it's written. */
  private fun keptWrite(change: (Map<String, KeptThread>) -> Map<String, KeptThread>) {
    val store = thread ?: return
    if (!secure() || _signedOutHere.value) return
    store.write(KeptThreads.pruned(change(KeptThreads.pruned(store.read(), now())), now()))
  }

  /** Keeps Coach's conversation on the watch: it's opened as the app is, since it's the app's home. */
  private fun saveThread() {
    keptWrite { all -> all + (COACH_THREAD to KeptThread(now(), turns = thread().map { (said, ask) -> KeptTurn(said, ask) })) }
  }

  /**
   * Back on screen: what the phone kept for the watch while the app was put away (KeptResults wrote it to the stores) is taken in,
   * an ask newer than the one in memory replacing it, so the app shows what's there without a read.
   */
  fun reload() {
    if (_signedOutHere.value || !verified) return
    keptRead()[COACH_THREAD]?.turns?.forEach { turn ->
      val before = _asks.value[turn.ask.id]
      if (before == null || before.updated < turn.ask.updated) _asks.value = _asks.value + (turn.ask.id to turn.ask)
      if (turn.ask.id !in _said.value) _said.value = _said.value + (turn.ask.id to turn.said)
    }
    inboxStore?.let { store ->
      dismissed = store.dismissed()
      // What's kept, and what this open holds that the store doesn't (a write the watch's Keystore refused, say): a line never
      // vanishes on a reopen (row 20). One dismissed, here or on another watch, stays away; a lock or a sign-out empties both.
      val stored = store.read()
      val held = _inbox.value.filter { line -> line.id !in dismissed && stored.none { it.id == line.id } }
      // A taf's answer is its chat's alone: one an older watch filed here goes.
      val threads = keptRead()
      val lines = (stored + held).filterNot { it.kind == InboxItem.Kind.ASK && KeptThreads.inTafChat(threads, it.ref) }
      _inbox.value = lines.sortedByDescending { it.at }
      if (held.isNotEmpty() || lines.size != stored.size + held.size) store.write(_inbox.value)
    }
  }

  /**
   * Every ask goes through here: the reply's account mark is checked before anything of it is used ([AccountMark]). Another
   * sign-in's mark wipes everything kept first, then the reply is the new one's; held and missing, everything kept goes and the
   * reply isn't shown (it comes back as no answer). The same mark lets what's kept be drawn.
   */
  private suspend fun ask(request: WatchRequest): Asked {
    // To a phone that has shown it knows marks (the last reply this open checked carried the one held): the sign-in this act is
    // meant for, so a phone that has changed since refuses it. A phone before marks never sees the field.
    val mark = marks?.read()?.takeIf { verified && bot.tafs.watchlink.WatchLink.carriesMark(request) }
    return heard(phone.ask(request, mark))
  }

  private fun heard(asked: Asked): Asked {
    val store = marks ?: return asked
    val answered = asked as? Asked.Answered ?: return asked
    val reply = answered.reply
    // Signed out, to any request (an act sent under another sign-in's mark included): everything of the account goes now.
    if (reply == WatchReply.SignedOut) {
      wipeAccount()
      store.clear()
      verified = true
      _shown.value = Shown.SignedOut
      return asked
    }
    // A reply with nothing of an account proves nothing.
    if (AccountMark.neutral(reply)) return asked
    when (AccountMark.seen(store.read(), answered.mark)) {
      AccountMark.Seen.Legacy -> takeInKept()
      // The first mark ever (an updated phone): what was kept before marks can't say whose it is, so it goes, once, before
      // anything of the reply is drawn.
      AccountMark.Seen.First -> {
        wipeAccount()
        _shown.value = Shown.Asking
        store.write(answered.mark!!)
        verified = true
      }
      AccountMark.Seen.Same -> takeInKept()
      AccountMark.Seen.Changed -> {
        wipeAccount()
        // Not one more frame of the last read: the reply's own screen comes next.
        _shown.value = Shown.Asking
        store.write(answered.mark!!)
        verified = true
      }
      AccountMark.Seen.Missing -> {
        wipeAccount()
        verified = false
        _shown.value = Shown.Asking
        return Asked.Unreachable(LinkFailure.Link)
      }
    }
    return asked
  }

  /** The first reply this process with the mark the watch holds: what was kept is drawn now. */
  private fun takeInKept() {
    if (verified) return
    verified = true
    reload()
    _kept.value = drafts?.read()?.takeIf { secure() }
  }

  /**
   * Everything of an account goes, on the watch and in memory, before anything else is drawn: a sign-out on the phone, a change of
   * sign-in, or a reply that should carry the mark and doesn't. One list for all of them.
   */
  private fun wipeAccount() {
    tile?.signedOut()
    // Nothing of the account stays on the watch, a kept message and a recording included, nor its zone.
    WatchChatTime.use(null)
    cache?.clear()
    deleteKept()
    clearInbox()
    clearThread()
    unsent.take()
    voiceNow = null
    // Nor what an open screen read: a taf's page, the halt pick, the calendar, Account, a result, an answer read whole.
    _taf.value = null
    _haltPick.value = null
    closeCalendar()
    _account.value = null
    _result.value = null
    _outcome.value = null
    _wholes.value = emptyMap()
    seen.clear()
    wiped()
  }

  /**
   * The phone signed out, told by its kept item ([bot.tafs.watchlink.WatchLink.KEPT_SIGNED_OUT]) while the app is open: everything
   * of the account goes now, without asking, and the mark with it.
   */
  fun phoneSignedOut() {
    wipeAccount()
    marks?.clear()
    verified = true
    show(Shown.SignedOut)
  }

  /** The conversation gone, on the watch and in memory: either sign-out. */
  private fun clearThread() {
    thread?.clear()
    _asks.value = emptyMap()
    _said.value = emptyMap()
  }

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
    // A taf's chat with the account's switch off (row 13): its page says so, and nothing is recorded to be dropped.
    if (tafId != null && _tafChatOff.value) return
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

  private val _tafChatOff = MutableStateFlow(false)

  /**
   * tafs cloud said chatting with a taf isn't on for this account ([WatchReply.TafChatOff], row 13): a taf's chat says so where
   * its microphone and Type would be, until the app is opened again. tafs cloud gives no read of the switch, so the watch learns
   * it from the first send.
   */
  val tafChatOff: StateFlow<Boolean> = _tafChatOff.asStateFlow()

  private fun reviewOf(text: String, replyTo: String?, audio: VoiceAudio? = null): Voice.Review {
    val taf = messaging?.let { id -> _taf.value?.takeIf { it.tafId == id } }
    val notice = if (taf != null && _tafChatOff.value) Words.Watch.askNotOn else null
    return Voice.Review(text.take(bot.tafs.watchlink.WatchLink.MAX_ASK_TEXT), replyTo, audio, tafId = taf?.tafId, tafName = taf?.name, notice = notice)
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

  /**
   * While the linking screen shows for a send ([Voice.Linking]): the same words with the same id, sent again after each of
   * [backoff], then every [every], for at most [limit]; then "Not sure it arrived", with Retry and Keep for later. It stops at the
   * first answer, at Cancel, or when the screen goes (the screen runs it). A locked watch sends nothing meanwhile: it waits.
   */
  suspend fun keepTrying(
    backoff: List<Long> = SEND_RETRY_MS,
    every: Long = SEND_RETRY_EVERY_MS,
    limit: Long = SEND_RETRY_FOR_MS,
    wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
  ) {
    var waited = 0L
    var tries = 0
    while (true) {
      val linking = _voice.value as? Voice.Linking ?: return
      val pause = backoff.getOrElse(tries) { every }
      if (waited + pause > limit) {
        voiceNow = Voice.Unsure(linking.text, linking.clientId, linking.replyTo, linking.tafId)
        return
      }
      wait(pause)
      waited += pause
      tries++
      if (_voice.value != linking || _signedOutHere.value) return
      if (!unlocked()) continue
      send(Voice.Sending(linking.text, linking.clientId, linking.replyTo, linking.tafId), shown = linking)
    }
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

  /** Sends [sending]; [shown] is the screen meanwhile: Sending, or the linking screen while the watch tries again by itself. */
  private suspend fun send(sending: Voice.Sending, shown: Voice = sending) {
    voiceNow = shown
    val asked = ask(WatchRequest.Ask(now(), sending.clientId, sending.text, sending.replyTo, tafId = sending.tafId))
    // Only while this ask is still the one shown: a cancel meanwhile isn't undone by its answer.
    if (_voice.value != shown) return
    // A taf's chat: the turn shows in that chat, back from the microphone's screen; nothing of it goes to Coach's conversation or
    // the Inbox.
    val taken = ((asked as? Asked.Answered)?.reply as? WatchReply.AskTaken)?.ask
    if (sending.tafId != null && taken != null) {
      _tafChatOff.value = false
      tafAsks += taken.id
      val turn = TafTurn(sending.text, now(), taken)
      _taf.update { open -> open?.takeIf { it.tafId == sending.tafId }?.let { it.copy(chat = it.chat.copy(turns = it.chat.turns + turn, waitedOut = false)) } ?: open }
      saveTaf(sending.tafId)
      voiceNow = null
      return
    }
    // A taf's chat that didn't take it (row 13): the words go back to Check before sending, kept, with why; never dropped behind
    // Open on phone. "Not sure it arrived" stays as Coach's, with Retry under the same id.
    if (sending.tafId != null && !(asked is Asked.Unreachable || (asked as? Asked.Answered)?.reply == WatchReply.Unsure)) {
      val reply = (asked as? Asked.Answered)?.reply
      if (reply == WatchReply.TafChatOff) _tafChatOff.value = true
      val notice = when (reply) {
        WatchReply.TafChatOff -> Words.Watch.askNotOn
        is WatchReply.Failed -> reply.words
        WatchReply.SignedOut -> Words.Watch.signInOnPhone
        is WatchReply.Refused -> if (reply.reason == NotAccepted.UNKNOWN_PATH) Words.Watch.updatePhone else Words.Watch.phoneLinkFailed
        else -> Words.Watch.phoneLinkFailed
      }
      val name = _taf.value?.takeIf { it.tafId == sending.tafId }?.name
      voiceNow = Voice.Review(sending.text, sending.replyTo, tafId = sending.tafId, tafName = name, notice = notice)
      return
    }
    voiceNow = when (asked) {
      is Asked.Answered -> when (val reply = asked.reply) {
        is WatchReply.AskTaken -> {
          val at = now()
          receivedAt[sending.clientId] = at
          timings[sending.clientId] = Timing(listenMs, heardAt?.let { at - it }, null)
          _said.value = _said.value + (reply.ask.id to Said(sending.text, at))
          took(reply.ask)
          saveThread()
          // v2: back to the conversation, where the words are the newest turn with tafs cloud's state under them, and the answer
          // comes in its place, as in a chat (the owner's call). v1 shows Sent until the answer.
          if (reviewFirst) null else Voice.Sent(reply.ask)
        }
        // No receipt: the calm linking screen, and the watch sends it again by itself (row 30).
        WatchReply.Unsure -> Voice.Linking(sending.text, sending.clientId, sending.replyTo, sending.tafId)
        is WatchReply.Failed -> Voice.NotSent(reply.words)
        WatchReply.SignedOut -> Voice.NotSent(Words.Watch.signInOnPhone)
        // A phone with an older tafs doesn't know the ask.
        is WatchReply.Refused -> Voice.NotSent(if (reply.reason == NotAccepted.UNKNOWN_PATH) Words.Watch.updatePhone else null)
        else -> Voice.NotSent(null)
      }
      // No answer in the watch's time: the phone may have sent it.
      is Asked.Unreachable -> Voice.Linking(sending.text, sending.clientId, sending.replyTo, sending.tafId)
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
        else (ask(WatchRequest.Transcribe(now(), id, language)) as? Asked.Answered)?.reply
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
    if (_signedOutHere.value || !verified) return
    if (ask.tafChat || ask.id in tafAsks || KeptThreads.inTafChat(keptRead(), ask.id)) {
      // A taf's chat: its open chat's turn, if it's there; never Coach's asks, the Inbox or a result card.
      _taf.update { open ->
        open?.takeIf { o -> o.chat.turns.any { it.ask.id == ask.id && it.ask.updated <= ask.updated } }
          ?.let { o -> o.copy(chat = o.chat.copy(turns = o.chat.turns.map { if (it.ask.id == ask.id) it.copy(ask = ask) else it })) } ?: open
      }
      keptWrite { all -> KeptThreads.took(all, ask) ?: all }
      return
    }
    val before = _asks.value[ask.id]
    // An older state never replaces a newer one (two messages crossing).
    if (before != null && before.updated > ask.updated) return
    _asks.value = _asks.value + (ask.id to ask)
    if (ask.id in _said.value) saveThread()
    // An ended ask is a line in the Inbox: Coach's or a taf's answer, kept until dismissed.
    // Kept on the watch, so with no amount (docs/watch-wear.md, "What the watch keeps"): the result shows whole while it's open.
    InboxLines.of(ask, now())?.let(::inboxPut)
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
    // v2: the answer comes in the conversation, under the words it answers, as in a chat (the owner's call; it replaces W3's
    // card that opened by itself), and unread in the Inbox.
  }

  /** Opens an ask's result card (from the home screen or its notification). */
  fun open(ask: AskLine) {
    if (_signedOutHere.value) return
    seen += ask.id
    markRead(InboxItem.askId(ask.id))
    _result.value = ask
  }

  // MARK: The Inbox (v2, docs/watch-wear.md): kept on the watch until dismissed; cleared on sign-out and when the watch is locked.

  private val _inbox = MutableStateFlow(if (verified) inboxStore?.read().orEmpty().sortedByDescending { it.at } else emptyList())

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
  private var dismissed: Set<String> = if (verified) inboxStore?.dismissed().orEmpty() else emptySet()

  /** A new line, unread; one already there keeps its place and whether it was read, with the newest words. */
  private fun inboxPut(item: InboxItem) {
    // v1 keeps no Inbox; a locked watch, or one with no screen lock, adds nothing to it.
    if (inboxStore == null && !reviewFirst) return
    if (!unlocked()) return
    InboxLines.put(_inbox.value, dismissed, item)?.let(::saveInbox)
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
    for (chunk in InboxMarkIds.checked(read).chunked(InboxMarkIds.LIMIT)) ask(WatchRequest.InboxMark(now(), read = chunk))
    for (chunk in InboxMarkIds.checked(gone).chunked(InboxMarkIds.LIMIT)) ask(WatchRequest.InboxMark(now(), dismissed = chunk))
  }

  /**
   * After Needs you and the asks are read: sends this watch's marks, then reads the marks tafs cloud keeps, through the phone, and
   * takes them in. With no Inbox kept here nothing is read; a phone from before the marks, or no answer, leaves the lines as they are.
   */
  suspend fun syncInbox() {
    if (!inboxHere()) return
    sendMarks()
    val marks = (ask(WatchRequest.InboxMarks(now())) as? Asked.Answered)?.reply as? WatchReply.InboxMarks ?: return
    if (!inboxHere()) return
    takeMarks(marks)
  }

  /**
   * The marks from tafs cloud: a line dismissed on any watch goes, and stays away while the phone lists it (its id kept with the
   * ids taken away, pruned as theirs are); a line read on any watch is read here. Nothing is ever brought back.
   */
  fun takeMarks(marks: WatchReply.InboxMarks) {
    if (!inboxHere() || !verified) return
    val (next, away) = InboxLines.marked(_inbox.value, dismissed, marks.read.toSet(), marks.dismissed.toSet())
    if (away != dismissed) setDismissed(away)
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
   * Locked, or no screen lock: the lines go (no taf, words or time stays), and the conversation kept on the watch, but the ids
   * taken away stay, bare, so a line dismissed before the lock doesn't come back unread after it.
   */
  private fun clearInboxLines() {
    inboxStore?.write(emptyList())
    _inbox.value = emptyList()
    // The conversation kept on the watch goes with them; what's in memory stays for the app's life, as the reads do.
    thread?.clear()
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

  private val _wholes = MutableStateFlow<Map<String, Whole>>(emptyMap())

  /** Answers read whole (Read more on a cut answer, Coach's or a taf's), by the ask's id: in memory only. */
  val wholes: StateFlow<Map<String, Whole>> = _wholes.asStateFlow()

  /**
   * Read more on an answer tafs cloud says goes on past its line (`more`): the whole, read through the phone
   * (`GET /v1/watch/asks/{id}?full=1`), shown in place and heard by Hear, kept nowhere; with none, the rest is on the phone.
   */
  suspend fun readWhole(askId: String) {
    if (_signedOutHere.value || _wholes.value[askId] is Whole.Read) return
    _wholes.update { it + (askId to Whole.Reading) }
    val whole = when (val asked = ask(WatchRequest.AskFull(now(), askId))) {
      is Asked.Answered -> (asked.reply as? WatchReply.AskFull)?.let { Whole.Read(it.text) } ?: Whole.OnPhone
      else -> Whole.OnPhone
    }
    _wholes.update { it + (askId to whole) }
  }

  /** What Hear reads for [ask]: the whole answer once read, else its line or its state. */
  fun heard(ask: AskLine): String = Plain.of((_wholes.value[ask.id] as? Whole.Read)?.text ?: ask.line ?: ask.words)

  /** The asks at work, newest first, for the home screen. */
  fun working(): List<AskLine> = _asks.value.values.filter { !it.ended }.sortedByDescending { it.updated }

  /** Ended asks not opened yet, newest first. */
  fun ready(): List<AskLine> = _asks.value.values.filter { it.ended && it.id !in seen }.sortedByDescending { it.updated }

  /**
   * Every answered ask the watch knows, opened or not, newest first: the Inbox where it can't be kept on this watch (no screen
   * lock), so an answer opened there doesn't vanish from it (row 20). Never a message to a taf.
   */
  fun answered(): List<AskLine> {
    val threads = keptRead()
    return _asks.value.values.filter { it.ended && !it.tafChat && it.id !in tafAsks && !KeptThreads.inTafChat(threads, it.id) }.sortedByDescending { it.updated }
  }

  /** Reads the watch's asks from the phone: those of the last day, as tafs cloud has them. A failed read leaves what's shown. */
  suspend fun readAsks() {
    if (_signedOutHere.value) return
    val reply = (ask(WatchRequest.Asks(now())) as? Asked.Answered)?.reply as? WatchReply.AskList ?: return
    // tafs cloud's switch for chatting with a taf (row 13): off, a taf's chat says so before anything is recorded. Not said (an
    // older tafs cloud), the watch learns it from a send's refusal, as before.
    reply.tafChat?.let { _tafChatOff.value = !it }
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
      _shown.value = cache?.takeIf { kept && verified }?.load()?.let { c ->
        // The count alone: Needs you's line comes with the phone's read.
        Shown.Read(NeedsYouRead(c.count, "", emptyList()), c.tafs?.let { TafsRead(it, c.allHalted) }, updating = true)
      } ?: Shown.Asking
    } else _shown.value = (_shown.value as Shown.Read).copy(updating = true)
    val asked = ask(WatchRequest.NeedsYou(now()))
    if (_signedOutHere.value) return
    val needsYou = when (asked) {
      is Asked.Answered -> when (val reply = asked.reply) {
        is WatchReply.NeedsYou -> NeedsYouRead(reply.count, reply.words, reply.calls).also {
          tile?.saw(reply.count, now())
          // Each held call is a line in the Inbox, with no amount kept; one the phone no longer lists (answered, refused or run out,
          // row 29) goes, so a Refuse is never offered on a call KIFF has dropped ([HeldCalls.inbox], as the phone's push does).
          if ((inboxStore != null || reviewFirst) && unlocked()) HeldCalls.inbox(_inbox.value, dismissed, reply.calls, now())?.let(::saveInbox)
          pruneDismissed("held:", reply.calls.map { InboxItem.heldId(it.holdId) }.toSet())
        }
        is WatchReply.SignedOut -> {
          // Signed out: nothing of the account stays on the watch, nor its mark: the next sign-in's is a first one.
          wipeAccount()
          marks?.clear()
          verified = true
          return show(Shown.SignedOut)
        }
        else -> return show(Shown.OpenOnPhone)
      }
      is Asked.Unreachable -> return unreachable(asked.why)
      Asked.NotUnderstood -> return show(Shown.OpenOnPhone)
    }
    failingSince = null
    lastFailed = false
    // Your brief, only with the switch on: none, or a phone that can't say (an older one), and Needs you stays, as today.
    val brief = if (briefOn) (ask(WatchRequest.Brief(now())) as? Asked.Answered)?.reply as? WatchReply.Brief else null
    val read = (ask(WatchRequest.Tafs(now())) as? Asked.Answered)?.let { it.reply as? WatchReply.Tafs }
      ?.also { if (it.zone != null) WatchChatTime.use(it.zone) }?.let { TafsRead(it.tafs, it.allHalted) }
    if (_signedOutHere.value) return
    // A list read that missed (a slow phone, the 10 s passing) never empties the Tafs page: the list the watch had stays, shown and
    // kept, and [start] reads it again behind it (row 27). Only a list the phone gave replaces it.
    tafsMissed = read == null
    val tafs = read ?: lastTafs(kept)
    if (kept && unlocked()) cache?.save(needsYou, tafs)
    show(Shown.Read(needsYou, tafs, brief))
    // The phone answered: a message kept for later goes now.
    sendKept()
  }

  /** The last read of Your tafs missed: what's shown is the list from before, and [retryTafs] reads it again. */
  private var tafsMissed = false

  /** The list the watch had: on screen, or as kept, never another account's ([verified]). */
  private fun lastTafs(kept: Boolean): TafsRead? =
    (_shown.value as? Shown.Read)?.tafs ?: cache?.takeIf { kept && verified }?.load()?.let { c -> c.tafs?.let { TafsRead(it, c.allHalted) } }

  /**
   * Your tafs read again after a read that missed, quietly, after each of [backoff]: the first list the phone gives replaces the
   * one shown and kept. Nothing on screen changes while it tries, and it stops at the first answer, or after the last try.
   */
  suspend fun retryTafs(backoff: List<Long> = TAFS_RETRY_MS, wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }) {
    for (pause in backoff) {
      if (!tafsMissed || _signedOutHere.value) return
      wait(pause)
      val read = (ask(WatchRequest.Tafs(now())) as? Asked.Answered)?.reply as? WatchReply.Tafs ?: continue
      tafsChanged(read.tafs, read.allHalted, read.zone)
      return
    }
  }

  /**
   * Your tafs as the phone holds them now: from a read, or pushed by the phone when its list changes ([bot.tafs.watchlink.KeptTafs],
   * taken only under the mark held). The Tafs page moves at once, and the kept copy follows.
   */
  fun tafsChanged(tafs: List<TafLine>, allHalted: Boolean, zone: String? = null) {
    if (_signedOutHere.value || !verified) return
    tafsMissed = false
    if (zone != null) WatchChatTime.use(zone)
    val read = TafsRead(tafs, allHalted)
    val shown = _shown.value as? Shown.Read ?: return
    _shown.value = shown.copy(tafs = read)
    if (unlocked()) cache?.save(shown.needsYou, read)
  }

  /**
   * Needs you as the phone pushed it when a call was held or ended (`kept/needs-you`, taken only under the mark held): the count
   * on the tile and on screen, each held call's line at the top of the Inbox, and an ended one gone, at once and without asking.
   * The pushed lines carry no amount or reason; a call already shown keeps the ones its live read gave.
   */
  fun needsYouPushed(count: Int, words: String, calls: List<HeldLine>) {
    if (_signedOutHere.value || !verified) return
    tile?.saw(count, now())
    (_shown.value as? Shown.Read)?.let { read ->
      val known = read.needsYou.calls.associateBy { it.holdId }
      val merged = calls.map { call -> known[call.holdId]?.let { call.copy(amount = it.amount, reason = it.reason) } ?: call }
      _shown.value = read.copy(needsYou = NeedsYouRead(count, words, merged))
    }
    if ((inboxStore != null || reviewFirst) && unlocked()) HeldCalls.inbox(_inbox.value, dismissed, calls, now())?.let(::saveInbox)
    pruneDismissed("held:", calls.map { InboxItem.heldId(it.holdId) }.toSet())
  }

  /** Since when the phone hasn't answered, while the last read is still shown. */
  private var failingSince: Long? = null

  /** The last read went unanswered. */
  private var lastFailed = false

  /** The watch will try again by itself ([start]): an unanswered read changes nothing on screen yet. */
  private var tryingAgain = false

  /** The read just made was the last of the watch's own tries. */
  private var afterTries = false

  /**
   * No phone answered. While the watch is still to try again by itself, nothing changes on screen. Then the last read, or what was
   * kept, stays, with a small line ([Shown.Read.unreachable]) and Try again, never a screen of its own: at once after the watch's
   * own tries, or after [UNREACHABLE_AFTER_MS] of failing for any other read (row 44, D: "a short disconnect must not flash
   * 'Asking your phone'"). With nothing to show, the phone is called unreachable.
   */
  private fun unreachable(why: LinkFailure = LinkFailure.NotReachable) {
    lastFailed = true
    val shown = _shown.value
    if (tryingAgain) return if (shown is Shown.Read) show(shown.copy(updating = true)) else Unit
    if (shown is Shown.Read) {
      val since = failingSince ?: now().also { failingSince = it }
      val short = now() - since < UNREACHABLE_AFTER_MS && !afterTries
      return show(if (short) shown.copy(updating = true) else shown.copy(updating = false, unreachable = why))
    }
    show(Shown.PhoneUnreachable(why))
  }

  /**
   * The app opened (docs/watch-wear.md, Delivery): it shows what's kept at once, and reads the phone behind it. A read the phone
   * doesn't answer in time is tried again by itself, after each of [backoff]; only once those go unanswered does the small line
   * show. With nothing kept to show, it goes on trying every [LINKING_RETRY_MS] behind the linking screen while the app is open.
   * Then the asks and the Inbox's marks are read.
   */
  suspend fun start(backoff: List<Long> = RETRY_BACKOFF_MS, wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }) {
    if (_signedOutHere.value) return
    // Each open tries the taf chat's switch again: tafs cloud may have turned it on since.
    _tafChatOff.value = false
    // Coach's conversation is the app's home: opening the app opens it, so it stays kept while the app is used.
    keptWrite { all -> all[COACH_THREAD]?.let { all + (COACH_THREAD to it.copy(openedAt = now())) } ?: all }
    var tries = 0
    while (true) {
      val last = tries >= backoff.size
      tryingAgain = !last
      afterTries = last
      lastFailed = false
      try {
        refresh()
      } finally {
        tryingAgain = false
        afterTries = false
      }
      if (!lastFailed) break
      // Tries spent: what's shown stays with its small line; with nothing to show, the linking screen stays and the watch goes on.
      if (last && !linking()) break
      wait(if (last) LINKING_RETRY_MS else backoff[tries])
      tries++
    }
    if (_signedOutHere.value) return
    readAsks()
    // Row 21: the Inbox's read and dismissed, in step with the person's other watches.
    syncInbox()
    // Your tafs, again, if their read missed: the page keeps the last list until one comes (row 27).
    retryTafs(wait = wait)
  }

  /**
   * The watch has nothing to show while it reaches the phone (a first link, a first open): no read, no conversation, no Inbox
   * kept. Then it shows one calm screen, the tafs mark in a ring and "Linking tafs…", while it tries; anything kept opens instead.
   */
  fun linking(): Boolean {
    val shown = _shown.value
    if (shown is Shown.Read || shown == Shown.SignedOut || shown == Shown.OpenOnPhone) return false
    // tafs isn't on the phone: no try mends that, so its line shows.
    if ((shown as? Shown.PhoneUnreachable)?.why == LinkFailure.NoApp) return false
    return _said.value.isEmpty() && _inbox.value.isEmpty()
  }

  // MARK: Keep for later (v2, docs/watch-wear.md)

  /**
   * A message kept on the watch to send when the phone is back: its words, its ask id and the chat it continues. Text only. [mark]
   * is the phone's account mark it was kept under: it's sent only to that same sign-in, never another.
   */
  data class Draft(val text: String, val clientId: String, val replyTo: String?, val mark: String? = null)

  private val _kept = MutableStateFlow(if (!secure()) { drafts?.clear(); null } else if (!verified) null else drafts?.read())

  /** The message kept for later, or none. Kept on disk ([DraftStore]) until it's sent, deleted, or the phone signs out. */
  val kept: StateFlow<Draft?> = _kept.asStateFlow()

  /**
   * Keep for later, from "Not sure it arrived": the words are kept with their id, and sent at the next read that reaches the phone.
   * The same id, so tafs cloud starts it at most once if the first send did arrive. One at a time: a newer one replaces it.
   */
  fun keepForLater() {
    val unsure = when (val v = _voice.value) {
      is Voice.Unsure -> v
      is Voice.Linking -> Voice.Unsure(v.text, v.clientId, v.replyTo, v.tafId)
      else -> return
    }
    // A message to a taf is never kept on the watch (nothing of a taf's chat is), and a kept message is sent as Coach's:
    // Retry or Cancel only.
    if (unsure.tafId != null) return
    val draft = Draft(unsure.text, unsure.clientId, unsure.replyTo, marks?.read())
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
    // Only to the sign-in it was kept under, once a reply this process has said it's that one: another's, it goes unsent.
    if (marks != null) {
      if (!verified) return
      if (draft.mark != marks.read()) return deleteKept()
    }
    val asked = ask(WatchRequest.Ask(now(), draft.clientId, draft.text, draft.replyTo))
    if (_kept.value != draft) return
    val taken = ((asked as? Asked.Answered)?.reply as? WatchReply.AskTaken)?.ask ?: return
    deleteKept()
    took(taken)
  }

  /**
   * Put away (the screen off, another app, the keyboard's own screen): what's kept stays, the conversation, the asks, the last
   * read, a taf's chat open and an answer being read included, so the app comes back as it was. A review keeps its words, never
   * its recording, which is erased now; words heard so far stay as a review, never sent. What was being asked or confirmed goes.
   */
  fun putAway() {
    _outcome.value = null
    _confirmingHaltAll.value = false
    _haltPick.value = null
    voiceNow = when (val v = _voice.value) {
      is Voice.Review -> v.copy(audio = null)
      is Voice.Listening -> v.heard.trim().takeIf { it.isNotEmpty() }?.let { reviewOf(it, v.replyTo) }
      is Voice.Sending, is Voice.Unsure, is Voice.Linking -> v
      else -> null
    }
    closeCalendar()
    _account.value = null
  }

  /** What the watch keeps once the phone signs out, or it's signed out here: nothing, the conversation included. */
  fun forget() {
    _shown.value = Shown.Asking
    _outcome.value = null
    _confirmingHaltAll.value = false
    voiceNow = null
    clearThread()
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

  private suspend inline fun <reified T : WatchReply> read(request: WatchRequest): Reading<T> = when (val asked = ask(request)) {
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
    _wholes.value = emptyMap()
    timings.clear()
    receivedAt.clear()
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
    return WatchModel.Draft(text, id, prefs.getString(REPLY_TO, null), prefs.getString(MARK, null))
  }

  override fun write(draft: WatchModel.Draft) {
    prefs.edit().clear().putString(TEXT, draft.text).putString(ID, draft.clientId).apply {
      draft.replyTo?.let { putString(REPLY_TO, it) }
      draft.mark?.let { putString(MARK, it) }
    }.apply()
  }

  override fun clear() {
    prefs.edit().clear().apply()
  }

  private companion object {
    const val TEXT = "text"
    const val ID = "client_id"
    const val REPLY_TO = "reply_to"
    const val MARK = "account_mark"
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
