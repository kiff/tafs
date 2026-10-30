package bot.tafs.app.model

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A file the person attached to a message, as the thread names it: never its bytes (docs/attachments.md). */
@Serializable
data class AttachmentRef(
  val id: String,
  /** How the model reads it: "image", "pdf", "text", "word" or "excel". */
  val kind: String = "",
  @SerialName("media_type") val mediaType: String,
  val name: String,
  val size: Long = 0,
)

/** A file picked for the chat's next message, while it goes up. It is sent with the message once it's ready; one that failed says why. */
data class PendingFile(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val mediaType: String,
  /** A small JPEG for the tray: a photo's, else null. */
  val thumbnail: ByteArray? = null,
  val state: State = State.Uploading,
) {
  sealed interface State {
    data object Uploading : State
    data class Ready(val ref: AttachmentRef) : State
    data class Failed(val why: String) : State
  }

  val ref: AttachmentRef? get() = (state as? State.Ready)?.ref

  override fun equals(other: Any?): Boolean = other is PendingFile && other.id == id && other.state == state && other.name == name
  override fun hashCode(): Int = id.hashCode()
}

/** A file made ready to attach: its bytes as tafs sends them, its media type and its name. */
class PreparedFile(val name: String, val mediaType: String, val data: ByteArray, val thumbnail: ByteArray? = null)

/** A file that can't be attached, in words the person reads. */
class FileProblem(message: String) : Exception(message)

/**
 * What a person may attach, and making it ready (kiff/tafs#209): the types and limits kiff/agents keeps, checked here first
 * so a file over its limit is refused before upload. Photos are redrawn by the phone (`PhotoPrep`).
 */
object FilePrep {
  const val PER_MESSAGE = 5

  /** tafs cloud doesn't take the file: its kind or its size. Another file may go. */
  const val NOT_TAKEN = "tafs can't attach this file: it isn't a kind or size it takes. Pick another one."

  /** A photo's long edge, at most: the most the model reads. */
  const val LONG_EDGE = 2576

  private const val MB = 1L shl 20
  const val WORD = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
  const val EXCEL = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

  /** The media types tafs takes, with the most each may weigh. */
  val LIMITS: Map<String, Long> = mapOf(
    "image/jpeg" to 10 * MB, "image/png" to 10 * MB, "image/gif" to 10 * MB, "image/webp" to 10 * MB,
    "application/pdf" to 25 * MB,
    "text/plain" to 2 * MB, "text/csv" to 2 * MB, "text/markdown" to 2 * MB,
    WORD to 2 * MB, EXCEL to 2 * MB,
  )

  /** The kinds of file the file picker offers. */
  val PICKABLE: Array<String> = arrayOf("image/*", "application/pdf", "text/plain", "text/csv", "text/comma-separated-values", "text/markdown", "text/x-markdown", WORD, EXCEL)

  /** The most any file may weigh: one over it is refused before it's read. */
  val LARGEST: Long = LIMITS.values.max()

  /**
   * The media type tafs sends a file as, from what the phone says it is and its name; null when it can't be attached.
   * Images are sent as JPEG after the phone redraws them, so any image type the phone reads counts.
   */
  fun mediaType(phoneSays: String?, name: String): String? {
    val type = phoneSays?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim().orEmpty()
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
      type.startsWith("image/") -> type
      type == "application/pdf" || ext == "pdf" -> "application/pdf"
      type == "text/csv" || type == "text/comma-separated-values" || ext == "csv" -> "text/csv"
      type == "text/markdown" || type == "text/x-markdown" || ext == "md" || ext == "markdown" -> "text/markdown"
      type == WORD || ext == "docx" -> WORD
      type == EXCEL || ext == "xlsx" -> EXCEL
      type == "text/plain" || ext == "txt" -> "text/plain"
      ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif") -> "image/$ext"
      else -> null
    }
  }

  /** Whether the phone redraws it as a photo before it goes. */
  fun isImage(mediaType: String): Boolean = mediaType.startsWith("image/")

  /** A file as it is, not a photo: refused when it can't be attached or is over its limit. */
  fun file(name: String, data: ByteArray, mediaType: String?): PreparedFile {
    val type = mediaType ?: throw FileProblem("$name can't be attached. Photos, PDFs, text, CSV, Markdown, Word and Excel files can.")
    check(name, data.size.toLong(), type)
    return PreparedFile(name, type, data)
  }

  /** Refuses a file over its type's limit, saying which. */
  fun check(name: String, size: Long, mediaType: String) {
    val limit = LIMITS[mediaType] ?: throw FileProblem("$name can't be attached.")
    if (size > limit) throw FileProblem("$name is ${megabytes(size)}; the most is ${megabytes(limit)}.")
  }

  fun megabytes(n: Long): String = String.format(Locale.ROOT, "%.1f MB", n.toDouble() / MB)

  /** A sent file's size, as its line says it: kilobytes under a megabyte. */
  fun sizeWords(n: Long): String = if (n < MB) "${maxOf(1, (n + 512) / 1024)} KB" else megabytes(n)

  /** A file's SHA-256, in lowercase hex, as its upload is signed for. */
  fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

  /** A photo's name as it goes: its own, as a JPEG, or "Photo.jpg". */
  fun photoName(name: String): String = name.substringBeforeLast('.').ifBlank { "Photo" } + ".jpg"

  /** The size a photo is drawn at: its long edge at most [edge], never larger than it was. */
  fun scaledSize(width: Int, height: Int, edge: Int): Pair<Int, Int> {
    val long = maxOf(width, height)
    if (long <= 0) return width to height
    val ratio = minOf(1.0, edge.toDouble() / long)
    return maxOf(1, Math.round(width * ratio).toInt()) to maxOf(1, Math.round(height * ratio).toInt())
  }

  /** Which icon a file shows without a thumbnail. */
  enum class Icon { photo, pdf, table, text }

  fun icon(mediaType: String): Icon = when {
    mediaType.startsWith("image/") -> Icon.photo
    mediaType == "application/pdf" -> Icon.pdf
    mediaType == "text/csv" || mediaType == EXCEL -> Icon.table
    else -> Icon.text
  }
}
