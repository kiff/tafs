package bot.tafs.app.kiff

import bot.tafs.app.model.Knob
import bot.tafs.app.model.KnobRim
import bot.tafs.app.model.KnobRing
import bot.tafs.app.model.KnobStop
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A taf's dial as tafs cloud sends it (`GET /v1/tafs/{id}/knob`, the knob API): When · What · Where around its Why. Every label,
 * line and word is tafs cloud's; the app draws them and composes none.
 */
@Serializable
data class KnobWire(
  /** The live version. */
  val version: Int? = null,
  /** The version this dial shows, when it's a change waiting to go live (`?version=N`). */
  val shown: Int? = null,
  /** The rings, inner to outer, with their names: When (`wakes`), What (`what`), Where (`results`). */
  val rings: List<RingName> = emptyList(),
  /** The face's word when nothing is chosen: the taf's purpose, as Coach wrote it. */
  val why: String? = null,
  val does: Does = Does(),
  val wakes: List<Item> = emptyList(),
  /** The recipe, its steps in order. */
  val what: List<Item> = emptyList(),
  val results: List<Item> = emptyList(),
  /** The connections the stops come from, in order, for "Showing ▾". */
  val connections: List<Connection> = emptyList(),
  val rim: Rim = Rim(),
  /** The newest dial change waiting to go live, or null. */
  val draft: Draft? = null,
  /** The change Make it live switches to, and how many stops it changes from the live dial; null, nothing waits. */
  val pending: Pending? = null,
  /** Whether ⏻ applies: false for a taf that works from its chat, which has no schedule to pause. */
  @SerialName("can_pause") val canPause: Boolean = true,
  /** The shown version's rings as `PUT` takes them. */
  val setting: JsonObject? = null,
) {
  @Serializable
  data class RingName(val key: String, val name: String)

  @Serializable
  data class Does(val label: String = "", val line: String = "")

  @Serializable
  data class Connection(val grant: String, val name: String = "")

  @Serializable
  data class Pending(val version: Int, val changes: Int = 1)

  /** One stop: a trigger on When, a step on What, a place on Where. */
  @Serializable
  data class Item(
    val id: String = "",
    /** When: door, schedule, run_now, app_event, follow_up… */
    val kind: String? = null,
    val place: String? = null,
    /** A step's number on What. */
    val step: Int? = null,
    val label: String = "",
    val words: String = "",
    /** Whether it's on; a stop that doesn't say is on. */
    val on: Boolean = true,
    /** The face's big word when it's chosen ("Open"), when tafs cloud sends one. */
    val state: String? = null,
    /** What pressing the face (OK) does, said under it, when tafs cloud sends it; none, OK does nothing. */
    val tap: String? = null,
    val expires: String? = null,
    /** A door's id: OK opens or closes it through the doors API. */
    val door: String? = null,
    /** A follow-up the taf set itself, by its id: OK removes it. */
    @SerialName("follow_up") val followUp: String? = null,
    /** The connection a connected app's stop comes from; none on the taf's own. */
    val grant: String? = null,
    /** Its one choice for − and +, when it has one. */
    val size: Size? = null,
    /** What OK (pressing the face) does on it: door, run, remove_follow_up or coach; none, nothing. */
    val ok: String? = null,
    /** A door stop's look: open, closed, resting or knock. */
    val look: String? = null,
    /** An app's event on When: whether the owner approved its filters at planque, and what approving signs. */
    val approval: Approval? = null,
  )

  /**
   * An `app_event` stop's approval, as tafs cloud sends it: `needed` until planque's subscription for exactly these filters is
   * recorded, then `approved` with it. The phone subscribes [config] as it is and tells tafs cloud planque's id.
   */
  @Serializable
  data class Approval(
    val state: String = "",
    val grant: String = "",
    val event: String = "",
    val config: JsonObject = JsonObject(emptyMap()),
    val subscription: String? = null,
  ) {
    val needed: Boolean get() = state == "needed"
  }

  /** A stop's one choice: its name, the value now, and each value's whole `PUT` body. */
  @Serializable
  data class Size(val label: String = "", val index: Int = 0, val choices: List<Choice> = emptyList()) {
    @Serializable
    data class Choice(val label: String = "", val setting: JsonObject)

    val now: Choice? get() = choices.getOrNull(index)

    /** The choice one step lower or higher, or null at the end. */
    fun step(by: Int): Choice? = choices.getOrNull(index + by)
  }

  @Serializable
  data class Rim(
    val done: Int = 0,
    val working: Int = 0,
    val retrying: Int = 0,
    @SerialName("needs_you") val needsYou: Int = 0,
    val line: String? = null,
  )

  /** The newest dial version made after the live one, waiting to go live: its words by ring, and by connection for Coach's card. */
  @Serializable
  data class Draft(
    val version: Int,
    val words: List<String> = emptyList(),
    val wakes: List<String> = emptyList(),
    val what: List<String> = emptyList(),
    @SerialName("when") val whenLines: List<String> = emptyList(),
    val results: List<String> = emptyList(),
    val groups: List<Group> = emptyList(),
  ) {
    /** Every line, ring by ring. */
    val lines: List<String> get() = (wakes + what + results).ifEmpty { wakes + whenLines + results }.ifEmpty { words }
  }

  /** One connection's lines in a draft, or This taf's (no grant). */
  @Serializable
  data class Group(
    val grant: String? = null,
    val name: String = "",
    val wakes: List<String> = emptyList(),
    val what: List<String> = emptyList(),
    val results: List<String> = emptyList(),
  )

  /** What `PUT` answers: the taf's next version, and its words. Nothing runs from it until it's live. */
  @Serializable
  data class Change(val version: Int, val words: List<String> = emptyList())

  /** All of a ring's stops, by its key. */
  fun items(key: String): List<Item> = when (key) {
    "wakes" -> wakes
    "what" -> what
    "results" -> results
    else -> emptyList()
  }

  /** A stop by its id, on any ring. */
  fun item(id: String): Item? = (wakes + what + results).firstOrNull { it.id == id }

  /**
   * The dial's model, inner ring to outer: When, What, Where, as [rings] names them. [showing] keeps one connection's stops (its
   * grant), the taf's own ([THIS_TAF]), or all of them (null).
   */
  fun knob(showing: String? = null): Knob {
    val names = rings.ifEmpty { listOf(RingName("wakes", "When"), RingName("what", "What"), RingName("results", "Where")) }
    fun keep(i: Item) = when (showing) {
      null -> true
      THIS_TAF -> i.grant == null
      else -> i.grant == showing
    }
    return Knob(
      does = why ?: does.label,
      overview = does.line,
      rings = names.mapNotNull { r ->
        val kind = when (r.key) {
          "wakes" -> KnobRing.Kind.WakesIt
          "what" -> KnobRing.Kind.What
          "results" -> KnobRing.Kind.ResultsGo
          else -> return@mapNotNull null
        }
        KnobRing(kind, items(r.key).filter(::keep).map(::stop), r.name)
      },
      rim = KnobRim(rim.done, rim.working, rim.retrying, rim.needsYou),
      summary = rim.line,
    )
  }

  // A recipe step reads "1 · Check"; anything else, its label as tafs cloud wrote it.
  private fun stop(i: Item) = KnobStop(i.id, i.step?.let { "$it · ${i.label}" } ?: i.label, i.on, i.state, i.tap, i.words, i.size?.now?.label, i.look)

  companion object {
    /** "Showing ▾"'s choice for the taf's own stops, the ones with no connection. */
    const val THIS_TAF = "this-taf"
  }
}

/** Connect apps (`GET /v1/connect/apps`, the knob API): the catalog first, then KIFF's own apps for Google, Microsoft and Slack. */
@Serializable
data class ConnectApps(
  val apps: List<App> = emptyList(),
  /** Coach can draft a vendor that isn't listed. */
  @SerialName("by_key") val byKey: Boolean = false,
  /** The managed provider this page came from, sent back with the next page's request. */
  val provider: String? = null,
  /** The next page's cursor; none on the last page. */
  @SerialName("next_cursor") val nextCursor: String? = null,
) {
  @Serializable
  data class App(
    val entry: String,
    val vendor: String = "",
    val title: String = "",
    val icon: String? = null,
    /** oauth or key. */
    val how: String = "oauth",
    /** connected, available or needs_reconnect. */
    val state: String = "available",
    val grant: String? = null,
  ) {
    val connected: Boolean get() = state == "connected"
    val needsReconnect: Boolean get() = state == "needs_reconnect"
  }
}

/**
 * What starting a connection answers: its grant, and whether the vendor takes a key. Then the approval as for any grant: KIFF's
 * words, the signature, and only then planque's sign-in page.
 */
@Serializable
data class ConnectStart(
  /** planque's grant: its view as an object, or its id. */
  @SerialName("grant") val grantValue: kotlinx.serialization.json.JsonElement,
  @SerialName("needs_key") val needsKey: Boolean = false,
) {
  /** The grant's id, from either form. */
  val grant: String
    get() = (grantValue as? kotlinx.serialization.json.JsonObject)?.get("id")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
      ?: (grantValue as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
}
