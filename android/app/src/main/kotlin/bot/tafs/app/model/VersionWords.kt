package bot.tafs.app.model

import bot.tafs.app.kiff.TrialSummary
import bot.tafs.app.kiff.Version
import java.time.Instant

/** What the Versions screens say, in the words iOS's has. */
object VersionWords {
  val INTRO get() = Words.Version.intro

  val TRY_FIRST get() = Words.Version.tryFirst

  val FIRST_ONLY get() = Words.Version.firstOnly

  /** Who made it, in tafs cloud's words, when, and whether it brings a new Card. A version kiff/agents wrote itself names no one. */
  fun byline(v: Version): String =
    listOfNotNull(v.byWords, shownDate(v.created) + ", " + shortTime(v.created), if (v.draft == null) null else Words.Version.bringsNewCard).joinToString(" · ")

  /** One ended trial, in a line: "Tried 21 Oct 2026 · 3 calls, 1 held · went back". */
  fun tried(t: TrialSummary): String {
    val counts = listOfNotNull(
      Words.Version.Tried.calls(count = t.calls),
      Words.Version.Tried.held(count = t.held).takeIf { t.held > 0 },
      Words.Version.Tried.refused(count = t.refused).takeIf { t.refused > 0 },
    ).joinToString(", ")
    val how = when (t.why) {
      "installed" -> Words.Version.Tried.installed
      "back" -> Words.Version.Tried.wentBack
      "idle" -> Words.Version.Tried.endedUnused
      else -> Words.Version.Tried.ended
    }
    return listOf(Words.Version.Tried.on(shownDate(t.started)), counts, how).joinToString(" · ")
  }

  /** What the Card sheet says under the Cards, before the one confirmation. */
  fun cardNote(removes: Boolean): String =
    if (removes) Words.Version.CardNote.removes else Words.Version.CardNote.issues

  /** KIFF answered a confirm-as-trial with something other than "trying": never taken for a trial. */
  fun notATrial(status: String): String =
    if (status == "confirmed") Words.Version.NotATrial.confirmed else Words.Version.NotATrial.other(status)

  /** What trying a version means, read before the one confirmation that starts it. */
  fun whileYouTry(n: Int): List<String> = listOf(
    Words.Version.WhileYouTry.chat(n.toString()),
    Words.Version.WhileYouTry.liveKeepsWorking,
    Words.Version.WhileYouTry.countsFromZero,
    Words.Version.WhileYouTry.ends,
  )

  /** What a trial's guide says before its first message: what is new, what to try it with, that the live taf keeps working, and when it ends. */
  fun trialGuide(changed: String?, tryWith: String?, name: String, ends: Instant?): List<String> = listOfNotNull(
    changed?.let { Words.Version.Guide.whatsNew(it) },
    tryWith?.let { Words.Version.Guide.tryWith(it) } ?: Words.Version.Guide.giveTask(name),
    listOfNotNull(
      Words.Version.Guide.keepsWorking(name),
      ends?.let { Words.Version.Guide.trialEnds(shownDate(it)) },
      Words.Version.Guide.installOrBack,
    ).joinToString(" "),
  )

  val OUT_OF_DATE_CARD get() = Words.Version.outOfDateCard
}
