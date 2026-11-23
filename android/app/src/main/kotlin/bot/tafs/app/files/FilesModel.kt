package bot.tafs.app.files

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the Files list is shown: every file newest first by day, each taf with its files, or each run with its files. */
enum class FilesView { All, ByTaf, ByRun }

/** When a file goes, in the account's days: today (or already past), tomorrow, or in so many days. */
sealed interface Goes {
  data object Today : Goes
  data object Tomorrow : Goes
  data class InDays(val days: Long) : Goes

  companion object {
    /** [expires] against [today], both in the account's zone [zone]: a day boundary is the account's midnight. */
    fun of(expires: Instant, today: LocalDate, zone: ZoneId): Goes =
      when (val days = ChronoUnit.DAYS.between(today, expires.atZone(zone).toLocalDate())) {
        in Long.MIN_VALUE..0L -> Today
        1L -> Tomorrow
        else -> InDays(days)
      }
  }
}

/**
 * A file's name and its preview as the app draws them: plain text only, never a path, and no control or direction
 * characters, so a name like "report‮fdp.exe" can't pass for another, and a preview can't turn its lines around.
 */
object FileNames {
  /** U+202A–U+202E (embeddings and overrides), U+2066–U+2069 (isolates), and the marks U+200E, U+200F and U+061C. */
  private fun isDirection(c: Char): Boolean = c in '‪'..'‮' || c in '⁦'..'⁩' || c == '‎' || c == '‏' || c == '؜'

  private fun isControl(c: Char): Boolean = Character.getType(c) == Character.CONTROL.toInt() || c == ' ' || c == ' ' || c == '﻿'

  /** The name alone: what follows the last `/` or `\`, with control and direction characters taken out; [fallback] if nothing's left. */
  fun name(raw: String, fallback: String): String {
    val last = raw.substringAfterLast('/').substringAfterLast('\\')
    val clean = last.filterNot { isDirection(it) || isControl(it) }.trim()
    return clean.ifEmpty { fallback }
  }

  /** A preview's text: lines and tabs kept, every other control or direction character taken out, Windows' line ends made one. */
  fun preview(raw: String): String =
    raw.replace("\r\n", "\n").replace('\r', '\n').filterNot { it != '\n' && it != '\t' && (isDirection(it) || isControl(it)) }

  /** The file's type as a short tag: its name's extension, upper case ("CSV"); else the media type's subtype. */
  fun type(file: KeptFile): String {
    val ext = name(file.name, "").substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
    return (ext ?: file.mediaType.substringAfter('/').substringBefore(';').substringBefore('+')).uppercase()
  }
}

/** The files with what they're grouped under: a day, a taf or a run, in the order shown. */
data class FileGroup(val key: String, val files: List<KeptFile>)

object FileGroups {
  /** Narrowed to one taf, by its id; every file with none. */
  fun forTaf(files: List<KeptFile>, taf: String?): List<KeptFile> = if (taf == null) files else files.filter { it.tafId == taf }

  /** Newest first, under the day each was made, in the account's zone. */
  fun byDay(files: List<KeptFile>, zone: ZoneId): List<Pair<LocalDate, List<KeptFile>>> =
    files.sortedByDescending { it.created }.groupBy { it.created.atZone(zone).toLocalDate() }.toList()

  /** Each taf, the one with the newest file first, with its files newest first. A file with no taf is under its own name. */
  fun byTaf(files: List<KeptFile>): List<FileGroup> =
    files.sortedByDescending { it.created }.groupBy { it.tafId ?: "" }.map { (k, v) -> FileGroup(k, v) }

  /** Each run, the newest first, with its files. */
  fun byRun(files: List<KeptFile>): List<FileGroup> =
    files.sortedByDescending { it.created }.groupBy { it.run ?: it.id }.map { (k, v) -> FileGroup(k, v) }

  /** The tafs that have files, newest first, by id with their name at delivery: the chips over the list. */
  fun tafs(files: List<KeptFile>): List<Pair<String, String>> =
    byTaf(files).mapNotNull { g -> g.files.first().let { f -> f.tafId?.let { it to (f.tafName ?: "") } } }

  /** When a group's files are kept until: the latest of them. */
  fun keptUntil(files: List<KeptFile>): Instant? = files.maxOfOrNull { it.expires }
}

/** Files on screen: the list as read, how it's shown, the file open, and the question or line over it. */
data class FilesState(
  /** The account's files, newest first; null until read. */
  val files: List<KeptFile>? = null,
  val error: String? = null,
  /** The Files screen is open. */
  val showing: Boolean = false,
  val view: FilesView = FilesView.All,
  /** The taf chip chosen; null is All tafs. */
  val taf: String? = null,
  val open: FileDetail? = null,
  /** Delete now tapped: asked first, nothing sent yet. */
  val asking: KeptFile? = null,
  /** What came of the last thing done: Saved to this device, Deleted for everyone. */
  val line: String? = null,
  /** The file was opened on its own (a chat's chip): closing it closes Files, back to where it was opened. */
  val alone: Boolean = false,
)

/**
 * Files' state and what it does, over a [FilesStore]: read the list, open a file, its bytes for Download and Share, and Delete
 * now, asked first. The words come from the screen; errors are said in the app's own words ([errorWords]).
 */
class FilesModel(
  private val store: FilesStore,
  private val errorWords: (Throwable) -> String = { it.message.orEmpty() },
) {
  private val _state = MutableStateFlow(FilesState())
  val state: StateFlow<FilesState> = _state.asStateFlow()

  /** Every page of the account's files, newest first. A read that fails leaves what was shown, with why. */
  suspend fun refresh() {
    try {
      val files = mutableListOf<KeptFile>()
      var cursor: String? = null
      do {
        val page = store.list(cursor = cursor)
        files += page.files
        cursor = page.next
      } while (cursor != null)
      _state.value = _state.value.copy(files = files.sortedByDescending { it.created }, error = null)
    } catch (e: Exception) {
      if (e is kotlinx.coroutines.CancellationException) throw e
      _state.value = _state.value.copy(error = errorWords(e))
    }
  }

  /** Opens Files, on one taf's files when [taf] is given, or straight on one file. */
  fun show(taf: String? = null, view: FilesView = FilesView.All) {
    _state.value = _state.value.copy(showing = true, taf = taf, view = view, open = null, asking = null, line = null, alone = false)
  }

  fun close() {
    _state.value = _state.value.copy(showing = false, open = null, asking = null, line = null, alone = false)
  }

  fun view(view: FilesView) {
    _state.value = _state.value.copy(view = view)
  }

  fun pickTaf(taf: String?) {
    _state.value = _state.value.copy(taf = taf)
  }

  /** One file and its preview, from the list or, [alone], from a chat's chip; a file that's gone is taken off the list. */
  suspend fun open(id: String, alone: Boolean = false) {
    try {
      val detail = store.file(id)
      _state.value = if (detail == null) {
        _state.value.copy(files = _state.value.files?.filterNot { it.id == id }, open = null)
      } else {
        _state.value.copy(open = detail, showing = true, line = null, asking = null, alone = alone)
      }
    } catch (e: Exception) {
      if (e is kotlinx.coroutines.CancellationException) throw e
      _state.value = _state.value.copy(error = errorWords(e))
    }
  }

  /** Back from a file: to the list, or, opened on its own, out of Files. */
  fun closeFile() {
    if (_state.value.alone) return close()
    _state.value = _state.value.copy(open = null, asking = null, line = null)
  }

  /** The file's bytes, fetched by this phone from tafs cloud's 60 s link, for Download and Share; null when they can't be. */
  suspend fun bytes(id: String): ByteArray? = try {
    store.download(id)?.let { store.fetch(it) }
  } catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    _state.value = _state.value.copy(error = errorWords(e))
    null
  }

  /** Saved, or Deleted: the line shown after it. */
  fun said(line: String?) {
    _state.value = _state.value.copy(line = line)
  }

  /** Delete now tapped: asked first. Nothing is sent. */
  fun askDelete(file: KeptFile) {
    _state.value = _state.value.copy(asking = file)
  }

  fun cancelDelete() {
    _state.value = _state.value.copy(asking = null)
  }

  /**
   * Delete now, confirmed: tafs cloud deletes it for everyone on the account; then it's off the list, and its screen closes with
   * [deleted] said. Nothing is sent unless it was asked first, and only for the file asked about.
   */
  suspend fun confirmDelete(deleted: String): Boolean {
    val file = _state.value.asking ?: return false
    _state.value = _state.value.copy(asking = null)
    val gone = try {
      store.delete(file.id)
    } catch (e: Exception) {
      if (e is kotlinx.coroutines.CancellationException) throw e
      _state.value = _state.value.copy(error = errorWords(e))
      false
    }
    if (gone) {
      val s = _state.value
      _state.value = s.copy(files = s.files?.filterNot { it.id == file.id }, open = s.open?.takeIf { it.file.id != file.id }, line = deleted)
    }
    return gone
  }
}
