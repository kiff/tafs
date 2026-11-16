package bot.tafs.app.watch

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.model.Answer
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.Words
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.DecisionLine
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
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
class WatchRelay(
  /** The app's model, its session restored; null when there's none to read with. */
  private val model: suspend () -> AppModel?,
  private val now: () -> Long = System::currentTimeMillis,
  /** How long the phone waits for Coach's answer before the watch is told it's on the phone: under the watch's own 10 s. */
  private val coachWaitMs: Long = 8_000,
  /** Shared by every request in the process (WearRelayService): a watch asking in a loop reads tafs cloud and KIFF at most this often. */
  private val floor: RefreshFloor = RefreshFloor(),
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
        if (request !is WatchRequest.Tafs && floor.due(now())) model.refresh()
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
    }
  }

  /**
   * Ask tafs from the wrist (docs/watch-wear.md): the words, as text, sent to tafs cloud as this phone's, signed with its device
   * key, after the AI consent given on the phone. tafs cloud gives its receipt, the same for a retry with the same id; with no
   * answer from it, the watch is told it may have arrived ([WatchReply.Unsure]) and retries with the same id.
   */
  private suspend fun ask(request: WatchRequest.Ask): WatchReply {
    val model = signedIn() ?: return WatchReply.SignedOut
    if (model.state.value.aiConsent?.given != true) return WatchReply.Failed(Words.Watch.askNeedsConsent)
    return try {
      val ask = model.watchAsk(request.clientId, request.text, request.replyTo) ?: return WatchReply.SignedOut
      WatchReply.AskTaken(lineOf(ask))
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: bot.tafs.app.kiff.CloudError) {
      refusedAsk(e)
    } catch (_: java.io.IOException) {
      // Sent, perhaps, with no answer back: only a retry with the same id tells.
      WatchReply.Unsure
    }
  }

  /** The watch's asks as tafs cloud has them, updated after [since]. */
  private suspend fun asks(since: String?): WatchReply {
    val model = signedIn() ?: return WatchReply.SignedOut
    return try {
      val asks = model.watchAsks(since) ?: return WatchReply.SignedOut
      WatchReply.AskList(asks.map(::lineOf))
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
      return WatchReply.Failed(model.state.value.chatErrors[bot.tafs.app.app.COACH_CHAT] ?: Words.Watch.openOnPhone)
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
      AskLine(ask.id, ask.clientId, ask.state, ask.taf?.name, ask.line, ask.words, ask.updated)

    /**
     * tafs cloud's refusal of an ask, in its words: asking from the watch not on yet says so; 5xx, which may have come after the
     * ask was taken, is "may have arrived", so the watch retries with the same id; anything else, tafs cloud's own words.
     */
    fun refusedAsk(e: bot.tafs.app.kiff.CloudError): WatchReply = when {
      e.code == "watch_off" -> WatchReply.Failed(Words.Watch.askNotOn)
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
     * Needs you as the phone shows it: tafs cloud's count and its words, and each held call with its taf, what it wants, KIFF's
     * amount and the reason. One this phone can't answer from here (KIFF doesn't hold its key) isn't refusable from the wrist.
     */
    fun needsYou(state: AppState): WatchReply.NeedsYou = WatchReply.NeedsYou(
      count = state.needsYouCount,
      words = state.needsYouWords,
      calls = state.held.map { call ->
        HeldLine(
          holdId = call.id,
          taf = state.taf(call.tafId)?.name ?: Words.NeedsYou.aTaf,
          wants = call.proposed.summary,
          amount = call.proposed.amount?.formatted,
          reason = call.reason.takeIf { it.isNotBlank() },
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

    /** Each taf in one line, in tafs cloud's words, and whether it, or the whole account, is halted. */
    fun tafs(state: AppState): WatchReply.Tafs = WatchReply.Tafs(
      tafs = state.tafs.map { taf ->
        val halted = state.haltOf(taf.id).halted || state.haltOf(null).halted
        TafLine(taf.id, taf.name, if (halted) Words.Taf.State.halted else taf.now, halted)
      },
      allHalted = state.haltOf(null).halted,
    )
  }
}

/**
 * The shortest time between two reads of tafs cloud and KIFF for the watch's Needs you: within [ms] of the last one, the phone
 * answers from what that read brought. Only the watch's reads count; the screen's own refreshes don't wait on it.
 */
class RefreshFloor(private val ms: Long = 10_000) {
  private var last: Long? = null

  @Synchronized fun due(now: Long): Boolean {
    val then = last
    if (then != null && now - then in 0 until ms) return false
    last = now
    return true
  }
}
