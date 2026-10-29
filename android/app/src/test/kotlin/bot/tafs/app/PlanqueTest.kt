package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.Jcs
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.PlanqueClient
import bot.tafs.app.kiff.PlanqueError
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.VendorSignIn
import bot.tafs.app.kiff.VendorSignInCancelled
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Canonical JSON, the signed requests planque takes, and approving what Coach proposes. A fake planque, KIFF and tafs cloud stand in. */
class PlanqueTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  // MARK: Canonical JSON

  @Test fun theCanonicalFormIsRfc8785sOwnExample() {
    val bs = '\\'
    // The string's value as RFC 8785 writes it, JSON escapes and all.
    val value = "${bs}u20ac$ ${bs}u000F${bs}u000aA'${bs}u0042${bs}u0022${bs}u005c${bs}${bs}${bs}\"${bs}/".replace("$ ", "$")
    val input = "{\"numbers\":[333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001],\"string\":\"" + value + "\",\"literals\":[null,true,false]}"
    // The same, canonical: the characters themselves where JSON needn't escape them.
    val euro = String(Character.toChars(0x20ac))
    val expected = "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],\"string\":\"${euro}$${bs}u000f${bs}nA'B${bs}\"${bs}${bs}${bs}${bs}${bs}\"/\"}"
    assertEquals(expected, Jcs.canonical(input))
  }
  @Test fun keysSortByTheirUtf16UnitsAndSpacesGoAway() {
    assertEquals("""{"a":1,"b":[{"x":null,"y":2}],"é":3,"😀":4}""", Jcs.canonical("""{ "😀": 4, "é": 3, "b": [ {"y":2, "x":null} ], "a": 1 }"""))
  }

  @Test fun anIntegerIsItsDigitsAndAFractionPrintsAsADouble() {
    assertEquals("0", Jcs.number("-0"))
    assertEquals("12", Jcs.number("0012"))
    assertEquals("-5", Jcs.number("-5"))
    assertEquals("100", Jcs.number("100.0"))
    assertEquals("0.000001", Jcs.number("1e-6"))
    assertEquals("1e-7", Jcs.number("0.0000001"))
    assertEquals("1e+21", Jcs.number("1000000000000000000000.0"))
    assertEquals("123456789012345680000", Jcs.number("1.2345678901234568e20"))
    assertEquals("9007199254740991", Jcs.number("9007199254740991"))
  }

  @Test fun anIntegerPastTheSafeRangeIsRefusedAsPlanqueRefusesIt() {
    try { Jcs.number("9007199254740992"); fail("refused") } catch (_: Jcs.Failure) { }
    try { Jcs.canonical("""{"limit":99999999999999999999}"""); fail("refused") } catch (_: Jcs.Failure) { }
    try { Jcs.canonical("not json"); fail("refused") } catch (_: Jcs.Failure) { }
  }

  // MARK: The signed request

  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}"""
  private val request = """{"server":"mcp.stripe.com","agent":"refunds-1","tools":[{"tool":"create_refund","name":"create_refund_repg","title":"Refund a payment","read_only":false},{"tool":"list_charges","read_only":true}]}"""
  private fun digestOf(json: String) = MessageDigest.getInstance("SHA-256").digest(Jcs.canonical(json).toByteArray()).joinToString("") { "%02x".format(it) }
  private val digest = digestOf(request)

  private fun grant(id: String = "g1", status: String = "pending", digest: String = this.digest, extra: String = "") =
    """{"id":"$id","status":"$status","text":"Connect Stripe: refund a payment, list charges.","terms":["create_refund: pinned to refunds"],"digest":"$digest","request":$request,"events":[{"at":"2026-10-28T08:00:00.123456789Z","what":"Setup asked"}]$extra}"""

  private fun planque(tenant: String? = "t_01", signed: Boolean = true, device: String? = "dev_1"): PlanqueClient {
    val client = PlanqueClient(transport)
    client.tenant = tenant
    if (signed) client.signer = { signer.signConfirmed(it) }
    client.deviceId = device
    return client
  }

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDER))
    return Signature.getInstance("SHA256withECDSA").run {
      initVerify(key)
      update(message)
      verify(Base64.getDecoder().decode(signatureBase64))
    }
  }

  @Test fun theMessageIsSevenLinesWithNoTrailingNewline() {
    val body = """{"digest":"x"}""".toByteArray()
    val m = String(PlanqueClient.message("https://planque.kiff.dev/", "t_01", "POST", "/v1/accounts/t_01/grants/g1/approve", body, "2026-10-28T09:00:00.000Z"))
    val lines = m.split("\n")
    assertEquals(7, lines.size)
    assertEquals(listOf("planque.request.v1", "https://planque.kiff.dev", "t_01", "POST", "/v1/accounts/t_01/grants/g1/approve"), lines.take(5))
    assertEquals(MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }, lines[5])
    assertEquals("2026-10-28T09:00:00.000Z", lines[6])
  }

  @Test fun everyRequestNamesTheDeviceAndIsSignedByThePlainKey() = runTest {
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant()}]}""")
    planque().grants()
    val request = transport.requestsTo("GET /v1/accounts/t_01/grants").single()
    assertEquals("https://planque.kiff.dev/v1/accounts/t_01/grants", request.url)
    val parts = request.headers.getValue("Planque-Device-Signature").split(",")
    val at = parts[0].removePrefix("t=")
    assertEquals("dev_1", parts[2].removePrefix("d="))
    val message = PlanqueClient.message("https://planque.kiff.dev", "t_01", "GET", "/v1/accounts/t_01/grants", ByteArray(0), at)
    assertTrue(verifies(signer.publicKeyDER, message, parts[1].removePrefix("v1=")))
    assertFalse("not the approval key", verifies(signer.approvalPublicKeyDER, message, parts[1].removePrefix("v1=")))
  }

  @Test fun twoRequestsNeverCarryTheSameTime() = runTest {
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    val client = planque()
    client.grants(); client.grants()
    val times = transport.requestsTo("GET /v1/accounts/t_01/grants").map { it.headers.getValue("Planque-Device-Signature").split(",")[0] }
    assertEquals(2, times.toSet().size)
  }

  @Test fun noSignerOrNoTenantSendsNothing() = runTest {
    for (client in listOf(planque(signed = false), planque(tenant = null))) {
      try { client.grants(); fail("refused") } catch (e: PlanqueError) { assertEquals(PlanqueError.NotSigning, e) }
    }
    assertTrue(transport.requests.isEmpty())
  }

  @Test fun noDeviceIdSendsNothingUntilKiffNamesTheDevice() = runTest {
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    val client = planque(device = null)
    try { client.grants(); fail("refused") } catch (e: PlanqueError) { assertEquals(PlanqueError.NoDevice, e) }
    assertTrue(transport.requests.isEmpty())
    client.deviceLoader = { "dev_9" }
    client.grants()
    assertEquals("dev_9", transport.requests.single().headers.getValue("Planque-Device-Signature").split(",")[2].removePrefix("d="))
  }

  @Test fun aGrantsDigestIsCheckedAgainstItsRequestAsPlanqueSentIt() = runTest {
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("g1")},${grant("g2", digest = "0".repeat(64))}]}""")
    val (ok, bad) = planque().grants()
    assertTrue(ok.digestMatches)
    assertFalse(bad.digestMatches)
    assertEquals("mcp.stripe.com", ok.vendor)
    assertEquals(listOf("Refund a payment", "list charges"), ok.request.tools.map { it.words })
    assertTrue(ok.request.tools.all { it.tafs })
  }

  @Test fun aRequestTheAppCannotCanonicalizeIsNeverApproved() = runTest {
    val risky = request.replace(""""read_only":true""", """"read_only":true,"limit":99999999999999999999""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[{"id":"g1","status":"pending","text":"t","digest":"${digest}","request":$risky,"events":[]}]}""")
    assertFalse(planque().grants().single().digestMatches)
  }

  @Test fun approvingSignsTheDigestAndRefusesOneThatDoesNotMatch() = runTest {
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("g1")},${grant("g2", digest = "0".repeat(64))}]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth?x=1","expires_at":"2026-10-28T09:15:00Z"}""")
    val client = planque()
    val (ok, bad) = client.grants()
    try { client.approve(bad); fail("refused") } catch (e: PlanqueError) { assertEquals(PlanqueError.DigestMismatch, e) }
    assertTrue(transport.requestsTo("POST /v1/accounts/t_01/grants/g2/approve").isEmpty())
    val approved = client.approve(ok)
    assertEquals("https://mcp.stripe.com/oauth?x=1", approved.signInUrl)
    assertEquals(digest, KiffClient.json.parseToJsonElement(String(transport.requestsTo("POST /v1/accounts/t_01/grants/g1/approve").single().body!!)).jsonObject.getValue("digest").jsonPrimitive.content)
  }

  @Test fun confirmingSendsTheCodeAndDisconnectingDeletes() = runTest {
    transport.answer("POST /v1/accounts/t_01/grants/g1/confirm", 200, grant("g1", "connected"))
    transport.answer("DELETE /v1/accounts/t_01/grants/g1", 200, grant("g1", "disconnected"))
    val client = planque()
    assertTrue(client.confirm("g1", "c0de").connected)
    assertEquals("c0de", KiffClient.json.parseToJsonElement(String(transport.requestsTo("POST /v1/accounts/t_01/grants/g1/confirm").single().body!!)).jsonObject.getValue("confirm").jsonPrimitive.content)
    assertEquals("disconnected", client.disconnect("g1").status)
  }

  @Test fun planquesRefusalIsItsOwnWords() = runTest {
    transport.answer("POST /v1/accounts/t_01/grants/g1/confirm", 409, """{"error":"the sign-in expired"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 500, "oops")
    try { planque().confirm("g1", "x"); fail("refused") } catch (e: PlanqueError.Server) { assertEquals(409, e.status); assertEquals("the sign-in expired", e.message) }
    try { planque().grants(); fail("refused") } catch (e: PlanqueError.Server) { assertEquals("The connection answered 500", e.message) }
  }

  @Test fun aSignInComesBackAsALinkWithTheTenantTheGrantAndTheCode() {
    val link = PlanqueClient.signedIn("tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0%2Fde")!!
    assertEquals(listOf("t_01", "g1", "c0/de"), listOf(link.tenant, link.grant, link.code))
    assertNull(PlanqueClient.signedIn("tafs://planque/signed-in?tenant=t_01&grant=g1"))
    assertNull(PlanqueClient.signedIn("tafs://planque/signed-in?tenant=t_01&grant=g1&confirm="))
    assertNull(PlanqueClient.signedIn("https://planque.kiff.dev/signed-in?tenant=t&grant=g&confirm=c"))
    assertNull(PlanqueClient.signedIn("tafs://elsewhere/signed-in?tenant=t&grant=g&confirm=c"))
    assertNull(PlanqueClient.signedIn("tafs://planque/other?tenant=t&grant=g&confirm=c"))
    assertNull(PlanqueClient.signedIn("not a url"))
  }

  // MARK: Approving what Coach proposes

  private class FakeSignIn(var back: String? = null, var opened: String? = null) : VendorSignIn {
    override suspend fun signIn(url: String): String { opened = url; return back ?: throw VendorSignInCancelled() }
  }

  private fun reads() {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
  }

  private suspend fun model(signIn: VendorSignIn = FakeSignIn(), keyed: Boolean = true): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    if (keyed) secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false, vendorSignIn = signIn).also { it.restore() }
  }

  private val grants = "GET /v1/accounts/t_01/grants"
  private val approve = "POST /v1/accounts/t_01/grants/g1/approve"
  private val confirm = "POST /v1/accounts/t_01/grants/g1/confirm"

  @Test fun aPhoneKnownToPlanqueReadsTheGrantsAndOneItDoesNotKnowIsNotReady() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    val m = model()
    m.refreshConnections()
    assertTrue(m.state.value.planqueReady)
    assertEquals(listOf("g1"), m.state.value.grants.map { it.id })
    assertTrue("a read asks for no confirmation", auth.asked.isEmpty())
    transport.answer(grants, 403, """{"error":"this device is not enrolled"}""")
    m.refreshConnections()
    assertFalse(m.state.value.planqueReady)
  }

  @Test fun approvingAConnectionConfirmsApprovesSignsInAtTheVendorAndConfirmsTheCode() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(approve, 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth?x=1"}""")
    transport.answer(confirm, 200, grant("g1", "connected"))
    val vendor = FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de")
    val m = model(vendor)
    m.refreshConnections()
    val result = m.connect("a1", "g1", digest)
    assertTrue(result.toString(), result is AppModel.Connected.Done)
    assertEquals(listOf("Connect mcp.stripe.com to refunds-1"), auth.asked)
    assertEquals("https://mcp.stripe.com/oauth?x=1", vendor.opened)
    assertEquals("c0de", KiffClient.json.parseToJsonElement(String(transport.requestsTo(confirm).single().body!!)).jsonObject.getValue("confirm").jsonPrimitive.content)
    val order = transport.requests.map { it.method + " " + java.net.URI(it.url).path }
    assertTrue(order.indexOf("POST /v1/accounts/t_01/grants/g1/approve") < order.indexOf("POST /v1/accounts/t_01/grants/g1/confirm"))
    assertNull("the signer a confirmed step set is gone", m.state.value.connecting)
  }

  @Test fun aSignInPlanqueAlreadyHoldsConnectsOnApprovalWithNoVendorPage() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(extra = ""","signed_in":true""")}]}""")
    transport.answer(approve, 200, """{"status":"connected"}""")
    val vendor = FakeSignIn()
    val m = model(vendor)
    m.refreshConnections()
    assertTrue(m.connect("a1", "g1", digest) is AppModel.Connected.Done)
    assertNull(vendor.opened)
    assertTrue(transport.requestsTo(confirm).isEmpty())
  }

  @Test fun termsThatChangedSinceTheyWereShownAreNotSignedAndAreShownAgain() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    val m = model()
    m.refreshConnections()
    assertEquals(AppModel.Connected.Stopped, m.connect("a1", "g1", "0".repeat(64)))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(approve).isEmpty())
    assertEquals("What you're asked to approve changed since it was shown. Read it again, then approve.", m.state.value.coachNotes["a1"])
    assertEquals(AppModel.Connected.Stopped, m.connect("a1", "g1", null))
  }

  @Test fun anOwnerWhoDoesNotConfirmApprovesNothing() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    val m = model()
    m.refreshConnections()
    auth.confirms = false
    assertEquals(AppModel.Connected.Stopped, m.connect("a1", "g1", digest))
    assertTrue(transport.requestsTo(approve).isEmpty())
    assertEquals("Not done: it wasn't confirmed.", m.state.value.coachNotes["a1"])
    assertNull(m.state.value.connecting)
  }

  @Test fun aRequestWhoseDigestIsNotItsHashIsNeverApproved() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(digest = "0".repeat(64))}]}""")
    val m = model()
    m.refreshConnections()
    val result = m.connect("a1", "g1", "0".repeat(64))
    assertEquals(AppModel.Connected.Failed("This request doesn't match what it asks for. Nothing was approved."), result)
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(approve).isEmpty())
  }

  @Test fun aVendorPageClosedBeforeTheSignInEndsLeavesNothingConnected() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(approve, 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth"}""")
    val m = model(FakeSignIn(back = null))
    m.refreshConnections()
    assertEquals(AppModel.Connected.Failed("The sign-in was closed. Nothing was connected."), m.connect("a1", "g1", digest))
    assertTrue(transport.requestsTo(confirm).isEmpty())
    assertNull(m.state.value.connecting)
  }

  @Test fun aSignInThatComesBackForAnotherGrantOrAccountIsNeverConfirmed() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(approve, 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth"}""")
    for (back in listOf("tafs://planque/signed-in?tenant=t_01&grant=g_other&confirm=c", "tafs://planque/signed-in?tenant=t_other&grant=g1&confirm=c", "tafs://planque/signed-in?tenant=t_01&grant=g1")) {
      val m = model(FakeSignIn(back = back))
      m.refreshConnections()
      val result = m.connect("a1", "g1", digest)
      assertTrue(back, result is AppModel.Connected.Failed && result.why.contains("without its confirmation code"))
    }
    assertTrue(transport.requestsTo(confirm).isEmpty())
  }

  @Test fun planqueRefusingTheApprovalIsToldInItsWords() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(approve, 409, """{"error":"this request was already approved"}""")
    val m = model()
    m.refreshConnections()
    assertEquals(AppModel.Connected.Failed("Not connected: this request was already approved"), m.connect("a1", "g1", digest))
  }

  @Test fun aPhonePlanqueDoesNotKnowCannotApproveAndSaysWhereToStart() = runTest {
    reads()
    val m = model()
    val result = m.connect("a1", "g1", digest)
    assertTrue(result is AppModel.Connected.Failed && result.why.contains("Start from tafs on an iPhone"))
    assertTrue(transport.requests.none { it.url.contains("planque") })
  }

  private val draft = """{"id":"dr_1","agent_id":"refunds-1","status":"draft","version":"v3","cards":[{"grants":[{"action":"create_refund_repg","title":"Refund a payment"}]}]}"""

  @Test fun theCardsOverAConnectedToolAreConfirmedInKiffByTheApprovalKeyOnTheDraftShown() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    transport.answer("GET /v1/me/agent-drafts/dr_1", 200, draft)
    transport.answer("POST /v1/me/agent-drafts/dr_1/confirm", 200, draft.replace("draft", "confirmed"))
    val m = model()
    m.refreshConnections()
    assertTrue(m.connect("a1", "g1", digest, "dr_1", "v3") is AppModel.Connected.Done)
    assertEquals(listOf("Give refunds-1 its Card"), auth.asked)
    val sent = transport.requestsTo("POST /v1/me/agent-drafts/dr_1/confirm").single()
    assertEquals("v3", KiffClient.json.parseToJsonElement(String(sent.body!!)).jsonObject.getValue("version").jsonPrimitive.content)
    val parts = sent.headers.getValue("KIFF-Device-Signature").split(",")
    val message = KiffClient.requestMessage("t_01", "POST", "/v1/me/agent-drafts/dr_1/confirm", sent.body!!, parts[0].removePrefix("t="))
    assertTrue("it issues Cards: the approval key signs it", verifies(signer.approvalPublicKeyDER, message, parts[1].removePrefix("v1=")))
    assertTrue("planque is not asked to approve a connection that is connected", transport.requestsTo(approve).isEmpty())
  }

  @Test fun aDraftThatChangedSinceItWasShownIsNotConfirmed() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    transport.answer("GET /v1/me/agent-drafts/dr_1", 200, draft.replace("v3", "v4"))
    val m = model()
    m.refreshConnections()
    assertEquals(AppModel.Connected.Stopped, m.connect("a1", "g1", digest, "dr_1", "v3"))
    assertEquals("v4", m.state.value.setupDrafts["dr_1"]!!.version)
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo("POST /v1/me/agent-drafts/dr_1/confirm").isEmpty())
  }

  @Test fun aDraftKiffAlreadyConfirmedNeedsNothingMore() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    transport.answer("GET /v1/me/agent-drafts/dr_1", 200, draft.replace("\"draft\"", "\"confirmed\""))
    val m = model()
    m.refreshConnections()
    assertTrue(m.connect("a1", "g1", digest, "dr_1", "v3") is AppModel.Connected.Done)
    assertTrue(auth.asked.isEmpty())
  }

  @Test fun kiffRefusingTheCardsIsToldAndNothingIsClaimedDone() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    transport.answer("GET /v1/me/agent-drafts/dr_1", 200, draft)
    transport.answer("POST /v1/me/agent-drafts/dr_1/confirm", 409, """{"error":"a Card moved on","reason":"card_revised"}""")
    val m = model()
    m.refreshConnections()
    assertEquals(AppModel.Connected.Failed("KIFF didn't issue the taf's Cards: a Card moved on"), m.connect("a1", "g1", digest, "dr_1", "v3"))
  }

  @Test fun aSecondApprovalWaitsForTheOneGoingThrough() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    val m = model()
    m.refreshConnections()
    val field = AppModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val flow = field.get(m) as kotlinx.coroutines.flow.MutableStateFlow<bot.tafs.app.app.AppState>
    flow.value = flow.value.copy(connecting = "g_other")
    assertEquals(AppModel.Connected.Stopped, m.connect("a1", "g1", digest))
    assertEquals("Another approval is going through. Approve this one once it's done.", m.state.value.coachNotes["a1"])
    assertTrue(auth.asked.isEmpty())
  }

  // MARK: Telling Coach

  private val approvalCard = """{"type":"approval","id":"a1","grant":"g1","service":"mcp.stripe.com","title":"Connect Stripe"}"""

  private suspend fun coachWithCard(m: AppModel) {
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach"}}""")
    transport.answer(
      "GET /v1/tafs/setup_1/conversation", 200,
      """{"messages":[],"components":[{"at":"2026-10-28T08:00:00Z","session":"s1","run":"r1","component":$approvalCard}]}""",
    )
    transport.answer("POST /v1/tafs/setup_1/messages", 200, """{"session":"s1","new_session":false}""")
    m.loadCoach()
    m.loadChat(COACH_CHAT)
  }

  private fun told() = KiffClient.json.parseToJsonElement(String(transport.requestsTo("POST /v1/tafs/setup_1/messages").last().body!!)).jsonObject.getValue("text").jsonPrimitive.content

  @Test fun readingCoachsThreadReadsWhatItsApprovalsNeed() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    val m = model()
    coachWithCard(m)
    assertEquals(listOf("g1"), m.state.value.grants.map { it.id })
    assertTrue(m.state.value.planqueReady)
  }

  @Test fun anApprovalThatWentThroughIsToldToCoachAsApproved() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(approve, 200, """{"status":"connected"}""")
    val m = model()
    coachWithCard(m)
    m.approveCoach(approvalComponent(), digest, null)
    assertEquals("[approval a1] approved", told())
  }

  @Test fun anApprovalThatFailedIsToldToCoachWithWhyAndOneNotConfirmedIsNotTold() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(approve, 409, """{"error":"already approved"}""")
    val m = model()
    coachWithCard(m)
    m.approveCoach(approvalComponent(), digest, null)
    assertEquals("[approval a1] failed: Not connected: already approved", told())
    assertEquals("Not connected: already approved", m.state.value.coachNotes["a1"])
    auth.confirms = false
    val before = transport.requestsTo("POST /v1/tafs/setup_1/messages").size
    m.approveCoach(approvalComponent(), digest, null)
    assertEquals("nothing told: the owner didn't confirm", before, transport.requestsTo("POST /v1/tafs/setup_1/messages").size)
  }

  private fun approvalComponent() = KiffClient.json.decodeFromString<SetupComponent>(approvalCard)

  @Test fun disconnectingConfirmsThenDeletesThenReadsAgain() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    transport.answer("DELETE /v1/accounts/t_01/grants/g1", 200, grant("g1", "disconnected"))
    val m = model()
    m.refreshConnections()
    assertTrue(m.disconnect("g1"))
    assertEquals(listOf("Disconnect mcp.stripe.com"), auth.asked)
    assertEquals(1, transport.requestsTo("DELETE /v1/accounts/t_01/grants/g1").size)
    assertTrue("read again", transport.requestsTo(grants).size >= 2)
    auth.confirms = false
    assertFalse(m.disconnect("g1"))
    assertEquals(1, transport.requestsTo("DELETE /v1/accounts/t_01/grants/g1").size)
  }

  @Test fun anApprovalCardReadsAsApprovedDeclinedOrWhyItFailed() {
    assertEquals("You approved", bot.tafs.app.model.CoachWords.approvalResult(bot.tafs.app.model.CoachAnswer("approval", "a1", "approved")))
    assertEquals("You declined", bot.tafs.app.model.CoachWords.approvalResult(bot.tafs.app.model.CoachAnswer("approval", "a1", "declined")))
    assertEquals("It didn't go through: nope", bot.tafs.app.model.CoachWords.approvalResult(bot.tafs.app.model.CoachAnswer("approval", "a1", "failed: nope")))
    assertEquals("It didn't go through: nothing was approved", bot.tafs.app.model.CoachWords.approvalResult(bot.tafs.app.model.CoachAnswer("approval", "a1", "failed")))
    assertNotNull(PlanqueError.NotSigning.message)
  }
}
