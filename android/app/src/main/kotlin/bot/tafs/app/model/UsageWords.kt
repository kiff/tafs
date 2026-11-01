package bot.tafs.app.model

import bot.tafs.app.kiff.CountedTask
import bot.tafs.app.kiff.Halt
import bot.tafs.app.kiff.PackRequest
import bot.tafs.app.kiff.Usage
import bot.tafs.app.kiff.UsagePack
import java.time.Instant

/** What the Halt screen says, in the words iOS's has. */
object HaltWords {
  fun headline(h: Halt): String = when {
    h.running -> "Running"
    h.halted -> "Halted"
    else -> "Halting…"
  }

  fun explanation(h: Halt, all: Boolean): String {
    val who = if (all) "your tafs" else "it"
    return when {
      h.running ->
        "Halting stops $who: no run starts, and nothing reaches your tools until you resume. A call already on its way may still arrive; nothing is undone."
      h.halted -> {
        val since = h.haltedAt?.let { " since ${shortTime(it)}" } ?: ""
        "Halted$since. Nothing reaches your tools. When you resume, only new work runs: nothing from before the halt picks up again."
      }
      else -> "Nothing new starts. Halted shows once KIFF and planque both confirm."
    }
  }

  /** The row on a taf's page, or Home, under "Halt": where it stands. */
  fun rowLine(h: Halt): String = if (h.running) "Stop it until you resume" else headline(h)

  /** A taf halted with the whole account is resumed with it, not on its own. */
  fun resumesWithAccount(h: Halt, tafId: String?): Boolean = h.account && tafId != null
}

/** What the Usage screens say, in the words iOS's has. */
object UsageWords {
  /** The account's screen is the account's, apart from a taf's own. */
  const val TITLE = "Account usage"

  /** Whose the account's numbers are: every taf's together. */
  const val ALL_TAFS = "All tafs"

  /** "1 task", "8 tasks". */
  fun tasks(n: Int): String = if (n == 1) "1 task" else "$n tasks"

  /** A taf's counted tasks this period, for its page's Usage row. */
  fun tafCount(n: Int): String = "${tasks(n)} this period"

  /**
   * "14 of 25 tasks", or "472 tasks" where there is no limit (KIFF's `included: 0`), in the singular where the number it follows
   * is 1: "1 task", "0 of 1 task". [unit] is the plural; its singular drops the final "s".
   */
  fun count(counted: Int, of: Int, unit: String): String {
    val one = unit.removeSuffix("s")
    return if (of > 0) "$counted of $of ${if (of == 1) one else unit}" else "$counted ${if (counted == 1) one else unit}"
  }

  /** Under held packs while no plan runs (KIFF's `can_add_packs` false). */
  const val PACKS_WAIT_FOR_SOLO = "Counts once Solo starts."

  /** "Tasks last 12 months", "Tasks last 1 month": how long a pack's tasks last. */
  fun packLasts(months: Int): String = "Tasks last $months ${if (months == 1) "month" else "months"}"

  /** "65 tasks left", with what the period has beside it; null where tasks have no limit. */
  fun leftLine(u: Usage): String? {
    val left = u.tasks.left ?: return null
    val line = if (left == 1) "1 task left" else "$left tasks left"
    val packs = u.tasks.packs ?: return line
    return if (packs > 0) "$line · ${u.tasks.included} included, $packs from packs" else line
  }

  /** The plan's name as the owner bought it; null for one that isn't sold (our own accounts' "internal"). */
  fun planName(plan: String): String? = when (plan) {
    "trial" -> "Trial"
    "solo" -> "Solo"
    "team" -> "Team"
    "company" -> "Company"
    else -> null
  }

  /** "All tafs · Trial": whose the count is, and the plan where it has a name to show. */
  fun whose(u: Usage): String = planName(u.plan)?.let { "$ALL_TAFS · $it" } ?: ALL_TAFS

  /** "This period ends 22 Nov 2026." */
  fun periodEnds(end: Instant): String = "This period ends ${shownDate(end)}."

  /** What agents that aren't tafs did through KIFF; null when they did nothing, so an empty line never asks what 0 means. */
  fun othersLine(u: Usage): String? = if (u.actions.counted > 0) count(u.actions.counted, u.actions.included, "actions") else null

  /** Whether Solo is offered: to someone who may add tasks, on an account with no plan billed (the trial, or one that ended). */
  fun offersSolo(u: Usage): Boolean =
    u.canAddTasks == true && u.store?.googleSoloProduct != null && u.billing?.source.isNullOrEmpty() && u.plan == "trial"

  /** Solo's terms, every one, as Google Play's price says them: "$9.99 a month. Renews every month until cancelled…". */
  fun soloTerms(price: String, period: String?, freeTrial: String?): String {
    val every = period ?: "month"
    val start = freeTrial?.let { "Free for $it, then $price a $every. " } ?: "$price a $every. "
    return start + "Renews every $every until cancelled. Cancel any time in Google Play's subscriptions, at least a day before it renews."
  }

  /** One line on who bills the plan; null when no plan is billed. */
  fun billingLine(u: Usage): String? = when (u.billing?.source) {
    "apple" -> "Billed by Apple"
    "google" -> "Billed by Google Play"
    "stripe" -> "Billed by KIFF"
    "contract" -> "Managed by your company's agreement"
    else -> null
  }

  /** "60 of 70 left, until 22 Oct 2027". */
  fun packLeft(p: UsagePack): String = "${p.tasksLeft} of ${p.tasks} left" + (p.expiresAt?.let { ", until ${shownDate(it)}" } ?: "")

  /** What the account should know now: tasks used up, an ended trial, or what is left of it. Null when there is nothing to say. */
  fun notice(u: Usage): String? {
    if (u.tasks.usedUp) {
      return if (u.plan == "trial") "Your trial's tasks are used. Your tafs are paused until tasks are added."
      else "This period's tasks are used. Your tafs are paused until tasks are added or the next period starts."
    }
    val trial = u.trial ?: return null
    if (trial.endsAt == null) return "Your trial hasn't started."
    if (trial.daysLeft <= 0) return "Your trial has ended."
    val days = if (trial.daysLeft == 1) "1 day" else "${trial.daysLeft} days"
    val left = u.tasks.left ?: trial.tasksLeft
    return "Your trial: $days and ${if (left == 1) "1 task" else "$left tasks"} left."
  }

  /** A counted task's tool as the Record names it: KIFF's title, else its name in words. */
  fun toolName(t: CountedTask): String = t.toolTitle?.takeIf { it.isNotEmpty() } ?: toolWords(t.tool)

  /** A tool's name in words, when KIFF gives no title: "refund_order" reads "Refund order". */
  fun toolWords(tool: String): String =
    tool.replace('_', ' ').replace('-', ' ').let { it.take(1).uppercase() + it.drop(1) }

  /** AI use's explanation: what the share means, and where the tafs ask next. */
  fun aiExplain(asksAtPct: Int?): String {
    val at = asksAtPct ?: 100
    val next = if (at <= 100) "At 100%, your tafs ask you before going on." else "You said go on; your tafs ask you again at $at%."
    return "Chats with your tafs and Setup are included. 100% is this month's fair use. $next It's never billed."
  }

  const val AI_ASKING = "This month's AI use is high: your tafs ask before going on."

  // MARK: Asking an admin for tasks, as iOS's Usage says it

  /** A pack's line, "Rayo · 70 tasks", from what's on sale; its id, capitalised, when it isn't. */
  fun packLine(id: String, u: Usage?): String =
    u?.store?.packs?.firstOrNull { it.id == id }?.line?.takeIf { it.isNotEmpty() } ?: id.replaceFirstChar { it.uppercase() }

  /** The member's own request still waiting. */
  fun waiting(u: Usage): PackRequest? = if (u.canAddTasks == true) null else u.requests?.firstOrNull { it.status == "open" }

  /** The member's latest answered request, said plainly. */
  fun lastAnswer(u: Usage): String? {
    if (u.canAddTasks == true) return null
    val r = u.requests?.firstOrNull() ?: return null
    val pack = packLine(r.pack, u)
    return when (r.status) {
      "approved" -> "An admin added $pack."
      "declined" -> r.note?.takeIf { it.isNotEmpty() }?.let { "An admin declined $pack: $it" } ?: "An admin declined $pack."
      "expired" -> "Your request for $pack ended with the period."
      else -> null
    }
  }

  /** Open requests, for someone who may add tasks. */
  fun openRequests(u: Usage): List<PackRequest> = if (u.canAddTasks != true) emptyList() else u.requests.orEmpty().filter { it.status == "open" }

  /** Asking or answering is offered only where tasks have a limit and there's a store. */
  fun asksForTasks(u: Usage): Boolean = u.tasks.left != null && u.store != null

  const val ASK_LINE = "Your account's admins get your request. When one adds the pack, your tafs go on."
}
