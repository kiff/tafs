package bot.tafs.app.kiff

import bot.tafs.app.model.InstantAsString
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A taf's versions, kept like app builds (kiff/tafs#107), and the Card draft a version can bring: as tafs cloud and
// KIFF send them. What the app doesn't use is left out, and what it doesn't know yet is ignored.

/** What a version keeps of a trial once it ended: when, how, and KIFF's counts. */
@Serializable
data class TrialSummary(
  @Serializable(with = InstantAsString::class) val started: Instant,
  @Serializable(with = InstantAsString::class) val ended: Instant,
  /** installed, back, idle or replaced. */
  val why: String = "",
  val calls: Int = 0,
  val held: Int = 0,
  val refused: Int = 0,
)

/** One version of a taf: any one can be live, forward or back. */
@Serializable
data class Version(
  val n: Int,
  /** What changed, in plain words. */
  val note: String? = null,
  /** Who made it: the owner's subject, or "setup". */
  val by: String? = null,
  @Serializable(with = InstantAsString::class) val created: Instant,
  val live: Boolean = false,
  /** The KIFF draft whose confirmation brings this version's Card revision, while it isn't confirmed; null: the taf's current Cards. */
  val draft: String? = null,
  /** Its trials that ended, oldest first. */
  val trials: List<TrialSummary>? = null,
  /** Who made it, in tafs cloud's words ("By Coach mode", "By you"); none for one kiff/agents wrote itself. */
  @SerialName("by_words") val byWords: String? = null,
)

/** A switch to version n under way: nothing of the taf runs until it ends, with that version live, or is dropped. */
@Serializable
data class Switching(
  val n: Int,
  /** The KIFF draft whose confirmation it waits for. */
  val draft: String? = null,
  @Serializable(with = InstantAsString::class) val at: Instant? = null,
)

/** The taf's versions, oldest first, the live one marked, and a switch under way. */
@Serializable
data class VersionState(val versions: List<Version> = emptyList(), val switching: Switching? = null)

@Serializable
internal data class SwitchResponse(val switching: Switching)

@Serializable
internal data class CancelledResponse(val cancelled: Boolean = false)

/** A run in the way of an install: one at work, or one waiting on the owner. */
@Serializable
data class BlockingRun(val run: String = "", val session: String = "", val status: String = "", val trigger: String? = null)

/** The taf's open trial: the owner chats with version n under its own trial Card, while the live version keeps its schedule, Card and budget. One per taf. */
@Serializable
data class OpenTrial(
  val id: String = "",
  val n: Int,
  /** The KIFF draft trying as its Card. */
  val draft: String,
  /** Its chat, opened with the trial. */
  val session: String? = null,
  @Serializable(with = InstantAsString::class) val started: Instant? = null,
  /** When the owner last wrote in it: unused for 7 days, it ends. */
  @Serializable(with = InstantAsString::class) val used: Instant? = null,
  /** Its guidance, as tafs cloud words it: a badge ("Trial"), a title ("Trying version 2"), what the version changes, the task to try it with, and when it ends unused. */
  val label: String? = null,
  val title: String? = null,
  val changed: String? = null,
  @SerialName("try") val tryWith: String? = null,
  @Serializable(with = InstantAsString::class) val ends: Instant? = null,
)

@Serializable
internal data class TrialResponse(val trial: OpenTrial? = null)

@Serializable
internal data class TrialDraftResponse(val draft: String)

@Serializable
internal data class TrialInstalled(val ended: TrialSummary)

/** The session and run one message to a trial started. */
@Serializable
data class TrialTurn(val session: String = "", val run: String = "")

/** What this person may do with a taf, as KIFF answers it: use it, see everyone's work with it, change it. */
@Serializable
data class Access(val use: Boolean = false, val oversee: Boolean = false, val manage: Boolean = false, val groups: List<String>? = null)

@Serializable
internal data class AccessResponse(val agents: Map<String, Access> = emptyMap())

/** One action of a Card as a draft reads it, with its per-call caps. */
@Serializable
data class DraftGrant(
  val action: String,
  val title: String? = null,
  @SerialName("per_action_max") val perActionMax: List<DraftCap>? = null,
)

/** A per-call cap: `unit` and `scale` when the tool declares them. */
@Serializable
data class DraftCap(val quantity: String, val max: Long = 0, val unit: String? = null, val scale: Int? = null)

/** A total over a window. */
@Serializable
data class DraftAggregate(val quantity: String, val limit: Long = 0, val window: String = "day", val unit: String? = null, val scale: Int? = null)

/** A Card's terms alone: what `current` holds. */
@Serializable
data class DraftTerms(
  val grants: List<DraftGrant>? = null,
  val aggregates: List<DraftAggregate>? = null,
  @SerialName("on_exceed") val onExceed: String? = null,
  @SerialName("lines") val kiffLines: List<String>? = null,
) {
  val lines: List<String> get() = DraftCard(grants, aggregates, onExceed = onExceed, kiffLines = kiffLines).lines
}

/** One Card of a draft, as the owner reads it: each action and its limits. */
@Serializable
data class DraftCard(
  val grants: List<DraftGrant>? = null,
  val aggregates: List<DraftAggregate>? = null,
  /** A revision draft's Card for one the agent holds: that Card's id, and its terms as they are now, so the owner reads now → after. */
  val revises: String? = null,
  val current: DraftTerms? = null,
  /** KIFF says a Card the draft revises keeps its full terms: only KIFF holds both, so the app never works it out from the lines. */
  val unchanged: Boolean? = null,
  /** What KIFF does with a call past a limit: "owner" holds it for the owner, "refuse" refuses it; null from a KIFF that doesn't say. */
  @SerialName("on_exceed") val onExceed: String? = null,
  /** KIFF's own words for the Card: shown as they are when sent. The app words it only for a KIFF that doesn't send them. */
  @SerialName("lines") val kiffLines: List<String>? = null,
) {
  /** Past a limit, the call asks the owner first: the lines say so, as KIFF's own words do. */
  val asksPastLimits: Boolean get() = onExceed == "owner"

  val isUnchanged: Boolean get() = unchanged == true && current != null

  /**
   * The lines the owner approves, in their words: "Refund a payment: up to €100.00 each", "€1,000.00 in total a day", "20 calls a day".
   * On a Card that asks past its limits: "up to €100.00 each; more asks you first", "€1,000.00 in total a day, then it asks you",
   * "20 calls a day, then it asks you", and a count of 0 is "Each call asks you first".
   */
  val lines: List<String> get() = lines(alike(grants.orEmpty()))

  /**
   * The lines, where a grant whose name is in [alike] (from every Card the owner reads together) adds its
   * action in words. An amount's unit is KIFF's, or the number stays bare: never a guessed name or currency.
   */
  fun lines(alike: Set<String>): List<String> {
    kiffLines?.takeIf { it.isNotEmpty() }?.let { return it }
    val out = mutableListOf<String>()
    for (g in grants.orEmpty()) {
      val name = KiffMapping.name(g.action, g.title)
      val caps = g.perActionMax.orEmpty().map { cap ->
        if (KiffMapping.isSum(cap.quantity)) "up to ${KiffMapping.money(cap.max, cap.unit, cap.scale).formatted} each" else "up to ${cap.max} each"
      }
      val action = if (name.lowercase() in alike) "$name · ${KiffMapping.toolWords(g.action)}" else name
      val more = if (asksPastLimits) "; more asks you first" else ""
      out += if (caps.isEmpty()) action else "$action: " + caps.joinToString(", ") + more
    }
    val then = if (asksPastLimits) ", then it asks you" else ""
    for (a in aggregates.orEmpty()) {
      val window = KiffMapping.window(a.window).label
      out += when {
        KiffMapping.isSum(a.quantity) -> "${KiffMapping.money(a.limit, a.unit, a.scale).formatted} in total $window$then"
        asksPastLimits && a.limit == 0L -> "Each call asks you first"
        else -> "${a.limit} ${if (a.limit == 1L) "call" else "calls"} $window$then"
      }
    }
    return out
  }

  companion object {
    /** The names, ignoring case, that more than one of these grants would read as. */
    fun alike(grants: List<DraftGrant>): Set<String> {
      val seen = mutableSetOf<String>()
      val twice = mutableSetOf<String>()
      for (g in grants) {
        val name = KiffMapping.name(g.action, g.title).lowercase()
        if (!seen.add(name)) twice += name
      }
      return twice
    }
  }
}

/** An agent's Card confirming a draft revokes: going back to a version that didn't hold it, with its terms now. */
@Serializable
data class DraftRetired(val id: String, val current: DraftTerms? = null)

/** A draft of an agent's Cards, as KIFF keeps it: what confirming it would issue, change or remove. */
@Serializable
data class AgentDraft(
  val id: String,
  @SerialName("agent_id") val agentId: String = "",
  /** draft (waits for someone who may confirm it), confirming, confirmed, rolling_back, failed, discarding, discarded, claimed. */
  val status: String = "",
  val version: String = "",
  @SerialName("card_ids") val cardIds: List<String>? = null,
  val reason: String? = null,
  /** The Cards it would issue, as the owner reads them before confirming. */
  val cards: List<DraftCard>? = null,
  val label: String? = null,
  val retires: List<DraftRetired>? = null,
  /** Shown once, when an agent with no tools is submitted: tafs cloud claims the agent's key with it, so the key never reaches the phone. */
  @SerialName("claim_secret") val claimSecret: String? = null,
) {
  val confirmed: Boolean get() = status == "confirmed"

  /**
   * Each Card's lines, as the owner reads them together before one confirmation signs them all: two grants that
   * read alike, on one Card or two, each add their action. Every place that shows a draft's Cards reads them from here.
   */
  val cardLines: List<List<String>>
    get() {
      val all = cards.orEmpty()
      val alike = DraftCard.alike(all.flatMap { it.grants.orEmpty() })
      return all.map { it.lines(alike) }
    }
}
