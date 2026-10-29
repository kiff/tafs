package bot.tafs.app.app

import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.Taf
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Keeps the sample on the phone between launches, as one JSON file. */
class SampleStore(private val file: File) {
  @Serializable
  data class Snapshot(
    val tafs: List<Taf>,
    val held: List<HeldCall>,
    val record: List<RecordEntry>,
    val scriptPosition: Map<String, Int>,
    val templateOf: Map<String, String>,
  )

  private val json = Json { ignoreUnknownKeys = true }

  fun load(): Snapshot? = runCatching { json.decodeFromString<Snapshot>(file.readText()) }.getOrNull()

  fun save(snapshot: Snapshot) {
    runCatching {
      // Written beside it and moved over, so a file is never half written.
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText(json.encodeToString(Snapshot.serializer(), snapshot))
      if (!tmp.renameTo(file)) {
        file.writeText(tmp.readText())
        tmp.delete()
      }
    }
  }

  fun clear() {
    file.delete()
  }
}
