package bot.tafs.app.model

/**
 * The owner's answers to KIFF's held calls, kept by their record id until KIFF records the outcome
 * (kiff/tafs#138): a refresh just after an answer still reads the call as held, and the Record keeps the
 * owner's words for it.
 */
object Decisions {
  /**
   * The record to show, and the answers KIFF now has an outcome for. A call KIFF still has as held reads as
   * the owner's answer; one it has an outcome for keeps the owner's words for it when the outcome is what
   * the owner decided, and is settled (the owner's line goes) when it is not.
   */
  fun merge(kiff: List<RecordEntry>, decided: Map<String, RecordEntry>): Pair<List<RecordEntry>, Set<String>> {
    val out = kiff.toMutableList()
    val settled = mutableSetOf<String>()
    for ((id, mine) in decided) {
      val i = out.indexOfFirst { it.id == id }
      when {
        i < 0 -> out += mine
        out[i].kind == RecordEntry.Kind.held -> out[i] = mine
        // KIFF's outcome says what the owner decided: the line keeps the owner's words for it
        // ("Refused by you · 08:56 · …"), so it doesn't change on screen (kiff/tafs#206).
        out[i].kind == mine.kind -> out[i] = out[i].copy(title = mine.title)
        else -> settled += id
      }
    }
    return out.sortedByDescending { it.at } to settled
  }
}
