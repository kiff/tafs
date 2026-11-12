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
  /** KIFF's words for how much of it is from calls the tool refused, which count until KIFF confirms nothing moved; null for none. */
  @SerialName("pending_text") val pendingText: String? = null,
  /** KIFF's words for why its amount has no currency (a tool the Card grants isn't connected); null otherwise. */
  @SerialName("unit_words") val unitWords: String? = null,
  /** Which tools that is, and whether they're connected, as KIFF says. */
  @SerialName("unit_missing") val unitMissing: UnitMissing? = null,
)

/** The tools a Card's currency waits on, as KIFF says: they aren't connected. */
@Serializable
data class UnitMissing(val tools: List<String> = emptyList(), val connected: Boolean = false)

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
  /** How this is counted: each covered tool's lines in KIFF's words, as the owner approved them; none when KIFF has none. */
  val counted: List<CountedTool>? = null,
  /** Its totals for each item apart, in tafs cloud's words ("1 call in any 7 days for each invoice id"); none without any. */
  @SerialName("each_item") val eachItem: List<String>? = null,
)

/** One tool's "How this is counted": its title, and KIFF's lines for its profile, shown as they are. */
@Serializable
data class CountedTool(val tool: String = "", val title: String = "", val lines: List<String> = emptyList())

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
  /** Where its results go: `door_reply`, `app`, `owner_email` (an action place isn't listed here). */
  val results: List<String> = emptyList(),
  /** The account's clock its times are in, in tafs cloud's words ("Times in Europe/Paris"), and the zone its schedule runs in. */
  @SerialName("time_zone") val timeZone: String? = null,
  @SerialName("time_zone_words") val timeZoneWords: String? = null,
  @SerialName("schedule_time_zone") val scheduleTimeZone: String? = null,
  /** Only while its recurring schedule still runs in another zone: what to do about it, in tafs cloud's words. */
  @SerialName("schedule_time_zone_words") val scheduleTimeZoneWords: String? = null,
  val status: String? = null,
  @SerialName("next_run") @Serializable(with = InstantAsString::class) val nextRun: Instant? = null,
  /** The next run in the person's language, its weekday when it isn't today ("Next run Mon 08:00"): drawn as is. */
  @SerialName("next_run_words") val nextRunWords: String? = null,
  @SerialName("last_run") @Serializable(with = InstantAsString::class) val lastRun: Instant? = null,
  val color: Int = 0,
  /** working, paused, waiting, idle (an agent tafs doesn't run) or stopped (no active Card). */
  val state: String = "idle",
  /** Its calls held for the owner. */
  val waiting: Int = 0,
  val card: HomeCardSummary = HomeCardSummary(),
  @SerialName("avatar_rim") val avatarRim: bot.tafs.app.model.AvatarRim? = null,
  /** Where its work stands, in tafs cloud's words: its status and line (`GET /v1/tafs/{id}/work`'s `summary`). */
  val work: bot.tafs.app.kiff.WorkSummary? = null,
  /** How it's doing, in tafs cloud's words (docs/reliability.md › Its health); absent when it can't be read in time. */
  val health: bot.tafs.app.kiff.TafHealth? = null,
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
  /** What Needs you holds, counted by tafs cloud with its words; absent when it couldn't count all of it. */
  @SerialName("needs_you") val needsYou: NeedsYouCount? = null,
  /** Cards asked to take a currency they have no limit for, for the tafs this person sees: Needs you draws them. */
  @SerialName("card_unit_exceptions") val cardUnitExceptions: List<CardUnitException>? = null,
  /** Steps a taf handed its owner, oldest first (docs/api.md › Your step): Needs you draws them. */
  @SerialName("your_steps") val yourSteps: List<YourStep>? = null,
)

/**
 * A step a taf may never take itself, handed to its owner, as tafs cloud shows it: KIFF's hand-off id, what to do and why, in
 * tafs cloud's words. [link] comes only when the taf's own connections vouch for its host, with [linkHost] apart, so the app never
 * parses it; with no link or no [YourStepWords.open], the card has no button.
 */
@Serializable
data class YourStep(
  val handoff: String,
  val run: String = "",
  val taf: YourStepTaf? = null,
  val what: String = "",
  val why: String = "",
  val link: String? = null,
  @SerialName("link_host") val linkHost: String? = null,
  val since: String? = null,
  val expires: String? = null,
  val words: YourStepWords = YourStepWords(),
) {
  /**
   * The vendor's page, only as tafs cloud vouched for it: an https link, its host apart ([linkHost]) and the button's words. The
   * host must be the link's own, so the line under the button names where it really goes; anything else is no button, only the
   * words. As iOS's `openLink`.
   */
  val open: OpenLink? get() {
    val l = link ?: return null
    val host = linkHost?.takeIf { it.isNotBlank() } ?: return null
    val w = words.open?.takeIf { it.isNotBlank() } ?: return null
    val uri = runCatching { java.net.URI(l) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals(host, ignoreCase = true)) return null
    return OpenLink(w, l, host)
  }
}

/** A step's button: its words, the link it opens, and the host shown under it. */
data class OpenLink(val words: String, val link: String, val host: String)

@Serializable
data class YourStepTaf(val id: String, val name: String = "")

/** The card's words, tafs cloud's in the person's language: "Your step", "Done", "Skip it", "Open in {vendor}", its line. */
@Serializable
data class YourStepWords(val title: String = "", val done: String = "", val skip: String = "", val open: String? = null, val line: String = "")

/** A hand-off as KIFF keeps it: what the phone needs to sign its answer. */
@Serializable
data class Handoff(val id: String, val version: Int, val status: String = "")

/** Needs you, counted once by tafs cloud: held calls, "Did this happen?" calls, currency requests and tafs with a knock. */
@Serializable
data class NeedsYouCount(val count: Int, val words: String)

// MARK: tafs cloud's chat

@Serializable
data class WireStep(
  val tool: String,
  /** What the step did, in tafs cloud's words: KIFF's title for the tool, or Coach's. None for a step it has no words for. */
  val words: String? = null,
  val error: Boolean? = null,
  /** It failed, as tafs cloud says it (gap 9); and when the tool said something readable, [why], one line in the owner's words. */
  val failed: Boolean? = null,
  val why: String? = null,
  @SerialName("not_made") val notMade: Boolean? = null,
)

@Serializable
data class WireWork(val ms: Int = 0, val steps: List<WireStep> = emptyList())

/** One line of a taf's thread: the owner's messages and the taf's replies and reports. */
@Serializable
data class WireChatMessage(
  /** tafs cloud's id for the message: a run's first owner message is `<run>.in`, the id its send answered. */
  val id: String? = null,
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
  /** The card this owner's message answered, by id: they wrote while it waited, and their words were its answer. */
  val answers: String? = null,
  /** On a job run's report: where its result went, in tafs cloud's words, one line drawn above the taf's own. */
  val delivered: String? = null,
  /** On a run's last message: what the run did, counted from kiff/agents' record, in tafs cloud's words, drawn under the taf's. */
  val counted: String? = null,
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
  /**
   * Where the latest run stopped without finishing, any stop tafs cloud names (`turn_cap`, `cost_cap`, `max_token`,
   * `refused`); `capped` carries the two caps alone, for older apps.
   */
  val stopped: WireCapped? = null,
  /** Coach's cards, each read apart: one the app can't read is left out. */
  val components: List<JsonElement>? = null,
  /** News of the tasks Coach handed tafs (kiff/tafs#206), each read apart: one the app can't read is left out. */
  @SerialName("coach_lines") val coachLines: List<JsonElement>? = null,
  /** The thread in tafs cloud's order: the app reads only its drives' ends from it (kiff/tafs#568), each apart. */
  val items: List<JsonElement>? = null,
  /** What tafs cloud holds of the person's words (docs/chat.md): read apart, so one the app can't read leaves the thread as it is. */
  val steering: JsonElement? = null,
)

/** What tafs cloud says of a message it took: the chat it went into. */
@Serializable
data class Sent(val session: String, @SerialName("new_session") val newSession: Boolean = false, val message: SentMessage? = null)

/** The message a send made, as the thread will name it: the app matches its send to the thread by [id], never by its words. */
@Serializable
data class SentMessage(val id: String)

/**
 * What came of a message written to a run at work: the run's [status] when it took it; with `hold` (docs/chat.md), [sent] when tafs
 * cloud sent it at once as the person's next message, and [held] for what waits in that chat to go once the taf is done.
 */
@Serializable
data class Steered(val run: String? = null, val status: String = "", val sent: SteerSent? = null, val held: SteerWords? = null)

/** The run a held message went as, and its chat. */
@Serializable
data class SteerSent(val run: String = "", val session: String = "")

/** A person's words tafs cloud holds or didn't send, with when, and why for one not sent (its words, shown as they are). */
@Serializable
data class SteerWords(val text: String = "", val at: String = "", val why: String? = null)

/** The thread's `steering`, as the app shows it: what waits for the taf to be done, and what a stopped run never read. */
@Serializable
data class WireSteering(val run: String? = null, val held: SteerWords? = null, @SerialName("not_sent") val notSent: List<SteerWords> = emptyList())

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
  /** For a total: what it had used, what this call carries, and its window ("calendar_day"). */
  val used: Long? = null,
  val draw: Long? = null,
  val window: String? = null,
  /** For a total for each item apart: the argument it counts by, and the item this call was on ("invoice_id", "in_42"). */
  val per: String? = null,
  val value: String? = null,
)

/**
 * `POST /v1/tafs/{id}/holds/{exception}/allow` (docs/allow-from-now-on.md): tafs cloud's offer to change the Card so calls like a
 * held one go on their own, or why there's none ([none], with tafs cloud's [words]).
 */
@Serializable
data class AllowAnswer(val offer: AllowOffer? = null, val none: String? = null, val words: String? = null)

/** The offer: the narrowest change, what it would have let go over the last 30 days, and the draft KIFF holds for it. */
@Serializable
data class AllowOffer(
  val changes: List<AllowChange> = emptyList(),
  val would: AllowWould? = null,
  val draft: String = "",
  @SerialName("confirm_key") val confirmKey: String? = null,
  @SerialName("send_held") val sendHeld: Boolean = true,
  /** What rounding would give, named before any tap; absent when rounding changes nothing. The app never rounds. */
  val round: AllowRound? = null,
)

/** One limit the offer changes: on which Card (`mandate_id`), what kind, from what to what, in tafs cloud's words. */
@Serializable
data class AllowChange(
  @SerialName("mandate_id") val mandateId: String,
  val kind: String,
  val before: Long? = null,
  val after: Long? = null,
  val unit: String? = null,
  @SerialName("words_before") val wordsBefore: String = "",
  @SerialName("words_after") val wordsAfter: String = "",
  val rounded: Boolean = false,
)

@Serializable
data class AllowWould(val calls: Int = 0, val words: String = "")

/**
 * The secure connection sheet's words for one key grant (docs/secure-connection.md), from tafs cloud's
 * `GET /v1/connect/grants/{grant}/sheet`, in the screen's language. The phone draws them and fills only `{last4}`, from the key it
 * pasted, and `{vendor}`, `{terms}` and `{privacy}` from this answer.
 */
@Serializable
data class SecureSheet(
  val vendor: SecureVendor = SecureVendor(),
  val title: String = "",
  val lines: List<SecureLine> = emptyList(),
  val paste: SecurePaste = SecurePaste(),
  val details: SecureDetails? = null,
  @SerialName("card_title") val cardTitle: String? = null,
  val legal: SecureLegal? = null,
  val button: String = "",
  val receipt: SecureReceipt? = null,
)

@Serializable
data class SecureVendor(val name: String = "", val logo: String? = null, val host: String? = null)

/** One plain line: `reads_only` or `may_change`, `sealed`, `disconnect`. The phone draws `text`. */
@Serializable
data class SecureLine(val kind: String = "", val text: String = "")

@Serializable
data class SecurePaste(val button: String = "", val what: String? = null, val pasted: String = "", val cleared: String? = null)

@Serializable
data class SecureDetails(val title: String = "", val lines: List<SecureDetail> = emptyList())

@Serializable
data class SecureDetail(val label: String = "", val value: String = "")

@Serializable
data class SecureLegal(val text: String = "", val terms: SecureLink? = null, val privacy: SecureLink? = null)

@Serializable
data class SecureLink(val words: String = "", val url: String = "")

@Serializable
data class SecureReceipt(val text: String = "", val disconnect: String? = null)

@Serializable
data class AllowRound(val after: Long? = null, val words: String = "")

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
  /** KIFF's own words for why it's held, in the amount's unit: "$60 is over the $50 limit for one call". */
  @SerialName("reason_text") val reasonText: String? = null,
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
  /** What kind of exception: a held call, or `card_unit`, a currency its Card has no limit for, which tafs cloud draws itself. */
  val kind: String? = null,
  /**
   * The taf's one line on why, kept by KIFF with the held call (its `kiff_reason`): the order as the owner knows it and the customer's
   * reason. The taf's words, from text a customer wrote: never signed, never used to decide, and never in place of KIFF's amount,
   * rule or action.
   */
  @SerialName("taf_reason") val tafReason: String? = null,
  /** What the call is on, in the taf's words (its `kiff_about`): a business reference only, such as "Order 1042". As [tafReason]. */
  @SerialName("taf_about") val tafAbout: String? = null,
)

@Serializable
data class CardExceptions(val exceptions: List<CardException> = emptyList())

// MARK: KIFF's activity

/** How the call ended at the tool: done, refused_by_tool, not_sent, sending, unknown or not_recorded. */
@Serializable
data class ToolOutcome(
  val status: String,
  val message: String? = null,
  /** Who refused a call KIFF allowed, as KIFF types it: `connection` for the connection, before anything was sent. */
  @SerialName("refused_by") val refusedBy: String? = null,
  /** The connection's machine code for why; the words come from [ActivityCall.detail]. */
  val code: String? = null,
)

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
  /** KIFF's words for what happened to the call, when it gives them (a connection's refusal). */
  val detail: String? = null,
)

@Serializable
data class ActivityCalls(val calls: List<ActivityCall> = emptyList())

/**
 * The tafs' scheduled work still to come on a range of dates (`GET /v1/calendar`, kiff/tafs#534). tafs cloud computes every
 * occurrence in the account's zone and gives each its day, time and minute there, so the app places it without converting a zone.
 */
@Serializable
data class CalendarRead(
  @SerialName("time_zone") val timeZone: String = "UTC",
  val from: String = "",
  val to: String = "",
  @SerialName("events") val rawEvents: List<kotlinx.serialization.json.JsonElement> = emptyList(),
  /** The dates with an event, so the app marks them. */
  val days: List<String> = emptyList(),
  /** tafs cloud's line for the range: "3 to come", "Nothing scheduled in these days", "Halted: nothing runs until it's resumed". */
  val words: String = "",
  /** Some events were left out: more than one read answers. */
  val more: Boolean = false,
  /** The account is halted: nothing runs, so nothing is to come. */
  val halted: Boolean = false,
) {
  /** The events this app can read: one it can't is left out, never the read. */
  val events: List<CalendarEvent> by lazy {
    rawEvents.mapNotNull { runCatching { KiffClient.json.decodeFromJsonElement(CalendarEvent.serializer(), it) }.getOrNull() }
  }
}

/** One occurrence: a live schedule's firing, a follow-up the taf set itself, or a run's next try. */
@Serializable
data class CalendarEvent(
  /** The taf it belongs to (tafs cloud's id), its name and its colour. */
  val taf: String,
  val name: String = "",
  val color: Int = 0,
  /** `schedule`, `follow_up` or `retry`. */
  val kind: String = "schedule",
  /** The instant, RFC 3339 in UTC, as tafs cloud wrote it. */
  val at: String,
  /** Its date, time of day and minutes since midnight, in the account's zone. */
  val day: String,
  val time: String,
  val minute: Int,
  /** What it is, in tafs cloud's words. */
  val words: String = "",
  /** How many firings it stands for: tafs cloud folds a day of a dense schedule into one event. */
  @SerialName("count") val rawCount: Int = 1,
) {
  val id: String get() = "$taf|$kind|$at"
  val count: Int get() = rawCount.coerceAtLeast(1)
}
