package bot.tafs.app.files

import bot.tafs.app.kiff.TafsCloud
import bot.tafs.app.kiff.WireFile
import java.time.Instant

/**
 * [FilesStore] over tafs cloud's four Files routes (docs/api.md, contracts/files-*.json): what the screens read when Files is on.
 * Pages go by the last id read (`?after=`); a file's preview is plain text, and its bytes are read from the short link by this
 * phone alone.
 */
class TafsCloudFilesStore(private val cloud: TafsCloud) : FilesStore {
  override suspend fun list(taf: String?, run: String?, chat: String?, cursor: String?): FilesPage {
    val page = cloud.files(taf = taf, run = run, chat = chat, after = cursor)
    return FilesPage(page.files.mapNotNull(::kept), page.next)
  }

  override suspend fun file(id: String): FileDetail? {
    val f = cloud.file(id) ?: return null
    val file = kept(f) ?: return null
    return FileDetail(file, f.preview?.let { FilePreview.Text(it, truncated = f.previewTruncated) } ?: FilePreview.None)
  }

  override suspend fun download(id: String): String? = cloud.fileDownload(id)?.url

  override suspend fun delete(id: String): Boolean = cloud.deleteFile(id)

  override suspend fun fetch(url: String): ByteArray? = cloud.signedBytes(url)

  companion object {
    /** A file as the screens hold it; one whose times can't be read is left out, never drawn with made-up ones. */
    fun kept(f: WireFile): KeptFile? {
      val created = runCatching { Instant.parse(f.created) }.getOrNull() ?: return null
      val expires = runCatching { Instant.parse(f.expires) }.getOrNull() ?: return null
      return KeptFile(f.id, f.name, f.mediaType, f.size, created, expires, f.taf, f.tafName, f.run, f.chat)
    }
  }
}
