package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.AgentDraft
import bot.tafs.app.kiff.AllowOffer
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.HoldAllow
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Allow this from now on" on a held call (docs/allow-from-now-on.md): the app's own rule for the button, tafs cloud's offer made
 * on the tap, and one signature only over KIFF's draft as the phone read it, when it is what was offered. Fakes stand in for KIFF and
 * tafs cloud.
 */
class AllowFromNowOnTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-11-12T09:00:00Z")

  private fun held(breaches: String = """[{"outcome":"per_action_exceeded","limit":15000,"quantity":"amount","unit":"EUR","scale":2}]""", kind: String = "") =
    """{"id":"ce_9","subject":"refunds-1","action":"create_refund","title":"Refund a payment","entity_id":"ch_1","accounting_version":3,
       "breaches":$breaches,"parameters":{"amount":32000},"created_at":"2026-11-12T09:00:00Z","expires_at":"2099-01-01T00:00:00Z"$kind}"""

  private val home = """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}"""

  private fun reads(held: String = held(), capabilities: String = """"holds.answer","cards.manage"""") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":[$capabilities]}""")
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$held]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":true}}}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore(); it.refresh() }
  }

  private val allow = "POST /v1/tafs/taf_1/holds/ce_9/allow"
  private val confirm = "POST /v1/me/agent-drafts/dr_9/confirm"

  private val offer =
    """{"offer":{"changes":[{"mandate_id":"c1","kind":"per_action","before":15000,"after":32000,"unit":"EUR",
       "words_before":"Refunds up to €150 each","words_after":"Refunds up to €320 each","rounded":false}],
       "would":{"calls":4,"words":"With this Card, 4 more refunds would have gone out on their own (€1,180)"},
       "draft":"dr_9","confirm_key":"critical","send_held":true,"round":{"after":35000,"words":"€350"}}}"""

  /** KIFF's draft: card c1 revised with its per-call limit at [max], and anything else [extra] adds. */
  private fun draft(max: Long = 32000, extra: String = "", retires: String = "") =
    """{"id":"dr_9","agent_id":"refunds-1","status":"draft","version":"v7","cards":[
       {"revises":"c1","grants":[{"action":"create_refund","per_action_max":[{"quantity":"amount","max":$max,"unit":"EUR","scale":2}]}],
        "current":{"grants":[{"action":"create_refund","per_action_max":[{"quantity":"amount","max":15000,"unit":"EUR","scale":2}]}]},
        "lines":["Refunds up to €${max / 100} each"]}$extra]$retires}"""

  private fun parsedDraft(json: String) = KiffClient.json.decodeFromString<AgentDraft>(json)
  private fun parsedOffer(json: String = offer) = KiffClient.json.decodeFromString<bot.tafs.app.kiff.AllowAnswer>(json).offer!!

  /** Whether the phone would sign [draft] for held call ce_9 (create_refund, refunds-1), offered [o]. */
  private fun signs(draft: String, o: AllowOffer = parsedOffer(), agent: String = "refunds-1") =
    HoldAllow.matches(parsedDraft(draft), o, agent, "ce_9", "create_refund")

  /** A draft with one Card c1, its terms [now] and in force [was], each a Card's JSON body. */
  private fun card(now: String, was: String, from: String = "") =
    """{"id":"dr_9","agent_id":"refunds-1","status":"draft","version":"v7"$from,"cards":[{"revises":"c1",$now,"current":{$was}}]}"""

  private val cap = """{"quantity":"amount","max":15000,"unit":"EUR","scale":2}"""
  private val raised = """{"quantity":"amount","max":32000,"unit":"EUR","scale":2}"""
  private fun refund(vararg caps: String) = """{"action":"create_refund","per_action_max":[${caps.joinToString(",")}]}"""
  private val day = """{"quantity":"amount","limit":50000,"window":"day","unit":"EUR","scale":2}"""

  private fun totalOffer(after: Long = 60000, unit: String = "EUR") = parsedOffer(
    """{"offer":{"changes":[{"mandate_id":"c1","kind":"total","before":50000,"after":$after,"unit":"$unit","words_before":"","words_after":""}],
       "draft":"dr_9","confirm_key":"critical","send_held":true}}""",
  )

  // MARK: The app's own rule for the button

  @Test fun theButtonShowsOnAHoldACardChangeCanLiftForWhoeverManagesCards() = runTest {
    reads()
    val m = model()
    val call = m.state.value.held.single()
    assertTrue(m.state.value.offersAllow(call))
    assertTrue("no request is made to draw it", transport.requestsTo(allow).isEmpty())
  }

  @Test fun noButtonForACurrencyMismatch() = runTest {
    // A currency the Card has no limit for is its own card, never a held call with this answer: that's Coach's to fix.
    reads(held = held(kind = ""","kind":"card_unit""""))
    val m = model()
    assertTrue(m.state.value.held.none { m.state.value.offersAllow(it) })
  }

  @Test fun noButtonForABreachNoCardChangeLifts() = runTest {
    reads(held = held(breaches = """[{"outcome":"per_action_exceeded"},{"outcome":"something_new"}]"""))
    assertFalse(model().let { it.state.value.offersAllow(it.state.value.held.single()) })
  }

  @Test fun noButtonWithoutCardsManage() = runTest {
    reads(capabilities = """"holds.answer"""")
    assertFalse(model().let { it.state.value.offersAllow(it.state.value.held.single()) })
  }

  // MARK: The device signs what the person saw

  @Test fun aDraftThatCarriesExactlyTheOfferedChangeMatches() {
    assertTrue(signs(draft()))
  }

  @Test fun aDraftWithAnotherFigureAnotherCardChangedARetiredCardOrANewCardIsNotWhatWasOffered() {
    assertFalse("another figure", signs(draft(max = 50000)))
    val otherCard = """,{"revises":"c2","grants":[{"action":"list_charges"}],"current":{"grants":[{"action":"list_charges"},{"action":"create_refund"}]}}"""
    assertFalse("another Card changed", signs(draft(extra = otherCard)))
    assertFalse("a Card retired", signs(draft(retires = ""","retires":[{"id":"c3"}]""")))
    val newCard = """,{"grants":[{"action":"create_refund"}]}"""
    assertFalse("a Card that revises none in force", signs(draft(extra = newCard)))
    val unchanged = """,{"revises":"c2","unchanged":true,"current":{"grants":[{"action":"list_charges"}]},"grants":[{"action":"list_charges"}]}"""
    assertTrue("a Card left as it is doesn't count", signs(draft(extra = unchanged)))
  }

  @Test fun aChangeOfAKindTheContractDoesntNameIsNeverSigned() {
    val unknown = parsedOffer().let { it.copy(changes = it.changes.map { c -> c.copy(kind = "something_new") }) }
    assertFalse(signs(draft(), unknown))
  }

  @Test fun theChangedCardReadsAsTheCardInForceWithOnlyTheOfferedFigureMoved() {
    val g = "\"grants\":"
    assertTrue("the offered cap, nothing else", signs(card("$g[${refund(raised)}]", "$g[${refund(cap)}]")))
    assertFalse("a second cap moved too", signs(card("$g[${refund(raised, cap.replace("amount", "count"))}]", "$g[${refund(cap, cap.replace("amount", "count").replace("15000", "3"))}]")))
    assertFalse("a cap added", signs(card("$g[${refund(raised, cap.replace("amount", "fee"))}]", "$g[${refund(cap)}]")))
    assertFalse("a cap taken away", signs(card("$g[${refund()}]", "$g[${refund(cap)}]")))
    val list = """{"action":"list_charges","per_action_max":[$cap]}"""
    assertFalse(
      "the cap moved on another action",
      signs(card("$g[${refund(cap)},${list.replace("15000", "32000")}]", "$g[${refund(cap)},$list]")),
    )
    assertFalse("an action added", signs(card("$g[${refund(raised)},{\"action\":\"delete_customer\"}]", "$g[${refund(cap)}]")))
    assertFalse("an action taken away", signs(card("$g[${refund(raised)}]", "$g[${refund(cap)},{\"action\":\"list_charges\"}]")))
    assertFalse("a total added", signs(card("$g[${refund(raised)}],\"aggregates\":[$day]", "$g[${refund(cap)}]")))
    assertFalse("a total's window changed", signs(card("$g[${refund(raised)}],\"aggregates\":[${day.replace("day\"", "week\"")}]", "$g[${refund(cap)}],\"aggregates\":[$day]")))
    assertFalse("a total's figure changed", signs(card("$g[${refund(raised)}],\"aggregates\":[${day.replace("50000", "90000")}]", "$g[${refund(cap)}],\"aggregates\":[$day]")))
    assertFalse("what happens past a limit changed", signs(card("$g[${refund(raised)}],\"on_exceed\":\"refuse\"", "$g[${refund(cap)}],\"on_exceed\":\"owner\"")))
    assertTrue("KIFF's lines and a grant's title are words, not terms", signs(card("$g[${refund(raised).replace("{", "{\"title\":\"Refund\",")}],\"lines\":[\"x\"]", "$g[${refund(cap)}],\"lines\":[\"y\"]")))
  }

  @Test fun theUnitMustBeTheOffersExactly() {
    val g = "\"grants\":"
    assertFalse("another currency", signs(card("$g[${refund(raised.replace("EUR", "USD"))}]", "$g[${refund(cap.replace("EUR", "USD"))}]")))
    val noUnit = parsedOffer().let { it.copy(changes = it.changes.map { c -> c.copy(unit = null) }) }
    assertFalse("an offer with no unit for a cap in euros", signs(card("$g[${refund(raised)}]", "$g[${refund(cap)}]"), noUnit))
  }

  @Test fun aTotalOrACountChangesOnlyItsFigureInItsWindow() {
    val g = "\"grants\":"
    val week = day.replace("day\"", "week\"")
    assertTrue(signs(card("$g[${refund(cap)}],\"aggregates\":[${day.replace("50000", "60000")},$week]", "$g[${refund(cap)}],\"aggregates\":[$day,$week]"), totalOffer()))
    assertFalse("another figure", signs(card("$g[${refund(cap)}],\"aggregates\":[${day.replace("50000", "70000")}]", "$g[${refund(cap)}],\"aggregates\":[$day]"), totalOffer()))
    assertFalse("the window moved with it", signs(card("$g[${refund(cap)}],\"aggregates\":[${day.replace("50000", "60000").replace("day\"", "week\"")}]", "$g[${refund(cap)}],\"aggregates\":[$day]"), totalOffer()))
    assertFalse("the cap moved too", signs(card("$g[${refund(raised)}],\"aggregates\":[${day.replace("50000", "60000")}]", "$g[${refund(cap)}],\"aggregates\":[$day]"), totalOffer()))
    assertFalse("another unit", signs(card("$g[${refund(cap)}],\"aggregates\":[${day.replace("50000", "60000")}]", "$g[${refund(cap)}],\"aggregates\":[$day]"), totalOffer(unit = "USD")))
    // Two totals at the same figure, a day's and a week's: the offer's window says which one moves.
    val weekSame = day.replace("day\"", "week\"")
    val inDay = totalOffer().let { t -> t.copy(changes = t.changes.map { it.copy(window = "day") }) }
    assertTrue(signs(card("$g[${refund(cap)}],\"aggregates\":[${day.replace("50000", "60000")},$weekSame]", "$g[${refund(cap)}],\"aggregates\":[$day,$weekSame]"), inDay))
    assertFalse("the week's moved instead", signs(card("$g[${refund(cap)}],\"aggregates\":[$day,${weekSame.replace("50000", "60000")}]", "$g[${refund(cap)}],\"aggregates\":[$day,$weekSame]"), inDay))
  }

  @Test fun aResourceChangeAllowsNoReadableDifference() {
    val g = "\"grants\":"
    val resource = parsedOffer(
      """{"offer":{"changes":[{"mandate_id":"c1","kind":"resource","parameter":"customer","value":"cus_42","words_before":"","words_after":""}],
         "draft":"dr_9","confirm_key":"critical","send_held":true}}""",
    )
    assertTrue(signs(card("$g[${refund(cap)}]", "$g[${refund(cap)}]"), resource))
    assertFalse("a cap moved with it", signs(card("$g[${refund(raised)}]", "$g[${refund(cap)}]"), resource))
    assertFalse("an action added with it", signs(card("$g[${refund(cap)},{\"action\":\"list_charges\"}]", "$g[${refund(cap)}]"), resource))
  }

  @Test fun aCardMarkedUnchangedMustReadAsInForce() {
    val sneaky = """,{"revises":"c2","unchanged":true,"current":{"grants":[{"action":"list_charges"}]},"grants":[{"action":"list_charges"},{"action":"delete_customer"}]}"""
    assertFalse(signs(draft(extra = sneaky)))
  }

  @Test fun theDraftMustBeThisHeldCallsAgentsAndFromThisHoldWhenKiffSays() {
    val g = "\"grants\":"
    assertFalse("another agent's draft", signs(draft().replace("refunds-1", "payouts-1")))
    assertFalse("a draft with no agent", signs(draft().replace("\"agent_id\":\"refunds-1\",", "")))
    assertFalse("made from another held call", signs(card("$g[${refund(raised)}]", "$g[${refund(cap)}]", from = ""","from_hold":"ce_8"""")))
    assertTrue("made from this one", signs(card("$g[${refund(raised)}]", "$g[${refund(cap)}]", from = ""","from_hold":"ce_9"""")))
  }

  // MARK: The sheet, from the tap to the signature

  @Test fun theOfferIsMadeOnTheTapReadFromKiffAndSignedWithTheHeldCallWhenTheSwitchIsOn() = runTest {
    reads()
    transport.answer(allow, 200, offer)
    transport.answer("GET /v1/me/agent-drafts/dr_9", 200, draft())
    transport.answer(confirm, 200, """{"id":"dr_9","status":"confirmed","version":"v7","hold":{"id":"ce_9","status":"approved"}}""")
    val m = model()
    m.openAllowNow(m.state.value.held.single())
    assertEquals("{}", String(transport.requestsTo(allow).single().body!!))
    assertTrue(m.state.value.allow!!.matches)
    assertTrue(m.confirmAllow())
    val body = KiffClient.json.parseToJsonElement(String(transport.requestsTo(confirm).single().body!!)).jsonObject
    assertEquals("v7", body.getValue("version").jsonPrimitive.content)
    assertEquals("ce_9", body.getValue("hold").jsonObject.getValue("id").jsonPrimitive.content)
    assertEquals("3", body.getValue("hold").jsonObject.getValue("accounting_version").jsonPrimitive.content)
    assertEquals("one fingerprint, for the new Card", 1, auth.asked.size)
    assertNull("the sheet closes", m.state.value.allow)
  }

  @Test fun withTheSwitchOffTheCardChangesAndTheHeldCallStillWaits() = runTest {
    reads()
    transport.answer(allow, 200, offer)
    transport.answer("GET /v1/me/agent-drafts/dr_9", 200, draft())
    transport.answer(confirm, 200, """{"id":"dr_9","status":"confirmed","version":"v7"}""")
    val m = model()
    m.openAllowNow(m.state.value.held.single())
    m.setAllowSendHeld(false)
    assertTrue(m.confirmAllow())
    val body = KiffClient.json.parseToJsonElement(String(transport.requestsTo(confirm).single().body!!)).jsonObject
    assertNull("no held call goes with it", body["hold"])
  }

  @Test fun aDraftThatIsntWhatWasOfferedIsNeverSigned() = runTest {
    reads()
    transport.answer(allow, 200, offer)
    transport.answer("GET /v1/me/agent-drafts/dr_9", 200, draft(max = 99000))
    val m = model()
    m.openAllowNow(m.state.value.held.single())
    assertFalse(m.state.value.allow!!.matches)
    assertFalse(m.confirmAllow())
    assertTrue(transport.requestsTo(confirm).isEmpty())
    assertTrue("nothing was asked of the owner", auth.asked.isEmpty())
  }

  @Test fun noOfferSaysTafsCloudsWordsWithNothingToSign() = runTest {
    reads()
    transport.answer(allow, 200, """{"none":"no_change","words":"No Card limit holds this one back."}""")
    val m = model()
    m.openAllowNow(m.state.value.held.single())
    assertEquals("No Card limit holds this one back.", m.state.value.allow!!.answer!!.words)
    assertFalse(m.confirmAllow())
    assertTrue(transport.requestsTo("GET /v1/me/agent-drafts/dr_9").isEmpty())
  }

  @Test fun roundingUpAsksTafsCloudForTheRoundedDraftAndNeverRoundsHere() = runTest {
    reads()
    transport.answer(allow, 200, offer)
    transport.answer("GET /v1/me/agent-drafts/dr_9", 200, draft())
    val m = model()
    m.openAllowNow(m.state.value.held.single())
    m.loadAllow(round = true)
    assertEquals("""{"round":true}""", String(transport.requestsTo(allow).last().body!!))
    assertTrue(m.state.value.allow!!.rounded)
  }

  @Test fun roundingUpKeepsThePersonsSwitch() = runTest {
    reads()
    transport.answer(allow, 200, offer)
    transport.answer("GET /v1/me/agent-drafts/dr_9", 200, draft())
    val m = model()
    m.openAllowNow(m.state.value.held.single())
    m.setAllowSendHeld(false)
    m.loadAllow(round = true)
    assertFalse("the person turned it off; tafs cloud's send_held: true doesn't turn it back on", m.state.value.allow!!.sendHeld)
  }

  @Test fun aLateAnswerForAnotherCallLeavesTheOpenSheetAlone() = runTest {
    val second = held().replace("\"ce_9\"", "\"ce_10\"").replace("\"ch_1\"", "\"ch_2\"")
    reads(held = "${held()},$second")
    transport.answer(allow, 200, offer)
    transport.answer("POST /v1/tafs/taf_1/holds/ce_10/allow", 200, offer.replace("dr_9", "dr_10"))
    transport.answer("GET /v1/me/agent-drafts/dr_9", 200, draft())
    transport.answer("GET /v1/me/agent-drafts/dr_10", 200, draft().replace("dr_9", "dr_10"))
    val m = model()
    val (first, other) = m.state.value.held.sortedBy { it.kiffId }.let { it.first { c -> c.kiffId == "ce_9" } to it.first { c -> c.kiffId == "ce_10" } }
    val slow = transport.holdNext(allow)
    launch { m.openAllowNow(first) }
    runCurrent()
    m.closeAllow()
    m.openAllowNow(other)
    m.setAllowSendHeld(false)
    val before = m.state.value.allow
    slow.complete(Unit)
    runCurrent()
    assertEquals("ce_9's answer, come late, changes nothing on ce_10's sheet", before, m.state.value.allow)
    assertEquals("dr_10", m.state.value.allow!!.draft!!.id)
  }

  @Test fun aDraftThatCouldntBeReadIsAnErrorNotAMismatch() = runTest {
    reads()
    transport.answer(allow, 200, offer)
    transport.drop("GET /v1/me/agent-drafts/dr_9")
    val m = model()
    m.openAllowNow(m.state.value.held.single())
    val open = m.state.value.allow!!
    assertNull(open.draft)
    assertFalse(open.matches)
    assertTrue("the error says what went wrong", open.error != null)
    assertFalse(m.confirmAllow())
  }

  @Suppress("unused")
  private fun order() = transport.requests.map { it.method + " " + URI(it.url).path }
}
