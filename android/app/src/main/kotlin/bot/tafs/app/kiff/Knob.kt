package bot.tafs.app.kiff

import bot.tafs.app.model.Knob
import bot.tafs.app.model.KnobRim
import bot.tafs.app.model.KnobRing
import bot.tafs.app.model.KnobStop
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A taf's dial as tafs cloud sends it (`GET /v1/tafs/{id}/knob`, the knob API). Every label, line and words is tafs
 * cloud's; the app draws them and composes none.
 */
@Serializable
data class KnobWire(
  val version: Int? = null,
  val does: Does = Does(),
  val wakes: List<Item> = emptyList(),
  @SerialName("when") val whenItems: List<Item> = emptyList(),
  val results: List<Item> = emptyList(),
  val rim: Rim = Rim(),
  /** A change waiting for the owner's confirmation, or null. */
  val draft: Draft? = null,
  /** The live version's rings as `PUT` takes them: `{wakes: [trigger], busy, results: [place]}`. A tap copies it and changes one thing. */
  val setting: kotlinx.serialization.json.JsonObject? = null,
) {
  @Serializable
  data class Does(val label: String = "", val line: String = "")

  /** One stop: a trigger on Wakes it, a rule on When, a place on Results go. */
  @Serializable
  data class Item(
    val id: String = "",
    /** Wakes it: run_now, door, schedule, vendor_event, app_check, follow_up, before_event. When: arrivals, schedule, missed, busy. */
    val kind: String? = null,
    /** Results go: door_reply, app, owner_email, slack, notion, gmail, teams. */
    val place: String? = null,
    val label: String = "",
    val words: String = "",
    /** Whether it's on; a stop that doesn't say is on. */
    val on: Boolean = true,
    /** The face's big word when it's chosen ("Open"), when tafs cloud sends one. */
    val state: String? = null,
    /** What tapping the face does, said under it ("Tap to close"), when tafs cloud sends it; none, the face does nothing. */
    val tap: String? = null,
    /** A follow-up's end. */
    val expires: String? = null,
    /** A door's id, on a door stop: the face opens or closes it through the doors API. */
    val door: String? = null,
    /** A trigger stop's form exactly as `PUT` takes it; none on Run now, nor on a schedule the dial can't set. */
    val trigger: kotlinx.serialization.json.JsonObject? = null,
  )

  @Serializable
  data class Rim(
    val done: Int = 0,
    val working: Int = 0,
    val retrying: Int = 0,
    @SerialName("needs_you") val needsYou: Int = 0,
    val line: String? = null,
  )

  @Serializable
  data class Draft(val version: Int, val words: List<String> = emptyList())

  /**
   * The setting with [stopId] changed, the one thing a tap changes, as `PUT` takes it: a result place, or If busy. Null for
   * anything else: a door and Run now have their own routes, and triggers change through Coach's draft for now.
   */
  fun changed(stopId: String): kotlinx.serialization.json.JsonObject? {
    val now = setting ?: return null
    fun list(key: String) = (now[key] as? kotlinx.serialization.json.JsonArray)?.toList().orEmpty()
    val wakes = list("wakes")
    val results = list("results")
    var busy = now["busy"]
    this.results.firstOrNull { it.id == stopId }?.let { item ->
      val place = item.place ?: return null
      val held = results.firstOrNull { (it as? kotlinx.serialization.json.JsonObject)?.get("place")?.let { p -> (p as? kotlinx.serialization.json.JsonPrimitive)?.content } == place }
      val next = when {
        item.on && held != null -> results - held
        // Back on: a plain place only; one that names a channel or a page is set where it's chosen, not by a tap.
        !item.on && place in PLAIN_PLACES -> results + kotlinx.serialization.json.JsonObject(mapOf("place" to kotlinx.serialization.json.JsonPrimitive(place)))
        else -> return null
      }
      return put(wakes, busy, next)
    }
    this.whenItems.firstOrNull { it.id == stopId && it.kind == "busy" }?.let { _ ->
      val was = (busy as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "skip"
      busy = kotlinx.serialization.json.JsonPrimitive(if (was == "latest") "skip" else "latest")
      return put(wakes, busy, results)
    }
    return null
  }

  private fun put(wakes: List<kotlinx.serialization.json.JsonElement>, busy: kotlinx.serialization.json.JsonElement?, results: List<kotlinx.serialization.json.JsonElement>) =
    kotlinx.serialization.json.JsonObject(buildMap {
      put("wakes", kotlinx.serialization.json.JsonArray(wakes))
      put("when", kotlinx.serialization.json.JsonObject(mapOf("busy" to (busy ?: kotlinx.serialization.json.JsonPrimitive("skip")))))
      put("results", kotlinx.serialization.json.JsonArray(results))
    })

  companion object {
    /** The places a tap can turn back on: they need nothing chosen. */
    val PLAIN_PLACES = setOf("door_reply", "app", "owner_email")
  }

  /** What `PUT` answers: the taf's next version, and what it changes in tafs cloud's words. Nothing runs from it until it's live. */
  @Serializable
  data class Change(val version: Int, val words: List<String> = emptyList())

  /** The dial's model: the rings in their order, inside to out, each stop as tafs cloud wrote it. */
  fun knob(): Knob = Knob(
    does = does.label,
    overview = does.line,
    rings = listOf(
      KnobRing(KnobRing.Kind.WakesIt, wakes.map(::stop)),
      KnobRing(KnobRing.Kind.When, whenItems.map(::stop)),
      KnobRing(KnobRing.Kind.ResultsGo, results.map(::stop)),
    ),
    rim = KnobRim(rim.done, rim.working, rim.retrying, rim.needsYou),
    summary = rim.line,
  )

  private fun stop(i: Item) = KnobStop(i.id, i.label, i.on, i.state, i.tap, i.words)
}

/** Connect apps (`GET /v1/connect/apps`, the knob API): the catalog first, then KIFF's own apps for Google, Microsoft and Slack. */
@Serializable
data class ConnectApps(
  val apps: List<App> = emptyList(),
  /** Coach can draft a vendor that isn't listed. */
  @SerialName("by_key") val byKey: Boolean = false,
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
