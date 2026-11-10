package bot.tafs.app.model

/** Exporting the person's own chats (docs/data.md, Your own copy): the row, the wait, and a failure. */
object ExportWords {
  val ROW get() = Words.Export.row
  val PREPARING get() = Words.Export.preparing
  val TRY_AGAIN get() = Words.Common.tryAgain

  /** The two files: the data, and the same as text. */
  const val JSON_FILE = "tafs-chats.json"
  const val TEXT_FILE = "tafs-chats.txt"

  /** The files couldn't be written on the phone or handed to another app. */
  val NOT_OFFERED get() = Words.Export.notOffered

  fun notExported(why: String) = Words.Export.notExported(why)
}

/**
 * The export, as the two files it saves: [json] is tafs cloud's answer without `readable`, and [text] is `readable`. The phone
 * reads nothing else in it.
 */
data class ChatExport(val json: String, val text: String) {
  companion object {
    fun of(body: ByteArray): ChatExport {
      val all = bot.tafs.app.kiff.KiffClient.json.parseToJsonElement(String(body)) as kotlinx.serialization.json.JsonObject
      val readable = (all["readable"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
      val data = kotlinx.serialization.json.JsonObject(all - "readable")
      return ChatExport(PRETTY.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), data), readable)
    }

    private val PRETTY = kotlinx.serialization.json.Json { prettyPrint = true }
  }
}

/** How an export went: ready to save, or not, with why. */
sealed interface ExportResult {
  data class Ready(val export: ChatExport) : ExportResult
  data class NotExported(val why: String) : ExportResult
}
