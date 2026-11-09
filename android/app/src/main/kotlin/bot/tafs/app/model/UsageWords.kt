package bot.tafs.app.model

import bot.tafs.app.kiff.Halt
import bot.tafs.app.kiff.PackRequest
import bot.tafs.app.kiff.Usage
import java.time.Instant

/** What the Halt screen says, in the words iOS's has. */
object HaltWords {
  fun headline(h: Halt): String = when {
    h.running -> Words.Halt.Headline.running
    h.halted -> Words.Halt.Headline.halted
    else -> Words.Halt.Headline.halting
  }

  fun explanation(h: Halt, all: Boolean): String {
    return when {
      h.running -> if (all) Words.Halt.Explain.runningAll else Words.Halt.Explain.runningOne
      h.halted -> h.haltedAt?.let { Words.Halt.Explain.haltedSince(shortTime(it)) } ?: Words.Halt.Explain.halted
      else -> Words.Halt.Explain.halting
    }
  }

  /** The row on a taf's page, or Home, under "Halt": where it stands. */
  fun rowLine(h: Halt): String = if (h.running) Words.Halt.rowRunning else headline(h)

  /** A taf halted with the whole account is resumed with it, not on its own. */
  fun resumesWithAccount(h: Halt, tafId: String?): Boolean = h.account && tafId != null
}

/** What the Usage screens say, in the words iOS's has. */
object UsageWords {
  /** The account's screen: its plan, and what the period used. */
  val TITLE get() = Words.Usage.title

  /** The balance's panel and buying's: their labels. The balance's lines are tafs cloud's (`GET /v1/usage/balance`). */
  val BALANCE get() = Words.Usage.balance
  val BUY get() = Words.Usage.buy

  /** Under a pack of credits on sale: its credits never expire. */
  val PACKS_KEEP get() = Words.Usage.packsKeep

  /** A raise from a limit that changed meanwhile (KIFF's `limit_changed`), and one past what the phone can raise (`limit_ceiling`), as iPhone says them. */
  /** On an open request for a raise, for who manages billing. */
  val RAISE_LIMIT get() = Words.Usage.raiseLimit

  val LIMIT_CHANGED get() = Words.Usage.limitChanged
  val LIMIT_CEILING get() = Words.Usage.limitCeiling

  /** After a raise that went through, while the balance couldn't be read since: never a second raise. */
  val RAISED_UNREAD get() = Words.Usage.raisedUnread

  /** The Credits panel, and its switch between this period and the one before; the rest of it is tafs cloud's words. */
  val CREDITS get() = Words.Usage.credits
  val LAST_PERIOD get() = Words.Usage.lastPeriod
  val THIS_PERIOD get() = Words.Usage.thisPeriod
  val MORE_RUNS get() = Words.Usage.moreRuns

  /** "1 task", "8 tasks". */
  fun tasks(n: Int): String = Words.Usage.tasks(count = n)

  /** A taf's counted tasks this period, for its page's Usage row. */
  fun tafCount(n: Int): String = Words.Usage.tasksThisPeriod(count = n)

  /**
   * "14 of 25 tasks", or "472 tasks" where there is no limit (KIFF's `included: 0`), in the singular where the number it follows
   * is 1: "1 task", "0 of 1 task". [unit] is the plural; its singular drops the final "s".
   */
  fun count(counted: Int, of: Int, unit: String): String {
    val one = unit.removeSuffix("s")
    return if (of > 0) Words.Usage.countOf(counted.toString(), of.toString(), if (of == 1) one else unit) else Words.Usage.count(counted.toString(), if (counted == 1) one else unit)
  }

  /** Solo's terms, every one, as Google Play's price says them: "$9.99 a month. Renews every month until cancelled…". */
  fun soloTerms(price: String, period: String?, freeTrial: String?): String {
    val every = period ?: Words.Usage.month
    return freeTrial?.let { Words.Usage.soloTermsTrial(it, price, every) } ?: Words.Usage.soloTerms(price, every)
  }

  /** One line on who bills the plan; null when no plan is billed. */
  fun billingLine(u: Usage): String? = when (u.billing?.source) {
    "apple" -> Words.Billing.byApple
    "google" -> Words.Billing.byGooglePlay
    "stripe" -> Words.Billing.byKiff
    "contract" -> Words.Billing.byAgreement
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
  fun asked(r: PackRequest): String = if (r.isRaise) Words.Billing.Ask.askedRaise else Words.Billing.Ask.askedPack

  /** The person's latest answered request, said plainly. */
  fun lastAnswer(u: Usage, managesBilling: Boolean): String? {
    if (managesBilling) return null
    val r = u.requests?.firstOrNull() ?: return null
    return when (r.status) {
      "approved" -> if (r.isRaise) Words.Billing.Ask.raised else Words.Billing.Ask.addedPack
      "declined" -> r.note?.takeIf { it.isNotEmpty() }?.let { Words.Billing.Ask.declinedNote(it) } ?: Words.Billing.Ask.declined
      "expired" -> Words.Billing.Ask.expired
      else -> null
    }
  }

  /** Open requests, for someone who manages billing. */
  fun openRequests(u: Usage, managesBilling: Boolean): List<PackRequest> = if (!managesBilling) emptyList() else u.requests.orEmpty().filter { it.status == "open" }

  /** An open request, as an admin reads it. */
  fun requestLine(r: PackRequest, u: Usage): String =
    if (r.isRaise) Words.Billing.Ask.someoneRaise else Words.Billing.Ask.someonePack(packLine(r.pack.orEmpty(), u))

  /** Under Ask an admin: what happens next. */
  fun askLine(kind: String): String =
    if (kind == PackRequest.RAISE) Words.Billing.Ask.lineRaise else Words.Billing.Ask.linePack
}
