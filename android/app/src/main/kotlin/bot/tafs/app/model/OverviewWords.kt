package bot.tafs.app.model

/** Overview's lines (as iOS's `OverviewView`): today across the tafs, one line each. */
object OverviewWords {
  /** "300 US$ left today of 300 US$", from the Card in front, as its page says it: its money, else its calls. */
  fun cardLine(taf: Taf): String? {
    val card = taf.shownCards.firstOrNull() ?: return null
    card.hero?.let { return Words.Overview.hero(it.figure, it.words) }
    card.total?.let { t ->
      val used = t.used ?: return Words.Card.amountWindow(t.limit.formatted, t.window.label)
      return Words.Card.usedOf(used.formatted, t.limit.formatted, t.window.current)
    }
    card.calls?.let { c ->
      val used = c.used ?: return Words.Card.callsWindow(count = c.limit, window = c.window.label)
      return Words.Card.callsUsedOf(count = c.limit, used = used.toString(), window = c.window.current)
    }
    return null
  }

  /** The first line of its last run's report, else why its runs couldn't be read, else that it has none yet. */
  fun lastResult(report: RunReport?): String {
    val first = report?.text?.lines()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
    if (first != null) return first.replace("**", "").replace("#", "").trim()
    return report?.error ?: Words.Overview.nothingYet
  }
}
