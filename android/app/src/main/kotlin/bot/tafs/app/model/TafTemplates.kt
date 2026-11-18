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
  val all: List<TafTemplate> get() = listOf(support, parcels, access, deploys, leads)

  fun template(key: String?): TafTemplate? = all.firstOrNull { it.key == key }

  /**
   * The templates read their words each time, so each is in the app's language now (a language picked in Account, or each of the
   * store screenshots' 8). Their rules keep one id each all the same, from the action (unique across the templates), so a template
   * read again is the same template and a sample taf's Card still matches its job's.
   */
  private fun sampleRules(vararg rules: CardRule): List<CardRule> =
    rules.map { it.copy(id = java.util.UUID.nameUUIDFromBytes("tafs.sample.rule.${it.action}".toByteArray()).toString()) }

  /** Support: answer tickets the same day; promises wait for the owner, a customer's details never leave. */
  val support: TafTemplate get() = TafTemplate(
    key = "support", name = Words.Sample.Support.name,
    job = Words.Sample.Support.job,
    tools = listOf(Words.Sample.Tool.email, Words.Sample.Tool.store),
    rules = sampleRules(
      CardRule(action = "reply_ticket", label = Words.Sample.Support.Rule.replies, countPerWindow = 40, window = Window.day),
      CardRule(action = "promise", label = Words.Sample.Support.Rule.promises, alwaysAsk = true, nextSteps = listOf(Words.Sample.Support.nextStep)),
      CardRule(action = "share_customer_data", label = Words.Sample.Support.Rule.customerData, never = true, outside = Outside.refuse),
    ),
    script = listOf(
      ProposedAction("reply_ticket", Words.Sample.Support.reply7781, null, Words.Sample.Support.reply7781Why),
      ProposedAction("reply_ticket", Words.Sample.Support.reply7790, null, Words.Sample.Support.reply7790Why),
      ProposedAction("promise", Words.Sample.Support.promise7802, null, Words.Sample.Support.promise7802Why),
      ProposedAction("share_customer_data", Words.Sample.Support.shareHistory, null, Words.Sample.Support.shareHistoryWhy),
    ),
  )

  /** Operations: tell customers of a late parcel before they ask; reships wait, an address never changes from an email. */
  val parcels: TafTemplate get() = TafTemplate(
    key = "parcels", name = Words.Sample.Parcels.name,
    job = Words.Sample.Parcels.job,
    tools = listOf(Words.Sample.Tool.store, Words.Sample.Tool.email),
    rules = sampleRules(
      CardRule(action = "delay_message", label = Words.Sample.Parcels.Rule.delayMessages, countPerWindow = 100, window = Window.day),
      CardRule(action = "reship", label = Words.Sample.Parcels.Rule.reships, alwaysAsk = true, nextSteps = listOf(Words.Sample.Parcels.nextStep)),
      CardRule(action = "change_address", label = Words.Sample.Parcels.Rule.addressChanges, never = true, outside = Outside.refuse),
      CardRule(action = "parcel_refund", label = Words.Sample.Parcels.Rule.refunds, never = true, outside = Outside.refuse),
    ),
    script = listOf(
      ProposedAction("delay_message", Words.Sample.Parcels.tell5120, null, Words.Sample.Parcels.tell5120Why),
      ProposedAction("delay_message", Words.Sample.Parcels.tell5134, null, Words.Sample.Parcels.tell5134Why),
      ProposedAction("reship", Words.Sample.Parcels.reship5098, null, Words.Sample.Parcels.reship5098Why),
      ProposedAction("change_address", Words.Sample.Parcels.changeAddress, null, Words.Sample.Parcels.changeAddressWhy),
    ),
  )

  /** IT: joiners, movers and leavers across identity, workspace and code. */
  val access: TafTemplate get() = TafTemplate(
    key = "access", name = Words.Sample.Access.name,
    job = Words.Sample.Access.job,
    tools = listOf(Words.Sample.Tool.signIn, Words.Sample.Tool.code),
    rules = sampleRules(
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
  val deploys: TafTemplate get() = TafTemplate(
    key = "deploys", name = Words.Sample.Deploys.name,
    job = Words.Sample.Deploys.job,
    tools = listOf(Words.Sample.Tool.code, Words.Sample.Tool.hosting, Words.Sample.Tool.network),
    rules = sampleRules(
      CardRule(action = "deploy", label = Words.Sample.Deploys.Rule.deploys, countPerWindow = 10, window = Window.day),
      CardRule(action = "rollback", label = Words.Sample.Deploys.Rule.rollbacks, countPerWindow = 20, window = Window.day),
      CardRule(action = "migration", label = Words.Sample.Deploys.Rule.migrations, alwaysAsk = true, nextSteps = listOf(Words.Sample.Deploys.nextStep)),
      CardRule(action = "destroy_data", label = Words.Sample.Deploys.Rule.destroyData, never = true, outside = Outside.refuse),
    ),
    script = listOf(
      ProposedAction("deploy", Words.Sample.Deploys.deployApi, null, Words.Sample.Deploys.deployApiWhy),
      ProposedAction("migration", Words.Sample.Deploys.migration, null, Words.Sample.Deploys.migrationWhy),
      ProposedAction("destroy_data", "DROP TABLE sessions_old", null, Words.Sample.Deploys.dropTableWhy),
    ),
  )

  /** Sales: reply to new leads within the hour and book a call; discounts wait for the owner. */
  val leads: TafTemplate get() = TafTemplate(
    key = "leads", name = Words.Sample.Leads.name,
    job = Words.Sample.Leads.job,
    tools = listOf(Words.Sample.Tool.email),
    rules = sampleRules(
      CardRule(action = "reply_lead", label = Words.Sample.Leads.Rule.replies, countPerWindow = 50, window = Window.day),
      CardRule(action = "offer_discount", label = Words.Sample.Leads.Rule.discounts, alwaysAsk = true),
    ),
    script = listOf(
      ProposedAction("reply_lead", Words.Sample.Leads.replyKaya, null, Words.Sample.Leads.replyKayaWhy),
      ProposedAction("reply_lead", Words.Sample.Leads.confirmNord, null, Words.Sample.Leads.confirmNordWhy),
      ProposedAction("offer_discount", Words.Sample.Leads.discountLumen, null, Words.Sample.Leads.discountLumenWhy),
    ),
  )
}
