package bot.tafs.app.kiff

import bot.tafs.app.model.Words
import bot.tafs.app.model.Card
import bot.tafs.app.model.CardRule
import bot.tafs.app.model.CardTotal
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.BrowserStep
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

  /** Whether [value] is what KIFF hashed for what a call acted on: "sha256:" and its hex. */
  fun isOn(value: String, hash: String): Boolean =
    hash == "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

  /**
   * The Record's lines with what each acted on, from the tafs' call references by KIFF's key (kiff/tafs#167): only a value
   * whose hash is KIFF's, so a line never names a payment KIFF didn't.
   */
  fun naming(record: List<RecordEntry>, refs: Map<String, String>): List<RecordEntry> = record.map { e ->
    val key = e.entityId
    val hash = e.onHash
    val value = key?.let { refs[it] }
    if (hash != null && value != null && isOn(value, hash)) e.copy(onValue = value) else e
  }

  // MARK: Words

  /**
   * What the owner reads for an action, on a Card, an approval or a held call: its tool's title as KIFF
   * gives it, else the action in words. Never a name made up here.
   */
  fun name(action: String, title: String?): String {
    val t = title.orEmpty().trim()
    return if (t.isEmpty()) toolWords(action) else visible(t)
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
    return visible(words.ifEmpty { tool })
  }

  /** A vendor as people say it: "stripe-mcp" or "mcp.stripe.com" → "Stripe" (item 52). */
  fun vendorWords(vendor: String): String {
    var v = vendor.lowercase()
    if (v.startsWith("mcp.")) v = v.drop(4)
    for (suffix in listOf("-mcp", "_mcp", ".mcp")) if (v.endsWith(suffix)) v = v.dropLast(suffix.length)
    val dot = v.indexOf('.')
    if (dot >= 0) v = v.substring(0, dot)
    val first = v.firstOrNull() ?: return visible(vendor)
    return visible(first.uppercase() + v.drop(1).replace("-", " "))
  }

  // MARK: The list

  internal fun window(raw: String): Window = runCatching { Window.valueOf(raw) }.getOrDefault(Window.day)

  /**
   * A taf as tafs cloud lists it (#91), for the screen: its state in the app's words, its Card's lines
   * and use as tafs cloud read them from KIFF. Nothing is worked out here. Its id is its KIFF agent's: the
   * same on every refresh and every launch.
   */
  /**
   * The tafs whose setup stopped partway: each by its name, with no state. The person whose Coach chat it was gets `finish`:
   * tafs cloud's words in place of a state, and the chat. Anyone else, or a finish with no chat or words, gets the name alone.
   */
  fun settingUp(list: List<HomeSettingUp>, owner: String): List<Taf> = list.filter { it.name.isNotBlank() }.map { s ->
    val finish = s.finish?.takeIf { it.session.isNotBlank() && it.words.isNotBlank() }
    Taf(id = s.kiffAgent.ifBlank { "setting-up:" + s.name }, name = visible(s.name), job = "", tools = emptyList(), colorIndex = s.color, card = Card(emptyList(), owner), now = visible(finish?.words.orEmpty()), finish = finish,
      // Discard only with every word, and only for an agent tafs cloud named.
      discard = s.discard?.takeIf { it.words.usable && s.kiffAgent.isNotBlank() }, kiffCardIds = s.discard?.cards.orEmpty())
  }

  fun taf(h: HomeTaf, owner: String): Taf {
    fun amount(a: HomeAmount?): Money? = a?.let { money(it.minor, it.unit, it.scale) }
    fun rule(r: HomeRule) = CardRule(
      action = r.action, label = visible(r.label),
      perAction = amount(r.perAction), perWindow = amount(r.perWindow), countPerWindow = r.countPerWindow,
      window = window(r.window), outside = if (r.outside == "refuse") Outside.refuse else Outside.hold,
      extraRoom = amount(r.extraRoom), baseLimit = amount(r.baseLimit), roomEnds = r.roomWindowEnd,
    )
    val count = h.card.ids.size
    var taf = Taf(
      id = h.kiffAgent,
      name = visible(h.name),
      // Its job in words, as tafs cloud says it (kiff/tafs#167); an agent tafs doesn't run has none, and is described by its Cards.
      job = h.jobSummary?.let(::visible) ?: Words.Taf.worksUnderCards(count = count),
      tools = h.card.tools,
      colorIndex = h.color,
      card = Card(h.card.rules.map(::rule), owner, h.card.expires, revoked = !h.card.active),
    )
    taf = when {
      h.state == "waiting" -> taf.copy(state = TafState.waiting, now = Words.Taf.Now.waitingOnYou)
      h.state == "paused" -> taf.copy(state = TafState.paused, now = Words.Taf.State.paused)
      // Halted, by itself or with the whole account: it reads so everywhere (kiff/tafs#236, item 13).
      h.state == "halted" -> taf.copy(state = TafState.halted, now = Words.Taf.Now.halted)
      // Its run waits for a connected app's event (the slow-tools design): not the owner's, in the connection's words.
      h.work?.status == "waiting_on_app" -> taf.copy(state = TafState.idle, now = visible(h.work.line))
      // Something went wrong first (a failed check, its health, a run stopped or failed): tafs cloud's line, never "Working".
      h.work?.attention == true && h.work.line.isNotBlank() -> taf.copy(state = TafState.idle, now = visible(h.work.line))
      // A taf with no schedule isn't at work until it's told something in its chat (kiff/tafs#138, item 55).
      h.state == "working" && h.unscheduled == true -> taf.copy(state = TafState.idle, now = Words.Taf.Now.worksFromChat)
      h.state == "working" -> taf.copy(
        state = TafState.working,
        // tafs cloud's words, with the day when it isn't today; the app's own format only where an older tafs cloud sends none.
        now = h.nextRunWords?.takeIf { it.isNotBlank() }?.let(::visible) ?: h.nextRun?.let { Words.Taf.Now.nextRun(time = shortTime(it)) } ?: Words.Taf.Now.running,
      )
      h.state == "idle" -> taf.copy(state = TafState.idle, now = Words.Taf.Now.actingUnderKiff)
      h.state == "stopped" -> taf.copy(state = TafState.revoked, now = Words.Taf.Now.cardNotActive)
      // A state this app doesn't know yet: tafs cloud's line for its work, never a guess.
      else -> taf.copy(state = TafState.idle, now = visible(h.work?.line.orEmpty()))
    }
    // tafs cloud orders what the row says: its work's line carries health's first reason only while the work is idle (halted,
    // needs you, retrying, working, waiting on an app and paused come first). Then that line shows, tinted by tafs cloud's level.
    val level = h.health?.level?.takeIf { it == "at_risk" || it == "failing" }
    val line = h.work?.takeIf { it.status == "idle" }?.line?.takeIf { it.isNotBlank() }
    if (level != null && line != null) taf = taf.copy(now = visible(line), nowHealth = level)
    // Its setup stopped partway: tafs cloud's words in place of what it's doing, and the chat to finish it in.
    val finish = h.finish?.takeIf { it.session.isNotBlank() && it.words.isNotBlank() }
    if (finish != null) taf = taf.copy(now = visible(finish.words), finish = finish)
    return taf.copy(
      relevantNow = h.relevantNow,
      labels = h.labels.orEmpty().filter { it.isNotBlank() },
      cloudId = h.id,
      paused = h.status == "paused",
      nextRun = h.nextRun,
      nextRunWords = h.nextRunWords?.takeIf { it.isNotBlank() }?.let(::visible),
      worksFromChat = h.unscheduled == true,
      results = h.results,
      timeZoneWords = h.timeZoneWords,
      scheduleTimeZoneWords = h.scheduleTimeZoneWords,
      avatarRim = h.avatarRim,
      kiffCardIds = h.card.ids,
      // Each Card on its own (kiff/tafs#211), as tafs cloud read it from KIFF.
      kiffCards = h.card.cards?.map { c ->
        TafCard(
          id = c.id, title = visible(c.title), environment = c.environment, live = c.live, active = c.active, expires = c.expires,
          rules = c.rules.map(::rule),
          total = c.total?.let { t ->
            amount(t.limit)?.let { limit ->
              CardTotal(limit, amount(t.used), window(t.window), amount(t.extraRoom), amount(t.baseLimit), t.roomWindowEnd, t.pendingText, t.unitWords)
            }
          },
          calls = c.calls?.let { CardTotal(it.limit, it.used, window(it.window), it.extraRoom, it.baseLimit, it.roomWindowEnd, it.pendingText) },
          counted = c.counted.orEmpty().filter { it.lines.isNotEmpty() },
          eachItem = c.eachItem.orEmpty().filter { it.isNotBlank() },
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
        "owner_required", "approval_required" -> RecordEntry.Kind.held to Words.Record.waitingForYou
        // KIFF's own words for why, when it has them (kiff/tafs#138).
        else -> RecordEntry.Kind.refused to (c.reason?.let { Words.Record.notSentBecause(reason = visible(it)) } ?: Words.Record.notSent)
      }
      val refusedByTool = c.outcome == "allowed" && c.toolOutcome?.status == "refused_by_tool"
      // Typed by KIFF as the connection's refusal: KIFF's words, or the plain fact, never the tool's raw message.
      val byConnection = refusedByTool && c.toolOutcome.refusedBy == "connection"
      val shown = if (byConnection && c.toolOutcome.code != null) c.detail?.let(::visible) ?: Words.Record.connectionRefusedBeforeSending else detail
      RecordEntry(
        id = recordId(c.proposalId), tafId = actor, kind = kind, title = visible(name(c.actionName, c.title)), detail = shown, at = c.createdAt,
        ownerApproved = if (c.outcome == "allowed" && c.held) true else null,
        readOnly = c.readOnly, amountText = c.amount?.let(::amountText), amountValue = c.amount?.value, amountUnit = c.amount?.unit,
        entityId = c.entityId?.let(::visible), action = c.actionName, onHash = c.onHash, onArg = c.onArg, answer = c.answer, receipt = c.receipt,
        why = c.reasons?.map(::visible), madeAt = c.madeAt, reason = c.reason?.let(::visible),
        refusedByTool = if (refusedByTool) true else null,
        refusedBy = if (byConnection) Words.Record.theConnection else if (refusedByTool) vendors[c.actionName] else null,
      )
    }

  /** The longest message from the app the Record quotes: a short sentence. */
  const val QUOTABLE_MAX = 160

  /**
   * KIFF's `tool_outcome.message` when it's safe to quote as the app's reason, else null. KIFF passes the app's answer through,
   * and an app's error body can be JSON, long, or carry the connected account's address, a file's name or a request id. The
   * Record, and the "Last failed" line built from it, are seen by everyone who can see the taf, people it's shared with
   * included. So only a short, one-line, plain reason is quoted ("Agent Mail refused it (it answered 403): Forbidden."), and
   * anything else leaves the plain line, which already names the app.
   */
  fun quotable(message: String?): String? {
    val said = message?.trim().orEmpty()
    if (said.isEmpty() || said.length > QUOTABLE_MAX || said.any { it == '\n' || it == '\r' }) return null
    if (said.any { it == '{' || it == '[' || it == '@' }) return null
    return visible(said)
  }

  /**
   * What the tool did with an allowed call: its kind and the words for it. [approved]: the owner approved
   * it, held first. A call the vendor refused still counts against the Card's totals: it says so plainly,
   * naming the vendor as connected. Unless KIFF says no total kept it (`counted: false`, a Card that counts what's done):
   * then it says it doesn't count (the fourth line). Money and amounts keep their own lines whatever KIFF says.
   */
  fun executed(o: ToolOutcome?, approved: Boolean, vendor: String? = null, counts: Counted = Counted.nothing): Pair<RecordEntry.Kind, String> {
    val why = quotable(o?.message)?.let { ": $it" }
    val allowed = if (approved) Words.Record.approvedByYou else Words.Record.allowedByCard
    return when (o?.status) {
      "done" -> if (approved) RecordEntry.Kind.approved to Words.Record.approvedThenDone else RecordEntry.Kind.done to Words.Record.doneWithinCard
      "refused_by_tool" -> {
        val who = vendor ?: Words.Record.theTool
        RecordEntry.Kind.notDone to when (counts) {
          Counted.money -> Words.Record.toolRefusedMoney(who = who)
          Counted.amount -> Words.Record.toolRefusedAmount(who = who)
          Counted.nothing -> if (o.counted == false) {
            why?.let { Words.Record.toolRefusedNotCountedWhy(who = who, why = it) } ?: Words.Record.toolRefusedNotCounted(who = who)
          } else {
            why?.let { Words.Record.toolRefusedCountsWhy(who = who, why = it) } ?: Words.Record.toolRefusedCounts(who = who)
          }
        }
      }
      "not_sent" -> RecordEntry.Kind.notDone to (why?.let { Words.Record.notSentNothingReachedWhy(allowed = allowed, why = it) } ?: Words.Record.notSentNothingReached(allowed = allowed))
      "sending" -> RecordEntry.Kind.sending to Words.Record.sentWaiting(allowed = allowed)
      "unknown" -> RecordEntry.Kind.unknown to Words.Record.answerLost(allowed = allowed)
      // not_recorded, none, or one this app doesn't know yet.
      else -> RecordEntry.Kind.unknown to Words.Record.notRecorded(allowed = allowed)
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
    val n = (e.arguments["n"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return VersionInstall(taf, n)
  }

  /** The called-for answer of a held call: what the owner reads, and what they sign. [coach]: see [install]. */
  fun held(e: CardException, coach: String? = null): HeldCall {
    // Coach's install of a version keeps KIFF's title ("Install a version") but isn't drawn as a call: no `n:`/`taf:` Details,
    // and no rule badge, since installing is asked every time by design, never something outside the Card.
    val installs = install(e, coach)
    val breach = reasonBreach(e)
    // KIFF's own words first; the app's only where KIFF sends none.
    val reason = e.reasonText?.takeIf { it.isNotBlank() } ?: moneyReason(e) ?: countReason(e) ?: breach?.reason ?: e.reason ?: outcomeText(breach?.outcome)
    // A gateway call is filed under its tool call's id, which says nothing to the owner: what it's on is in
    // its arguments, as KIFF keeps them (kiff/tafs#102).
    val name = visible(says(e.action, e.title))
    val summary = if (e.arguments == null) "$name ${visible(e.entityId)}" else name
    return HeldCall(
      id = heldId(e.id),
      tafId = e.subject,
      proposed = ProposedAction(e.action, summary, amountOf(e)),
      reason = if (installs != null) "" else visible(reason),
      heldAt = e.createdAt,
      expiresAt = e.expiresAt ?: e.createdAt.plus(Duration.ofMinutes(10)),
      kiffId = e.id,
      proposalId = e.proposalId,
      approvableOnce = e.canApproveOnce ?: true,
      accountingVersion = e.accountingVersion,
      nextSteps = e.nextSteps ?: emptyList(),
      details = if (installs != null) emptyList() else details(e),
      tafSays = tafSays(e.tafReason),
      tafAbout = tafSays(e.tafAbout, TAF_ABOUT_MAX),
      install = installs,
      kind = e.kind,
      breachOutcomes = e.breaches.map { it.outcome },
      browser = e.browser?.let(::browserStep),
    )
  }

  /** The longest page excerpt drawn on a held browser step's card, as planque cuts it. */
  const val BROWSER_EXCERPT_MAX = 600

  /**
   * A held browser step's block as plain text (docs/chrome.md): every piece through [tafSays], so no line can reorder or hide
   * words; a block with no site or no action draws nothing, never half a page.
   */
  fun browserStep(b: HeldBrowser): BrowserStep? {
    val site = tafSays(b.site, 120)?.takeIf { it.isNotBlank() } ?: return null
    val action = tafSays(b.actionWords, 200)?.takeIf { it.isNotBlank() } ?: return null
    fun text(e: kotlinx.serialization.json.JsonElement?): String? = (e as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { tafSays(it, 40) }?.takeIf { it.isNotBlank() }
    val lines = b.checkout?.lines.orEmpty().mapNotNull { l ->
      val item = tafSays(l.item, 120)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      listOfNotNull(text(l.quantity)?.let { "$it × $item" } ?: item, text(l.price)).joinToString(" · ")
    }
    return BrowserStep(
      site = site,
      origin = tafSays(b.origin, 200).orEmpty(),
      action = action,
      pageTitle = tafSays(b.context?.title, 200)?.takeIf { it.isNotBlank() },
      excerpt = tafSays(b.context?.text, BROWSER_EXCERPT_MAX)?.takeIf { it.isNotBlank() },
      lines = lines,
      chrome = tafSays(b.chrome?.name, 40)?.takeIf { it.isNotBlank() },
    )
  }

  /**
   * The taf's line as the card shows it: plain text on one paragraph, with control characters and line breaks made spaces, and no
   * more than KIFF keeps ([max]: 280 for the reason, 120 for what it's on). It's drawn as text alone, so a link or markup in it is
   * only words. Blank is none.
   */
  /**
   * A line from KIFF, a vendor or a taf as an approval, the record, a notification or the watch draws it (qa-kiff, 20 Nov): no
   * direction character (U+202A–U+202E, U+2066–U+2069, U+200E, U+200F, U+061C, the set iOS's `FileText.isDirection` names), so a
   * payee name can't reorder how an amount or an IBAN beside it looks; any other control character becomes a space. Right-to-left
   * letters themselves stay.
   */
  fun visible(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
      val c = text.codePointAt(i)
      when {
        isDirection(c) -> Unit
        Character.isISOControl(c) -> out.append(' ')
        else -> out.appendCodePoint(c)
      }
      i += Character.charCount(c)
    }
    return out.toString()
  }

  /** The invisible characters that set or override a line's direction. */
  fun isDirection(c: Int): Boolean = c in 0x202A..0x202E || c in 0x2066..0x2069 || c == 0x200E || c == 0x200F || c == 0x061C

  fun tafSays(reason: String?, max: Int = TAF_REASON_MAX): String? {
    if (reason == null) return null
    val plain = StringBuilder()
    var i = 0
    while (i < reason.length) {
      val c = reason.codePointAt(i)
      when {
        // Format characters (Cf: bidi overrides such as U+202E, zero-width marks) are dropped: they could reorder or hide words.
        Character.getType(c) == Character.FORMAT.toInt() -> Unit
        // Controls and line or paragraph separators become a space: one paragraph.
        Character.isISOControl(c) || c == 0x2028 || c == 0x2029 -> plain.append(' ')
        else -> plain.appendCodePoint(c)
      }
      i += Character.charCount(c)
    }
    val text = plain.toString().replace(Regex("\\s+"), " ").trim()
    // Capped by code points, as KIFF caps it, so an emoji is never cut in half.
    val capped = if (text.codePointCount(0, text.length) > max) text.substring(0, text.offsetByCodePoints(0, max)) else text
    return capped.takeIf { it.isNotBlank() }
  }

  const val TAF_REASON_MAX = 280
  const val TAF_ABOUT_MAX = 120

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
      "per_action_exceeded" -> Words.Card.Held.overOneCall(amount = amount.formatted, cap = cap)
      "limit_reached" -> Words.Card.Held.overTotal(amount = amount.formatted, cap = cap)
      else -> null
    }
  }

  /**
   * A hold over a count of calls, in the Card's words, for a KIFF that doesn't send its own: a limit of 0 means each call asks
   * first; otherwise which call this would be ("20 calls a day: this would be the 21st"). Never the machine's "count limit 0
   * per calendar_day reached".
   */
  fun countReason(e: CardException): String? {
    val b = reasonBreach(e) ?: return null
    if (b.outcome != "limit_reached" || b.quantity != "count") return null
    val limit = b.limit ?: return null
    if (limit == 0L) return Words.Card.Held.eachCallAsks
    val nth = ordinal((b.used ?: limit) + (b.draw ?: 1))
    val window = b.window ?: return Words.Card.Held.overCount(count = limit.toInt(), nth = nth)
    // A total for each item apart says so, and which item: "1 call in any 7 days for each invoice id: this would be the 2nd for in_42".
    val each = b.per?.takeIf { it.isNotBlank() }?.let { Words.Card.Held.forEach(name = it.lowercase().replace('_', ' ')) }.orEmpty()
    val item = if (each.isNotEmpty()) b.value?.takeIf { it.isNotBlank() }?.let { Words.Card.Held.forItem(item = it) }.orEmpty() else ""
    return Words.Card.Held.countInWindow(count = limit.toInt(), window = windowWords(window), each = each, nth = nth, item = item)
  }

  /** A KIFF window in the words a Card's totals use (docs/tafs.md); one KIFF adds later shows as it is sent, never as a day. */
  fun windowWords(window: String): String = when (window) {
    "calendar_day" -> Words.Card.Window.aDay
    "rolling_24h" -> Words.Card.Window.anyDay
    "rolling_1h" -> Words.Card.Window.anyHour
    "rolling_7d" -> Words.Card.Window.anyWeek
    "lifetime" -> Words.Card.Window.inTotal
    else -> window
  }

  /** 1st, 2nd, 3rd, 11th, 21st: as the lines' language writes a number in order. */
  fun ordinal(n: Long): String = bot.tafs.app.model.Res.ordinal(n)

  /**
   * What a held gateway call is on, one line an argument, as KIFF keeps them ("payment_intent: pi_…"). The
   * amount isn't repeated (it's shown above), nor KIFF's own operation id.
   */
  fun details(e: CardException): List<String> {
    val arguments = e.arguments ?: return emptyList()
    val shown = (e.parameterUnits?.keys.orEmpty() + e.parameters?.keys.orEmpty()).map { it.lowercase() }.toSet()
    return arguments.keys.sorted().mapNotNull { key ->
      val k = key.lowercase()
      // The gateway's own arguments: the operation's id, and the taf's words, which show under "The taf says".
      if (k in shown || k == "kiff_operation_id" || k == "kiff_reason" || k == "kiff_about") return@mapNotNull null
      val text = argumentText(arguments[key]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
      "${visible(key)}: ${visible(text)}"
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
    "per_action_exceeded" -> Words.Card.Outcome.overOneAction
    "limit_reached" -> Words.Card.Outcome.overTotal
    "resource_not_on_card" -> Words.Card.Outcome.notAResource
    else -> Words.Card.Outcome.outside
  }
}
