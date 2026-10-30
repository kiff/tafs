package bot.tafs.app.kiff

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A call that acted and went through before a run failed (kiff/agents#249): by tool and what it acted on, never its input. */
data class DoneCall(val tool: String, val resource: String? = null, val invocation: String? = null)

/** What a streamed message's run does, as tafs cloud relays it (docs/chat.md, "The answer as it comes"). */
sealed interface StreamEvent {
  /** The chat the message went into, first, and the run that answers it: for Stop and steering. */
  data class Session(val session: String, val newSession: Boolean, val run: String?, val messageId: String? = null) : StreamEvent
  /** More of the answer, as it is written. */
  data class Text(val text: String) : StreamEvent
  /** More of what the taf is thinking. */
  data class Thinking(val text: String) : StreamEvent
  /** A step begins: the tool, what it's doing in words when tafs cloud says, and the call's id, which its `step` names. */
  data class Activity(val tool: String, val step: String?, val call: String?) : StreamEvent
  /** That step ended: "done", "error" or "not_made" (refused back unmade beside a read). */
  data class Step(val call: String, val status: String) : StreamEvent
  /** KIFF held the call just made: its decision's id and the call's. */
  data class Held(val exception: String, val invocation: String) : StreamEvent
  /** The run read a message the owner wrote meanwhile: the thread has it. */
  data object Taken : StreamEvent
  /** A run picked back up wrote a message: the thread has it. */
  data object Said : StreamEvent
  /** A run picked back up went on after a held call was answered: a fresh live answer starts. */
  data object Part : StreamEvent
  /** The agent showed a component: the thread has it. */
  data object Component : StreamEvent
  /**
   * The one line to show while it works (docs/chat.md, "The live line"): `thinking`, `working` with the step's [words],
   * `waiting_for_you` or `done`. tafs cloud sends it only on a change and holds each a second, so the app draws it as it comes.
   */
  data class State(val state: String, val words: String?) : StreamEvent
  /** The run stopped: its status (COMPLETED, ERROR, CANCELLED…), why, and for a failed run what went through before it stopped. */
  data class End(val status: String, val stop: String, val done: List<DoneCall> = emptyList()) : StreamEvent
  /** The stream broke on tafs cloud's side; the thread has the rest. */
  data object Broken : StreamEvent

  companion object {
    /** One event, from its name and its line of JSON; null for one the app doesn't know. */
    fun of(name: String, data: String): StreamEvent? {
      val o = runCatching { KiffClient.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
      fun str(key: String) = (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
      return when (name) {
        "session" -> str("session")?.let {
          val message = (o["message"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
          Session(it, (o["new_session"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false, str("run"), message)
        }
        "text" -> str("text")?.let(::Text)
        "thinking" -> str("text")?.let(::Thinking)
        "activity" -> str("tool")?.let { Activity(it, str("step"), str("call_id")) }
        "step" -> str("call_id")?.let { Step(it, str("status") ?: "done") }
        "held" -> Held(str("exception").orEmpty(), str("invocation").orEmpty())
        "taken" -> Taken
        "said" -> Said
        "part" -> Part
        "component" -> Component
        "end" -> End(
          str("status").orEmpty(),
          str("stop").orEmpty(),
          (o["done"] as? JsonArray).orEmpty().mapNotNull { c ->
            val call = c as? JsonObject ?: return@mapNotNull null
            val tool = call["tool"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            DoneCall(tool, call["resource"]?.jsonPrimitive?.contentOrNull, call["invocation"]?.jsonPrimitive?.contentOrNull)
          },
        )
        "state" -> str("state")?.let { State(it, str("words")?.takeIf(String::isNotBlank)) }
        "error" -> Broken
        else -> null
      }
    }

    /** Server-sent events, line by line: `event: name` sets the next data's name, `data: {…}` is one event. */
    fun parse(lines: Flow<String>): Flow<StreamEvent> = flow {
      var name = "message"
      lines.collect { line ->
        when {
          line.startsWith("event:") -> name = line.removePrefix("event:").trim()
          line.startsWith("data:") -> of(name, line.removePrefix("data:").trim())?.let { emit(it) }
        }
      }
    }
  }
}
