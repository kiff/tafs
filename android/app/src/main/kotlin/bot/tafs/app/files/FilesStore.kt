package bot.tafs.app.files

import java.time.Duration
import java.time.Instant

/**
 * Files (docs/android-app.md, Files): what the account's tafs made in their runs, kept 30 days from the run for everyone on the
 * account. On since Android 125, on the owner's yes (19 Nov), once its privacy lines were live and both phones clear a shared copy
 * on sign-out (tafs#1466, #1467); tafs cloud's routes are live (tafs#1412). Turned off, nothing of Files is drawn anywhere.
 */
object FilesFeature {
  const val on = true
}

/**
 * One file, as tafs cloud lists it (`GET /v1/files`): its name and media type as delivered, its size in bytes, when it was made and
 * when it goes, and the taf (by id, and by the name it had at delivery), the run and the chat it came from.
 */
data class KeptFile(
  val id: String,
  val name: String,
  val mediaType: String,
  val size: Long,
  val created: Instant,
  val expires: Instant,
  val tafId: String? = null,
  val tafName: String? = null,
  val run: String? = null,
  val chat: String? = null,
)

/** What a file's screen previews, as tafs cloud sends it with the file (`GET /v1/files/{id}`). */
sealed interface FilePreview {
  /**
   * A text file's first 4 KB, drawn as plain text only, once cleaned ([FileNames.preview]). [truncated] is tafs cloud's
   * `preview_truncated`: the file goes on past it, and the screen says only the start is shown.
   */
  data class Text(val text: String, val truncated: Boolean = false) : FilePreview

  /** An image or a PDF: a link that lasts 60 s. Not drawn yet (docs/android-app.md, Files). */
  data class Link(val url: String) : FilePreview

  data object None : FilePreview
}

data class FileDetail(val file: KeptFile, val preview: FilePreview)

/** One page of the list, newest first, and where the next starts; none after the last. */
data class FilesPage(val files: List<KeptFile>, val next: String? = null)

/**
 * tafs cloud's four Files routes, and nothing else: the seam the screens are written against. [TafsCloudFilesStore] is the
 * real one; [StubFilesStore] stands in for the screens' tests.
 */
interface FilesStore {
  /** `GET /v1/files?taf=&run=&chat=`: the account's files, paged, newest first. */
  suspend fun list(taf: String? = null, run: String? = null, chat: String? = null, cursor: String? = null): FilesPage

  /** `GET /v1/files/{id}`: one file and its preview; null when it's gone. */
  suspend fun file(id: String): FileDetail?

  /** `GET /v1/files/{id}/download`: a link to the bytes that lasts 60 s, for this phone to fetch, never to give out. */
  suspend fun download(id: String): String?

  /** `DELETE /v1/files/{id}`: Delete now, for anyone on the account. Gone for everyone. True once tafs cloud says so. */
  suspend fun delete(id: String): Boolean

  /** The bytes behind a [download] link, fetched by this phone. */
  suspend fun fetch(url: String): ByteArray?
}

/**
 * Sample files from the sample tafs (Late parcels, Support replies, Lead follow-up): a CSV, Markdown and plain text, kept in
 * memory. Deleting one takes it away for this store's life.
 */
class StubFilesStore(now: Instant = Instant.now(), private val pageSize: Int = 50) : FilesStore {
  private val day = Duration.ofDays(1)
  private val kept = Duration.ofDays(30)

  private data class Sample(val file: KeptFile, val text: String)

  private val samples = mutableListOf(
    sample("f_1", "Tickets answered, 17 Nov.csv", "text/csv", now.minus(Duration.ofHours(2)), "taf_support", "Support replies", "run_s1", "chat_s1",
      "Item,Done,By\n#7781,09:04,Support replies\n#7783,09:06,Support replies\n#7790,09:11,Support replies\n"),
    sample("f_2", "Parcels without a scan.csv", "text/csv", now.minus(Duration.ofHours(3)), "taf_parcels", "Late parcels", "run_p1", "chat_p1",
      "Order,Last scan,Days\n#5098,07 Nov,10\n#5124,15 Nov,2\n"),
    sample("f_3", "Late parcels, 17 Nov.md", "text/markdown", now.minus(Duration.ofHours(3)), "taf_parcels", "Late parcels", "run_p1", "chat_p1",
      "# Late parcels, 17 Nov\n\n- Order #5120: told the customer the parcel is two days late.\n- Order #5098: no scan for 10 days; a new parcel waits for your yes.\n"),
    sample("f_4", "Nord Atelier call notes.txt", "text/plain", now.minus(day).minus(Duration.ofHours(1)), "taf_leads", "Lead follow-up", "run_l1", "chat_l1",
      "Call with Nord Atelier, Thursday 10:00.\nThey want a quote for 40 chairs by Friday.\n"),
  )

  private fun sample(id: String, name: String, type: String, created: Instant, taf: String, tafName: String, run: String, chat: String, text: String) =
    Sample(KeptFile(id, name, type, text.toByteArray().size.toLong(), created, created.plus(kept), taf, tafName, run, chat), text)

  override suspend fun list(taf: String?, run: String?, chat: String?, cursor: String?): FilesPage {
    val all = samples.map { it.file }
      .filter { (taf == null || it.tafId == taf) && (run == null || it.run == run) && (chat == null || it.chat == chat) }
      .sortedByDescending { it.created }
    val from = cursor?.toIntOrNull() ?: 0
    val page = all.drop(from).take(pageSize)
    return FilesPage(page, (from + page.size).takeIf { it < all.size }?.toString())
  }

  override suspend fun file(id: String): FileDetail? =
    samples.firstOrNull { it.file.id == id }?.let { FileDetail(it.file, FilePreview.Text(it.text.take(PREVIEW_BYTES), truncated = it.text.length > PREVIEW_BYTES)) }

  override suspend fun download(id: String): String? = samples.firstOrNull { it.file.id == id }?.let { STUB_LINK + id }

  override suspend fun delete(id: String): Boolean = samples.removeAll { it.file.id == id }

  override suspend fun fetch(url: String): ByteArray? =
    samples.firstOrNull { STUB_LINK + it.file.id == url }?.text?.toByteArray()

  companion object {
    private const val STUB_LINK = "stub:files/"

    /** How much of a text file tafs cloud previews. */
    const val PREVIEW_BYTES = 4096
  }
}
