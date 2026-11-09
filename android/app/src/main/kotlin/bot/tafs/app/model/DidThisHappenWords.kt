package bot.tafs.app.model

import bot.tafs.app.kiff.UnsureCall

/** "Did this happen?" in the app's words, where KIFF sends none: the question, the answers, and why KIFF can't tell. */
object DidThisHappenWords {
  val TITLE get() = Words.Record.DidThisHappen.title
  val NO get() = Words.Record.DidThisHappen.no
  val YES get() = Words.Record.DidThisHappen.yes
  val WHAT_NO_DOES get() = Words.Record.DidThisHappen.whatNoDoes

  /** Why KIFF asks: KIFF's own line, and the app's only from a KIFF that sends none. */
  fun why(call: UnsureCall): String = call.statusWords ?: when (call.status) {
    UnsureCall.REFUSED -> Words.Record.DidThisHappen.whyRefused
    UnsureCall.PROBATION -> Words.Record.DidThisHappen.whyProbation
    else -> Words.Record.DidThisHappen.whyLost
  }

  /** The link to the vendor's own record of the call, where KIFF gives one. */
  fun record(vendor: String?): String = vendor?.takeIf { it.isNotBlank() }?.let { Words.Record.DidThisHappen.seeItAt(it) } ?: Words.Record.DidThisHappen.seeVendorRecord

  /** What the prompt says while the owner answers. */
  fun reason(happened: Boolean): String = if (happened) Words.Record.DidThisHappen.answerYes else Words.Record.DidThisHappen.answerNo
}
