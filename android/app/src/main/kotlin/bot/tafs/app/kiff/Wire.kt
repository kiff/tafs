package bot.tafs.app.kiff

import bot.tafs.app.model.InstantAsString
import bot.tafs.app.model.RecordAnswer
import bot.tafs.app.model.RecordReceipt
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// What tafs cloud and KIFF send, as the phone reads it. Anything the app doesn't use is left out, and
// anything it doesn't know yet is ignored.

// MARK: tafs cloud's home list

/** An amount and what KIFF says it means ("EUR", 2); no unit for a bare number. */
@Serializable
data class HomeAmount(val minor: Long, val unit: String? = null, val scale: Int = 0)

@Serializable
data class HomeRule(
  val action: String,
  val label: String,
  val outside: String = "hold",
  @SerialName("per_action") val perAction: HomeAmount? = null,
  @SerialName("per_window") val perWindow: HomeAmount? = null,
  @SerialName("count_per_window") val countPerWindow: Int? = null,
  val window: String = "day",
  /** Of perWindow, the extra room KIFF says is active, the Card's own limit, and when the room ends. */
  @SerialName("extra_room") val extraRoom: HomeAmount? = null,
  @SerialName("base_limit") val baseLimit: HomeAmount? = null,
  @SerialName("room_window_end") @Serializable(with = InstantAsString::class) val roomWindowEnd: Instant? = null,
)

/** A Card's total for its window and what it has used; no use when KIFF couldn't read it. */
@Serializable
data class HomeTotal<V>(
  val limit: V,
  val used: V? = null,
  val window: String = "day",
  @SerialName("extra_room") val extraRoom: V? = null,
  @SerialName("base_limit") val baseLimit: V? = null,
  @SerialName("room_window_end") @Serializable(with = InstantAsString::class) val roomWindowEnd: Instant? = null,
)

/** One KIFF Card on its own (kiff/tafs#211). */
@Serializable
data class HomeCardView(
  val id: String,
  val title: String,
  /** As KIFF names it; empty for every environment. */
  val environment: String = "",
  /** Production, or every environment: what the taf does for real. */
  val live: Boolean = true,
  val active: Boolean = true,
  @Serializable(with = InstantAsString::class) val expires: Instant? = null,
  val rules: List<HomeRule> = emptyList(),
  val total: HomeTotal<HomeAmount>? = null,
  val calls: HomeTotal<Int>? = null,
)

@Serializable
data class HomeCardSummary(
  /** The live Cards: what revoking stops. */
  val ids: List<String> = emptyList(),
  val active: Boolean = true,
  @Serializable(with = InstantAsString::class) val expires: Instant? = null,
  val tools: List<String> = emptyList(),
  val rules: List<HomeRule> = emptyList(),
  val used: Map<String, HomeAmount> = emptyMap(),
  @SerialName("usage_unknown") val usageUnknown: List<String> = emptyList(),
  /** Each Card on its own, the live ones first; null from an older tafs cloud. */
  val cards: List<HomeCardView>? = null,
)

/**
 * One holder of Cards on the home list, as tafs cloud makes it from its records and KIFF's Cards and
 * held calls (#91): the app shows it and combines nothing itself.
 */
@Serializable
data class HomeTaf(
  /** tafs cloud's id; null for an agent tafs doesn't run. */
  val id: String? = null,
  val name: String,
  val job: String? = null,
  /** It works from its chat, with no schedule (kiff/tafs#138). */
  val unscheduled: Boolean? = null,
  /** Its job in words (kiff/tafs#167); absent for an agent tafs doesn't run. */
  @SerialName("job_summary") val jobSummary: String? = null,
  @SerialName("kiff_agent") val kiffAgent: String,
  val status: String? = null,
  @SerialName("next_run") @Serializable(with = InstantAsString::class) val nextRun: Instant? = null,
  @SerialName("last_run") @Serializable(with = InstantAsString::class) val lastRun: Instant? = null,
  val color: Int = 0,
  /** working, paused, waiting, idle (an agent tafs doesn't run) or stopped (no active Card). */
  val state: String = "idle",
  /** Its calls held for the owner. */
  val waiting: Int = 0,
  val card: HomeCardSummary = HomeCardSummary(),
)

/** A taf whose removal began and didn't finish: archived, so no longer listed as a taf. */
@Serializable
data class PendingRemoval(val id: String, val name: String)

/** The home list: the account's tafs, its other agents with Cards (listed apart), and the removals still to finish. */
@Serializable
data class Home(
  val tafs: List<HomeTaf> = emptyList(),
  val others: List<HomeTaf> = emptyList(),
  val removing: List<PendingRemoval> = emptyList(),
  /** The doors tafs cloud offers here (docs/doors.md); absent when none, and the app shows no Doors. */
  @SerialName("doors_offered") val doorsOffered: List<String>? = null,
)

// MARK: tafs cloud's chat

@Serializable
data class WireStep(
  val tool: String,
  val error: Boolean? = null,
  @SerialName("not_made") val notMade: Boolean? = null,
)

@Serializable
data class WireWork(val ms: Int = 0, val steps: List<WireStep> = emptyList())

/** One line of a taf's thread: the owner's messages and the taf's replies and reports. */
@Serializable
data class WireChatMessage(
  @Serializable(with = InstantAsString::class) val at: Instant,
  val from: String,
  val kind: String? = null,
  val text: String,
  val session: String,
  val work: WireWork? = null,
  /** The files the person attached (docs/attachments.md), read apart: one the app can't read is left out. */
  val attachments: JsonElement? = null,
  /** Read apart (as JSON), so a mark the app can't read leaves the message plain instead of the thread unread. */
  val door: JsonElement? = null,
)

/** The run at work in a session. */
@Serializable
data class WireWorking(val session: String, val run: String? = null)

/** A run that stopped at its cost or turn cap, as tafs cloud reports it. */
@Serializable
data class WireCapped(val run: String, val stop: String)

@Serializable
data class WireThread(
  val messages: List<WireChatMessage> = emptyList(),
  val working: WireWorking? = null,
  val capped: WireCapped? = null,
  /** Coach's cards, each read apart: one the app can't read is left out. */
  val components: List<JsonElement>? = null,
)

/** What tafs cloud says of a message it took: the chat it went into. */
@Serializable
data class Sent(val session: String, @SerialName("new_session") val newSession: Boolean = false)

/** The run's status after a message written to it. */
@Serializable
data class Steered(val run: String? = null, val status: String)

// MARK: Alerts

/** Where KIFF sends the account's held calls: an id, and the secret it signs them with, shown once. */
@Serializable
data class AlertTarget(val id: String, @SerialName("signing_secret") val signingSecret: String)

/** What tafs cloud says after a phone registers: whether this person's phones are alerted, and whether it holds a KIFF secret for the account. */
@Serializable
data class PushStatus(val answers: Boolean = false, val alerts: Boolean = false)

@Serializable
internal data class AlertsKept(val alerts: Boolean = false)

// MARK: KIFF's held calls

@Serializable
data class Breach(
  val outcome: String,
  val reason: String? = null,
  val limit: Long? = null,
  val quantity: String? = null,
  /** What the summed amount means, when KIFF could say. */
  val unit: String? = null,
  val scale: Int? = null,
  @SerialName("unit_unavailable") val unitUnavailable: Boolean? = null,
  @SerialName("unit_ambiguous") val unitAmbiguous: Boolean? = null,
)

/** A parameter's declared unit and decimal places ("EUR", 2). */
@Serializable
data class ParameterUnit(val unit: String, val scale: Int = 0)

/** A call KIFF holds for the owner's answer. */
@Serializable
data class CardException(
  val id: String,
  val subject: String,
  val action: String,
  @SerialName("entity_id") val entityId: String = "",
  val status: String = "held",
  val breaches: List<Breach> = emptyList(),
  val parameters: Map<String, Long>? = null,
  val reason: String? = null,
  /** The units the held action declares for its parameters; absent for a bare number. */
  @SerialName("parameter_units") val parameterUnits: Map<String, ParameterUnit>? = null,
  @SerialName("units_unavailable") val unitsUnavailable: Boolean? = null,
  @SerialName("next_steps") val nextSteps: List<String>? = null,
  @SerialName("created_at") @Serializable(with = InstantAsString::class) val createdAt: Instant,
  @SerialName("expires_at") @Serializable(with = InstantAsString::class) val expiresAt: Instant? = null,
  /** The hold's accounting version: part of what the phone signs, so an answer counts only for the hold as it stood. */
  @SerialName("accounting_version") val accountingVersion: Int? = null,
  /** The held call's tool, as KIFF names it for people. */
  val title: String? = null,
  /** What a held gateway call asks, as KIFF keeps it: each argument as JSON, read as text where it is shown. */
  val arguments: Map<String, JsonElement>? = null,
  /** The proposal KIFF held: what its activity files the call under. */
  @SerialName("proposal_id") val proposalId: String? = null,
  /** KIFF's answer to whether approving once can pass this call (kiff/tafs#138); absent from an older response. */
  @SerialName("can_approve_once") val canApproveOnce: Boolean? = null,
)

@Serializable
data class CardExceptions(val exceptions: List<CardException> = emptyList())

// MARK: KIFF's activity

/** How the call ended at the tool: done, refused_by_tool, not_sent, sending, unknown or not_recorded. */
@Serializable
data class ToolOutcome(val status: String, val message: String? = null)

/** The money a call moves, as a decimal string and its unit ("150.00", "EUR"). A gateway call's integer amount has no unit. */
@Serializable
data class ActivityAmount(val value: String, val unit: String? = null)

/** One call of the account's, as KIFF's activity records it: what a taf did, newest first, with the tool's title for people. */
@Serializable
data class ActivityCall(
  @SerialName("proposal_id") val proposalId: String,
  @SerialName("action_name") val actionName: String,
  val title: String? = null,
  @SerialName("actor_id") val actorId: String? = null,
  val outcome: String,
  @SerialName("created_at") @Serializable(with = InstantAsString::class) val createdAt: Instant,
  val held: Boolean = false,
  @SerialName("tool_outcome") val toolOutcome: ToolOutcome? = null,
  val amount: ActivityAmount? = null,
  @SerialName("read_only") val readOnly: Boolean? = null,
  @SerialName("entity_id") val entityId: String? = null,
  @SerialName("on_hash") val onHash: String? = null,
  @SerialName("on_arg") val onArg: String? = null,
  val answer: RecordAnswer? = null,
  val receipt: RecordReceipt? = null,
  val reasons: List<String>? = null,
  @SerialName("made_at") @Serializable(with = InstantAsString::class) val madeAt: Instant? = null,
  val reason: String? = null,
)

@Serializable
data class ActivityCalls(val calls: List<ActivityCall> = emptyList())
