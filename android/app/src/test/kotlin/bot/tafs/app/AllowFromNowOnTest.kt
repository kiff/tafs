package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.AgentDraft
import bot.tafs.app.kiff.AllowOffer
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.HoldAllow
import java.net.URI
import java.time.Instant
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
  private fun parsedOffer() = KiffClient.json.decodeFromString<bot.tafs.app.kiff.AllowAnswer>(offer).offer!!

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
    assertTrue(HoldAllow.matches(parsedDraft(draft()), parsedOffer()))
  }

  @Test fun aDraftWithAnotherFigureAnotherCardChangedARetiredCardOrANewCardIsNotWhatWasOffered() {
    assertFalse("another figure", HoldAllow.matches(parsedDraft(draft(max = 50000)), parsedOffer()))
    val otherCard = """,{"revises":"c2","grants":[{"action":"list_charges"}],"current":{"grants":[{"action":"list_charges"},{"action":"create_refund"}]}}"""
    assertFalse("another Card changed", HoldAllow.matches(parsedDraft(draft(extra = otherCard)), parsedOffer()))
    assertFalse("a Card retired", HoldAllow.matches(parsedDraft(draft(retires = ""","retires":[{"id":"c3"}]""")), parsedOffer()))
    val newCard = """,{"grants":[{"action":"create_refund"}]}"""
    assertFalse("a Card that revises none in force", HoldAllow.matches(parsedDraft(draft(extra = newCard)), parsedOffer()))
    val unchanged = """,{"revises":"c2","unchanged":true,"current":{"grants":[{"action":"list_charges"}]},"grants":[{"action":"list_charges"}]}"""
    assertTrue("a Card left as it is doesn't count", HoldAllow.matches(parsedDraft(draft(extra = unchanged)), parsedOffer()))
  }

  @Test fun aChangeOfAKindTheContractDoesntNameIsNeverSigned() {
    val unknown = parsedOffer().let { it.copy(changes = it.changes.map { c -> c.copy(kind = "something_new") }) }
    assertFalse(HoldAllow.matches(parsedDraft(draft()), unknown))
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

  @Suppress("unused")
  private fun order() = transport.requests.map { it.method + " " + URI(it.url).path }
}
