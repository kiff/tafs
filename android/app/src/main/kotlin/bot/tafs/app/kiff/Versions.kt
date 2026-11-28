package bot.tafs.app.kiff

import bot.tafs.app.model.Words
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

/** A run that waited on the owner's answer and was stopped to let a version in (`stop_blocking`): its message didn't run. */
@Serializable
data class StoppedRun(val run: String, val session: String)

/**
 * A switch begun: the switch, and, when `stop_blocking` stopped a task that waited on the owner's answer, which ones and tafs
 * cloud's words saying so. Both are left out when nothing waiting was stopped.
 */
@Serializable
data class SwitchResponse(val switching: Switching, val stopped: List<StoppedRun> = emptyList(), val words: String? = null)

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
  /** The states of the thing acted on that the grant is limited to; none, any. */
  @SerialName("entity_states") val entityStates: List<String>? = null,
  /** The named resources the grant is limited to ("order_id": ["ord_123"]); none, any. */
  val resources: DraftResources? = null,
  /** The answers besides approve and refuse the owner may give on a held call under this grant. */
  @SerialName("next_steps") val nextSteps: List<String>? = null,
  /**
   * The categories the grant opens (K4: irreversible, delete or money, whole or for one argument's value), as KIFF keeps them.
   * Read so the phone compares them: this app draws no line for an opening yet, so a draft that opens anything the Card in force
   * doesn't is never signed here (docs/allow-from-now-on.md).
   */
  val opens: List<kotlinx.serialization.json.JsonElement>? = null,
) {
  /** Its openings as comparable text, each a canonical JSON object, in order. */
  val openTerms: List<String> get() = opens.orEmpty().map { Jcs.canonical(it) }.sorted()
}

/** A grant's scope: one parameter, and the values it may act on. */
@Serializable
data class DraftResources(val parameter: String = "", val values: List<String> = emptyList())

/** A per-call cap: `unit` and `scale` when the tool declares them. */
@Serializable
data class DraftCap(val quantity: String, val max: Long = 0, val unit: String? = null, val scale: Int? = null)

/** A total over a window. */
@Serializable
data class DraftAggregate(
  val quantity: String,
  val limit: Long = 0,
  val window: String = "day",
  val unit: String? = null,
  val scale: Int? = null,
  /** What the total counts: `attempts` or `effects`; none is KIFF's default for the quantity. */
  val measure: String? = null,
  /** A string parameter: the limit holds for each of its values apart ("1 a week per invoice"); none, one limit for every call. */
  val per: String? = null,
)

/** A Card's terms alone: what `current` holds. */
@Serializable
data class DraftTerms(
  /** The Card in force's own id, when KIFF sends it: it must be the Card `revises` names (HoldAllow.matches). */
  val id: String? = null,
  val grants: List<DraftGrant>? = null,
  val aggregates: List<DraftAggregate>? = null,
  @SerialName("on_exceed") val onExceed: String? = null,
  @SerialName("lines") val kiffLines: List<String>? = null,
  /** Every holder of the Card, the first its subject. */
  val subjects: List<String>? = null,
  @SerialName("valid_from") val validFrom: String? = null,
  @SerialName("valid_until") val validUntil: String? = null,
  val subject: String? = null,
  val domain: String? = null,
  val environment: String? = null,
  /** The time zone its daily totals reset in. */
  val location: String? = null,
  /** How long a held call waits for its answer, in seconds; 0 or none, KIFF's default. */
  @SerialName("hold_expiry_seconds") val holdExpirySeconds: Long? = null,
  @SerialName("revoked_at") val revokedAt: String? = null,
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
  /** Every holder the Card would have; none on a revision keeps the Card's, unless [subject] names another first holder. */
  val subjects: List<String>? = null,
  @SerialName("valid_from") val validFrom: String? = null,
  @SerialName("valid_until") val validUntil: String? = null,
  val subject: String? = null,
  val domain: String? = null,
  val environment: String? = null,
  /** The time zone its daily totals reset in; none on a revision keeps the Card's. */
  val location: String? = null,
  /** How long a held call waits ("10m", "24h"); none or "keep" on a revision keeps the Card's. */
  @SerialName("hold_expiry") val holdExpiry: String? = null,
  @SerialName("revoked_at") val revokedAt: String? = null,
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
   * The lines, in the app's words, only for a KIFF that sends none of its own. An amount's unit is KIFF's, or the number stays
   * bare: never a guessed name or currency. A grant whose name is in [alike] (from every Card the owner reads together) adds its
   * action in words when they tell it apart; the same tool through two of planque's grants (ids that differ only by planque's
   * suffix) reads as its name, once when the terms are the same, and by its terms when they differ: never an id.
   */
  fun lines(alike: Set<String>): List<String> {
    kiffLines?.takeIf { it.isNotEmpty() }?.let { return it }
    val out = mutableListOf<String>()
    for (g in grants.orEmpty()) {
      val name = KiffMapping.name(g.action, g.title)
      val caps = g.perActionMax.orEmpty().map { cap ->
        if (KiffMapping.isSum(cap.quantity)) Words.Card.Line.upToEach(amount = KiffMapping.money(cap.max, cap.unit, cap.scale).formatted) else Words.Card.Line.upToEach(amount = cap.max.toString())
      }
      // Two grants that read alike are told apart by their actions' words ("Write Stripe API data · create refund"), unless those
      // only repeat the name with planque's own suffix, four letters or digits of its grant per name ("list client invoices yhbp"):
      // then they say nothing the owner can use. Any other word after the name ("send email", "send sms") still tells them apart.
      val words = KiffMapping.toolWords(g.action)
      val action = if (name.lowercase() in alike && !onlyPlanqueSuffix(name, words)) "$name · $words" else name
      val more = if (asksPastLimits) Words.Card.Line.moreAsks else ""
      out += if (caps.isEmpty()) action else "$action: " + caps.joinToString(", ") + more
    }
    val then = if (asksPastLimits) Words.Card.Line.thenAsks else ""
    for (a in aggregates.orEmpty()) {
      val window = KiffMapping.window(a.window).label
      out += when {
        KiffMapping.isSum(a.quantity) -> Words.Card.Line.totalInWindow(amount = KiffMapping.money(a.limit, a.unit, a.scale).formatted, window = window, then = then)
        asksPastLimits && a.limit == 0L -> Words.Card.Held.eachCallAsks
        else -> Words.Card.Line.callsInWindow(count = a.limit.toInt(), window = window, then = then)
      }
    }
    return out.distinct()
  }

  companion object {
    /**
     * Whether an action's [words] are its [name] followed only by planque's suffixes: four letters or digits of a grant's id,
     * added once per name it gives a tool (planque's `toolKey`), so the same tool through two grants reads alike but for them.
     */
    fun onlyPlanqueSuffix(name: String, words: String): Boolean {
      val n = name.lowercase().split(' ').filter { it.isNotEmpty() }
      val w = words.lowercase().split(' ').filter { it.isNotEmpty() }
      return w.size > n.size && w.take(n.size) == n && w.drop(n.size).all { it.length == 4 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } }
    }

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

/** The held call a draft's confirm answered, as KIFF says: its id, and "approved" or why it wasn't. */
@Serializable
data class DraftHold(val id: String = "", val status: String = "")

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
  /** What became of the held call a confirm named (KiffClient.confirmDraft with a hold): "approved", or why not. */
  val hold: DraftHold? = null,
  /** The held call a draft was made from (KIFF's allow-draft), when KIFF says. */
  @SerialName("from_hold") val fromHold: String? = null,
  /** An agent drafted with no tools (Coach's own start): confirming it issues no Card to name. */
  @SerialName("no_tools") val noTools: Boolean? = null,
) {
  val confirmed: Boolean get() = status == "confirmed"

  /** KIFF took the owner's signature for it: confirmed, or confirmed and its key claimed since. */
  val signed: Boolean get() = status == "confirmed" || status == "claimed"

  /** Signed, and with a Card to name: what a signed approval's folded line names and lists. */
  val signedCard: Boolean get() = signed && noTools != true

  /** Whether KIFF approved held call [id] with this confirm, so it's answered already. */
  fun approvedHold(id: String): Boolean = hold?.id == id && hold.status == "approved"

  /**
   * Each Card's lines, as the owner reads them together before one confirmation signs them all: KIFF's own when it sends them,
   * else the app's, where two grants that read alike add their action's words only when those tell them apart, never an id.
   * Every place that shows a draft's Cards reads them from here.
   */
  val cardLines: List<List<String>>
    get() {
      val all = cards.orEmpty()
      val alike = DraftCard.alike(all.flatMap { it.grants.orEmpty() })
      return all.map { it.lines(alike) }
    }
}
