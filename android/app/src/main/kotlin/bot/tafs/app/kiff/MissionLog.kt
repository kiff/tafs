package bot.tafs.app.kiff

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A mission's log, as tafs cloud reads it out (`GET /v1/missions/{id}/log?before=`, Missions v2.1 §6): read only, newest
 * first, a page at a time. Each entry says when, by whom (a taf, the lead Coach, or the person), its kind and one or two
 * sentences, with what it refers to and a value where there is one. A removed taf's entries stay, marked removed. The app
 * draws them as they come and decides nothing from them.
 */
@Serializable
data class MissionLogPage(
  val items: List<MissionLogItem> = emptyList(),
  /** The id to read the next, older page from; none when this is the oldest. */
  val before: String? = null,
)

@Serializable
data class MissionLogItem(
  val id: String = "",
  val at: String = "",
  val by: MissionLogBy = MissionLogBy(),
  /** Who, in tafs cloud's words, when it says more than a name: "Bills in → Payouts" on a hand-off. */
  @SerialName("by_words") val byWords: String? = null,
  /** delivered, handed, stuck, decided or note: the colour the app draws it in. */
  val kind: String = "",
  /** The kind in the person's words ("delivered", "handed"…), from tafs cloud. */
  @SerialName("kind_words") val kindWords: String? = null,
  val words: String = "",
  val evidence: List<MissionLogEvidence> = emptyList(),
  /** "€4,120 sorted", "€1,480 due". */
  val value: String? = null,
  /** By a taf removed from the mission since: kept in the person's log, greyed. */
  val removed: Boolean = false,
)

/** Who wrote an entry: a taf (by its name, unless it was removed), the lead (Coach), or the person. */
@Serializable
data class MissionLogBy(val kind: String = "", val name: String? = null)

/** What an entry refers to: a run, an item, a hold, by tafs cloud's words for it ("bill 0412"), else by its kind. */
@Serializable
data class MissionLogEvidence(val kind: String = "", val id: String = "", val words: String? = null)

object MissionLogs {
  /** The entries worth drawing: one with no words says nothing; one already shown (a page read twice) is shown once. */
  fun drawn(pages: List<MissionLogPage>): List<MissionLogItem> =
    pages.flatMap { it.items }.filter { it.words.isNotBlank() }.distinctBy { it.id.ifEmpty { it.at + it.words } }

  /** Who an entry is by: tafs cloud's words for it ("Bills in → Payouts", "Coach", "You"), else the taf's name, else nothing. */
  fun byName(item: MissionLogItem): String? = item.byWords?.takeIf { it.isNotBlank() } ?: item.by.name?.takeIf { it.isNotBlank() }

  /** When an entry was written, in the phone's own time and language ("14 Nov, 07:10"); as tafs cloud sent it if unreadable. */
  fun shownAt(at: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), locale: java.util.Locale = java.util.Locale.getDefault()): String =
    runCatching { java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", locale).format(java.time.Instant.parse(at).atZone(zone)) }.getOrDefault(at)

  /** What an evidence chip says: tafs cloud's words, else its kind ("run", "hold"). */
  fun evidenceWords(evidence: MissionLogEvidence): String = evidence.words?.takeIf { it.isNotBlank() } ?: evidence.kind
}
