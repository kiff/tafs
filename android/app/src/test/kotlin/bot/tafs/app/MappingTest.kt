package bot.tafs.app

import bot.tafs.app.kiff.ActivityAmount
import bot.tafs.app.kiff.ActivityCall
import bot.tafs.app.kiff.ActivityCalls
import bot.tafs.app.kiff.CardException
import bot.tafs.app.kiff.CardExceptions
import bot.tafs.app.kiff.Home
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.kiff.ToolOutcome
import bot.tafs.app.model.CardRule
import bot.tafs.app.model.Money
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.TafState
import bot.tafs.app.model.Outside
import bot.tafs.app.model.Window
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingTest {
  private val json = KiffClient.json

  private fun exceptions(text: String): List<CardException> = json.decodeFromString<CardExceptions>(text).exceptions
  private fun calls(text: String): List<ActivityCall> = json.decodeFromString<ActivityCalls>(text).calls
  private fun home(text: String): Home = json.decodeFromString(text)

  private fun eur(minor: Long) = Money.of(minor, "EUR", 2)

  // MARK: Words (iOS's own vectors)

  @Test fun aToolIsNamedInWordsPastItsPrefixes() {
    assertEquals("list accounts", KiffMapping.toolWords("bxkwf7vi_list_accounts"))
    assertEquals("refund payment", KiffMapping.toolWords("bxkwf7vi_refund_payment"))
    // A word is not a prefix.
    assertEquals("create refund", KiffMapping.toolWords("create_refund"))
    assertEquals("create refund", KiffMapping.toolWords("stripe.create_refund"))
    assertEquals("issue credit", KiffMapping.toolWords("kiff__issue-credit"))
  }

  @Test fun aVendorIsNamedAsPeopleSayIt() {
    assertEquals("Stripe", KiffMapping.vendorWords("stripe-mcp"))
    assertEquals("Stripe", KiffMapping.vendorWords("mcp.stripe.com"))
    assertEquals("Stripe", KiffMapping.vendorWords("Stripe"))
    assertEquals("Access", KiffMapping.vendorWords("access.stripe.com"))
  }

  @Test fun aHeldCallIsNamedByItsTitleWithItsActionWhereTheTitleWouldReadAlike() {
    assertEquals("Write Stripe API data · refund payment", KiffMapping.says("bxkwf7vi_refund_payment", "Write Stripe API data"))
    assertEquals("Refund a payment", KiffMapping.says("bxkwf7vi_refund_payment", "Refund a payment"))
    assertEquals("Create refund", KiffMapping.says("create_refund", "Create refund"))
    assertEquals("Refund a payment", KiffMapping.says("create_refund", "Refund a payment"))
    assertEquals("refund payment", KiffMapping.says("bxkwf7vi_refund_payment", null))
    assertEquals("Write Stripe API data · stripe create refund", KiffMapping.says("stripe_create_refund", "Write Stripe API data"))
  }

  @Test fun aTitleOfOnlySpacesIsNoTitle() {
    assertEquals("create refund", KiffMapping.name("create_refund", "   "))
  }

  // MARK: Held calls (iOS's own fixtures)

  private val heldGateway = """
    {"exceptions":[{"id":"ce_1","subject":"refunds-k5j6m5qx","action":"create_refund","title":"Refund a payment","entity_id":"tc-bc422e3c",
      "status":"held","breaches":[{"outcome":"per_action_exceeded","reason":"150.00 EUR is above the 100.00 EUR limit for one call","quantity":"sum(amount)","limit":10000,"unit":"EUR","scale":2}],
      "parameters":{"amount":15000},"parameter_units":{"amount":{"unit":"EUR","scale":2}},
      "arguments":{"payment_intent":"pi_3USLXh","amount":15000,"kiff_operation_id":"op_1"},
      "created_at":"2026-10-19T19:00:00.000Z","expires_at":"2026-10-19T19:10:00Z"}]}
  """.trimIndent()

  @Test fun aHeldGatewayCallSaysWhatItIsOnNotItsToolCallId() {
    val held = KiffMapping.held(exceptions(heldGateway)[0])
    assertEquals("Refund a payment", held.proposed.summary)
    // The amount shows above it, and KIFF's operation id isn't the owner's.
    assertEquals(listOf("payment_intent: pi_3USLXh"), held.details)
    assertEquals(eur(15000), held.proposed.amount)
    assertEquals("${eur(15000).formatted} is over the ${eur(10000).formatted} limit for one call", held.reason)
  }

  @Test fun aHeldCallKeepsKiffsIdsAndTimes() {
    val held = KiffMapping.held(exceptions(heldGateway)[0])
    assertEquals("ce_1", held.kiffId)
    assertEquals("refunds-k5j6m5qx", held.tafId)
    assertEquals(Instant.parse("2026-10-19T19:00:00Z"), held.heldAt)
    assertEquals(Instant.parse("2026-10-19T19:10:00Z"), held.expiresAt)
    assertTrue(held.approvableOnce)
    // The same on every refresh, so an answer removes the right row.
    assertEquals(held.id, KiffMapping.held(exceptions(heldGateway)[0]).id)
    assertEquals(KiffMapping.heldId("ce_1"), held.id)
  }

  @Test fun aHeldCallOverBothLimitsGivesOneReasonTheTotal() {
    val e = exceptions(
      """
      {"exceptions":[{"id":"ce_2","subject":"refunds-k5j6m5qx","action":"create_refund","title":"Refund a payment","entity_id":"tc-1",
        "status":"held","can_approve_once":false,
        "breaches":[{"outcome":"per_action_exceeded","reason":"300.00 EUR is above the 100.00 EUR limit for one call","quantity":"sum(amount)","limit":10000,"unit":"EUR","scale":2},
                    {"outcome":"limit_reached","reason":"300.00 EUR would go over the 250.00 EUR total","quantity":"sum(amount)","limit":25000,"unit":"EUR","scale":2}],
        "parameters":{"amount":30000},"parameter_units":{"amount":{"unit":"EUR","scale":2}},
        "created_at":"2026-10-24T19:00:00.000Z","expires_at":"2026-10-24T19:10:00Z"}]}
      """.trimIndent(),
    )[0]
    val held = KiffMapping.held(e)
    assertFalse(held.approvableOnce)
    assertEquals("${eur(30000).formatted} would go over the ${eur(25000).formatted} total", held.reason)
  }

  @Test fun aHeldCallWithNoUnitKeepsKiffsWordsAndAShownAmountIsBare() {
    val e = exceptions(
      """
      {"exceptions":[{"id":"ce_3","subject":"a","action":"send_credit","entity_id":"tc-2","status":"held",
        "breaches":[{"outcome":"per_action_exceeded","reason":"50 is above the 30 limit for one call","quantity":"sum(amount)","limit":30}],
        "parameters":{"amount":50},"created_at":"2026-10-24T19:00:00Z"}]}
      """.trimIndent(),
    )[0]
    val held = KiffMapping.held(e)
    assertEquals(Money.bare(50), held.proposed.amount)
    // No unit KIFF declares: the reason is KIFF's own, never worded as money.
    assertEquals("50 is above the 30 limit for one call", held.reason)
  }

  /** A hold over a count of calls, as KIFF keeps it: the machine's reason, and KIFF's own words when [reasonText] is sent. */
  private fun countHold(limit: Long, used: Long, window: String? = "calendar_day", reasonText: String? = null) = exceptions(
    """{"exceptions":[{"id":"ce_5","subject":"setup-acme","action":"taf_results","created_at":"2026-10-30T22:50:00Z",
       ${reasonText?.let { "\"reason_text\":\"$it\"," } ?: ""}"can_approve_once":false,
       "breaches":[{"outcome":"limit_reached","quantity":"count","limit":$limit,"used":$used,"draw":1,${window?.let { "\"window\":\"$it\"," } ?: ""}
         "reason":"count limit $limit per calendar_day reached: $used used, this action draws 1"}]}]}""",
  )[0]

  @Test fun aCountOfCallsReadsInTheCardsWordsNeverTheMachines() {
    assertEquals("Each call asks you first", KiffMapping.held(countHold(0, 0)).reason)
    assertEquals("20 calls a day: this would be the 21st", KiffMapping.held(countHold(20, 20)).reason)
    assertEquals("1 call in any hour: this would be the 2nd", KiffMapping.held(countHold(1, 1, "rolling_1h")).reason)
    assertEquals("12 calls in any 24 hours: this would be the 13th", KiffMapping.held(countHold(12, 12, "rolling_24h")).reason)
    assertEquals("3 calls in total: this would be the 4th", KiffMapping.held(countHold(3, 3, "lifetime")).reason)
    // A window KIFF adds later shows as sent, never as a day; none at all says the limit alone.
    assertEquals("5 calls rolling_7d: this would be the 6th", KiffMapping.held(countHold(5, 5, "rolling_7d")).reason)
    assertEquals("Over its limit of 10 calls: this would be the 11th", KiffMapping.held(countHold(10, 10, null)).reason)
    // KIFF's own words come first.
    assertEquals("Each call asks you first, in KIFF's words", KiffMapping.held(countHold(0, 0, reasonText = "Each call asks you first, in KIFF's words")).reason)
  }

  @Test fun ordinalsReadAsWords() {
    assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "101st", "111th"),
      listOf(1L, 2, 3, 4, 11, 12, 13, 21, 22, 101, 111).map(KiffMapping::ordinal))
  }

  @Test fun aCallWithNoArgumentsSaysWhatItIsByItsEntity() {
    val e = exceptions("""{"exceptions":[{"id":"ce_4","subject":"a","action":"stripe.create_refund","entity_id":"tc-9","created_at":"2026-10-24T19:00:00Z"}]}""")[0]
    assertEquals("create refund tc-9", KiffMapping.held(e).proposed.summary)
  }

  @Test fun aHoldWithNoEndLapsesTenMinutesAfterItWasHeld() {
    val e = exceptions("""{"exceptions":[{"id":"ce_5","subject":"a","action":"x","entity_id":"e","created_at":"2026-10-24T19:00:00Z"}]}""")[0]
    assertEquals(Instant.parse("2026-10-24T19:10:00Z"), KiffMapping.held(e).expiresAt)
  }

  @Test fun anArgumentIsWrittenOutAsText() {
    val e = exceptions(
      """{"exceptions":[{"id":"ce_6","subject":"a","action":"x","entity_id":"e","created_at":"2026-10-24T19:00:00Z",
         "arguments":{"b_flag":true,"a_small":1.5e-05,"c_count":50,"d_name":"Ana","e_list":[1,2],"f_null":null,"g_empty":""}}]}""",
    )[0]
    assertEquals(
      listOf("a_small: 0.000015", "b_flag: true", "c_count: 50", "d_name: Ana", "e_list: …", "f_null: …"),
      KiffMapping.details(e),
    )
  }

  @Test fun aHoldKiffsWordsAreTheLastResort() {
    val e = exceptions("""{"exceptions":[{"id":"ce_7","subject":"a","action":"x","entity_id":"e","created_at":"2026-10-24T19:00:00Z","reason":"Ask first"}]}""")[0]
    assertEquals("Ask first", KiffMapping.held(e).reason)
    val none = exceptions("""{"exceptions":[{"id":"ce_8","subject":"a","action":"x","entity_id":"e","created_at":"2026-10-24T19:00:00Z"}]}""")[0]
    assertEquals("Outside the Card", KiffMapping.held(none).reason)
  }

  @Test fun theNextStepsAndProposalComeFromKiff() {
    val e = exceptions(
      """{"exceptions":[{"id":"ce_9","subject":"a","action":"x","entity_id":"e","created_at":"2026-10-24T19:00:00Z","next_steps":["return first"],"proposal_id":"prop_1","accounting_version":3}]}""",
    )[0]
    val held = KiffMapping.held(e)
    assertEquals(listOf("return first"), held.nextSteps)
    assertEquals("prop_1", held.proposalId)
    assertEquals(3, held.accountingVersion)
  }

  // MARK: The list

  private val homeJson = """
    {"tafs":[
      {"id":"taf_1","name":"Refunds","job":"raw job","kiff_agent":"refunds-k5j6m5qx","status":"active","next_run":"2026-10-28T10:00:00.000Z","color":2,"state":"working","waiting":0,
       "job_summary":"Settles refund requests.",
       "card":{"ids":["card_1"],"active":true,"expires":"2027-01-01T00:00:00Z","tools":["Payments","Email"],
         "rules":[{"action":"create_refund","label":"Refund a payment","outside":"hold","per_action":{"minor":10000,"unit":"EUR","scale":2},"per_window":{"minor":100000,"unit":"EUR","scale":2},"window":"day"},
                  {"action":"delete_all","label":"Delete everything","outside":"refuse","window":"week"}],
         "used":{"create_refund":{"minor":20000,"unit":"EUR","scale":2}},"usage_unknown":["delete_all"],
         "cards":[{"id":"card_1","title":"Refunds","environment":"","live":true,"active":true,"expires":"2027-01-01T00:00:00Z",
           "rules":[{"action":"create_refund","label":"Refund a payment","outside":"hold","per_window":{"minor":100000,"unit":"EUR","scale":2},"window":"day"}],
           "total":{"limit":{"minor":100000,"unit":"EUR","scale":2},"used":{"minor":20000,"unit":"EUR","scale":2},"window":"day"}},
          {"id":"card_t","title":"Refunds (test)","environment":"test","live":false,"active":true,"rules":[],
           "calls":{"limit":50,"used":3,"window":"week"}}]}},
      {"id":"taf_2","name":"Access","kiff_agent":"access-1","status":"paused","color":3,"state":"paused","waiting":0,"card":{"ids":["c2","c3"],"active":true,"tools":[],"rules":[],"used":{},"usage_unknown":[]}},
      {"id":"taf_3","name":"Deploys","kiff_agent":"deploys-1","color":4,"state":"waiting","waiting":2,"card":{"ids":["c4"],"active":true,"tools":[],"rules":[]}},
      {"id":"taf_4","name":"Billing","kiff_agent":"billing-1","color":5,"state":"halted","card":{"ids":["c5"],"active":true}},
      {"id":"taf_5","name":"Chatty","kiff_agent":"chatty-1","color":0,"state":"working","unscheduled":true,"card":{"ids":["c6"],"active":true}},
      {"id":"taf_6","name":"Idle","kiff_agent":"idle-1","color":1,"state":"idle","card":{"ids":["c7"],"active":true}},
      {"id":"taf_7","name":"Stopped","kiff_agent":"stopped-1","color":1,"state":"stopped","card":{"ids":[],"active":false}},
      {"id":"taf_8","name":"Running","kiff_agent":"running-1","color":1,"state":"working","card":{"ids":["c8"],"active":true}}],
     "others":[{"name":"Billing bot","kiff_agent":"billing-x","color":1,"state":"idle","waiting":0,"card":{"ids":["c9","c10"],"active":true}}],
     "removing":[{"id":"taf_9","name":"Old"}],"doors_offered":["email"]}
  """.trimIndent()

  private val tafs by lazy { home(homeJson).tafs.map { KiffMapping.taf(it, "You") } }
  private fun named(name: String) = tafs.first { it.name == name }

  @Test fun aTafIsKnownByItsKiffAgentAndNamedAsTafsCloudSaysIt() {
    val refunds = named("Refunds")
    assertEquals("refunds-k5j6m5qx", refunds.id)
    assertEquals("Settles refund requests.", refunds.job)
    assertEquals(listOf("Payments", "Email"), refunds.tools)
    assertEquals(2, refunds.colorIndex)
    assertEquals(listOf("card_1"), refunds.kiffCardIds)
    assertEquals("You", refunds.card.issuedBy)
    assertEquals(Instant.parse("2027-01-01T00:00:00Z"), refunds.card.expires)
    assertFalse(refunds.card.revoked)
  }

  @Test fun aTafsCardLinesCarryTheirUnitsAndWhatHappensOutsideThem() {
    val rules = named("Refunds").card.rules
    assertEquals(eur(10000), rules[0].perAction)
    assertEquals(eur(100000), rules[0].perWindow)
    assertEquals(Window.day, rules[0].window)
    assertEquals(bot.tafs.app.model.Outside.hold, rules[0].outside)
    assertEquals(bot.tafs.app.model.Outside.refuse, rules[1].outside)
    assertEquals(Window.week, rules[1].window)
  }

  @Test fun whatATafHasUsedIsKiffsAndWhatItCouldNotReadIsSaidSo() {
    val refunds = named("Refunds")
    assertEquals(eur(20000), refunds.used["create_refund"])
    assertEquals(setOf("delete_all"), refunds.usageUnknown)
    assertEquals(eur(20000), refunds.usedOf(refunds.card.rules[0]))
  }

  @Test fun eachKiffCardIsKeptOnItsOwnWithItsTotalOrItsCount() {
    val cards = named("Refunds").kiffCards!!
    assertEquals(listOf("card_1", "card_t"), cards.map { it.id })
    assertEquals(eur(100000), cards[0].total!!.limit)
    assertEquals(eur(20000), cards[0].total!!.used)
    assertEquals("${eur(80000).formatted}", cards[0].hero!!.figure)
    assertFalse(cards[1].live)
    assertEquals("TEST", cards[1].environmentLabel)
    assertEquals(50, cards[1].calls!!.limit)
    assertEquals(3, cards[1].calls!!.used)
    assertEquals(Window.week, cards[1].calls!!.window)
  }

  @Test fun aTafStateReadsInTheAppsWords() {
    assertEquals(TafState.working to "Next run " + bot.tafs.app.model.shortTime(Instant.parse("2026-10-28T10:00:00Z")), named("Refunds").let { it.state to it.now })
    assertEquals(TafState.paused to "Paused", named("Access").let { it.state to it.now })
    assertEquals(TafState.waiting to "Waiting on you", named("Deploys").let { it.state to it.now })
    assertEquals(TafState.halted to "Halted · resume it from Halt", named("Billing").let { it.state to it.now })
    // A taf with no schedule isn't at work until it's told something in its chat.
    assertEquals(TafState.idle to "Works from its chat", named("Chatty").let { it.state to it.now })
    assertEquals(TafState.idle to "Acting under KIFF", named("Idle").let { it.state to it.now })
    assertEquals(TafState.revoked to "Card not active", named("Stopped").let { it.state to it.now })
    assertEquals(TafState.working to "Running", named("Running").let { it.state to it.now })
  }

  @Test fun anAgentTafsDoesNotRunIsDescribedByItsCards() {
    val other = home(homeJson).others.map { KiffMapping.taf(it, "You") }.single()
    assertEquals("billing-x", other.id)
    assertEquals("Works under 2 KIFF Cards.", other.job)
    assertEquals("Works under 1 KIFF Card.", named("Deploys").job)
  }

  @Test fun theHomeListHoldsRemovalsAndIgnoresWhatItDoesNotUse() {
    val h = home(homeJson)
    assertEquals(8, h.tafs.size)
    assertEquals(listOf("taf_9"), h.removing.map { it.id })
  }

  @Test fun theExtraRoomAGivenCallLeavesIsKiffsAndShownApartFromTheCardsOwnTotal() {
    val rule = CardRule(
      action = "refund", label = "Refunds", perWindow = eur(33000), window = Window.day,
      extraRoom = eur(3000), baseLimit = eur(30000), roomEnds = Instant.parse("2099-01-01T00:00:00Z"),
    )
    assertEquals(eur(30000), rule.ownTotal)
    assertTrue(rule.roomWords!!.startsWith("+${eur(3000).formatted} extra room until "))
    assertEquals("${eur(30000).formatted} in total a day, then it asks you · ${rule.roomWords}", rule.summary)
    assertEquals("${eur(30000).formatted} a day · ${rule.roomWords}", rule.copy(outside = Outside.refuse).summary)
  }

  @Test fun aRuleThatAsksPastItsLimitsSaysSoInTheCardsWords() {
    val hold = CardRule(action = "refund", label = "Refunds", perAction = eur(4000), perWindow = eur(30000), countPerWindow = 20, window = Window.day)
    assertEquals(
      "up to ${eur(4000).formatted} each; more asks you first · ${eur(30000).formatted} in total a day, then it asks you · 20 calls a day, then it asks you",
      hold.summary,
    )
    assertEquals("1 call a week, then it asks you", CardRule(action = "list", label = "Lists", countPerWindow = 1, window = Window.week).summary)
    assertEquals("Each call asks you first", CardRule(action = "list", label = "Lists", countPerWindow = 0).summary)
    val refuse = hold.copy(outside = Outside.refuse)
    assertEquals("≤ ${eur(4000).formatted} each · ${eur(30000).formatted} a day · 20 calls a day", refuse.summary)
    assertEquals("0 calls a day", CardRule(action = "list", label = "Lists", countPerWindow = 0, outside = Outside.refuse).summary)
  }

  @Test fun whatsLeftOfATotalIsSaidInTheSameUnitOnly() {
    val rule = CardRule(action = "refund", label = "Refunds", perWindow = eur(100000), window = Window.day)
    assertEquals("${eur(80000).formatted} left today", rule.left(eur(20000)))
    assertNull(rule.left(Money.bare(5)))
    assertNull(rule.left(null))
  }

  // MARK: The Record

  private val activityJson = """
    {"calls":[
      {"proposal_id":"p1","action_name":"create_refund","title":"Refund a payment","actor_id":"refunds-k5j6m5qx","outcome":"allowed","created_at":"2026-10-28T08:00:00Z","held":false,
       "tool_outcome":{"status":"done"},"amount":{"value":"150.00","unit":"EUR"},"read_only":false,"entity_id":"tc-1","on_hash":"sha256:abc","on_arg":"payment",
       "receipt":{"trace_id":"tr_1","status":"signed"},"made_at":"2026-10-28T07:59:00Z"},
      {"proposal_id":"p2","action_name":"create_refund","title":"Refund a payment","actor_id":"refunds-k5j6m5qx","outcome":"allowed","created_at":"2026-10-28T08:10:00Z","held":true,
       "tool_outcome":{"status":"done"},"answer":{"decision":"approved","by":"admin","you":true,"signed":true,"device":"dev_1","at":"2026-10-28T08:09:00Z"}},
      {"proposal_id":"p3","action_name":"create_refund","actor_id":"refunds-k5j6m5qx","outcome":"allowed","created_at":"2026-10-28T08:20:00Z","held":false,"tool_outcome":{"status":"refused_by_tool","message":"card declined"},"amount":{"value":"30.00","unit":"EUR"}},
      {"proposal_id":"p4","action_name":"x","actor_id":"refunds-k5j6m5qx","outcome":"allowed","created_at":"2026-10-28T08:30:00Z","tool_outcome":{"status":"not_sent","message":"vendor down"}},
      {"proposal_id":"p5","action_name":"x","actor_id":"refunds-k5j6m5qx","outcome":"allowed","created_at":"2026-10-28T08:40:00Z","tool_outcome":{"status":"sending"}},
      {"proposal_id":"p6","action_name":"x","actor_id":"refunds-k5j6m5qx","outcome":"allowed","created_at":"2026-10-28T08:50:00Z","tool_outcome":{"status":"unknown"}},
      {"proposal_id":"p7","action_name":"x","actor_id":"refunds-k5j6m5qx","outcome":"allowed","created_at":"2026-10-28T09:00:00Z"},
      {"proposal_id":"p8","action_name":"x","actor_id":"refunds-k5j6m5qx","outcome":"owner_required","created_at":"2026-10-28T09:10:00Z"},
      {"proposal_id":"p9","action_name":"x","actor_id":"refunds-k5j6m5qx","outcome":"denied","created_at":"2026-10-28T09:20:00Z","reason":"over the limit"},
      {"proposal_id":"p10","action_name":"x","actor_id":"refunds-k5j6m5qx","outcome":"denied","created_at":"2026-10-28T09:30:00Z"},
      {"proposal_id":"p11","action_name":"x","actor_id":"someone-else","outcome":"allowed","created_at":"2026-10-28T09:40:00Z","tool_outcome":{"status":"done"}},
      {"proposal_id":"p12","action_name":"x","outcome":"allowed","created_at":"2026-10-28T09:50:00Z","tool_outcome":{"status":"done"}}]}
  """.trimIndent()

  private val record by lazy { KiffMapping.record(calls(activityJson), setOf("refunds-k5j6m5qx")) }
  private fun line(proposal: String) = record.first { it.id == KiffMapping.recordId(proposal) }

  @Test fun onlyTheCallsOfTafsTheAppShowsAreInTheRecord() {
    assertEquals(10, record.size)
    assertTrue(record.all { it.tafId == "refunds-k5j6m5qx" })
  }

  @Test fun aCallDoneWithinItsCardIsDoneAndOneTheOwnerApprovedSaysSo() {
    assertEquals(RecordEntry.Kind.done to "Done, within its Card", line("p1").let { it.kind to it.detail })
    val approved = line("p2")
    assertEquals(RecordEntry.Kind.approved to "Approved by you, then done", approved.kind to approved.detail)
    assertEquals(true, approved.ownerApproved)
  }

  @Test fun aCallTheVendorRefusedStillCountsAndSaysSo() {
    val refused = line("p3")
    assertEquals(RecordEntry.Kind.notDone, refused.kind)
    assertEquals("The tool refused it. No money moved, but it still counts against today's total.", refused.detail)
    assertEquals(true, refused.refusedByTool)
  }

  @Test fun theToolsOtherAnswersAreKeptApart() {
    assertEquals(RecordEntry.Kind.notDone, line("p4").kind)
    assertTrue(line("p4").detail.endsWith("nothing reached the tool: vendor down"))
    assertEquals(RecordEntry.Kind.sending, line("p5").kind)
    assertEquals(RecordEntry.Kind.unknown, line("p6").kind)
    // No outcome at all is never read as done.
    assertEquals(RecordEntry.Kind.unknown, line("p7").kind)
    assertTrue(line("p7").detail.contains("wasn't recorded"))
  }

  @Test fun aCallKiffHoldsIsWaitingAndOneItRefusedHasItsReason() {
    assertEquals(RecordEntry.Kind.held to "Waiting for you", line("p8").let { it.kind to it.detail })
    assertEquals(RecordEntry.Kind.refused to "Not sent · over the limit", line("p9").let { it.kind to it.detail })
    assertEquals(RecordEntry.Kind.refused to "Not sent", line("p10").let { it.kind to it.detail })
  }

  @Test fun anAmountIsWrittenAsTheAppWritesMoneyAndALineKeepsWhatKiffSaid() {
    val p1 = line("p1")
    assertEquals(eur(15000).formatted, p1.amountText)
    // "Refund a payment" with its amount reads as the verb and the money: the article is the mockup's cue.
    assertEquals("Refund ${eur(15000).formatted}", p1.titleWithAmount)
    assertEquals("Refund order · ${eur(15000).formatted}", p1.copy(title = "Refund order").titleWithAmount)
    assertEquals("Refund a payment", p1.copy(amountText = null).titleWithAmount)
    assertEquals("tc-1", p1.entityId)
    assertEquals("sha256:abc", p1.onHash)
    assertEquals("payment", p1.onArg)
    assertEquals("signed", p1.receipt!!.status)
    assertEquals("tr_1", p1.receipt!!.traceId)
    assertEquals(Instant.parse("2026-10-28T07:59:00Z"), p1.madeAt)
    assertEquals(Instant.parse("2026-10-28T08:00:00Z"), p1.at)
  }

  @Test fun aLineSaysWhoDecidedAndWhatBecameOfIt() {
    val p1 = line("p1")
    assertEquals("within its Card", p1.decidedLine)
    assertEquals("done", p1.resultLine)
    val p2 = line("p2")
    assertTrue(p2.answeredByOwner)
    assertEquals("approved by you", p2.decidedLine)
    assertEquals("you", p2.answerer)
    assertEquals("refused by its Card", line("p9").decidedLine)
    assertEquals("nothing sent", line("p9").resultLine)
    assertEquals("waiting for you", line("p8").decidedLine)
  }

  @Test fun anotherPersonsAnswerIsNamedByTheirRole() {
    val answered = line("p2").copy(answer = line("p2").answer!!.copy(you = false, by = "admin"))
    assertEquals("approved by an admin", answered.decidedLine)
    val refused = line("p2").copy(answer = line("p2").answer!!.copy(you = false, by = "billing_manager", decision = "refused"))
    assertEquals("refused by a billing manager", refused.decidedLine)
    assertEquals("someone else", line("p2").copy(answer = line("p2").answer!!.copy(you = false, by = null)).answerer)
  }

  @Test fun aRecordLineKeepsItsIdOnEveryRefresh() {
    assertEquals(record.map { it.id }, KiffMapping.record(calls(activityJson), setOf("refunds-k5j6m5qx")).map { it.id })
  }

  @Test fun whatItActedOnReadsAsItsArgumentAndTheLastFiveCharacters() {
    val entry = line("p1").copy(onValue = "pi_3USLXhQbstR")
    assertEquals("payment …QbstR", entry.onText)
    assertEquals("payment pi_1", line("p1").copy(onValue = "pi_1").onText)
    assertNull(line("p1").onText)
  }

  // MARK: Amounts

  @Test fun anAmountIsWrittenInItsUnit() {
    assertEquals(eur(15000).formatted, KiffMapping.amountText(ActivityAmount("150.00", "EUR")))
    assertEquals(Money.of(80, "USD", 0).formatted, KiffMapping.amountText(ActivityAmount("80", "USD")))
    // No unit: as KIFF wrote it.
    assertEquals("5", KiffMapping.amountText(ActivityAmount("5")))
    // A unit that isn't a currency: as written.
    assertEquals("12.5 kg", KiffMapping.amountText(ActivityAmount("12.5", "kg")))
    assertNull(KiffMapping.amountText(ActivityAmount("abc", "EUR")))
  }

  @Test fun onlyACurrencyIsMoney() {
    assertTrue(KiffMapping.isCurrency("EUR"))
    assertTrue(KiffMapping.isCurrency("usd"))
    assertFalse(KiffMapping.isCurrency("kg"))
    assertFalse(KiffMapping.isCurrency("ZZZ"))
    assertFalse(KiffMapping.isCurrency(null))
  }

  @Test fun aWritesAmountCountsAsMoneyOrAnotherUnitOrNothing() {
    fun call(amount: ActivityAmount?, readOnly: Boolean? = null) =
      ActivityCall("p", "x", outcome = "allowed", createdAt = Instant.EPOCH, amount = amount, readOnly = readOnly, toolOutcome = ToolOutcome("refused_by_tool"))
    assertEquals(KiffMapping.Counted.money, KiffMapping.counted(call(ActivityAmount("1", "EUR"))))
    assertEquals(KiffMapping.Counted.amount, KiffMapping.counted(call(ActivityAmount("1", "kg"))))
    assertEquals(KiffMapping.Counted.nothing, KiffMapping.counted(call(null)))
    assertEquals(KiffMapping.Counted.nothing, KiffMapping.counted(call(ActivityAmount("1", "EUR"), readOnly = true)))
  }

  @Test fun aVendorsRefusalNamesTheVendorAsConnected() {
    val (kind, detail) = KiffMapping.executed(ToolOutcome("refused_by_tool"), approved = false, vendor = "Stripe", counts = KiffMapping.Counted.amount)
    assertEquals(RecordEntry.Kind.notDone, kind)
    assertEquals("Stripe refused it. Nothing was done, but it still counts against today's total.", detail)
    assertNotNull(KiffMapping.executed(null, approved = true))
  }
}
