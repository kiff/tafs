package bot.tafs.app.model

/** Exporting the person's own chats (docs/data.md, Your own copy): the row, the wait, and a failure. */
object ExportWords {
  const val ROW = "Export my chats"
  const val PREPARING = "Preparing your chats…"
  const val TRY_AGAIN = "Try again"

  /** The two files: the data, and the same as text. */
  const val JSON_FILE = "tafs-chats.json"
  const val TEXT_FILE = "tafs-chats.txt"

  fun notExported(why: String) = "Your chats weren't exported. $why"
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
