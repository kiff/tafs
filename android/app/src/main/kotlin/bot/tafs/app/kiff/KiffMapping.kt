package bot.tafs.app.kiff

import bot.tafs.app.model.Card
import bot.tafs.app.model.CardRule
import bot.tafs.app.model.CardTotal
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.Money
import bot.tafs.app.model.Outside
import bot.tafs.app.model.ProposedAction
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.Taf
import bot.tafs.app.model.TafCard
import bot.tafs.app.model.TafState
import bot.tafs.app.model.VersionInstall
import bot.tafs.app.model.Window
import bot.tafs.app.model.shortTime
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.util.Currency
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns tafs cloud's list and KIFF's held calls and activity into what the app shows.
 *
 * tafs cloud makes the list from KIFF's Cards (one taf per Card holder); the app reads held calls from
 * KIFF itself, to answer them as KIFF sent them. An amount carries the unit and decimal places KIFF
 * declares for it; where KIFF declares none, or could not read it, the amount is a bare number. The
 * app never guesses a currency or a scale from a parameter's name.
 */
object KiffMapping {
  /** A summed quantity ("sum(amount_cents)") is an amount; "count" is a count. */
  fun isSum(quantity: String): Boolean = quantity.lowercase().startsWith("sum(")

  /** The parameter a summed quantity adds up: "sum(amount_cents)" → "amount_cents". */
  fun summed(quantity: String): String? =
    if (isSum(quantity) && quantity.endsWith(")")) quantity.drop(4).dropLast(1) else null

  /** An amount in the unit KIFF declares for it; a bare number when it declares none. */
  fun money(minor: Long, unit: String?, scale: Int?): Money =
    if (unit.isNullOrEmpty()) Money.bare(minor) else Money.of(minor, unit, scale ?: 0)

  /** A held call's local id, the same on every refresh: derived from KIFF's id, so an answer removes the right row. */
  fun heldId(kiffId: String): String = UUID.nameUUIDFromBytes("tafs.held|$kiffId".toByteArray()).toString()

  /** A record entry's id, from KIFF's call id: the same on every refresh. */
  fun recordId(proposalId: String): String = UUID.nameUUIDFromBytes("tafs.record|$proposalId".toByteArray()).toString()

  // MARK: Words

  /**
   * What the owner reads for an action, on a Card, an approval or a held call: its tool's title as KIFF
   * gives it, else the action in words. Never a name made up here.
   */
  fun name(action: String, title: String?): String {
    val t = title.orEmpty().trim()
    return if (t.isEmpty()) toolWords(action) else t
  }

  /**
   * A held call's name, which the owner approves alone, without the Card's other lines: its title, with
   * its action in words unless the title names the action's last word, the thing it acts on or does.
   */
  fun says(action: String, title: String?): String {
    val name = name(action, title)
    var words = toolWords(action)
    val titleWords = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
    var parts = words.lowercase().split(" ").filter { it.isNotEmpty() }
    // planque ends a tool's name with four letters or digits of its grant ("create_refund_repg"): with
    // a title, a last word of four the title doesn't have is that, and never shown (kiff/tafs#236, item 7).
    val last = parts.lastOrNull()
    if (!title.isNullOrEmpty() && parts.size > 1 && last != null && last.length == 4 && last !in titleWords && last.all { it.isLetterOrDigit() }) {
      parts = parts.dropLast(1)
      words = parts.joinToString(" ")
    }
    val end = parts.lastOrNull() ?: return name
    return if (end in titleWords) name else "$name · $words"
  }

  private val grantPrefix = Regex("^(?=[a-z]*[0-9])[a-z0-9]{6,}_")

  /**
   * A tool's name in words, for people (kiff/tafs#138, item 52): past a server's prefix
   * ("stripe.create_refund", "kiff__refund_order") and the grant's own ("bxkwf7vi_list_charges"),
   * separators as spaces. "bxkwf7vi_list_charges" → "list charges".
   */
  fun toolWords(tool: String): String {
    var name = tool.split("__").last().split(".").lastOrNull { it.isNotEmpty() } ?: tool
    grantPrefix.find(name)?.let { if (it.range.last + 1 < name.length) name = name.substring(it.range.last + 1) }
    val words = name.split('_', '-').filter { it.isNotEmpty() }.joinToString(" ")
    return words.ifEmpty { tool }
  }

  /** A vendor as people say it: "stripe-mcp" or "mcp.stripe.com" → "Stripe" (item 52). */
  fun vendorWords(vendor: String): String {
    var v = vendor.lowercase()
    if (v.startsWith("mcp.")) v = v.drop(4)
    for (suffix in listOf("-mcp", "_mcp", ".mcp")) if (v.endsWith(suffix)) v = v.dropLast(suffix.length)
    val dot = v.indexOf('.')
    if (dot >= 0) v = v.substring(0, dot)
    val first = v.firstOrNull() ?: return vendor
    return first.uppercase() + v.drop(1).replace("-", " ")
  }

  // MARK: The list

  internal fun window(raw: String): Window = runCatching { Window.valueOf(raw) }.getOrDefault(Window.day)

  /**
   * A taf as tafs cloud lists it (#91), for the screen: its state in the app's words, its Card's lines
   * and use as tafs cloud read them from KIFF. Nothing is worked out here. Its id is its KIFF agent's: the
   * same on every refresh and every launch.
   */
  fun taf(h: HomeTaf, owner: String): Taf {
    fun amount(a: HomeAmount?): Money? = a?.let { money(it.minor, it.unit, it.scale) }
    fun rule(r: HomeRule) = CardRule(
      action = r.action, label = r.label,
      perAction = amount(r.perAction), perWindow = amount(r.perWindow), countPerWindow = r.countPerWindow,
      window = window(r.window), outside = if (r.outside == "refuse") Outside.refuse else Outside.hold,
      extraRoom = amount(r.extraRoom), baseLimit = amount(r.baseLimit), roomEnds = r.roomWindowEnd,
    )
    val count = h.card.ids.size
    var taf = Taf(
      id = h.kiffAgent,
      name = h.name,
      // Its job in words, as tafs cloud says it (kiff/tafs#167); an agent tafs doesn't run has none, and is described by its Cards.
      job = h.jobSummary ?: "Works under $count KIFF Card${if (count == 1) "" else "s"}.",
      tools = h.card.tools,
      colorIndex = h.color,
      card = Card(h.card.rules.map(::rule), owner, h.card.expires, revoked = !h.card.active),
    )
    taf = when {
      h.state == "waiting" -> taf.copy(state = TafState.waiting, now = "Waiting on you")
      h.state == "paused" -> taf.copy(state = TafState.paused, now = "Paused")
      // Halted, by itself or with the whole account: it reads so everywhere (kiff/tafs#236, item 13).
      h.state == "halted" -> taf.copy(state = TafState.halted, now = "Halted · resume it from Halt")
      // A taf with no schedule isn't at work until it's told something in its chat (kiff/tafs#138, item 55).
      h.state == "working" && h.unscheduled == true -> taf.copy(state = TafState.idle, now = "Works from its chat")
      h.state == "working" -> taf.copy(state = TafState.working, now = h.nextRun?.let { "Next run ${shortTime(it)}" } ?: "Running")
      h.state == "idle" -> taf.copy(state = TafState.idle, now = "Acting under KIFF")
      else -> taf.copy(state = TafState.revoked, now = "Card not active")
    }
    return taf.copy(
      cloudId = h.id,
      kiffCardIds = h.card.ids,
      // Each Card on its own (kiff/tafs#211), as tafs cloud read it from KIFF.
      kiffCards = h.card.cards?.map { c ->
        TafCard(
          id = c.id, title = c.title, environment = c.environment, live = c.live, active = c.active, expires = c.expires,
          rules = c.rules.map(::rule),
          total = c.total?.let { t ->
            amount(t.limit)?.let { limit ->
              CardTotal(limit, amount(t.used), window(t.window), amount(t.extraRoom), amount(t.baseLimit), t.roomWindowEnd)
            }
          },
          calls = c.calls?.let { CardTotal(it.limit, it.used, window(it.window), it.extraRoom, it.baseLimit, it.roomWindowEnd) },
        )
      },
      used = h.card.used.mapNotNull { (action, a) -> amount(a)?.let { action to it } }.toMap(),
      usageUnknown = h.card.usageUnknown.toSet(),
    )
  }

  // MARK: The Record

  /** What a write's amount counts on the Card's totals: money when its unit is a currency, some other unit when it isn't, or nothing. */
  enum class Counted { money, amount, nothing }

  fun counted(c: ActivityCall): Counted {
    val amount = c.amount ?: return Counted.nothing
    if (c.readOnly == true) return Counted.nothing
    return if (isCurrency(amount.unit)) Counted.money else Counted.amount
  }

  /** An ISO 4217 code: a gateway's unit can name anything, so only a currency is money. */
  fun isCurrency(unit: String?): Boolean {
    if (unit == null || unit.length != 3) return false
    return Currency.getAvailableCurrencies().any { it.currencyCode == unit.uppercase() }
  }

  /**
   * What a taf did, as KIFF's activity records it (kiff/tafs#102): one entry a call of a holder the app
   * shows, newest first, each with an id the same on every refresh. KIFF's decision and what the tool
   * then did are kept apart (kiff/tafs#115): a call is done only when the tool said so, and no outcome is
   * never read as done. [vendors] names the vendor each connected tool is from, by its name in KIFF.
   */
  fun record(calls: List<ActivityCall>, holders: Set<String>, vendors: Map<String, String> = emptyMap()): List<RecordEntry> =
    calls.mapNotNull { c ->
      val actor = c.actorId ?: return@mapNotNull null
      if (actor !in holders) return@mapNotNull null
      val (kind, detail) = when (c.outcome) {
        "allowed" -> executed(c.toolOutcome, approved = c.held, vendor = vendors[c.actionName], counts = counted(c))
        "owner_required", "approval_required" -> RecordEntry.Kind.held to "Waiting for you"
        // KIFF's own words for why, when it has them (kiff/tafs#138).
        else -> RecordEntry.Kind.refused to (c.reason?.let { "Not sent · $it" } ?: "Not sent")
      }
      val refusedByTool = c.outcome == "allowed" && c.toolOutcome?.status == "refused_by_tool"
      RecordEntry(
        id = recordId(c.proposalId), tafId = actor, kind = kind, title = name(c.actionName, c.title), detail = detail, at = c.createdAt,
        ownerApproved = if (c.outcome == "allowed" && c.held) true else null,
        readOnly = c.readOnly, amountText = c.amount?.let(::amountText),
        entityId = c.entityId, onHash = c.onHash, onArg = c.onArg, answer = c.answer, receipt = c.receipt, why = c.reasons,
        madeAt = c.madeAt, reason = c.reason,
        refusedByTool = if (refusedByTool) true else null,
        refusedBy = if (refusedByTool) vendors[c.actionName] else null,
      )
    }

  /**
   * What the tool did with an allowed call: its kind and the words for it. [approved]: the owner approved
   * it, held first. A call the vendor refused still counts against the Card's totals: it says so plainly,
   * naming the vendor as connected.
   */
  fun executed(o: ToolOutcome?, approved: Boolean, vendor: String? = null, counts: Counted = Counted.nothing): Pair<RecordEntry.Kind, String> {
    val why = o?.message?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""
    val allowed = if (approved) "Approved by you" else "Allowed by its Card"
    return when (o?.status) {
      "done" -> if (approved) RecordEntry.Kind.approved to "Approved by you, then done" else RecordEntry.Kind.done to "Done, within its Card"
      "refused_by_tool" -> {
        val who = vendor ?: "The tool"
        RecordEntry.Kind.notDone to when (counts) {
          Counted.money -> "$who refused it. No money moved, but it still counts against today's total."
          Counted.amount -> "$who refused it. Nothing was done, but it still counts against today's total."
          Counted.nothing -> "$who refused it, and it still counts toward today's limits$why"
        }
      }
      "not_sent" -> RecordEntry.Kind.notDone to "$allowed, but it couldn't be sent: nothing reached the tool$why"
      "sending" -> RecordEntry.Kind.sending to "$allowed, and sent: waiting for the tool's answer"
      "unknown" -> RecordEntry.Kind.unknown to "$allowed, and sent, but the tool's answer was lost: check the tool's own records"
      // not_recorded, none, or one this app doesn't know yet.
      else -> RecordEntry.Kind.unknown to "$allowed, but whether the tool did it wasn't recorded: check the tool's own records"
    }
  }

  /**
   * "€150.00", from KIFF's decimal string and its unit; the unit as written when it isn't a currency.
   * As the app writes money everywhere: "US$80", never "US$80,00" (kiff/tafs#236, item 16).
   */
  fun amountText(amount: ActivityAmount): String? {
    val value = amount.value.toBigDecimalOrNull() ?: return null
    val unit = amount.unit
    if (unit.isNullOrEmpty()) return amount.value
    if (unit.length == 3 && unit == unit.uppercase() && isCurrency(unit)) {
      val scale = amount.value.substringAfter('.', "").length
      val minor = value.movePointRight(scale).setScale(0, RoundingMode.HALF_UP).toLong()
      return Money.of(minor, unit, scale).formatted
    }
    return "${amount.value} $unit"
  }

  // MARK: Held calls

  /**
   * The version a held version_install of Coach's names (kiff/tafs#206), or null. Coach's only: held from [coach], the account's
   * Setup agent, once this phone connected Coach's tools itself, which KIFF names the action after (a name is one connection per
   * account). Anything else is an ordinary held call.
   */
  fun install(e: CardException, coach: String?): VersionInstall? {
    if (coach.isNullOrEmpty() || e.subject != coach || e.action != "version_install") return null
    val taf = (e.arguments?.get("taf") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
    val n = (e.arguments?.get("n") as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return VersionInstall(taf, n)
  }

  /** The called-for answer of a held call: what the owner reads, and what they sign. [coach]: see [install]. */
  fun held(e: CardException, coach: String? = null): HeldCall {
    val breach = reasonBreach(e)
    val reason = moneyReason(e) ?: breach?.reason ?: e.reason ?: outcomeText(breach?.outcome)
    // A gateway call is filed under its tool call's id, which says nothing to the owner: what it's on is in
    // its arguments, as KIFF keeps them (kiff/tafs#102).
    val name = says(e.action, e.title)
    val summary = if (e.arguments == null) "$name ${e.entityId}" else name
    return HeldCall(
      id = heldId(e.id),
      tafId = e.subject,
      proposed = ProposedAction(e.action, summary, amountOf(e)),
      reason = reason,
      heldAt = e.createdAt,
      expiresAt = e.expiresAt ?: e.createdAt.plus(Duration.ofMinutes(10)),
      kiffId = e.id,
      proposalId = e.proposalId,
      approvableOnce = e.canApproveOnce ?: true,
      accountingVersion = e.accountingVersion,
      nextSteps = e.nextSteps ?: emptyList(),
      details = details(e),
      install = install(e, coach),
    )
  }

  /**
   * The one breach a held call names (kiff/tafs#236, item 33). A call over both its limit for one call
   * and a total can't be approved once, and the card says it's the total: the reason names that total
   * too, not the first breach KIFF lists.
   */
  fun reasonBreach(e: CardException): Breach? {
    if (e.canApproveOnce == false) e.breaches.firstOrNull { it.outcome != "per_action_exceeded" }?.let { return it }
    return e.breaches.firstOrNull()
  }

  /**
   * Why a call is held, with its amounts written as the app writes money everywhere else (kiff/tafs#206
   * 6.8), when KIFF declares the limit's unit and it is the amount's: "€150 is over the €100 limit for
   * one call". Otherwise null, and KIFF's own words show.
   */
  fun moneyReason(e: CardException): String? {
    val b = reasonBreach(e) ?: return null
    val limit = b.limit ?: return null
    val unit = b.unit ?: return null
    if (b.unitAmbiguous == true) return null
    val amount = amountOf(e) ?: return null
    if (amount.currency != unit) return null
    val cap = Money.of(limit, unit, b.scale ?: amount.scale).formatted
    return when (b.outcome) {
      "per_action_exceeded" -> "${amount.formatted} is over the $cap limit for one call"
      "limit_reached" -> "${amount.formatted} would go over the $cap total"
      else -> null
    }
  }

  /**
   * What a held gateway call is on, one line an argument, as KIFF keeps them ("payment_intent: pi_…"). The
   * amount isn't repeated (it's shown above), nor KIFF's own operation id.
   */
  fun details(e: CardException): List<String> {
    val arguments = e.arguments ?: return emptyList()
    val shown = (e.parameterUnits?.keys.orEmpty() + e.parameters?.keys.orEmpty()).map { it.lowercase() }.toSet()
    return arguments.keys.sorted().mapNotNull { key ->
      val k = key.lowercase()
      if (k in shown || k == "kiff_operation_id") return@mapNotNull null
      val text = argumentText(arguments[key]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
      "$key: $text"
    }
  }

  /** One argument of a held call, as text: a string as it is, a number or a flag written out, anything else left as "…". */
  fun argumentText(value: JsonElement?): String {
    val p = value as? JsonPrimitive ?: return "…"
    if (p.isString) return p.content
    p.content.toBooleanStrictOrNull()?.let { return it.toString() }
    // A decimal written out plainly, as sent: "0.000015", never "1.5e-05".
    return p.content.toBigDecimalOrNull()?.toPlainString() ?: "…"
  }

  /**
   * The amount to show above the answer: a parameter KIFF declares a unit for, in that unit; otherwise the
   * parameter a breach sums, as a bare number; otherwise none.
   */
  fun amountOf(e: CardException): Money? {
    val parameters = e.parameters?.takeIf { it.isNotEmpty() } ?: return null
    val units = (e.parameterUnits ?: emptyMap()).mapKeys { it.key.lowercase() }
    val sorted = parameters.entries.sortedBy { it.key }
    sorted.firstOrNull { units[it.key.lowercase()] != null }?.let { declared ->
      val unit = units.getValue(declared.key.lowercase())
      return Money.of(declared.value, unit.unit, unit.scale)
    }
    val summedNames = e.breaches.mapNotNull { it.quantity?.let(::summed) }.map { it.lowercase() }
    sorted.firstOrNull { it.key.lowercase() in summedNames }?.let { return Money.bare(it.value) }
    return null
  }

  fun outcomeText(outcome: String?): String = when (outcome) {
    "per_action_exceeded" -> "Over the Card's limit for one action"
    "limit_reached" -> "Over the Card's total"
    "resource_not_on_card" -> "Not one of the Card's resources"
    else -> "Outside the Card"
  }
}
