package bot.tafs.app.model

/**
 * One of the sample's jobs: its tools, its Card, and the actions the sample plays through.
 * Five jobs, each consequential and painful today. Not a catalogue.
 */
data class TafTemplate(
  val key: String,
  val name: String,
  val job: String,
  val tools: List<String>,
  val rules: List<CardRule>,
  /** Actions the sample proposes, in order, to show the Card at work. */
  val script: List<ProposedAction>,
)

object TafTemplates {
  val all: List<TafTemplate> get() = listOf(refunds, billPay, access, deploys, chargebacks)

  fun template(key: String?): TafTemplate? = all.firstOrNull { it.key == key }

  /** Support: settle refund and credit requests within policy. */
  val refunds = TafTemplate(
    key = "refunds", name = Words.Sample.Refunds.name,
    job = Words.Sample.Refunds.job,
    tools = listOf(Words.Sample.Tool.payments, Words.Sample.Tool.store, Words.Sample.Tool.email),
    rules = listOf(
      CardRule(action = "refund", label = Words.Sample.Refunds.Rule.refunds, perAction = Money.eur(150.0), perWindow = Money.eur(2_000.0), window = Window.day, nextSteps = listOf("Return first")),
      CardRule(action = "credit", label = Words.Sample.Refunds.Rule.credits, perAction = Money.eur(100.0), perWindow = Money.eur(1_000.0), window = Window.day),
      CardRule(action = "payout", label = Words.Sample.Refunds.Rule.payouts, never = true, outside = Outside.refuse),
    ),
    script = listOf(
      ProposedAction("refund", Words.Sample.Refunds.refund4471, Money.eur(40.0), Words.Sample.Refunds.refund4471Why),
      ProposedAction("credit", Words.Sample.Refunds.credit2290, Money.eur(25.0), Words.Sample.Refunds.credit2290Why),
      ProposedAction("refund", Words.Sample.Refunds.refund4502, Money.eur(480.0), Words.Sample.Refunds.refund4502Why),
      ProposedAction("payout", Words.Sample.Refunds.payout, Money.eur(900.0), Words.Sample.Refunds.payoutWhy),
    ),
  )

  /** Accounts payable: match, approve and pay routine vendor bills. */
  val billPay = TafTemplate(
    key = "billpay", name = Words.Sample.BillPay.name,
    job = Words.Sample.BillPay.job,
    tools = listOf(Words.Sample.Tool.accounting, Words.Sample.Tool.email),
    rules = listOf(
      CardRule(action = "pay_bill", label = Words.Sample.BillPay.Rule.bills, perAction = Money.eur(5_000.0), perWindow = Money.eur(25_000.0), window = Window.week, nextSteps = listOf("Pay half now")),
      CardRule(action = "wire", label = Words.Sample.BillPay.Rule.wires, alwaysAsk = true),
      CardRule(action = "bank_details", label = Words.Sample.BillPay.Rule.bankDetails, never = true, outside = Outside.refuse),
      CardRule(action = "new_payee", label = Words.Sample.BillPay.Rule.newPayees, never = true, outside = Outside.refuse),
    ),
    script = listOf(
      ProposedAction("pay_bill", Words.Sample.BillPay.payNorthwind, Money.eur(1_240.0), Words.Sample.BillPay.payNorthwindWhy),
      ProposedAction("pay_bill", Words.Sample.BillPay.payAtlas, Money.eur(8_400.0), Words.Sample.BillPay.payAtlasWhy),
      ProposedAction("bank_details", Words.Sample.BillPay.updateBank, null, Words.Sample.BillPay.updateBankWhy),
    ),
  )

  /** IT: joiners, movers and leavers across identity, workspace and code. */
  val access = TafTemplate(
    key = "access", name = Words.Sample.Access.name,
    job = Words.Sample.Access.job,
    tools = listOf(Words.Sample.Tool.signIn, Words.Sample.Tool.code),
    rules = listOf(
      CardRule(action = "grant_role", label = Words.Sample.Access.Rule.approvedRoles, countPerWindow = 40, window = Window.day),
      CardRule(action = "deactivate", label = Words.Sample.Access.Rule.deactivations, countPerWindow = 20, window = Window.day),
      CardRule(action = "elevate", label = Words.Sample.Access.Rule.elevated, alwaysAsk = true),
      CardRule(action = "grant_admin", label = Words.Sample.Access.Rule.adminRoles, never = true, outside = Outside.refuse),
      CardRule(action = "delete_user", label = Words.Sample.Access.Rule.deletingUsers, never = true, outside = Outside.refuse),
    ),
    script = listOf(
      ProposedAction("grant_role", Words.Sample.Access.salesRole, null, Words.Sample.Access.salesRoleWhy),
      ProposedAction("deactivate", Words.Sample.Access.deactivateTom, null, Words.Sample.Access.deactivateTomWhy),
      ProposedAction("elevate", Words.Sample.Access.databaseLee, null, Words.Sample.Access.databaseLeeWhy),
      ProposedAction("grant_admin", Words.Sample.Access.adminLee, null, Words.Sample.Access.adminLeeWhy),
    ),
  )

  /** Engineering: ship fixes and roll back, never destroy. */
  val deploys = TafTemplate(
    key = "deploys", name = Words.Sample.Deploys.name,
    job = Words.Sample.Deploys.job,
    tools = listOf(Words.Sample.Tool.code, Words.Sample.Tool.hosting, Words.Sample.Tool.network),
    rules = listOf(
      CardRule(action = "deploy", label = Words.Sample.Deploys.Rule.deploys, countPerWindow = 10, window = Window.day),
      CardRule(action = "rollback", label = Words.Sample.Deploys.Rule.rollbacks, countPerWindow = 20, window = Window.day),
      CardRule(action = "migration", label = Words.Sample.Deploys.Rule.migrations, alwaysAsk = true, nextSteps = listOf("Run it tonight")),
      CardRule(action = "destroy_data", label = Words.Sample.Deploys.Rule.destroyData, never = true, outside = Outside.refuse),
    ),
    script = listOf(
      ProposedAction("deploy", Words.Sample.Deploys.deployApi, null, Words.Sample.Deploys.deployApiWhy),
      ProposedAction("migration", Words.Sample.Deploys.migration, null, Words.Sample.Deploys.migrationWhy),
      ProposedAction("destroy_data", "DROP TABLE sessions_old", null, Words.Sample.Deploys.dropTableWhy),
    ),
  )

  /** Payments: answer disputes with evidence before the deadline. */
  val chargebacks = TafTemplate(
    key = "chargebacks", name = Words.Sample.Chargebacks.name,
    job = Words.Sample.Chargebacks.job,
    tools = listOf(Words.Sample.Tool.payments),
    rules = listOf(
      CardRule(action = "submit_evidence", label = Words.Sample.Chargebacks.Rule.evidence, perAction = Money.eur(500.0), countPerWindow = 50, window = Window.day),
      CardRule(action = "accept_dispute", label = Words.Sample.Chargebacks.Rule.acceptingLoss, perAction = Money.eur(100.0), perWindow = Money.eur(500.0), window = Window.week),
    ),
    script = listOf(
      ProposedAction("submit_evidence", Words.Sample.Chargebacks.answer3318, Money.eur(120.0), Words.Sample.Chargebacks.answer3318Why),
      ProposedAction("accept_dispute", Words.Sample.Chargebacks.accept3342, Money.eur(35.0), Words.Sample.Chargebacks.accept3342Why),
      ProposedAction("submit_evidence", Words.Sample.Chargebacks.answer3360, Money.eur(1_800.0), Words.Sample.Chargebacks.answer3360Why),
    ),
  )
}
