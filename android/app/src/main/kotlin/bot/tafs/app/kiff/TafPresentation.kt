package bot.tafs.app.kiff

import bot.tafs.app.model.Words
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.net.URLEncoder

@Serializable data class AlertChoice(val id: String, val title: String, val words: String = "", val enabled: Boolean = true)
@Serializable data class AlertTaf(val id: String, val name: String)
@Serializable data class AlertTafs(val title: String = "", val tafs: List<AlertTaf> = emptyList())
@Serializable data class PhoneAlerts(val enabled: Boolean = false, val sound: Boolean = true, val kinds: List<AlertChoice> = emptyList(), val answering: AlertTafs = AlertTafs())
@Serializable data class TimeZoneChoice(val id: String, val title: String)
@Serializable private data class TimeZoneChoices(val zones: List<TimeZoneChoice> = emptyList())

@Serializable data class ActivityChoice(val id: String, val title: String)
/**
 * A Card asked to take a currency it has no limit for (KIFF's `card_unit` exception), ready to draw: what it is, its history
 * of tries, Coach's way to change the Card, and Dismiss. Never approved once: only a changed Card lets those calls through.
 */
@Serializable data class CardUnitException(val id: String, val kind: String = "card_unit", val taf: String, val name: String, val title: String,
  val words: String, val history: String = "", @SerialName("action_title") val actionTitle: String, @SerialName("coach_text") val coachText: String,
  @SerialName("dismiss_title") val dismissTitle: String, @SerialName("can_approve_once") val canApproveOnce: Boolean = false)
@Serializable data class TafActivityEntry(val id: String, val at: String, val kind: String, val title: String, val detail: String,
  val time: String, @SerialName("amount_text") val amountText: String? = null, val call: ActivityCall,
  /** What the owner can do about a call that wasn't done, as tafs cloud says it (kiff/tafs#476, gap 10). */
  val next: NextStep? = null)
/**
 * The owner's next step for a call that wasn't done: [kind] is `reconnect` (the connection whose tools include [tool]), `ask_coach`
 * (Coach on the taf, [message] written in) or `try_later`; [words] are its button's, or its line's for a kind the app doesn't know.
 */
@Serializable data class NextStep(val kind: String, val words: String, val tool: String = "", val taf: String? = null, val message: String? = null)
@Serializable data class TafActivityGroup(val id: String, val title: String, val entries: List<TafActivityEntry> = emptyList())
@Serializable data class TafActivityPage(val entries: List<TafActivityEntry> = emptyList(), val groups: List<TafActivityGroup> = emptyList(),
  val filters: List<ActivityChoice> = emptyList(), val filter: String = "all", val revision: String = "", @SerialName("next_cursor") val nextCursor: String? = null,
  /** The taf's open currency requests, as of this page: drawn above its entries, ahead of what Home last said. */
  @SerialName("card_unit_exceptions") val cardUnitExceptions: List<CardUnitException> = emptyList(),
  /** Why no requests show, when KIFF's couldn't be read for this page; its history is still there. */
  @SerialName("card_unit_exceptions_notice") val cardUnitExceptionsNotice: String? = null,
  /** Under the last page, in tafs cloud's words: "Nothing older in this view". */
  @SerialName("end_words") val endWords: String? = null)
@Serializable data class TafRunsPage(val requests: List<WorkRequest> = emptyList(), @SerialName("next_cursor") val nextCursor: String? = null,
  val title: String = Words.Taf.Work.title, @SerialName("more_title") val moreTitle: String = Words.Taf.Work.showAllRuns, @SerialName("end_words") val endWords: String? = null)

@Serializable data class ConnectedAction(val name: String, val title: String, val limits: List<String> = emptyList(), val steps: List<Int> = emptyList(), @SerialName("step_words") val stepWords: String = "")
@Serializable data class ConnectedStats(val calls: Int = 0, val done: Int = 0, val held: Int = 0, val words: String = "", val complete: Boolean = false, @SerialName("activity_query") val activityQuery: String = "")
@Serializable data class ConnectedApp(val id: String, val name: String, val status: String, @SerialName("status_words") val statusWords: String = "", val reconnect: Boolean = false, @SerialName("action_count") val actionCount: Int = 0,
  @SerialName("action_words") val actionWords: String = "", val actions: List<ConnectedAction> = emptyList(), val steps: List<Int> = emptyList(),
  val running: Boolean = false, val stats: ConnectedStats = ConnectedStats(), @SerialName("workflow_steps") val workflowSteps: List<WorkflowStep> = emptyList(),
  /** With no saved workflow, what its own Workflow line says, once: "Ask Coach to set its workflow". */
  @SerialName("workflow_words") val workflowWords: String? = null,
  /** Who keeps its sign-in, in tafs cloud's one line (kiff/planque#87); none when the connection service doesn't say. */
  @SerialName("custody_words") val custodyWords: String? = null,
  /** Which vendor account it works in and in which mode, tafs cloud's one line from planque's words: "Demo Shop · Live". */
  @SerialName("account_words") val accountWords: String? = null,
  /** A browser connection's own sign-in to a site (kiff/planque#86): tafs cloud's title and line; none on any other connection. */
  @SerialName("site_sign_in") val siteSignIn: SiteSignIn? = null)
@Serializable data class SiteSignIn(val title: String, val words: String = "")
@Serializable data class WorkflowStep(val number: Int, val label: String, val words: String, val app: String? = null, val grant: String? = null, val running: Boolean = false)
@Serializable data class WorkflowView(val title: String = Words.Taf.Workflow.title, val summary: String = "", val steps: List<WorkflowStep> = emptyList(),
  @SerialName("running_step") val runningStep: Int? = null)
@Serializable data class MemorySummary(val count: Int = 0, val title: String = "", val latest: String? = null)
@Serializable data class VersionSummary(val title: String = Words.Taf.Versions.title, val summary: String = "", val live: Int? = null, val earlier: Int = 0)
@Serializable data class TafSetup(val connections: List<ConnectedApp> = emptyList(), val workflow: WorkflowView = WorkflowView(),
  val memory: MemorySummary = MemorySummary(), val versions: VersionSummary = VersionSummary(), @SerialName("connections_error") val connectionsError: String? = null,
  @SerialName("card_unit_exceptions") val cardUnitExceptions: List<CardUnitException> = emptyList())

data class Representation<T>(val value: T?, val etag: String?)
private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

suspend fun TafsCloud.activityPage(id: String, filter: String, cursor: String? = null, etag: String? = null, selection: String = ""): Representation<TafActivityPage> {
  val query = "filter=${encoded(filter)}" + (if (selection.isBlank()) "" else "&$selection") + (cursor?.let { "&cursor=${encoded(it)}" } ?: "")
  val res = representation("/v1/tafs/${encoded(id)}/activity?$query", etag)
  return Representation(if (res.status == 304) null else KiffClient.json.decodeFromString<TafActivityPage>(String(res.body)), res.headers["etag"] ?: etag)
}
suspend fun TafsCloud.runsPage(id: String, cursor: String? = null, etag: String? = null, limit: Int = 20): Representation<TafRunsPage> {
  val res = representation("/v1/tafs/${encoded(id)}/work/runs?limit=$limit" + (cursor?.let { "&cursor=${encoded(it)}" } ?: ""), etag)
  return Representation(if (res.status == 304) null else KiffClient.json.decodeFromString<TafRunsPage>(String(res.body)), res.headers["etag"] ?: etag)
}
suspend fun TafsCloud.setupView(id: String, proof: String?): TafSetup = KiffClient.json.decodeFromString(String(representation(
  "/v1/tafs/${encoded(id)}/setup", extra = proof?.let { mapOf("Planque-Device-Signature" to it) } ?: emptyMap()).body))
suspend fun TafsCloud.phoneAlerts(token: String): PhoneAlerts = KiffClient.json.decodeFromString(String(representation("/v1/push", extra = mapOf("Tafs-Device-Token" to token)).body))
suspend fun TafsCloud.timeZones(query: String): List<TimeZoneChoice> = KiffClient.json.decodeFromString<TimeZoneChoices>(String(representation("/v1/time-zones?q=${encoded(query)}").body)).zones
