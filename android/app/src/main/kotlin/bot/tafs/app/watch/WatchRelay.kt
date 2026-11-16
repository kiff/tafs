package bot.tafs.app.watch

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.model.Words
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest

/**
 * The phone's answers to the Wear OS watch (docs/watch-wear.md). Every message passes [WatchLink.accept] before anything is read
 * or signed; then the phone reads with its own session, through the app's one [AppModel], and answers in its own words. The
 * watch draws them as they come.
 *
 * This step answers the reads only. Refuse, Halt and Coach from the wrist come later: until then they're answered with Open on
 * phone, and nothing is signed or sent.
 */
class WatchRelay(
  /** The app's model, its session restored; null when there's none to read with. */
  private val model: suspend () -> AppModel?,
  private val now: () -> Long = System::currentTimeMillis,
) {
  suspend fun answer(path: String, data: ByteArray): WatchReply {
    val request = when (val accepted = WatchLink.accept(path, data, now())) {
      is WatchLink.Accepted.No -> return WatchReply.Refused(accepted.reason)
      is WatchLink.Accepted.Yes -> accepted.request
    }
    return when (request) {
      is WatchRequest.NeedsYou, is WatchRequest.Tafs -> {
        val model = model() ?: return WatchReply.SignedOut
        if (model.state.value.mode != Mode.kiff) return WatchReply.SignedOut
        // Read again now: the watch shows what tafs cloud and KIFF say, not what the phone last drew.
        if (request is WatchRequest.NeedsYou) model.refresh()
        val state = model.state.value
        if (state.mode != Mode.kiff) WatchReply.SignedOut else if (request is WatchRequest.NeedsYou) needsYou(state) else tafs(state)
      }
      is WatchRequest.Refuse, is WatchRequest.Halt, is WatchRequest.Coach -> WatchReply.Failed(Words.Watch.openOnPhone)
    }
  }

  companion object {
    /**
     * Needs you as the phone shows it: tafs cloud's count and its words, and each held call with its taf, what it wants, KIFF's
     * amount and the reason. None may be refused from the wrist yet.
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
          refusable = false,
        )
      },
    )

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
