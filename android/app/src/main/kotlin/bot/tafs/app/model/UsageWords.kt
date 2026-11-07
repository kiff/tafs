package bot.tafs.app.model

import bot.tafs.app.kiff.Halt
import bot.tafs.app.kiff.PackRequest
import bot.tafs.app.kiff.Usage
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
      else -> "Nothing new starts. Halted shows once KIFF and the connection both confirm."
    }
  }

  /** The row on a taf's page, or Home, under "Halt": where it stands. */
  fun rowLine(h: Halt): String = if (h.running) "Stop it until you resume" else headline(h)

  /** A taf halted with the whole account is resumed with it, not on its own. */
  fun resumesWithAccount(h: Halt, tafId: String?): Boolean = h.account && tafId != null
}

/** What the Usage screens say, in the words iOS's has. */
object UsageWords {
  /** The account's screen: its plan, and what the period used. */
  const val TITLE = "Plan & usage"

  /** The balance's panel and buying's: their labels. The balance's lines are tafs cloud's (`GET /v1/usage/balance`). */
  const val BALANCE = "Balance"
  const val BUY = "Add credits"

  /** Under a pack of credits on sale: its credits never expire. */
  const val PACKS_KEEP = "Never expires"

  /** A raise from a limit that changed meanwhile (KIFF's `limit_changed`), and one past what the phone can raise (`limit_ceiling`), as iPhone says them. */
  /** On an open request for a raise, for who manages billing. */
  const val RAISE_LIMIT = "Raise the limit"

  const val LIMIT_CHANGED = "The limit changed meanwhile. Here it is now: raise it again if it's still needed."
  const val LIMIT_CEILING = "The limit is as high as it can be raised from the app. Contact KIFF to raise it further."

  /** After a raise that went through, while the balance couldn't be read since: never a second raise. */
  const val RAISED_UNREAD = "Your tafs can go on. This period's credits couldn't be read since."

  /** The Credits panel, and its switch between this period and the one before; the rest of it is tafs cloud's words. */
  const val CREDITS = "Credits"
  const val LAST_PERIOD = "Last period"
  const val THIS_PERIOD = "This period"
  const val MORE_RUNS = "More runs"

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

  // MARK: Asking an admin, in the words docs/accounts.md gives both apps

  /** A pack's line from what's on sale, "Pack · 500 credits"; its id, capitalised, when it isn't. */
  fun packLine(id: String, u: Usage?): String =
    u?.store?.packs?.firstOrNull { it.id == id }?.line?.takeIf { it.isNotEmpty() } ?: id.replaceFirstChar { it.uppercase() }

  /** What's asked of an admin on this plan: a raise of the spend limit on Company (KIFF's `team`), a pack on Solo; nothing on others. */
  fun asks(u: Usage): String? = when (u.plan) {
    "team" -> PackRequest.RAISE
    "solo" -> if (u.store?.packs.isNullOrEmpty()) null else PackRequest.PACK
    else -> null
  }

  /** The person's own request still waiting, for someone who doesn't manage billing. */
  fun waiting(u: Usage, managesBilling: Boolean): PackRequest? = if (managesBilling) null else u.requests?.firstOrNull { it.status == "open" }

  /** "You asked an admin to raise the spend limit.", or for a pack. */
  fun asked(r: PackRequest): String = if (r.isRaise) "You asked an admin to raise the spend limit." else "You asked an admin for a pack."

  /** The person's latest answered request, said plainly. */
  fun lastAnswer(u: Usage, managesBilling: Boolean): String? {
    if (managesBilling) return null
    val r = u.requests?.firstOrNull() ?: return null
    return when (r.status) {
      "approved" -> if (r.isRaise) "An admin raised the spend limit." else "An admin added a pack."
      "declined" -> r.note?.takeIf { it.isNotEmpty() }?.let { "An admin declined: $it" } ?: "An admin declined."
      "expired" -> "Your request ended with the period."
      else -> null
    }
  }

  /** Open requests, for someone who manages billing. */
  fun openRequests(u: Usage, managesBilling: Boolean): List<PackRequest> = if (!managesBilling) emptyList() else u.requests.orEmpty().filter { it.status == "open" }

  /** An open request, as an admin reads it. */
  fun requestLine(r: PackRequest, u: Usage): String =
    if (r.isRaise) "Someone asks to raise the spend limit." else "Someone asks for ${packLine(r.pack.orEmpty(), u)}."

  /** Under Ask an admin: what happens next. */
  fun askLine(kind: String): String =
    if (kind == PackRequest.RAISE) "Your account's admins get your request. When one raises the spend limit, your tafs go on."
    else "Your account's admins get your request. When one adds the pack, your tafs go on."
}
