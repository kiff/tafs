package bot.tafs.app.drive

import bot.tafs.app.kiff.KiffClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Coach drives the app (docs/drive.md): what tafs cloud and the phone say to each other about a drive.

/** A drive as tafs cloud keeps it: the request it's for, in the person's words, and how it ended once it's over. */
@Serializable
data class DriveInfo(
  val id: String,
  /** Coach's chat the drive is for: the one that approved it. */
  val session: String = "",
  val task: String = "",
  val started: String? = null,
  val ended: DriveEnd? = null,
)

/** Why a drive ended (`owner_took_over`, `left_app`, `stopped`, `locked`, `signed_out`, `halted`, `done`, `idle`, `replaced`), and tafs cloud's words for it. */
@Serializable
data class DriveEnd(val why: String, val at: String? = null, val words: String? = null)

/** One step the app did, as tafs cloud recorded it: Coach's caption, and what it touched in the words on screen. */
@Serializable
data class DriveStep(val at: String = "", val line: String = "", val touched: Touched? = null)

/**
 * A drive's end as Coach's chat has it (kiff/tafs#568): a `drive` item in the thread, written by tafs cloud when the drive
 * ended, at that time. The request, why it ended in the person's words, and each step from tafs cloud's record.
 */
@Serializable
data class DriveRecap(
  val id: String,
  val session: String = "",
  val task: String = "",
  val why: String = "",
  val words: String = "",
  val steps: List<DriveStep> = emptyList(),
  val at: String,
)

/** What a step touched: the words on screen, and the taf whose screen it was. */
@Serializable
data class Touched(val words: String, val taf: String? = null)

/** `GET /v1/coach/drive`: the person's drive as it stands, and its steps for the "What I did" card. */
@Serializable
data class DriveRead(val drive: DriveInfo? = null, val steps: List<DriveStep> = emptyList())

@Serializable
internal data class DriveStarted(val drive: DriveInfo)

/** The event a look waited for, and whether it came in time. */
@Serializable
data class Waited(val event: String, val came: Boolean)

/** The screen after a step: a JPEG of the app's own windows, secret views blanked. */
@Serializable
data class DriveImage(@SerialName("media_type") val mediaType: String = "image/jpeg", val data: String)

/** One request on the drive stream: Coach's tool, its arguments as tafs cloud checked them, and how long the app has to start it. */
data class DriveRequest(val id: String, val tool: String, val args: JsonObject, val startWithinMs: Long)

/** What the drive stream sends (docs/drive.md, "The stream"). */
sealed interface DriveEvent {
  /** First: the drive the stream is for. */
  data class Opened(val drive: String) : DriveEvent

  /** One step to do, then answer. */
  data class Request(val request: DriveRequest) : DriveEvent

  /** The drive is over: stop at once, and say [words]. */
  data class Ended(val why: String, val words: String?) : DriveEvent

  /** The stream lived its time: open it again. */
  data object Reopen : DriveEvent

  companion object {
    /** One event from its name and its line of JSON; null for one the app doesn't know, or can't read. */
    fun of(name: String, data: String): DriveEvent? {
      val o = runCatching { KiffClient.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
      fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
      return when (name) {
        "drive" -> str("drive")?.let(::Opened)
        "request" -> {
          val id = str("id") ?: return null
          val tool = str("tool") ?: return null
          val args = o["args"] as? JsonObject ?: JsonObject(emptyMap())
          // A request that doesn't say how long it may wait is started at once or never: no time at all to spare.
          val within = (o["start_within_ms"] as? JsonPrimitive)?.longOrNull ?: 0L
          Request(DriveRequest(id, tool, args, within))
        }
        "ended" -> Ended(str("why") ?: "stopped", str("words")?.takeIf(String::isNotBlank))
        "reopen" -> Reopen
        else -> null
      }
    }

    /** Server-sent events, line by line: `event:` names the next `data:`; a comment line (`: ping`) is nothing. */
    fun parse(lines: Flow<String>): Flow<DriveEvent> = flow {
      var name = "message"
      lines.collect { line ->
        when {
          line.startsWith(":") -> Unit
          line.startsWith("event:") -> name = line.removePrefix("event:").trim()
          line.startsWith("data:") -> {
            of(name, line.removePrefix("data:").trim())?.let { emit(it) }
            name = "message"
          }
        }
      }
    }
  }
}

/** The app's answer to one request (docs/drive.md, "Results"). */
data class DriveAnswer(
  /** `done` or `refused`. */
  val outcome: String,
  /** The app's own words for what it did, or why not. */
  val line: String,
  val image: DriveImage? = null,
  val tree: JsonElement? = null,
  val diagnostics: JsonElement? = null,
  val touched: Touched? = null,
  val waited: Waited? = null,
) {
  val body: JsonObject
    get() = buildJsonObject {
      put("outcome", outcome)
      put("line", line)
      // By hand, as the rest is: tafs cloud refuses an image without its media_type, and the app's Json leaves out a value
      // equal to its default.
      image?.let { put("image", buildJsonObject { put("media_type", it.mediaType); put("data", it.data) }) }
      tree?.let { put("tree", it) }
      diagnostics?.let { put("diagnostics", it) }
      touched?.let { put("touched", KiffClient.json.encodeToJsonElement(Touched.serializer(), it)) }
      waited?.let { put("waited", KiffClient.json.encodeToJsonElement(Waited.serializer(), it)) }
    }

  companion object {
    fun done(line: String, touched: Touched? = null) = DriveAnswer("done", line, touched = touched)

    fun refused(line: String) = DriveAnswer("refused", line)
  }
}
