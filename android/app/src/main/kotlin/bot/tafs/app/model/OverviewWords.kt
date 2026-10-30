package bot.tafs.app.model

/** Overview's lines (as iOS's `OverviewView`): today across the tafs, one line each. */
object OverviewWords {
  /** "300 US$ left today of 300 US$", from the Card in front, as its page says it: its money, else its calls. */
  fun cardLine(taf: Taf): String? {
    val card = taf.shownCards.firstOrNull() ?: return null
    card.hero?.let { return "${it.figure} ${it.words}" }
    card.total?.let { t ->
      val used = t.used ?: return "${t.limit.formatted} ${t.window.label}"
      return "${used.formatted} of ${t.limit.formatted} ${t.window.current}"
    }
    card.calls?.let { c ->
      val used = c.used ?: return "${c.limit} calls ${c.window.label}"
      return "$used of ${c.limit} calls ${c.window.current}"
    }
    return null
  }

  /** The first line of its last run's report, else why its runs couldn't be read, else that it has none yet. */
  fun lastResult(report: RunReport?): String {
    val first = report?.text?.lines()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
    if (first != null) return first.replace("**", "").replace("#", "").trim()
    return report?.error ?: "Nothing yet"
  }
}
