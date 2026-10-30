package bot.tafs.app.model

/**
 * A job tafs knows how to draft: its tools, its Card, and the actions the sample plays through.
 * Five jobs, each consequential and painful today. Not a catalogue.
 */
data class TafTemplate(
  val key: String,
  val name: String,
  val keywords: List<String>,
  val job: String,
  val tools: List<String>,
  val rules: List<CardRule>,
  /** Actions the sample proposes, in order, to show the Card at work. */
  val script: List<ProposedAction>,
)

object TafTemplates {
  val all: List<TafTemplate> get() = listOf(refunds, billPay, access, deploys, chargebacks)

  fun template(key: String?): TafTemplate? = all.firstOrNull { it.key == key }

  fun draft(job: String): TafDraft {
    val words = job.lowercase()
    val match = all.firstOrNull { t -> t.keywords.any { words.contains(it) } }
      ?: return TafDraft("New taf", job, emptyList(), emptyList(), null)
    return TafDraft(match.name, job, match.tools, match.rules, match.key)
  }

  /** Support: settle refund and credit requests within policy. */
  val refunds = TafTemplate(
    key = "refunds", name = "Refunds",
    keywords = listOf("refund", "credit", "return", "goodwill"),
    job = "Settle refund and credit requests from the support queue. Never refund more than the original charge, never twice, never on a disputed charge.",
    tools = listOf("Payments", "Store", "Email"),
    rules = listOf(
      CardRule(action = "refund", label = "Refunds", perAction = Money.usd(150.0), perWindow = Money.usd(2_000.0), window = Window.day, nextSteps = listOf("Return first")),
      CardRule(action = "credit", label = "Credits", perAction = Money.usd(100.0), perWindow = Money.usd(1_000.0), window = Window.day),
      CardRule(action = "payout", label = "Payouts", never = true, outside = Outside.refuse),
    ),
    script = listOf(
      ProposedAction("refund", "Refund order #4471", Money.usd(40.0), "Arrived 6 days late"),
      ProposedAction("credit", "Credit on account #2290", Money.usd(25.0), "Billing error last month"),
      ProposedAction("refund", "Refund order #4502", Money.usd(480.0), "Damaged on arrival, photo attached"),
      ProposedAction("payout", "Pay out to a new bank account", Money.usd(900.0), "Customer asked for cash instead"),
    ),
  )

  /** Accounts payable: match, approve and pay routine vendor bills. */
  val billPay = TafTemplate(
    key = "billpay", name = "Bill pay",
    keywords = listOf("bill", "vendor", "payable", "pay invoice", "supplier"),
    job = "Pay routine vendor bills that match a purchase order. Existing vendors only. Never change bank details or add a payee.",
    tools = listOf("Accounting", "Email"),
    rules = listOf(
      CardRule(action = "pay_bill", label = "Bills", perAction = Money.usd(5_000.0), perWindow = Money.usd(25_000.0), window = Window.week, nextSteps = listOf("Pay half now")),
      CardRule(action = "wire", label = "Wires", alwaysAsk = true),
      CardRule(action = "bank_details", label = "Bank detail changes", never = true, outside = Outside.refuse),
      CardRule(action = "new_payee", label = "New payees", never = true, outside = Outside.refuse),
    ),
    script = listOf(
      ProposedAction("pay_bill", "Pay Northwind Packaging", Money.usd(1_240.0), "Matches PO-3381"),
      ProposedAction("pay_bill", "Pay Atlas Freight", Money.usd(8_400.0), "Matches PO-3390, quarterly"),
      ProposedAction("bank_details", "Update Atlas Freight's bank account", null, "Email from a new address asked for it"),
    ),
  )

  /** IT: joiners, movers and leavers across identity, workspace and code. */
  val access = TafTemplate(
    key = "access", name = "Access",
    keywords = listOf("access", "onboard", "offboard", "leaver", "joiner", "provision", "okta"),
    job = "Give new joiners their apps and remove leavers the day they go. Roles from the approved list only. Deactivate, never delete.",
    tools = listOf("Sign-in", "Code"),
    rules = listOf(
      CardRule(action = "grant_role", label = "Approved roles", countPerWindow = 40, window = Window.day),
      CardRule(action = "deactivate", label = "Deactivations", countPerWindow = 20, window = Window.day),
      CardRule(action = "elevate", label = "Elevated access", alwaysAsk = true),
      CardRule(action = "grant_admin", label = "Admin roles", never = true, outside = Outside.refuse),
      CardRule(action = "delete_user", label = "Deleting users", never = true, outside = Outside.refuse),
    ),
    script = listOf(
      ProposedAction("grant_role", "Give Ana the Sales role", null, "Starts Monday, per HR"),
      ProposedAction("deactivate", "Deactivate Tom's accounts", null, "Left Friday, per HR"),
      ProposedAction("elevate", "Production database access for Lee, 8 hours", null, "On call, incident open"),
      ProposedAction("grant_admin", "Make Lee an admin of the code", null, "Asked in the team chat"),
    ),
  )

  /** Engineering: ship fixes and roll back, never destroy. */
  val deploys = TafTemplate(
    key = "deploys", name = "Deploys",
    keywords = listOf("deploy", "production", "release", "migration", "rollback"),
    job = "Ship merged fixes to production when CI is green, and roll back when errors spike. Never drop or delete production data.",
    tools = listOf("Code", "Hosting", "Network"),
    rules = listOf(
      CardRule(action = "deploy", label = "Production deploys", countPerWindow = 10, window = Window.day),
      CardRule(action = "rollback", label = "Rollbacks", countPerWindow = 20, window = Window.day),
      CardRule(action = "migration", label = "Schema migrations", alwaysAsk = true, nextSteps = listOf("Run it tonight")),
      CardRule(action = "destroy_data", label = "Dropping or deleting data", never = true, outside = Outside.refuse),
    ),
    script = listOf(
      ProposedAction("deploy", "Deploy api v2.14.1", null, "PR #812 merged, CI green"),
      ProposedAction("migration", "Run migration 0042_orders_index", null, "Adds an index, 3M rows"),
      ProposedAction("destroy_data", "DROP TABLE sessions_old", null, "Looks unused"),
    ),
  )

  /** Payments: answer disputes with evidence before the deadline. */
  val chargebacks = TafTemplate(
    key = "chargebacks", name = "Chargebacks",
    keywords = listOf("chargeback", "dispute"),
    job = "Answer card disputes with the order's evidence before the deadline. Accept small ones that can't be won. Share nothing beyond the template.",
    tools = listOf("Payments"),
    rules = listOf(
      CardRule(action = "submit_evidence", label = "Evidence", perAction = Money.usd(500.0), countPerWindow = 50, window = Window.day),
      CardRule(action = "accept_dispute", label = "Accepting a loss", perAction = Money.usd(100.0), perWindow = Money.usd(500.0), window = Window.week),
    ),
    script = listOf(
      ProposedAction("submit_evidence", "Answer dispute on #3318", Money.usd(120.0), "Delivered, signed for"),
      ProposedAction("accept_dispute", "Accept dispute on #3342", Money.usd(35.0), "No proof of delivery"),
      ProposedAction("submit_evidence", "Answer dispute on #3360", Money.usd(1_800.0), "Due in 2 days"),
    ),
  )
}
