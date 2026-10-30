package bot.tafs.app.model

import bot.tafs.app.kiff.BlockingRun
import bot.tafs.app.kiff.TrialSummary
import bot.tafs.app.kiff.Version
import java.time.Instant

/** What the Versions screens say, in the words iOS's has. */
object VersionWords {
  const val INTRO = "Installing a version makes it the live version, forward or back. One confirmation covers it."

  const val TRY_FIRST = "Try a version first: you chat with it under a Card of its own, while the live version keeps working."

  const val FIRST_ONLY = "One version so far. When you improve it, each change is kept here."

  /** Who made it, when, and whether it brings a new Card. A version kiff/agents wrote itself (the first, or an Upgrade's) names no one. */
  fun byline(v: Version): String {
    val by = when (v.by) {
      null, "" -> null
      "setup" -> "By Coach mode"
      else -> "By you"
    }
    return listOfNotNull(by, shownDate(v.created) + ", " + shortTime(v.created), if (v.draft == null) null else "brings a new Card").joinToString(" · ")
  }

  /** One ended trial, in a line: "Tried 21 Oct 2026 · 3 calls, 1 held · went back". */
  fun tried(t: TrialSummary): String {
    var counts = "${t.calls} call${if (t.calls == 1) "" else "s"}"
    if (t.held > 0) counts += ", ${t.held} held"
    if (t.refused > 0) counts += ", ${t.refused} refused"
    val how = when (t.why) {
      "installed" -> "installed"
      "back" -> "went back"
      "idle" -> "ended unused"
      else -> "ended"
    }
    return listOf("Tried ${shownDate(t.started)}", counts, how).joinToString(" · ")
  }

  /** Which run is in the way of installing version n, in the owner's words. */
  fun blockedWords(n: Int, runs: List<BlockingRun>, stopping: Boolean): String {
    val waiting = runs.any { it.status == "PAUSED" }
    val what = when {
      runs.size == 1 && waiting -> "a task waits on your answer"
      runs.size == 1 -> if (runs[0].trigger == "schedule") "its scheduled run is at work" else "a task is at work"
      waiting -> "${runs.size} tasks are going, one waiting on your answer"
      else -> "${runs.size} tasks are at work"
    }
    return if (stopping) "Version $n isn't installed yet: $what and hasn't stopped. Try again in a moment."
    else "Version $n can't go live while $what. Stop it and install, or wait until it's done."
  }

  /** What the Card sheet says under the Cards, before the one confirmation. */
  fun cardNote(removes: Boolean): String =
    if (removes) "While it switches, your taf doesn't run. Confirming once issues these Cards in KIFF and removes the ones this version doesn't have, then this version goes live."
    else "While it switches, your taf doesn't run. Confirming once issues these Cards in KIFF, then this version goes live."

  /** KIFF answered a confirm-as-trial with something other than "trying": never taken for a trial. */
  fun notATrial(status: String): String =
    if (status == "confirmed") "KIFF didn't start a trial: it issued these Cards as the taf's own. Check its Card tab, or install the version you had."
    else "KIFF didn't start a trial for this version (it answered $status). Nothing runs under it."

  /** What trying a version means, read before the one confirmation that starts it. */
  fun whileYouTry(n: Int): List<String> = listOf(
    "You chat with version $n in its own window. What it does is real: its calls go through KIFF under the Card below.",
    "The live version keeps working: its schedule, Card and budget don't change.",
    "The trial's Card counts from zero, so on a day your taf can spend its live Card's limits plus this one's.",
    "It ends when you install it or go back, or after 7 days unused. Its chat is then deleted, and Versions keeps one line about it.",
  )

  /** What a trial's guide says before its first message: what is new, what to try it with, that the live taf keeps working, and when it ends. */
  fun trialGuide(changed: String?, tryWith: String?, name: String, ends: Instant?): List<String> = listOfNotNull(
    changed?.let { "What's new: $it" },
    tryWith?.let { "Try it with: $it" } ?: "Give it a task that uses the change, as you would the live $name.",
    listOfNotNull(
      "$name keeps working as it is. What this version does is real, under its own Card.",
      ends?.let { "The trial ends ${shownDate(it)} if you don't use it." },
      "Install makes it the live version; Go back ends the trial.",
    ).joinToString(" "),
  )

  const val OUT_OF_DATE_CARD = "What you're asked to approve changed since it was shown. Read it again, then approve."
}
