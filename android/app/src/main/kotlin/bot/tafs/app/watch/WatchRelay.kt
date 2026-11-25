package bot.tafs.app.watch

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.model.Answer
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.Money
import bot.tafs.app.model.Words
import bot.tafs.watchlink.Amounts
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.CardLine
import bot.tafs.watchlink.EventLine
import bot.tafs.watchlink.DecisionLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

/**
 * The phone's answers to the Wear OS watch (docs/watch-wear.md). Every message passes [WatchLink.accept] before anything is read
 * or signed; then the phone reads with its own session, through the app's one [AppModel], and answers in its own words. The
 * watch draws them as they come.
 *
 * Refuse and Halt from the wrist only take authority away, so the phone signs them with its signing key and no prompt of its
 * own, as a notification's Refuse on iPhone: the watch sends one only while its own screen lock is set and it's unlocked. The
 * approval key is never used here, and nothing on this path approves. Coach from the wrist is text only (the watch's own speech
 * input), sent as the phone's own message to Coach, after the AI consent given on the phone. Ask tafs from the wrist is text
 * too, sent to tafs cloud as this phone's, signed with its device key: tafs cloud marks it as the watch's and hands it to one taf.
 */
/** How long the phone waits for a recording's channel to finish after the watch asks for its text. */
internal const val VOICE_WAIT_MS = 15_000L

class WatchRelay(
  /** The app's model, its session restored; null when there's none to read with. */
  private val model: suspend () -> AppModel?,
  private val now: () -> Long = System::currentTimeMillis,
  /** How long the phone waits for Coach's answer before the watch is told it's on the phone: under the watch's own 10 s. */
  private val coachWaitMs: Long = 8_000,
  /** Shared by every request in the process (WearRelayService): a watch asking in a loop reads tafs cloud and KIFF at most this often. */
  private val floor: RefreshFloor = RefreshFloor(),
  /** The watch's recordings, as they arrive over their channels (row 44, A); none where the phone takes none. */
  private val voice: VoiceInbox? = null,
  /** The phone's on-device recognizer, for a recording the watch's own gave up on; none, and it's [WatchReply.NoOfflineSpeech]. */
  private val transcriber: Transcriber? = null,
  /** The taf chats the watch opened, which the phone then keeps for it as they change ([WatchChats]); none in a test. */
  private val chats: WatchChats? = null,
  /**
   * Where a read of tafs cloud and KIFF for the watch runs (the app's [bot.tafs.app.TafsApplication.background]), so one slower
   * than the watch's wait goes on after the phone has answered from what it holds; none in a test that waits for every read.
   */
  private val background: kotlinx.coroutines.CoroutineScope? = null,
  /** How long the phone waits for a fresh read when it already holds one: past it, it answers from what it holds. */
  private val freshWaitMs: Long = FRESH_WAIT_MS,
  /** How long it waits when it holds none yet: still under the watch's own 10 s, which then asks again by itself. */
  private val firstReadWaitMs: Long = FIRST_READ_WAIT_MS,
) {
  suspend fun answer(path: String, data: ByteArray): WatchReply {
    val request = when (val accepted = WatchLink.accept(path, data, now())) {
      is WatchLink.Accepted.No -> return WatchReply.Refused(accepted.reason)
      is WatchLink.Accepted.Yes -> accepted.request
    }
    return when (request) {
      is WatchRequest.NeedsYou, is WatchRequest.Tafs, is WatchRequest.Brief -> {
        val model = signedIn() ?: return WatchReply.SignedOut
        // Read again now: the watch shows what tafs cloud and KIFF say, not what the phone last drew. Within the floor of the
        // last read, that read stands. The brief is the one the phone holds, read with Needs you (`AppModel.refresh`): no read
        // of its own.
        if (request !is WatchRequest.Tafs) readAgain(model)
        val state = model.state.value
        when {
          state.mode != Mode.kiff -> WatchReply.SignedOut
          request is WatchRequest.NeedsYou -> needsYou(state)
          request is WatchRequest.Brief -> brief(state)
          else -> tafs(state)
        }
      }
      is WatchRequest.Refuse -> refuse(request.holdId)
      is WatchRequest.Halt -> halt(request.tafId)
      is WatchRequest.Coach -> coach(request.text)
      is WatchRequest.Ask -> ask(request)
      is WatchRequest.Asks -> asks(request.since)
      is WatchRequest.TafChat -> tafChat(request.tafId)
      is WatchRequest.AskFull -> askFull(request.askId)
      is WatchRequest.Taf -> {
        val model = signedIn() ?: return WatchReply.SignedOut
        // Read again within the floor, as Needs you: today's use is KIFF's now, not what the phone last drew.
        readAgain(model)
        tafPage(model.state.value, request.tafId) ?: WatchReply.Failed(Words.Watch.openOnPhone)
      }
      is WatchRequest.Transcribe -> transcribe(request)
      is WatchRequest.Calendar -> calendar(request.day)
      is WatchRequest.Account -> {
        val model = signedIn() ?: return WatchReply.SignedOut
        account(model.state.value)
      }
      is WatchRequest.InboxMarks -> inboxMarks { it.watchInbox() }
      is WatchRequest.InboxMark -> inboxMarks { it.markWatchInbox(request.read, request.dismissed) }
    }
  }

  /**
   * Row 21: the Inbox's lines read and dismissed on any of the person's watches, read or marked at tafs cloud with this phone's
   * session, by id alone (WatchLink checked them). Nothing is signed: a mark changes no one's power.
   */
  private suspend fun inboxMarks(read: suspend (AppModel) -> bot.tafs.app.kiff.WatchInboxMarks?): WatchReply {
    val model = signedIn() ?: return WatchReply.SignedOut
    return try {
      val marks = read(model) ?: return WatchReply.SignedOut
      WatchReply.InboxMarks(marks.read, marks.dismissed)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (_: Exception) {
      WatchReply.Failed(Words.Watch.openOnPhone)
    }
  }

  /** A calendar day, read from tafs cloud for that day alone (the phone's own calendar screen keeps its read). */
  private suspend fun calendar(day: String?): WatchReply {
    val model = signedIn() ?: return WatchReply.SignedOut
    return try {
      val (shown, read) = model.calendarDay(day) ?: return WatchReply.SignedOut
      calendarDay(shown, read)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (_: Exception) {
      WatchReply.Failed(Words.Watch.openOnPhone)
    }
  }

  /**
   * Ask tafs from the wrist (docs/watch-wear.md): the words, as text, sent to tafs cloud as this phone's, signed with its device
   * key; tafs cloud asks for the AI consent given on the phone. It gives its receipt, the same for a retry with the same id; with no
   * answer from it, the watch is told it may have arrived ([WatchReply.Unsure]) and retries with the same id.
   */
  private suspend fun ask(request: WatchRequest.Ask): WatchReply {
    val model = signedIn() ?: return WatchReply.SignedOut
    // Chatting with a taf (docs/watch-wear.md): the watch names the phone's taf, tafs cloud takes its own id. One the phone
    // doesn't list, or has no tafs cloud id for, is the phone's to open: never Coach's chat.
    val tafCloudId = request.tafId?.let { id ->
      model.state.value.tafs.firstOrNull { it.id == id }?.cloudId ?: return WatchReply.Failed(Words.Watch.openOnPhone)
    }
    // The AI consent is tafs cloud's to check: it refuses with `ai_consent_required`, said in the watch's words below.
    return try {
      val ask = model.watchAsk(request.clientId, request.text, request.replyTo, tafCloudId) ?: return WatchReply.SignedOut
      WatchReply.AskTaken(lineOf(ask))
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: bot.tafs.app.kiff.CloudError) {
      if (tafCloudId != null) refusedTafMessage(e) else refusedAsk(e)
    } catch (_: java.io.IOException) {
      // Sent, perhaps, with no answer back: only a retry with the same id tells.
      WatchReply.Unsure
    }
  }

  /**
   * A recording the watch's recognizer gave up on (row 44, A): its text from this phone's on-device recognizer, and only the text
   * goes back. The recording is deleted whatever happens; it never leaves the phone. Nothing here reads the account: the words
   * reach Coach or a taf only later, through Send and the same checks as typed words.
   */
  private suspend fun transcribe(request: WatchRequest.Transcribe): WatchReply {
    val file = voice?.take(request.voiceId, waitMs = VOICE_WAIT_MS) ?: return WatchReply.Failed(Words.Watch.openOnPhone)
    return try {
      when (val heard = transcriber?.transcribe(file, request.language) ?: Transcription.NoOffline) {
        is Transcription.Text -> WatchReply.Transcribed(heard.text)
        Transcription.NoOffline -> WatchReply.NoOfflineSpeech
        Transcription.Failed -> WatchReply.Failed(Words.Watch.openOnPhone)
      }
    } finally {
      file.delete()
    }
  }

  /** A taf's chat's last turns, for a taf this phone lists: read only, as the chat view reads them. */
  private suspend fun tafChat(tafId: String): WatchReply {
    val model = signedIn() ?: return WatchReply.SignedOut
    return try {
      val messages = model.watchTafChat(tafId) ?: return WatchReply.Failed(Words.Watch.openOnPhone)
      // Opened on the watch: from now on the phone keeps it for the watch as it changes here (docs/watch-wear.md, Delivery).
      chats?.opened(tafId)
      WatchReply.TafChat(chatLines(messages, bot.tafs.watchlink.ChatLine.KEPT), zone = bot.tafs.app.model.zone.id)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (_: Exception) {
      WatchReply.Failed(Words.Watch.openOnPhone)
    }
  }

  /** Read more: tafs cloud's whole answer, or, without one, the rest is on the phone. */
  private suspend fun askFull(askId: String): WatchReply {
    val model = signedIn() ?: return WatchReply.SignedOut
    return try {
      model.watchAskFull(askId)?.let { WatchReply.AskFull(it) } ?: WatchReply.Failed(Words.Watch.moreOnPhone)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (_: Exception) {
      WatchReply.Failed(Words.Watch.moreOnPhone)
    }
  }

  /** The watch's asks as tafs cloud has them, updated after [since]. */
  private suspend fun asks(since: String?): WatchReply {
    val model = signedIn() ?: return WatchReply.SignedOut
    return try {
      val read = model.watchAsksRead(since) ?: return WatchReply.SignedOut
      // The switch for chatting with a taf, as tafs cloud says it (row 13), so the watch says so before recording.
      WatchReply.AskList(read.asks.map(::lineOf), tafChat = read.tafChat)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: bot.tafs.app.kiff.CloudError) {
      refusedAsk(e)
    } catch (_: java.io.IOException) {
      WatchReply.Failed(Words.Watch.openOnPhone)
    }
  }

  /**
   * Coach from the wrist: the words, as text, sent as the phone's own message to Coach, then Coach's answer, short. Only with
   * the AI consent given on the phone, and only to a Coach started on it (starting asks for the fingerprint). An answer that
   * shows a card (an approval, a sign-in, a setup step) or is longer than the watch draws says Open on phone; one slower than
   * [coachWaitMs], counted from the request's start, is on the phone. Nothing heard reaches the phone: the watch sends text.
   */
  private suspend fun coach(text: String): WatchReply {
    val model = signedIn() ?: return WatchReply.SignedOut
    val before = model.state.value
    if (before.aiConsent?.given != true) return WatchReply.Failed(Words.Watch.coachNeedsConsent)
    if (before.coachCloudId == null) return WatchReply.Failed(Words.Watch.openOnPhone)
    val cards = before.coachComponents.size
    // The budget runs from the request's start, sending included, so the whole answer stays under the watch's own 10 s.
    val started = now()
    if (!model.deliverChat(text, bot.tafs.app.app.COACH_CHAT)) {
      // Refused for the AI consent though the phone held it as given (the consent asked again, e.g. new words): tafs cloud's
      // `ai_consent_required` put its screen up and its answer in the state, so the watch says so, never a bare "Open on phone".
      val after = model.state.value
      if (after.aiConsent?.given != true) return WatchReply.Failed(Words.Watch.coachNeedsConsent)
      return WatchReply.Failed(after.chatErrors[bot.tafs.app.app.COACH_CHAT] ?: Words.Watch.openOnPhone)
    }
    val answered = kotlinx.coroutines.withTimeoutOrNull(waitLeft(started, now(), coachWaitMs)) {
      model.state.first { coachDone(it) && coachAnswer(it, text) != null }
    } ?: return WatchReply.Coach(Words.Watch.moreOnPhone, long = true)
    val words = coachAnswer(answered, text)!!
    val card = answered.coachComponents.size > cards
    return if (card || words.length > COACH_SHORT) WatchReply.Coach(words.take(COACH_SHORT).trimEnd() + "…", long = true)
    else WatchReply.Coach(words, long = false)
  }

  private suspend fun signedIn(): AppModel? = model()?.takeIf { it.state.value.mode == Mode.kiff }

  /**
   * Reads tafs cloud and KIFF again for the watch, past the floor, or joins the read under way; a read is the app's whole refresh,
   * which can take longer than the watch waits (the Galaxy Watch 5 test: every first ask timed out with tafs in front). So it
   * runs in [background] and the phone waits for it at most [freshWaitMs] when it already holds a read, [firstReadWaitMs] when it
   * holds none; past that it answers from what it holds, and the read lands for the next ask.
   */
  private suspend fun readAgain(model: AppModel) {
    val scope = background ?: return if (floor.due(now())) model.refresh() else Unit
    val read = floor.read(now(), scope) { model.refresh() } ?: return
    val held = model.state.value.let { it.listLoaded && !it.listUnread }
    kotlinx.coroutines.withTimeoutOrNull(if (held) freshWaitMs else firstReadWaitMs) { runCatching { read.await() } }
  }

  /**
   * Refuses the held call the watch named, after reading Needs you again: one answered or gone meanwhile is "already answered",
   * never a second answer to KIFF. Signed with the signing key, confirmed by the watch's own unlock (`confirmed`), so the phone
   * asks nothing; the result is KIFF's, as the phone's own Refuse shows it.
   */
  private suspend fun refuse(holdId: String): WatchReply {
    val model = signedIn() ?: return WatchReply.SignedOut
    model.refresh()
    val before = model.state.value
    val call = before.held.firstOrNull { it.id == holdId } ?: return WatchReply.AlreadyAnswered
    if (!refusableFromWrist(before, call)) return WatchReply.Failed(Words.Watch.openOnPhone)
    val done = model.answer(call, Answer.Refuse, confirmed = true)
    val after = model.state.value
    return when {
      done -> WatchReply.Done(Words.Receipt.Label.refused)
      after.held.none { it.id == holdId } -> WatchReply.AlreadyAnswered
      else -> WatchReply.Failed(after.lastError?.takeIf { it != before.lastError } ?: Words.Alert.notRefused)
    }
  }

  /** Halts the taf the watch named, or every taf: no confirmation, as on the phone. The result is tafs cloud's, as it stands. */
  private suspend fun halt(tafId: String?): WatchReply {
    val model = signedIn() ?: return WatchReply.SignedOut
    if (tafId != null && model.state.value.taf(tafId) == null) return WatchReply.Failed(Words.Watch.openOnPhone)
    // Where it stands now, read again: one halted or halting already (a Halt sent twice, or from the phone meanwhile) is
    // answered as it stands, and nothing is asked of tafs cloud a second time.
    model.refreshHalt(tafId)
    if (!model.state.value.haltOf(tafId).running) return standing(model.state.value.haltOf(tafId), tafId)
    model.halt(tafId)
    val state = model.state.value
    state.haltError?.let { return WatchReply.Failed(it) }
    return standing(state.haltOf(tafId), tafId)
  }

  private fun standing(halt: bot.tafs.app.kiff.Halt, tafId: String?): WatchReply = when {
    halt.halted -> WatchReply.Done(if (tafId == null) Words.Alert.allHalted else Words.Taf.State.halted)
    halt.halting -> WatchReply.Done(Words.Halt.Explain.halting)
    // Nothing was asked of tafs cloud (a taf it doesn't run): the phone's own Halt says why.
    else -> WatchReply.Failed(Words.Watch.openOnPhone)
  }

  companion object {
    /** An ask as the watch draws it: tafs cloud's state and words, the taf's name, and the checked line, as they come. */
    fun lineOf(ask: bot.tafs.app.kiff.WatchAsk): AskLine =
      AskLine(ask.id, ask.clientId, ask.state, ask.taf?.name, ask.line, ask.words, ask.updated, held = ask.held, more = ask.more, kind = ask.kind)

    /**
     * tafs cloud's refusal of an ask, in its words: asking from the watch not on yet says so; 5xx, which may have come after the
     * ask was taken, is "may have arrived", so the watch retries with the same id; anything else, tafs cloud's own words.
     */
    /**
     * tafs cloud's refusal of a message to a taf: its switch off (`watch_taf_chat_off`, [WatchReply.TafChatOff]); the taf not
     * the person's to use (`no_taf`), Coach named as a taf (`coach_taf`), or the watch's own switch off: Open on phone. Never a
     * fallback to Coach. The rest is as for an ask ([refusedAsk]): a view-only phone, the AI consent, a 5xx.
     */
    fun refusedTafMessage(e: bot.tafs.app.kiff.CloudError): WatchReply = when {
      // The account's switch off (row 13): its own reply, so the watch keeps the words and says so before the next recording.
      e.code == "watch_taf_chat_off" -> WatchReply.TafChatOff
      e.status == 404 || e.code == "coach_taf" -> WatchReply.Failed(Words.Watch.openOnPhone)
      else -> refusedAsk(e)
    }

    fun refusedAsk(e: bot.tafs.app.kiff.CloudError): WatchReply = when {
      e.code == "watch_off" -> WatchReply.Failed(Words.Watch.askNotOn)
      e.code == "ai_consent_required" -> WatchReply.Failed(Words.Watch.askNeedsConsent)
      e.status >= 500 -> WatchReply.Unsure
      e.status == 401 -> WatchReply.SignedOut
      else -> WatchReply.Failed(e.message?.takeIf { it.isNotBlank() } ?: Words.Watch.openOnPhone)
    }

    /**
     * What's left of [budgetMs] for Coach's answer, the request having started at [startedMs]: the send's own time comes off it,
     * so a slow network never takes the whole answer past the watch's limit. Never below 0, never above the budget (a clock
     * set back).
     */
    fun waitLeft(startedMs: Long, nowMs: Long, budgetMs: Long): Long = (budgetMs - (nowMs - startedMs)).coerceIn(0, budgetMs)

    /** The phone's wait for a fresh read while it holds one: well under the watch's 10 s. */
    const val FRESH_WAIT_MS = 3_000L

    /** The phone's wait for a first read: under the watch's 10 s, with the time to find and reach the phone. */
    const val FIRST_READ_WAIT_MS = 7_000L

    /** The longest answer the watch draws whole: past it, the start and Open on phone. */
    const val COACH_SHORT = 280

    /** Coach has stopped working on the owner's message, as the phone's own chat says (`AppState.thinking`). */
    fun coachDone(state: AppState): Boolean = !state.thinking(bot.tafs.app.app.COACH_CHAT)

    /**
     * Coach's answer to [asked], as the phone shows it: its newest message after the owner's own message with those words, by the
     * chat's order, never by comparing tafs cloud's clock with the phone's (a phone running ahead would see every answer as old).
     * Null while there's none.
     */
    fun coachAnswer(state: AppState, asked: String): String? {
      val chat = state.chats[bot.tafs.app.app.COACH_CHAT].orEmpty()
      val mine = chat.indexOfLast { it.from == bot.tafs.app.model.ChatMessage.From.owner && it.text.trim() == asked.trim() }
      if (mine < 0) return null
      return chat.drop(mine + 1).lastOrNull {
        it.from == bot.tafs.app.model.ChatMessage.From.taf && it.kind == bot.tafs.app.model.ChatMessage.Kind.message && it.text.isNotBlank()
      }?.text?.trim()
    }

    /**
     * Whether the wrist may refuse [call]: one KIFF holds that this phone can answer from here. A call tafs cloud holds alone
     * (no KIFF id) is signed through the phone's own prompt, which nobody would see with the phone in a pocket, so it's
     * Approve and Refuse on the phone. The wrist's Refuse is always the signing key with no prompt (`signConfirmed`).
     */
    fun refusableFromWrist(state: AppState, call: HeldCall): Boolean = call.kiffId != null && state.canAnswer(call)

    /**
     * A held call's words as the watch may keep them (v2's Inbox keeps `wants`, docs/watch-wear.md, "What the watch keeps"):
     * with no amount, even where KIFF's title writes one in.
     * - First the call's own [amount], in the ways a figure is written (150, 150.00, 150,00, 1,234.56, 1.234,56, 1 234,56,
     *   1'234.56, and as the app formats it), with a currency beside it or none.
     * - Then any other figure with a currency beside it, spaced or not (150zł, 150 EUR): a sign, a code or a word (€, zł, kr, Fr.,
     *   PLN, euros, dollars, Franken, dolarów…), before
     *   it first and then after it, so in "order 1042 EUR 150.00" the order's number stays. A figure never starts inside a
     *   number, and a space groups thousands only after 1 to 3 digits, so "order 4502 150 €" keeps 4502.
     *
     * A figure with no currency that isn't the call's amount is never cut. The amount goes to the watch on its own
     * ([HeldLine.amount]), drawn while Needs you holds the call and never kept. As the iPhone's `WatchRelay.withoutAmount`.
     */
    fun withoutAmount(summary: String, amount: Money? = null): String {
      var words = summary
      ownAmount(amount)?.let { words = words.replace(it, " ") }
      return Amounts.strip(words)
    }

    /** The call's own amount as a pattern: each way its figure is written, longest first, with a currency beside it or none. */
    private fun ownAmount(amount: Money?): Regex? {
      if (amount == null || amount.minor == 0L) return null
      val value = amount.value.abs()
      val numbers = linkedSetOf(value.toPlainString(), value.stripTrailingZeros().toPlainString())
      val spellings = mutableSetOf(amount.figure.trim())
      for (n in numbers) {
        val whole = n.substringBefore('.')
        val fraction = n.substringAfter('.', "")
        for (group in Amounts.GROUPS) {
          val grouped = whole.reversed().chunked(3).joinToString(group).reversed()
          if (group.isNotEmpty() && grouped == whole) continue
          for (point in listOf(".", ",")) {
            if (point == group) continue
            spellings += if (fraction.isEmpty()) grouped else grouped + point + fraction
          }
        }
      }
      val any = spellings.filter { it.isNotEmpty() }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
      return Regex("""(?<![\d.,])(?:${Amounts.CURRENCY}\s?)?(?:$any)(?:\s?${Amounts.CURRENCY})?(?![.,]?\d)""", RegexOption.IGNORE_CASE)
    }


    /**
     * Needs you as the phone shows it: tafs cloud's count and its words, and each held call with its taf, what it wants, KIFF's
     * amount and the reason. One this phone can't answer from here (KIFF doesn't hold its key) isn't refusable from the wrist.
     */
    fun needsYou(state: AppState): WatchReply.NeedsYou = WatchReply.NeedsYou(
      count = state.needsYouCount,
      words = state.needsYouWords,
      calls = state.held.map { call ->
        HeldLine(
          holdId = call.id,
          // On the wrist too, no direction character reorders what's beside the amount.
          taf = state.taf(call.tafId)?.name?.let(bot.tafs.app.kiff.KiffMapping::visible) ?: Words.NeedsYou.aTaf,
          wants = bot.tafs.app.kiff.KiffMapping.visible(withoutAmount(call.proposed.summary, call.proposed.amount)),
          amount = call.proposed.amount?.formatted,
          reason = call.reason.takeIf { it.isNotBlank() }?.let(bot.tafs.app.kiff.KiffMapping::visible),
          refusable = refusableFromWrist(state, call),
        )
      },
    )

    /**
     * Your brief as the phone shows it in Needs you's place (`AppState.brief`), in tafs cloud's words: the decisions still to answer
     * here (one set aside on the phone isn't), in tafs cloud's order; Done's heading with its first two cases by name; the rest line,
     * and the phone's own count of what Needs you holds beyond the brief. A decision about a held call the phone holds names it as
     * Needs you does ([HeldLine.holdId]), so the watch's Refuse is Needs you's own. No brief: [WatchReply.NoBrief], and the watch
     * shows Needs you.
     */
    fun brief(state: AppState): WatchReply {
      val brief = state.brief ?: return WatchReply.NoBrief
      val cases = brief.done.flatMap { it.cases }.map { it.name.ifEmpty { it.words } }.filter { it.isNotEmpty() }
      return WatchReply.Brief(
        title = brief.words.titleShown,
        whenLine = brief.words.`when`.takeIf { it.isNotBlank() },
        decisions = state.briefDecisions.map { d ->
          val call = if (d.ref.kind == "hold" && d.ref.id.isNotEmpty()) state.held.firstOrNull { it.kiffId == d.ref.id } else null
          DecisionLine(
            id = d.id,
            question = d.question,
            taf = state.tafs.firstOrNull { it.cloudId != null && it.cloudId == d.ref.taf }?.name ?: call?.let { state.taf(it.tafId)?.name },
            deadline = d.deadline,
            urgent = d.urgent,
            cantWait = d.cantWait,
            refKind = d.ref.kind,
            refId = d.ref.id,
            holdId = call?.id,
          )
        },
        more = brief.more?.words,
        urgent = brief.words.urgentShown,
        done = if (cases.isEmpty()) null else brief.words.doneShown,
        doneCases = cases.take(2),
        rest = brief.rest?.words,
        needsYou = state.briefNeedsYouLine,
      )
    }

    /**
     * One taf's page as the phone's Card tab draws it: its state and job, each Card's title, face and rules, and how many of its
     * calls wait on the person. Null for a taf the phone doesn't list.
     */
    fun tafPage(state: AppState, tafId: String): WatchReply.TafPage? {
      val taf = state.taf(tafId) ?: return null
      val halted = state.haltOf(taf.id).halted || state.haltOf(null).halted
      return WatchReply.TafPage(
        tafId = taf.id,
        name = taf.name,
        state = if (halted) Words.Taf.State.halted else taf.now,
        halted = halted,
        job = taf.job,
        cards = taf.shownCards.map { card ->
          CardLine(
            title = card.title,
            figure = card.hero?.figure,
            words = card.hero?.words,
            rules = card.rules.map { Words.Card.ruleLine(it.label, it.summary) },
          )
        },
        waiting = state.held.count { it.tafId == taf.id },
      )
    }

    /**
     * A taf's chat as the watch shows it: messages with words only (a run's kickoff note isn't a turn), the last
     * [last] ([ChatLine.LAST] by default; [ChatLine.KEPT] for the chat the watch keeps), each cut as Coach's answer is on the wrist,
     * with whether more is on the phone. A message still on its way isn't one yet.
     */
    fun chatLines(messages: List<bot.tafs.app.model.ChatMessage>, last: Int = bot.tafs.watchlink.ChatLine.LAST): List<bot.tafs.watchlink.ChatLine> =
      messages.filter { it.kind == bot.tafs.app.model.ChatMessage.Kind.message && it.text.isNotBlank() && it.session != "pending" }
        .takeLast(last)
        .map { m ->
          val words = m.text.trim()
          val cut = words.length > COACH_SHORT
          bot.tafs.watchlink.ChatLine(m.id, m.from == bot.tafs.app.model.ChatMessage.From.owner, m.at.toString(),
            if (cut) words.take(COACH_SHORT).trimEnd() + "\u2026" else words, more = cut)
        }

    /** A calendar day as tafs cloud reads it: its line, and each event on that day with its time and its taf's name. */
    fun calendarDay(day: String, read: bot.tafs.app.kiff.CalendarRead): WatchReply.CalendarDay = WatchReply.CalendarDay(
      day = day,
      words = read.words,
      events = read.events.filter { it.day == day }.sortedBy { it.minute }.map { EventLine(it.time, it.name, it.words, it.count) },
      halted = read.halted,
    )

    /**
     * Who is signed in, the account's name, and the plan's line as Account shows it. A personal account is named by its email, so
     * its name goes only when it says something more (mac-air's walk-through, 19 Nov: the watch showed the email twice; iOS #1500).
     */
    fun account(state: AppState): WatchReply.AccountPage {
      val email = state.kiffAccount?.email
      val name = (state.kiffAccount?.displayName ?: state.kiffAccount?.slug)?.trim()?.takeIf { it.isNotEmpty() }
      return WatchReply.AccountPage(
        email = email,
        account = name?.takeUnless { email != null && it.equals(email.trim(), ignoreCase = true) },
        plan = state.balance?.planLine,
      )
    }

    /** Each taf in one line, in tafs cloud's words, and whether it, or the whole account, is halted. */
    fun tafs(state: AppState): WatchReply.Tafs = WatchReply.Tafs(
      tafs = state.tafs.map { taf ->
        val halted = state.haltOf(taf.id).halted || state.haltOf(null).halted
        TafLine(taf.id, taf.name, if (halted) Words.Taf.State.halted else taf.now, halted, paused = taf.paused && !halted)
      },
      allHalted = state.haltOf(null).halted,
      // The account's zone, the one every time on the phone is in, so the watch's chat times agree with the phone's.
      zone = bot.tafs.app.model.zone.id,
    )
  }
}

/**
 * The shortest time between two reads of tafs cloud and KIFF for the watch's Needs you: within [ms] of the last one, the phone
 * answers from what that read brought. Only the watch's reads count; the screen's own refreshes don't wait on it.
 */
class RefreshFloor(private val ms: Long = 10_000) {
  private var last: Long? = null
  private var running: kotlinx.coroutines.Deferred<Unit>? = null

  /**
   * The read under way, joined; or, past the floor, a new one started in [scope]; or none, within the floor of the last one,
   * whose read stands.
   */
  @Synchronized fun read(now: Long, scope: kotlinx.coroutines.CoroutineScope, refresh: suspend () -> Unit): kotlinx.coroutines.Deferred<Unit>? {
    running?.takeIf { it.isActive }?.let { return it }
    if (!due(now)) return null
    return scope.async { refresh() }.also { running = it }
  }

  @Synchronized fun due(now: Long): Boolean {
    val then = last
    if (then != null && now - then in 0 until ms) return false
    last = now
    return true
  }
}
