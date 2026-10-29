package bot.tafs.app.model

import java.text.NumberFormat
import java.time.Instant
import java.util.Currency
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** An instant as RFC 3339 text: read with a "Z" or any offset, as KIFF writes them, with or without fractions. */
object InstantAsString : KSerializer<Instant> {
  override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
  override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
  override fun deserialize(decoder: Decoder): Instant {
    val raw = decoder.decodeString()
    return runCatching { Instant.parse(raw) }.getOrNull() ?: java.time.OffsetDateTime.parse(raw).toInstant()
  }
}

fun newId(): String = UUID.randomUUID().toString()

/**
 * An amount as an integer, with what it means when that is known: a unit (a currency code such
 * as "EUR", or another unit) and its decimal places.
 *
 * Without a unit it is a bare number and is shown as stored: the app never guesses a currency or
 * a scale, and never lets it read like money ("30 000 (no currency)", kiff/tafs#309).
 */
@Serializable
data class Money(val minor: Long, val currency: String? = null, val scale: Int = 0) : Comparable<Money> {
  val hasUnit: Boolean get() = currency != null

  override fun compareTo(other: Money): Int = minor.compareTo(other.minor)

  /** Adding to zero takes the other side's unit, so sums start from [ZERO]. */
  operator fun plus(other: Money): Money {
    val unit = if (minor == 0L && !hasUnit) other else this
    return of(minor + other.minor, unit.currency, unit.scale)
  }

  /** The amount in whole units: 2550 at scale 2 is 25.5. */
  val value: java.math.BigDecimal get() = java.math.BigDecimal.valueOf(minor, scale)

  /**
   * The amount as shown: with its unit, or saying it has none (kiff/tafs#309). An amount with no
   * currency is the vendor's own number, often its smallest unit (cents), so it never stands
   * alone where money would.
   */
  val formatted: String get() = if (hasUnit) figure else "$figure (no currency)"

  /** The amount without the "no currency" note: for a figure whose words say it once. */
  val figure: String
    get() {
      val unit = currency ?: return NumberFormat.getIntegerInstance().format(minor)
      val whole = minor % power(scale) == 0L
      val digits = if (whole) 0 else scale
      val code = unit.uppercase()
      val iso = runCatching { Currency.getInstance(code) }.getOrNull()
      if (iso != null) {
        val f = NumberFormat.getCurrencyInstance(Locale.getDefault())
        f.currency = iso
        f.minimumFractionDigits = digits
        f.maximumFractionDigits = digits
        return f.format(value)
      }
      val f = NumberFormat.getNumberInstance(Locale.getDefault())
      f.minimumFractionDigits = digits
      f.maximumFractionDigits = digits
      return "${f.format(value)} $unit"
    }

  companion object {
    val ZERO = bare(0)

    /** A number with no declared unit; a unit-less amount has no decimal places. */
    fun of(minor: Long, currency: String?, scale: Int) = Money(minor, currency, if (currency == null) 0 else scale)
    fun bare(value: Long) = Money(value, null, 0)
    fun usd(dollars: Double) = Money(Math.round(dollars * 100), "USD", 2)

    private fun power(n: Int): Long {
      var p = 1L
      repeat(maxOf(n, 0)) { p *= 10 }
      return p
    }
  }
}

@Serializable
enum class Window(val label: String, val current: String) {
  day("a day", "today"),
  week("a week", "this week"),
  month("a month", "this month"),
}

/** What happens to an action the Card does not allow. */
@Serializable
enum class Outside {
  /** Waits for the owner. */
  hold,

  /** Refused without asking. */
  refuse,
}

/** One line of a Card: the limits on one kind of action. */
@Serializable
data class CardRule(
  val id: String = newId(),
  /** "refund", "pay_invoice", "grant_access" */
  val action: String,
  /** "Refunds" */
  val label: String,
  val perAction: Money? = null,
  val perWindow: Money? = null,
  val countPerWindow: Int? = null,
  val window: Window = Window.day,
  /** Never allowed, whatever the amount. */
  val never: Boolean = false,
  /** Allowed only with the owner's answer. */
  val alwaysAsk: Boolean = false,
  val outside: Outside = Outside.hold,
  /** Answers the owner may give besides approve and refuse, when held. */
  val nextSteps: List<String> = emptyList(),
  /**
   * Of perWindow, the extra room KIFF says is active (given on a held call), the Card's own limit,
   * and when the room ends, as KIFF says them: shown apart from the Card's own total, never worked
   * out here.
   */
  val extraRoom: Money? = null,
  val baseLimit: Money? = null,
  @Serializable(with = InstantAsString::class) val roomEnds: Instant? = null,
) {
  /** The Card's own total for its window: KIFF's, without the extra room in it. */
  val ownTotal: Money? get() = if (extraRoom != null) baseLimit ?: perWindow else perWindow

  /** "+30 US$ extra room until 00:00", when KIFF says there is some. */
  val roomWords: String?
    get() {
      val extra = extraRoom ?: return null
      if (baseLimit == null) return null
      return "+${extra.formatted} extra room " + (roomEnds?.let { "until ${endsWords(it)}" } ?: window.current)
    }

  /**
   * What's left of its total this window ("€800 left today"), given what it has used, when both are in
   * the same unit; null otherwise.
   */
  fun left(used: Money?): String? {
    val total = perWindow ?: return null
    if (used == null || total.currency != used.currency || total.scale != used.scale) return null
    val left = Money.of(maxOf(0, total.minor - used.minor), total.currency, total.scale)
    return "${left.formatted} left ${window.current}"
  }

  val summary: String
    get() {
      if (never) return "never"
      if (alwaysAsk) return "always asks you"
      val parts = mutableListOf<String>()
      perAction?.let { parts += "≤ ${it.formatted} each" }
      // A bare total and a count read apart (kiff/tafs#94): "10 in total a day · 20 calls a day".
      ownTotal?.let { parts += if (it.hasUnit) "${it.formatted} ${window.label}" else "${it.formatted} in total ${window.label}" }
      roomWords?.let { parts += it }
      countPerWindow?.let { parts += "$it ${if (it == 1) "call" else "calls"} ${window.label}" }
      return parts.joinToString(" · ")
    }
}

/** A Card's total for its window and what it has used: null when KIFF couldn't read it, never shown as zero. */
@Serializable
data class CardTotal<V>(
  val limit: V,
  val used: V?,
  val window: Window,
  /** Of limit, the extra room KIFF says is active, the Card's own limit, and when the room ends; null without any. */
  val extra: V? = null,
  val base: V? = null,
  @Serializable(with = InstantAsString::class) val roomEnds: Instant? = null,
)

/**
 * One KIFF Card, drawn on its own (kiff/tafs#211): a taf can hold several, each with its own
 * limits and balance, and a test Card is kept apart from the live one.
 */
@Serializable
data class TafCard(
  val id: String,
  /** Its own title from KIFF, else its main tool's. */
  val title: String,
  /** The environment KIFF scopes it to, as KIFF names it; empty for every environment. */
  val environment: String = "",
  /** It covers what the taf does for real: production, or every environment. */
  val live: Boolean = true,
  val active: Boolean = true,
  @Serializable(with = InstantAsString::class) val expires: Instant? = null,
  val rules: List<CardRule>,
  /** Its money total, at the ceiling it has now. */
  val total: CardTotal<Money>? = null,
  /** Its count of calls. */
  val calls: CardTotal<Int>? = null,
) {
  /** The pill on the face: the environment it's scoped to ("TEST"); nothing for a Card that acts for real. */
  val environmentLabel: String? get() = if (live || environment.isEmpty()) null else environment.uppercase()

  data class Hero(val figure: String, val words: String)

  /**
   * The face's big figure and the words under it: what's left of the money total, else of the
   * calls. Use KIFF couldn't read shows the limit alone, never as zero left or used.
   */
  val hero: Hero?
    get() {
      total?.let { t ->
        // An amount with no currency says so once, after the words, and its figures stand bare
        // beside it: "30 000 left today of 30 000 · no currency" (kiff/tafs#309).
        val unitless = !t.limit.hasUnit
        val shown = { m: Money -> if (unitless) m.figure else m.formatted }
        val note = if (unitless) " · no currency" else ""
        val used = t.used
        if (used != null && used.currency == t.limit.currency && used.scale == t.limit.scale) {
          val left = Money.of(maxOf(0, t.limit.minor - used.minor), t.limit.currency, t.limit.scale)
          // The Card's own total and the extra room apart, as KIFF says them.
          if (t.extra != null && t.base != null) {
            val until = t.roomEnds?.let { " until ${endsWords(it)}" } ?: ""
            return Hero(shown(left), "left ${t.window.current} of ${shown(t.base)} + ${shown(t.extra)} extra room$until$note")
          }
          return Hero(shown(left), "left ${t.window.current} of ${shown(t.limit)}$note")
        }
        return Hero(shown(t.limit), "${t.window.label} · use unavailable$note")
      }
      calls?.let { c ->
        val used = c.used ?: return Hero("${c.limit}", "calls ${c.window.label} · use unavailable")
        val left = maxOf(0, c.limit - used)
        return Hero("$left", "${if (left == 1) "call" else "calls"} left ${c.window.current} of ${c.limit}")
      }
      return null
    }

  /** What's left, short enough for a Card's edge in the stack: "US$170 left", "998 calls left". */
  val leftLine: String?
    get() {
      val hero = hero ?: return null
      total?.let { t ->
        val figure = if (t.limit.hasUnit) hero.figure else "${hero.figure} (no currency)"
        return if (hero.words.startsWith("left")) "$figure left" else figure
      }
      calls?.let { c -> if (c.used != null) return "${hero.figure} ${if (hero.figure == "1") "call" else "calls"} left" }
      return null
    }

  /**
   * The face's small lines under the figure: the main tool's limit for one call and what happens
   * above it, then how many more tools the Card covers. A Card with no figure shows its first
   * lines instead.
   */
  val facts: List<String>
    get() {
      val out = mutableListOf<String>()
      if (hero == null) {
        out += rules.take(2).map { "${it.label.lowercase()} ${it.summary}" }
        if (rules.size > 2) out += "${rules.size - 2} more"
        return out
      }
      rules.firstOrNull { it.perAction != null }?.let { rule ->
        out += "≤ ${rule.perAction!!.formatted} each · ${if (rule.outside == Outside.hold) "asks you above" else "refused above"}"
      }
      if (rules.size > 1) out += "${rules.size} tools"
      return out
    }

  companion object {
    /**
     * One Card from a taf's lines as the app had them before KIFF's Cards came apart: a draft's,
     * the sample's, or a list from an older tafs cloud.
     */
    fun merged(
      id: String, title: String, rules: List<CardRule>, expires: Instant?, active: Boolean = true,
      used: Map<String, Money> = emptyMap(), unknown: Set<String> = emptySet(), counted: Map<String, Int> = emptyMap(),
    ): TafCard {
      var card = TafCard(id = id, title = title, active = active, expires = expires, rules = rules)
      val money = rules.firstOrNull { it.perWindow != null }
      val count = rules.firstOrNull { it.countPerWindow != null }
      if (money?.perWindow != null) {
        val limit = money.perWindow
        val read = used[money.action] ?: if (money.action in unknown) null else Money.of(0, limit.currency, limit.scale)
        card = card.copy(total = CardTotal(limit, read, money.window))
      } else if (count?.countPerWindow != null) {
        // As the money total: no count is none used, unless KIFF couldn't read it.
        card = card.copy(calls = CardTotal(count.countPerWindow, counted[count.action] ?: if (count.action in unknown) null else 0, count.window))
      }
      return card
    }
  }
}

/** The authority a taf holds. KIFF enforces it in production; the app shows it. */
@Serializable
data class Card(
  val rules: List<CardRule>,
  val issuedBy: String,
  /** When the Card ends; null when it has no end (KIFF gives no valid_until). */
  @Serializable(with = InstantAsString::class) val expires: Instant? = null,
  val revoked: Boolean = false,
)

@Serializable
enum class TafState(val label: String) {
  working("Working"),
  waiting("Waiting"),
  idle("Ready"),
  paused("Paused"),
  revoked("Stopped"),

  /** Halted, on its own or with its account (kiff/tafs#236, item 13): nothing of it runs until resumed. */
  halted("Halted"),
}

@Serializable
data class Taf(
  val id: String = newId(),
  val name: String,
  val job: String,
  val tools: List<String>,
  val colorIndex: Int,
  val card: Card,
  val state: TafState = TafState.idle,
  val now: String = "Ready",
  /** Spent so far in the current window, per rule action. */
  val used: Map<String, Money> = emptyMap(),
  /** Rule actions whose use KIFF could not read: shown as unknown, never as zero. Null in sample mode. */
  val usageUnknown: Set<String>? = null,
  val counted: Map<String, Int> = emptyMap(),
  /** A short word beside its name, as tafs cloud sends it: "Trial" for an open trial's entry (kiff/tafs#247). */
  val badge: String? = null,
  /** Each of its KIFF Cards on its own, the live ones first (kiff/tafs#211); null from an older tafs cloud, and in the sample. */
  val kiffCards: List<TafCard>? = null,
  /** The KIFF Cards this taf holds, when signed in to KIFF: the live ones, which revoking stops. */
  val kiffCardIds: List<String> = emptyList(),
  /** tafs cloud's id for it, which its chat and runs are under; null for an agent tafs doesn't run, and in the sample. */
  val cloudId: String? = null,
) {
  val initial: String get() = name.take(1).uppercase()

  /**
   * What a rule has used this window. Nothing used reads as zero in the limit's own unit ("$0"),
   * never as a bare zero, which would say "no currency".
   */
  fun usedOf(rule: CardRule): Money =
    used[rule.action] ?: rule.perWindow?.let { Money.of(0, it.currency, it.scale) } ?: Money.ZERO

  /** The Cards its page draws: each KIFF Card, else its lines as one Card. */
  val shownCards: List<TafCard>
    get() {
      // The live Cards first, and among them the one that counts money: for a taf that pays out,
      // what's left to spend matters most. Otherwise as KIFF lists them.
      if (!kiffCards.isNullOrEmpty()) {
        return kiffCards.withIndex().sortedWith(
          compareBy({ (if (it.value.live) 0 else 2) + (if (it.value.total != null) 0 else 1) }, { it.index }),
        ).map { it.value }
      }
      return listOf(TafCard.merged("merged", name, card.rules, card.expires, !card.revoked, used, usageUnknown ?: emptySet(), counted))
    }
}

/** A taf as drafted from the owner's words, before its Card is issued. */
data class TafDraft(
  val name: String,
  val job: String,
  val tools: List<String>,
  val rules: List<CardRule>,
  val templateKey: String? = null,
)

/** A note a taf wrote down about how the owner likes the job done. */
data class MemoryNote(val id: String, val path: String, val content: String, val updatedAt: Instant) {
  /** The note's name, from its path: "/customers/acme.md" reads "customers · acme". */
  val title: String
    get() = path.split("/").filter { it.isNotEmpty() }.joinToString(" · ") { it.replace(".md", "").replace("-", " ") }
}

/** An action a taf asked to take. */
@Serializable
data class ProposedAction(
  val action: String,
  /** "Refund order #1042" */
  val summary: String,
  val amount: Money? = null,
  /** What the taf saw, in one line. */
  val context: String = "",
)

/**
 * What a hold says when it's back after the owner gave it extra room (KIFF keeps its id): the first step went through, and
 * why one more answer is needed, in KIFF's own reason. Two steps, as KIFF keeps them: extra room raises a total, and doesn't
 * itself pass a limit for one call.
 */
object SecondStep {
  fun words(reason: String): String =
    "Extra room went through. This call is held again for a limit of its own: ${reason.trim().trimEnd('.')}. Approve it once to let it go, or refuse it."
}

/** An action outside the Card, waiting for the owner. */
@Serializable
data class HeldCall(
  val id: String = newId(),
  val tafId: String,
  val proposed: ProposedAction,
  /** "Over $50 each" */
  val reason: String,
  @Serializable(with = InstantAsString::class) val heldAt: Instant,
  @Serializable(with = InstantAsString::class) val expiresAt: Instant,
  /** KIFF's id for this held call, when it came from KIFF. */
  val kiffId: String? = null,
  /** The proposal KIFF held, which its record files the call under. */
  val proposalId: String? = null,
  /** KIFF's accounting version for the hold, signed with the answer. */
  val accountingVersion: Int? = null,
  /**
   * Whether approving it once can pass it (kiff/tafs#138, item 49): not when it's over a total
   * the Card keeps across calls (a day's), which only a change to the Card can lift.
   */
  val approvableOnce: Boolean = true,
  /** Other answers the Card allows besides approve and refuse ("return first"). */
  val nextSteps: List<String> = emptyList(),
  /** What the call is on, one line each, as KIFF keeps its arguments. */
  val details: List<String>? = null,
  /**
   * Coach asks to install a version (kiff/tafs#206): approving it is installing it, switch and Card first, then this answer, in one
   * confirmation. Null for any other call.
   */
  val install: VersionInstall? = null,
)

/** The taf and version a held version_install names, as Coach sent them: the taf's id in tafs cloud, or its name. */
@Serializable
data class VersionInstall(val taf: String, val n: Int)

/**
 * The owner's answer to a held call: approve it once, refuse it, or refuse it with one of the
 * Card's next steps for the taf to follow ("Return first").
 */
@Serializable
sealed interface Answer {
  /** The words signed and sent, stable across versions. */
  val wireName: String

  @Serializable
  data object ApproveOnce : Answer {
    override val wireName get() = "approve-once"
  }

  @Serializable
  data object Refuse : Answer {
    override val wireName get() = "reject"
  }

  @Serializable
  data class NextStep(val step: String) : Answer {
    override val wireName get() = "reject:$step"
  }

  /**
   * Gives the call's Card the extra room this call needs, until that total resets (kiff/tafs#211, 6.13):
   * KIFF's change-card, the only answer that passes a call over a total kept across calls.
   */
  @Serializable
  data object ExtraRoom : Answer {
    override val wireName get() = "change-card"
  }
}

/** The answer as KIFF's signed answer message, `kiff.answer.v1`, names it: a next step is a refusal with its step beside it. */
val Answer.kiffName: String
  get() = when (this) {
    Answer.ApproveOnce -> "approve-once"
    Answer.Refuse, is Answer.NextStep -> "reject"
    Answer.ExtraRoom -> "change-card"
  }

/** The owner's answer to a held call, as KIFF's activity says it: approved once or refused, signed on which device. */
@Serializable
data class RecordAnswer(
  val decision: String,
  /** The answerer's role ("admin"), not who they are. */
  val by: String? = null,
  /** The person reading answered it themselves (KIFF knows; a role can't say). */
  val you: Boolean? = null,
  val signed: Boolean? = null,
  val device: String? = null,
  @Serializable(with = InstantAsString::class) val at: Instant? = null,
)

/** A call's KIFF receipt: signed, or pending until KIFF's next sweep (about 30 s). */
@Serializable
data class RecordReceipt(
  @kotlinx.serialization.SerialName("trace_id") val traceId: String,
  val status: String,
)

@Serializable
data class RecordEntry(
  val id: String = newId(),
  val tafId: String,
  val kind: Kind,
  val title: String,
  val detail: String,
  @Serializable(with = InstantAsString::class) val at: Instant,
  /** Present when the owner approved: the signature made on this phone. */
  val signature: String? = null,
  /** The owner approved it (a held call), whatever the tool then did. */
  val ownerApproved: Boolean? = null,
  /** Whether the call only read data. */
  val readOnly: Boolean? = null,
  /** "€150.00", as the Record shows an amount. */
  val amountText: String? = null,
  /** KIFF's key for the call (the gateway's "tc-…"), what the taf's call_ref names it by. */
  val entityId: String? = null,
  /**
   * What it acted on, as KIFF signs it: "sha256:<hex>" of the value, and the name of the argument it came
   * from ("payment"). Its value is tafs' own, shown only when it matches KIFF's hash.
   */
  val onHash: String? = null,
  val onArg: String? = null,
  val onValue: String? = null,
  /** The owner's answer to a held call, and its KIFF receipt. */
  val answer: RecordAnswer? = null,
  val receipt: RecordReceipt? = null,
  /** KIFF's reasons for its decision ("over the €100.00 limit for one refund"). */
  val why: List<String>? = null,
  /** When the call was made (KIFF's first decision), and why it wasn't sent, in KIFF's words. */
  @Serializable(with = InstantAsString::class) val madeAt: Instant? = null,
  val reason: String? = null,
  /** KIFF allowed the call and the vendor refused it, and it still counts against the Card's totals. */
  val refusedByTool: Boolean? = null,
  /** Who refused it at the tool: the vendor as connected, when known. */
  val refusedBy: String? = null,
) {
  /** "payment …QbstR": what it acted on, by the argument's name, when tafs has its value and it matches KIFF's hash. */
  val onText: String?
    get() {
      val value = onValue ?: return null
      val noun = (onArg ?: "it").replace("_", " ")
      return if (value.length > 6) "$noun …${value.takeLast(5)}" else "$noun $value"
    }

  /** The person reading answered it, as KIFF says (`answer.you`). */
  val answeredByOwner: Boolean get() = answer?.you == true

  /** Who answered, in words: "you", or by KIFF's role for them ("an admin"). */
  val answerer: String
    get() {
      if (answeredByOwner) return "you"
      val role = answer?.by?.replace("_", " ")?.takeIf { it.isNotEmpty() } ?: return "someone else"
      return (if (role.first() in "aeiou") "an " else "a ") + role
    }

  /** Who decided: its Card, the owner or another person (approving or refusing), or KIFF's refusal. */
  val decidedLine: String
    get() {
      answer?.let { return if (it.decision == "refused") "refused by $answerer" else "approved by $answerer" }
      return when (kind) {
        Kind.refused -> "refused by its Card"
        Kind.held -> "waiting for you"
        Kind.revoked -> "Card revoked"
        Kind.started -> "Card issued"
        else -> if (ownerApproved == true) "approved by you" else "within its Card"
      }
    }

  /** What became of it at the tool. */
  val resultLine: String
    get() = when (kind) {
      Kind.done, Kind.approved -> "done"
      Kind.refused, Kind.revoked -> "nothing sent"
      Kind.notDone -> "not done"
      Kind.held -> "nothing sent yet"
      Kind.sending -> "sent, no answer yet"
      Kind.unknown -> "result unknown"
      Kind.started -> "started"
      Kind.inTafs -> ""
    }

  /**
   * done and approved: the tool did it. notDone: allowed, but the tool refused it or it was never
   * sent. sending: sent, the tool hasn't answered. unknown: whether the tool did it isn't known
   * (kiff/tafs#115).
   */
  @Serializable
  enum class Kind {
    done, approved, refused, held, unknown, revoked, started, notDone, sending,

    /** Something done in tafs itself, not a call: a halt, a resume, a door's knock (kiff/tafs#236). */
    inTafs,
  }

  /** "Refund €150.00": the action's verb with its amount, as the mockup has it. */
  val titleWithAmount: String
    get() {
      val money = amountText ?: return title
      val words = title.split(" ")
      if (words.size > 1 && words[1].lowercase() in setOf("a", "an", "the")) return "${words[0]} $money"
      return "$title · $money"
    }
}
