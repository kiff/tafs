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

/** What starting a connection answers: a vendor sign-in page, or that a key is needed. Then the approval flow as for any grant. */
@Serializable
data class ConnectStart(
  val grant: String,
  @SerialName("sign_in_url") val signInUrl: String? = null,
  @SerialName("needs_key") val needsKey: Boolean = false,
)
