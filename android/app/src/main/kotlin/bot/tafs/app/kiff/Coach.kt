package bot.tafs.app.kiff

import bot.tafs.app.model.InstantAsString
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Coach, the account's Setup agent (docs/coach.md): it runs on tafs cloud like a taf, and its thread carries cards, which the
// phone reads leniently: one it doesn't know is left out, never the thread with it.

@Serializable
data class SetupStep(
  val label: String,
  /** pending, active, done, failed or skipped. */
  val status: String = "pending",
)

@Serializable
data class SetupOption(val value: String, val label: String)

/** One choice of a question: every word is the agent's. */
@Serializable
data class SetupChoice(val value: String, val label: String, val description: String? = null)

/** One of several questions an agent asks at once: the app adds the owner's own answer to each. */
@Serializable
data class SetupQuestion(
  val id: String,
  val text: String,
  val options: List<SetupChoice> = emptyList(),
  /** Several may be picked. */
  val multi: Boolean? = null,
  val note: String? = null,
)

/** The taf a hand-over names: a new one to start, or the next version of one (an improvement). */
@Serializable
data class SetupTaf(
  val agent: String = "",
  val name: String = "",
  val job: String? = null,
  /** The taf's KIFF draft, the one the owner confirmed: its key is claimed from it. For an improvement, the draft revising its Cards, confirmed only at the switch. */
  val draft: String? = null,
  /** The tafs id of the taf this is the next version of: an improvement, never a new taf. */
  val improves: String? = null,
  /** What changed, in the owner's language (an improvement). */
  val note: String? = null,
  /** The task to try the new version with, in the owner's words: its trial shows it. */
  @SerialName("try") val tryWith: String? = null,
  /** The taf's full new instructions from the owner; null keeps them (an improvement). */
  val notes: String? = null,
  /** Its tools can't find its work by themselves: it works from its chat. */
  val unscheduled: Boolean? = null,
)

/**
 * One thing Coach shows in its chat: its plan, a notice, a question, an approval, a vendor sign-in or the hand-over. A card
 * grants nothing: an approval names planque's grant and KIFF's draft, which the owner reads and signs there.
 */
@Serializable
data class SetupComponent(
  /** steps, notice, confirm, questions, approval, sign_in, result, knob or drive (Coach asks to drive the app: docs/drive.md). */
  val type: String,
  val id: String,
  val title: String? = null,
  val text: String? = null,
  /** info, success, warning or error. */
  val tone: String? = null,
  val steps: List<SetupStep>? = null,
  val options: List<SetupOption>? = null,
  val questions: List<SetupQuestion>? = null,
  /** planque's grant id. */
  val grant: String? = null,
  /** KIFF's agent draft id: the taf's Cards. */
  val draft: String? = null,
  /** The vendor's host, such as mcp.stripe.com. */
  val service: String? = null,
  /** The hand-over's taf, an object; on a `knob` card, the taf's tafs cloud id, a string. Read as it comes ([taf], [knobTaf]). */
  @SerialName("taf") val tafValue: kotlinx.serialization.json.JsonElement? = null,
  /** A `knob` card's taf, by its tafs cloud id, when it's sent under this name. */
  @SerialName("taf_id") val tafId: String? = null,
  /** A `knob` card's dial version: the card is drawn from that taf's knob `draft` while it's this version. */
  val version: Int? = null,
  val tools: List<String>? = null,
  val yes: String? = null,
  val no: String? = null,
  val note: String? = null,
  /** A plan's one-line form, from tafs cloud: "Step 3 of 6 · Your rules", or "Done · 6 of 6". */
  val line: String? = null,
) {
  /** The hand-over's taf, when [tafValue] is one. */
  val taf: SetupTaf?
    get() = (tafValue as? kotlinx.serialization.json.JsonObject)?.let { runCatching { KiffClient.json.decodeFromJsonElement(SetupTaf.serializer(), it) }.getOrNull() }

  /** A `knob` card's taf id, under `taf_id` or `taf`. */
  val knobTaf: String? get() = tafId ?: (tafValue as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

  /** Where a sign-in goes, as the app shows it: from the card's service, never from Coach's words, so a label can't stand in for the destination. */
  val destination: String get() = service ?: "the vendor's page"

  companion object {
    val KINDS = setOf("steps", "notice", "confirm", "questions", "approval", "sign_in", "result", "knob", "drive")
  }
}

/** A component as the conversation lists it, read apart so one the app can't read is skipped. */
@Serializable
data class WireShown(
  @Serializable(with = InstantAsString::class) val at: Instant,
  val session: String = "",
  val run: String = "",
  val component: JsonElement,
  val answer: JsonElement? = null,
  /** The card's own key in the thread, unique in it, from tafs cloud. */
  val key: String? = null,
)

/**
 * A card's answer as kiff/agents recorded it. [by] is "tafs cloud" when tafs cloud answered a result itself, with its [outcome]:
 * the taf it set up, the version it made, or why it didn't.
 */
@Serializable
data class CardAnswer(
  val state: String = "answered",
  val words: String = "",
  val by: String? = null,
  val outcome: ResultOutcome? = null,
  /** When it was answered, as tafs cloud writes it; null from a tafs cloud that doesn't say. */
  @SerialName("at") val atText: String? = null,
) {
  val at: java.time.Instant? get() = atText?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
}

/** A report of an answer, as tafs cloud kept it: `new` is false when this person had reported that answer already. */
@Serializable
data class AnswerReport(val id: String, val at: String? = null, val new: Boolean = true)

@Serializable
data class Reported(val report: AnswerReport)

/** What answering a card gives back: the run that goes on with it. */
@Serializable
data class CardAnswered(val session: String? = null, val run: String? = null, val status: String? = null)

/**
 * tafs cloud's decision on an approval card (kiff/tafs#413). [outcome] is `answered` (Coach heard [answer] from tafs cloud;
 * [note] is the card's line), `read_again` (the card is out of date: [note] says so; nothing was told) or `sign` ([ask]: the
 * one message to sign and the call it goes into).
 */
@Serializable
data class ApprovalDecision(
  val outcome: String,
  val answer: String? = null,
  val note: String? = null,
  val ask: ApprovalAsk? = null,
  val session: String? = null,
  val run: String? = null,
  val status: String? = null,
)

/** One message for the device's key, and the call it goes into: planque's approve body, or KIFF's draft to confirm. */
@Serializable
data class ApprovalAsk(
  val id: String,
  /** `connect` or `cards`. */
  val step: String,
  /** The exact bytes the device's key signs, under the fingerprint. */
  val message: String,
  /** What the prompt says. */
  val reason: String,
  /**
   * Which key the call takes, tafs cloud's hint (kiff/tafs#494): `window`, the device key inside an approval window, a tap;
   * `critical`, the approval key behind the fingerprint. The service the call goes to decides; none reads as `critical`.
   */
  val key: String? = null,
  val planque: PlanqueAsk? = null,
  val kiff: KiffAsk? = null,
)

/** planque's approve, its body as tafs cloud built it: the phone sends it as it is. */
@Serializable
data class PlanqueAsk(val grant: String, val body: JsonElement)

/** KIFF's draft to confirm, at exactly this version. */
@Serializable
data class KiffAsk(val draft: String, val version: String)

@Serializable
data class ResultOutcome(@SerialName("taf_id") val tafId: String? = null, val version: Int? = null, val failed: String? = null)

/** A component as the thread holds it: where it first appeared, its latest version (showing an id again replaces it in place), and its answer. */
data class ShownComponent(
  val firstAt: Instant,
  val session: String,
  val run: String,
  val component: SetupComponent,
  val answer: CardAnswer? = null,
  /** tafs cloud's key for the card, unique in the thread; null from a tafs cloud that doesn't send one. */
  val key: String? = null,
) {
  /** The model's name for the card: what an answer is sent for. Coach can use one again, so it never tells cards apart. */
  val id: String get() = component.id

  /** Which card it is: tafs cloud's key, else its conversation and id. What the phone keys a card by, never [id] alone. */
  val ref: String get() = key ?: bot.tafs.app.model.CoachWords.cardKey(session, component.id)
}

/** The account's Setup, as tafs cloud runs it: its id there. */
@Serializable
data class CloudSetup(
  val id: String,
  val name: String = "Coach",
  /** The KIFF agent Coach acts as: the holder of its Cards. */
  @SerialName("kiff_agent") val kiffAgent: String? = null,
)

@Serializable
internal data class SetupResponse(val setup: CloudSetup? = null, val tools: CoachTools? = null)

@Serializable
internal data class CreatedVersion(val version: Int)

/**
 * Coach's tools as KIFF has them, from tafs cloud (`GET /v1/setup`'s `tools`), read each time so every phone of the account
 * agrees: `given`, `to_give` with what's left, `taken` (a vendor's tool holds one of Coach's names) or `unknown` (KIFF didn't
 * answer). The phone signs and sends each body exactly as it comes: it builds none of it.
 */
@Serializable
data class CoachTools(
  val state: String = "unknown",
  val taken: String? = null,
  val connect: CoachConnect? = null,
  val card: CoachCardDraft? = null,
) {
  val toGive: Boolean get() = state == "to_give"
}

/** The batch for KIFF's `POST /v1/connections/batch`, and a line per tool for the owner to read before signing. */
@Serializable
data class CoachConnect(val body: kotlinx.serialization.json.JsonObject, val lines: List<String> = emptyList())

/** Coach's Cards' draft for `POST /v1/me/agent-drafts`, with KIFF's own words for it from its preview, unchanged. */
@Serializable
data class CoachCardDraft(
  val body: kotlinx.serialization.json.JsonObject,
  @SerialName("card_title") val cardTitle: String? = null,
  val cards: List<kotlinx.serialization.json.JsonElement> = emptyList(),
) {
  /** Each Card in KIFF's words, its title and lines; one the app can't read is left out. */
  val words: List<Pair<String, List<String>>>
    get() = cards.mapNotNull { element ->
      val card = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
      fun text(key: String) = (card[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
      val lines = (card["lines"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
      val title = text("title") ?: text("card_title") ?: text("label")
      if (title == null && lines.isEmpty()) return@mapNotNull null
      (title ?: "") to lines
    }
}

/**
 * Where a taf's work stands, as tafs cloud says it (`GET /v1/tafs/{id}/work`): its summary line, its next run, and its recent
 * requests and skipped firings, newest first. Every line and time is tafs cloud's, in the account's zone: the app formats none.
 */
@Serializable
data class TafWork(
  val summary: WorkSummary? = null,
  val requests: List<WorkRequest> = emptyList(),
  /** Scheduled runs that didn't happen: the last one was still working (busy), or the time passed (missed). */
  val skipped: List<SkippedFiring> = emptyList(),
)

@Serializable
data class WorkSummary(val status: String = "idle", val line: String = "", @SerialName("needs_you") val needsYou: Int = 0)

/** One request: done, working, retrying, needs_you, waiting, stopped or failed, in tafs cloud's words, with where it went. */
@Serializable
data class WorkRequest(
  val id: String,
  @Serializable(with = bot.tafs.app.model.InstantAsString::class) val at: Instant,
  val state: String = "",
  val line: String = "",
  /** What a waiting run waits for, in the connection's words, when it waits for an app's event: not for the owner. */
  @SerialName("waiting_for") val waitingFor: String? = null,
  val deliveries: List<WorkDelivery> = emptyList(),
)

/**
 * One request from the dial's rim, step by step (`GET /v1/tafs/{id}/requests/{run}`): what the taf's work gives for it, where it
 * came from, and its steps. Every line and time is tafs cloud's, in the account's zone, drawn as it is.
 */
@Serializable
data class RequestDetail(
  val id: String,
  @Serializable(with = bot.tafs.app.model.InstantAsString::class) val at: Instant,
  val state: String = "",
  val line: String = "",
  /** What it waits for, in the connection's words, while it waits for an app's event (the slow-tools design). */
  @SerialName("waiting_for") val waitingFor: String? = null,
  val deliveries: List<WorkDelivery> = emptyList(),
  /** "By email", "Its schedule", "From its chat", "Run now". */
  val from: String? = null,
  val steps: List<RequestStep> = emptyList(),
)

/** A step: Received, each call by KIFF's title for its tool, a hold, where the reply went, then the end or what comes next. */
@Serializable
data class RequestStep(
  val label: String = "",
  val line: String? = null,
  /** Its time as tafs cloud wrote it, in the account's zone. */
  val at: String? = null,
  /** done, failed, not_made, waiting or next. */
  val state: String = "done",
  /** `app` on a waiting step when the run waits for a connected app's event, not for the owner (the slow-tools design). */
  @SerialName("waits_on") val waitsOn: String? = null,
)

/** Where a request's result went, and whether it can be sent again (a reply given up on after its tries). */
@Serializable
data class WorkDelivery(
  /** `door_reply`, or a place of the taf's own: `action` (an app's action) or `taf` (another taf). */
  val place: String = "door_reply",
  val state: String = "",
  val line: String = "",
  @SerialName("send_again") val sendAgain: Boolean = false,
  /** A place of the taf's own: its key (`<grant>:<action>`), which Send again names, and its name in tafs cloud's words. */
  val key: String? = null,
  val name: String? = null,
  /** Still not delivered: Coach may be asked about it, in tafs cloud's words for what to ask. */
  @SerialName("open_coach") val openCoach: Boolean = false,
  @SerialName("coach_words") val coachWords: String? = null,
) {
  /** What Send again names in its route: the door reply, or the place's own key. */
  val sendPlace: String get() = if (place == "door_reply") place else key ?: place
}

/** A scheduled run that was skipped: when it was due, why (missed or busy), and tafs cloud's words for it. */
@Serializable
data class SkippedFiring(
  @Serializable(with = bot.tafs.app.model.InstantAsString::class) val due: Instant,
  val why: String = "",
  val words: String = "",
)
