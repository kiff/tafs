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
import org.junit.Assert.assertNotEquals
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

  @Test fun aDeviceIdReadWhileTheAccountChangedIsNotKeptForTheNext() = runTest {
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    transport.answer("GET /v1/accounts/t_02/grants", 200, """{"grants":[]}""")
    val client = planque(device = null)
    // The first account's device is read while the phone signs in to another.
    client.deviceLoader = { client.tenant = "t_02"; "dev_1" }
    client.grants()
    // The next account's read asks KIFF again: never the last account's device.
    client.deviceLoader = { "dev_2" }
    client.grants()
    assertEquals(listOf("dev_1", "dev_2"), transport.requests.map { it.headers.getValue("Planque-Device-Signature").split(",")[2].removePrefix("d=") })
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

  @Test fun approvingWithKiffsWordsSignsBothDigestsInTheSameBody() = runTest {
    // KIFF's profile contract: the profile and the words the owner read ride in planque's approve body, under the same signature.
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("g1")}]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth?x=1","expires_at":"2026-10-28T09:15:00Z"}""")
    val client = planque()
    val words = bot.tafs.app.kiff.ProfileWords("ca81de5f".repeat(8), "50723c8a".repeat(8), emptyList())
    client.approve(client.grants().single(), words)
    val sent = transport.requestsTo("POST /v1/accounts/t_01/grants/g1/approve").single()
    val body = KiffClient.json.parseToJsonElement(String(sent.body!!)).jsonObject
    assertEquals(digest, body.getValue("digest").jsonPrimitive.content)
    val profile = body.getValue("kiff_profile").jsonObject
    assertEquals("ca81de5f".repeat(8), profile.getValue("profile_sha256").jsonPrimitive.content)
    assertEquals("50723c8a".repeat(8), profile.getValue("words_sha256").jsonPrimitive.content)
    assertTrue("signed like any approval", sent.headers.keys.any { it.contains("Signature", ignoreCase = true) })
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

  @Test fun aSignInFromConnectAppsIsSaidAtOnceWithItsNextStep() = runTest {
    reads()
    transport.answer("GET /v1/home", 200, twoTafs)
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(approve, 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth?x=1"}""")
    // Signed in, its tools listed for Coach to choose from.
    transport.answer(confirm, 200, grant("g1", "listed"))
    val m = model(FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de"))
    m.refresh()
    m.refreshConnections()
    m.approveFromList("g1", digest)
    val now = m.state.value.connectedNow!!
    assertTrue(now.listed)
    assertEquals(
      "mcp.stripe.com is signed in for Refunds. Next, Coach chooses its actions and you approve them.",
      bot.tafs.app.model.ConnectWords.signedInFor(now.vendor, m.state.value.taf(now.request.agent)?.name),
    )
    m.dismissConnectedNow()
    assertNull(m.state.value.connectedNow)
  }

  // MARK: A connection with a profile (KIFF's profile contract): KIFF's words, read by the phone and signed with the approval

  private fun resource(name: String) = javaClass.classLoader!!.getResource("profile/$name.json")!!.readText()
  private val stripeProfile = resource("stripe-refunds")
  private val profiledRequest = request.dropLast(1) + ""","profile":""" + stripeProfile + "}"
  private fun profiledGrant() =
    """{"id":"g1","status":"pending","text":"Connect Stripe: refund a payment.","digest":"${digestOf(profiledRequest)}","request":$profiledRequest}"""
  private val words = "POST /v1/profiles/words"

  @Test fun aProfilesWordsAreReadFromKiffCheckedShownAndSignedIntoTheApproval() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${profiledGrant()}]}""")
    transport.answer(words, 200, resource("stripe-refunds.expected"))
    transport.answer(approve, 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth?x=1"}""")
    transport.answer(confirm, 200, grant("g1", "connected"))
    val m = model(FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de"))
    m.refreshConnections()
    assertTrue("the grant's digest covers its profile", m.state.value.grants.single().digestMatches)
    m.loadProfileWords("g1")
    // Asked of KIFF with the person's own session, the profile as the body: tafs cloud and planque never hand the phone words.
    val asked = transport.requestsTo(words).single()
    assertEquals("https://api.kiff.dev/v1/profiles/words", asked.url)
    assertEquals("Bearer at_1", asked.headers["Authorization"])
    assertEquals(Jcs.canonical(stripeProfile), Jcs.canonical(String(asked.body!!)))
    val shown = m.state.value.profileWords.getValue("g1")
    assertEquals("Refund a payment: moves money through Stripe. It pays out or charges.", shown.lines[2].text)
    val result = m.connect("a1", "g1", digestOf(profiledRequest))
    assertTrue(result.toString(), result is AppModel.Connected.Done)
    val body = KiffClient.json.parseToJsonElement(String(transport.requestsTo(approve).single().body!!)).jsonObject
    val signed = body.getValue("kiff_profile").jsonObject
    val expected = KiffClient.json.parseToJsonElement(resource("stripe-refunds.expected")).jsonObject
    assertEquals(expected["profile_sha256"], signed["profile_sha256"])
    assertEquals(expected["words_sha256"], signed["words_sha256"])
  }

  @Test fun aConnectionWithAProfileIsNeverApprovedBeforeItsWordsAreRead() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${profiledGrant()}]}""")
    val m = model()
    m.refreshConnections()
    val result = m.connect("a1", "g1", digestOf(profiledRequest))
    assertTrue(result.toString(), result is AppModel.Connected.Failed)
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(approve).isEmpty())
  }

  @Test fun wordsForAnotherProfileAreShownAsWrongAndNothingCanBeApproved() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${profiledGrant()}]}""")
    transport.answer(words, 200, resource("slack-messages.expected"))
    val m = model()
    m.refreshConnections()
    m.loadProfileWords("g1")
    assertTrue(m.state.value.profileWords.isEmpty())
    assertTrue(m.state.value.profileWordsErrors.getValue("g1").contains("Nothing was approved"))
    assertTrue(m.connect("a1", "g1", digestOf(profiledRequest)) is AppModel.Connected.Failed)
    assertTrue(transport.requestsTo(approve).isEmpty())
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

  // MARK: An API-key connection (Connect apps): the key is given on the sheet, with the same checks and confirmation

  private val key = "POST /v1/accounts/t_01/grants/g1/key"

  @Test fun aKeyIsConfirmedThenGivenToPlanqueOnceAndKeptNowhereHere() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(key, 200, grant("g1", "connected"))
    val m = model()
    m.refreshConnections()
    assertTrue(m.giveKey("g1", digest, "  sk_test_4eC39H  "))
    assertEquals("confirmed with the approval key", listOf("Give mcp.stripe.com its key for refunds-1"), auth.asked)
    val sent = transport.requestsTo(key).single()
    assertEquals("the key, trimmed, in its header alone", "sk_test_4eC39H", sent.headers[PlanqueClient.VENDOR_KEY])
    val body = String(sent.body!!)
    assertFalse("never in the body", body.contains("sk_test_4eC39H"))
    val fields = KiffClient.json.parseToJsonElement(body).jsonObject
    assertEquals(digest, fields.getValue("digest").jsonPrimitive.content)
    val sum = MessageDigest.getInstance("SHA-256").digest("sk_test_4eC39H".toByteArray()).joinToString("") { "%02x".format(it) }
    assertEquals(sum, fields.getValue("key_sha256").jsonPrimitive.content)
    // Signed by this device, over the body, as every planque request is.
    val parts = sent.headers.getValue("Planque-Device-Signature").split(",")
    val message = PlanqueClient.message("https://planque.kiff.dev", "t_01", "POST", "/v1/accounts/t_01/grants/g1/key", sent.body, parts[0].removePrefix("t="))
    assertTrue(verifies(signer.publicKeyDER, message, parts[1].removePrefix("v1=")))
    assertFalse("kept nowhere in the app's state", m.state.value.toString().contains("sk_test_4eC39H"))
    assertNull(m.state.value.connecting)
  }

  @Test fun anOwnerWhoDoesNotConfirmGivesNoKey() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    val m = model()
    m.refreshConnections()
    auth.confirms = false
    assertFalse(m.giveKey("g1", digest, "sk_test_4eC39H"))
    assertTrue(transport.requestsTo(key).isEmpty())
    assertEquals("Not done: it wasn't confirmed.", m.state.value.coachNotes[bot.tafs.app.model.ConnectWords.cardKey("g1")])
  }

  @Test fun aKeyForAConnectionWhoseWordsArentReadIsNeverGiven() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${profiledGrant()}]}""")
    val m = model()
    m.refreshConnections()
    assertFalse(m.giveKey("g1", digestOf(profiledRequest), "sk_test_4eC39H"))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(key).isEmpty())
  }

  @Test fun aKeyForWhatTheOwnerWasntShownIsNeverGiven() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    val m = model()
    m.refreshConnections()
    assertFalse(m.giveKey("g1", "an-older-digest", "sk_test_4eC39H"))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(key).isEmpty())
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
    assertEquals(AppModel.Connected.Failed("Not connected. " + bot.tafs.app.model.ErrorWords.CHANGED, "Not connected: this request was already approved"), m.connect("a1", "g1", digest))
  }

  @Test fun aGrantItsVendorSignedOutIsSignedInAgainNotRefused() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "needs_reconnect", extra = ""","status_words":"Signed out by Stripe · sign in again"""")}]}""")
    transport.answer(approve, 409, """{"error":"stop here"}""")
    val m = model()
    m.refreshConnections()
    val g = m.state.value.grants.single()
    assertTrue(g.needsReconnect)
    assertEquals("Signed out by Stripe · sign in again", g.statusWords)
    // It goes to planque's approve, as a new grant does, rather than stopping at "This connection is needs reconnect".
    m.connect("a1", "g1", digest)
    assertEquals(1, transport.requestsTo(approve).size)
  }

  private val twoTafs =
    """{"tafs":[{"id":"taf_2","name":"Bills","kiff_agent":"bills-1","color":2,"state":"idle","card":{"ids":["c2"],"active":true}},
        {"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}"""

  @Test fun aTafWhoseConnectionItsVendorSignedOutWaitsOnTheOwnerInPlanquesWords() = runTest {
    reads()
    transport.answer("GET /v1/home", 200, twoTafs)
    transport.answer(grants, 200, """{"grants":[${grant(status = "needs_reconnect", extra = ""","status_words":"Signed out by Stripe · sign in again"""")}]}""")
    val m = model()
    m.refresh()
    // Read on the refresh itself, with no Coach and no taf page open.
    assertEquals("refunds-1", m.state.value.signedOut("refunds-1")?.request?.agent)
    assertEquals(setOf("refunds-1"), m.state.value.waitingOnOwner)
    // It goes to the top, as a held call does, and its row reads planque's words.
    assertEquals("refunds-1", m.state.value.listedTafs.first().id)
    assertEquals("Signed out by Stripe · sign in again", bot.tafs.app.model.ConnectionWords.signedOut(m.state.value.signedOut("refunds-1")!!))
    // It's still the taf's connection on its page, to sign in again.
    assertEquals(listOf("g1"), m.state.value.connections("refunds-1").map { it.id })
  }

  @Test fun aSignedOutConnectionWithNoWordsYetSaysSoInTheAppsWords() {
    val g = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>(grant(status = "needs_reconnect"))
    assertEquals("Signed out by mcp.stripe.com · sign in again", bot.tafs.app.model.ConnectionWords.signedOut(g))
  }

  @Test fun anyLiveUpdateReadsPlanquesGrantsAgain() = runTest {
    reads()
    transport.answer("GET /v1/home", 200, twoTafs)
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    val m = model()
    m.refresh()
    transport.answer(grants, 200, """{"grants":[${grant(status = "needs_reconnect")}]}""")
    val before = transport.requestsTo(grants).size
    m.readChange("knock", "taf_1")
    assertEquals(before + 1, transport.requestsTo(grants).size)
    assertEquals(setOf("refunds-1"), m.state.value.waitingOnOwner)
  }

  @Test fun aPhonePlanqueDoesNotKnowCannotApproveAndSaysWhereToStart() = runTest {
    reads()
    val m = model()
    val before = transport.requests.count { it.url.contains("planque") }
    val result = m.connect("a1", "g1", digest)
    assertTrue(result is AppModel.Connected.Failed && result.why.contains("Start from tafs on an iPhone"))
    // The approval asks planque nothing: only the refresh read its grants.
    assertEquals(before, transport.requests.count { it.url.contains("planque") })
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
    val message = KiffClient.requestMessage("t_01", "POST", "/v1/me/agent-drafts/dr_1/confirm", sent.body, parts[0].removePrefix("t="))
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

  @Test fun aDraftKiffAlreadyConfirmedIsAlreadyApprovedAndSignsNothing() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    transport.answer("GET /v1/me/agent-drafts/dr_1", 200, draft.replace("\"draft\"", "\"confirmed\""))
    val m = model()
    m.refreshConnections()
    assertTrue(m.connect("a1", "g1", digest, "dr_1", "v3") is AppModel.Connected.AlreadyApproved)
    assertTrue(auth.asked.isEmpty())
  }

  @Test fun kiffRefusingTheCardsIsToldAndNothingIsClaimedDone() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    transport.answer("GET /v1/me/agent-drafts/dr_1", 200, draft)
    transport.answer("POST /v1/me/agent-drafts/dr_1/confirm", 409, """{"error":"a Card moved on","reason":"card_revised"}""")
    val m = model()
    m.refreshConnections()
    assertEquals(AppModel.Connected.Failed("KIFF didn't issue the taf's Cards. " + bot.tafs.app.model.ErrorWords.CHANGED, "KIFF didn't issue the taf's Cards: a Card moved on"), m.connect("a1", "g1", digest, "dr_1", "v3"))
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

  private val answerRoute = "POST /v1/tafs/setup_1/components/a1/answer"

  @Test fun readingCoachsThreadReadsWhatItsApprovalsNeed() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    val m = model()
    coachWithCard(m)
    assertEquals(listOf("g1"), m.state.value.grants.map { it.id })
    assertTrue(m.state.value.planqueReady)
  }

  private val askRoute = "POST /v1/tafs/setup_1/components/a1/approval"
  private val reportRoute = "POST /v1/tafs/setup_1/components/a1/approval/ask_1"

  /** tafs cloud asks for one signature: the connection, with the approve body as it built it (its words, not the phone's). */
  private fun signConnect(profile: String = "") = """{"outcome":"sign","ask":{"id":"ask_1","step":"connect","message":"tafs.connect.v1|t_01|g1|$digest","reason":"Connect Stripe to Refunds",""" +
    """"planque":{"grant":"g1","body":{"digest":"$digest"$profile}}}}"""

  private fun bodyOf(request: bot.tafs.app.kiff.HttpRequest) = KiffClient.json.parseToJsonElement(String(request.body!!)).jsonObject

  @Test fun approveAsksTafsCloudSignsOnlyWhatItNamesAndReportsBackSigned() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    val profile = ""","kiff_profile":{"profile_sha256":"${"ab".repeat(32)}","words_sha256":"${"cd".repeat(32)}"}"""
    transport.answer(askRoute, 200, signConnect(profile))
    transport.answer(approve, 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth?x=1"}""")
    transport.answer(confirm, 200, grant("g1", "connected"))
    transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"approved","session":"s1","run":"r1"}""")
    val m = model(FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de"))
    coachWithCard(m)
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[],"components":[]}""")
    m.approveCoach(approvalComponent(), digest, null)
    // tafs cloud was asked first, as this phone: what the card showed, and the read proof for planque.
    val ask = transport.requestsTo(askRoute).single()
    assertEquals("g1", bodyOf(ask).getValue("grant").jsonPrimitive.content)
    assertEquals(digest, bodyOf(ask).getValue("shown_digest").jsonPrimitive.content)
    assertTrue(ask.headers.getValue("Planque-Device-Signature").startsWith("t="))
    // One prompt, in tafs cloud's words; planque gets the body tafs cloud built, as it is, kiff_profile included.
    assertEquals(listOf("Connect Stripe to Refunds"), auth.asked)
    val sent = bodyOf(transport.requestsTo(approve).single())
    assertEquals(digest, sent.getValue("digest").jsonPrimitive.content)
    assertEquals("ab".repeat(32), sent.getValue("kiff_profile").jsonObject.getValue("profile_sha256").jsonPrimitive.content)
    assertEquals("c0de", bodyOf(transport.requestsTo(confirm).single()).getValue("confirm").jsonPrimitive.content)
    // The report: what came of it, signed as a setup call by this phone's key over exactly this request. Never an answer.
    val report = transport.requestsTo(reportRoute).single()
    // Its own read proof: the approval's was spent by tafs cloud's first read, and planque takes each signed request once.
    assertNotEquals(ask.headers.getValue("Planque-Device-Signature"), report.headers.getValue("Planque-Device-Signature"))
    assertEquals("connected", bodyOf(report).getValue("result").jsonPrimitive.content)
    assertNull(bodyOf(report)["answer"])
    val parts = report.headers.getValue("KIFF-Device-Signature").split(",")
    val message = KiffClient.requestMessage("t_01", "POST", "/v1/tafs/setup_1/components/a1/approval/ask_1", report.body!!, parts[0].removePrefix("t="))
    assertTrue(verifies(signer.publicKeyDER, message, parts[1].removePrefix("v1=")))
    assertTrue("the phone never answers the card itself", transport.requestsTo(answerRoute).isEmpty())
    assertNull(m.state.value.coachNotes["a1"])
    assertNull("the signer a confirmed step set is gone", m.state.value.connecting)
  }

  @Test fun whatTafsCloudAnsweredItselfIsOnlyShownAndNothingIsSigned() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    transport.answer(askRoute, 200, """{"outcome":"answered","answer":"already approved: Stripe is connected and its Card is confirmed","note":"Already approved","session":"s1","run":"r1"}""")
    val m = model()
    coachWithCard(m)
    m.approveCoach(approvalComponent().copy(draft = "dr_1"), digest, "v3")
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(approve).isEmpty())
    assertTrue(transport.requestsTo("POST /v1/me/agent-drafts/dr_1/confirm").isEmpty())
    assertTrue(transport.requestsTo(answerRoute).isEmpty())
    // The card's line is tafs cloud's, as sent.
    assertEquals("Already approved", m.state.value.coachNotes["a1"])
    assertEquals("dr_1", bodyOf(transport.requestsTo(askRoute).single()).getValue("draft").jsonPrimitive.content)
    assertEquals("v3", bodyOf(transport.requestsTo(askRoute).single()).getValue("shown_draft").jsonPrimitive.content)
  }

  @Test fun aFailureTafsCloudDecidedIsShownInItsWords() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    transport.answer(askRoute, 200, """{"outcome":"answered","answer":"failed: mcp.stripe.com is already connected; nothing new to approve","note":"Nothing new to approve.","session":"s1","run":"r1"}""")
    val m = model()
    coachWithCard(m)
    m.approveCoach(approvalComponent(), digest, null)
    assertTrue(auth.asked.isEmpty())
    assertEquals("Nothing new to approve.", m.state.value.coachNotes["a1"])
    assertTrue(transport.requestsTo(answerRoute).isEmpty())
  }

  @Test fun anOutOfDateCardIsReadAgainAndNothingIsSignedOrTold() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(askRoute, 200, """{"outcome":"read_again","note":"What you're asked to approve changed since it was shown. Read it again, then approve."}""")
    val m = model()
    coachWithCard(m)
    val before = transport.requestsTo(grants).size
    m.approveCoach(approvalComponent(), "old", null)
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(approve).isEmpty())
    assertTrue(transport.requestsTo(answerRoute).isEmpty())
    assertEquals("What you're asked to approve changed since it was shown. Read it again, then approve.", m.state.value.coachNotes["a1"])
    assertTrue("the grants are read again", transport.requestsTo(grants).size > before)
  }

  @Test fun aSignatureNotConfirmedReportsNothing() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(askRoute, 200, signConnect())
    val m = model()
    coachWithCard(m)
    auth.confirms = false
    m.approveCoach(approvalComponent(), digest, null)
    assertTrue(transport.requestsTo(approve).isEmpty())
    assertTrue(transport.requestsTo(reportRoute).isEmpty())
    assertTrue(transport.requestsTo(answerRoute).isEmpty())
    assertEquals("Not done: it wasn't confirmed.", m.state.value.coachNotes["a1"])
    assertNull(m.state.value.connecting)
  }

  @Test fun aClosedSignInIsReportedAsThatAndTheCardShowsTafsCloudsNote() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(askRoute, 200, signConnect())
    transport.answer(approve, 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth?x=1"}""")
    transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"failed: The sign-in was closed. Nothing was connected.","note":"The sign-in was closed. Nothing was connected.","session":"s1","run":"r1"}""")
    val m = model(FakeSignIn(back = null))
    coachWithCard(m)
    m.approveCoach(approvalComponent(), digest, null)
    assertEquals("sign_in_closed", bodyOf(transport.requestsTo(reportRoute).single()).getValue("result").jsonPrimitive.content)
    assertEquals("The sign-in was closed. Nothing was connected.", m.state.value.coachNotes["a1"])
    assertTrue(transport.requestsTo(answerRoute).isEmpty())
  }

  @Test fun planqueRefusingTheApproveIsReportedWithItsReasonNeverDecidedHere() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(askRoute, 200, signConnect())
    transport.answer(approve, 409, """{"error":"already approved"}""")
    transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"failed: Not connected: already approved","note":"Not connected: already approved","session":"s1","run":"r1"}""")
    val m = model()
    coachWithCard(m)
    m.approveCoach(approvalComponent(), digest, null)
    val report = bodyOf(transport.requestsTo(reportRoute).single())
    assertEquals("failed", report.getValue("result").jsonPrimitive.content)
    assertTrue(report.getValue("why").jsonPrimitive.content.contains("already approved"))
    assertEquals("Not connected: already approved", m.state.value.coachNotes["a1"])
  }

  @Test fun theCardsStepConfirmsKiffsDraftExactlyAsAsked() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    transport.answer(askRoute, 200, """{"outcome":"sign","ask":{"id":"ask_1","step":"cards","message":"tafs.cards.v1|t_01|dr_1|v3","reason":"Give refunds-1 its Card","kiff":{"draft":"dr_1","version":"v3"}}}""")
    transport.answer("POST /v1/me/agent-drafts/dr_1/confirm", 200, draft.replace("\"draft\"", "\"confirmed\""))
    transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"approved","session":"s1","run":"r1"}""")
    val m = model()
    coachWithCard(m)
    m.approveCoach(approvalComponent().copy(draft = "dr_1"), digest, "v3")
    assertEquals(listOf("Give refunds-1 its Card"), auth.asked)
    val confirmed = transport.requestsTo("POST /v1/me/agent-drafts/dr_1/confirm").single()
    assertEquals("v3", bodyOf(confirmed).getValue("version").jsonPrimitive.content)
    assertTrue(confirmed.headers.containsKey("KIFF-Device-Signature"))
    assertTrue(transport.requestsTo(approve).isEmpty())
    assertEquals("confirmed", bodyOf(transport.requestsTo(reportRoute).single()).getValue("result").jsonPrimitive.content)
    assertTrue(transport.requestsTo(answerRoute).isEmpty())
  }

  @Test fun aMessageThatIsntThisCardsIsRefusedBeforeAnyPrompt() = runTest {
    // tafs cloud names the message; the phone rebuilds it from what it holds and refuses any other: another grant, another
    // account, another draft, or a message of another kind behind a plausible prompt.
    val asks = listOf(
      """{"outcome":"sign","ask":{"id":"ask_1","step":"connect","message":"tafs.connect.v1|t_01|g2|$digest","reason":"Connect Stripe to Refunds","planque":{"grant":"g2","body":{"digest":"$digest"}}}}""",
      """{"outcome":"sign","ask":{"id":"ask_1","step":"connect","message":"tafs.connect.v1|t_02|g1|$digest","reason":"Connect Stripe to Refunds","planque":{"grant":"g1","body":{"digest":"$digest"}}}}""",
      """{"outcome":"sign","ask":{"id":"ask_1","step":"connect","message":"tafs.resume.v1|t_01|g1|$digest","reason":"Connect Stripe to Refunds","planque":{"grant":"g1","body":{"digest":"$digest"}}}}""",
      // The body and the message agree on a digest, but not the one the person was shown.
      """{"outcome":"sign","ask":{"id":"ask_1","step":"connect","message":"tafs.connect.v1|t_01|g1|${"0".repeat(64)}","reason":"Connect Stripe to Refunds","planque":{"grant":"g1","body":{"digest":"${"0".repeat(64)}"}}}}""",
      """{"outcome":"sign","ask":{"id":"ask_1","step":"cards","message":"tafs.cards.v1|t_01|dr_9|v3","reason":"Give refunds-1 its Card","kiff":{"draft":"dr_9","version":"v3"}}}""",
      """{"outcome":"sign","ask":{"id":"ask_1","step":"cards","message":"tafs.cards.v1|t_01|dr_1|v3","reason":"Give refunds-1 its Card","kiff":{"draft":"dr_1","version":"v3"}}}""",
    )
    // A Cards ask at a version other than the one shown (v3), on a card that names the draft.
    val otherVersion = """{"outcome":"sign","ask":{"id":"ask_1","step":"cards","message":"tafs.cards.v1|t_01|dr_1|v4","reason":"Give refunds-1 its Card","kiff":{"draft":"dr_1","version":"v4"}}}"""
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    transport.answer(askRoute, 200, otherVersion)
    model().also { coachWithCard(it); it.approveCoach(approvalComponent().copy(draft = "dr_1"), digest, "v3") }.let { m ->
      assertTrue(auth.asked.isEmpty())
      assertTrue(transport.requestsTo("POST /v1/me/agent-drafts/dr_1/confirm").isEmpty())
      assertTrue(transport.requestsTo(reportRoute).isEmpty())
      assertEquals(bot.tafs.app.model.CoachWords.ASK_NOT_THIS_CARDS, m.state.value.coachNotes["a1"])
    }
    for (ask in asks) {
      reads()
      transport.answer(grants, 200, """{"grants":[${grant()}]}""")
      transport.answer(askRoute, 200, ask)
      val m = model()
      coachWithCard(m)
      // The last ask is a Cards step for a card that names no draft.
      m.approveCoach(approvalComponent(), digest, null)
      assertTrue(ask, auth.asked.isEmpty())
      assertTrue(ask, transport.requestsTo(approve).isEmpty())
      assertTrue(ask, transport.requestsTo("POST /v1/me/agent-drafts/dr_9/confirm").isEmpty())
      assertTrue(ask, transport.requestsTo(reportRoute).isEmpty())
      assertEquals(bot.tafs.app.model.CoachWords.ASK_NOT_THIS_CARDS, m.state.value.coachNotes["a1"])
      assertNull(m.state.value.connecting)
    }
  }

  @Test fun theExpectedMessageIsRebuiltFromTheCardAndTheAccount() {
    val connect = bot.tafs.app.kiff.ApprovalAsk("ask_1", "connect", "x", "r", planque = bot.tafs.app.kiff.PlanqueAsk("g1", KiffClient.json.parseToJsonElement("""{"digest":"d1"}""")))
    assertEquals("tafs.connect.v1|t_01|g1|d1", AppModel.expectedApprovalMessage(connect, approvalComponent(), "t_01", "d1", null))
    // The digest is the one shown, never the ask's; nothing shown, nothing signed.
    assertNull(AppModel.expectedApprovalMessage(connect, approvalComponent(), "t_01", "d2", null))
    assertNull(AppModel.expectedApprovalMessage(connect, approvalComponent(), "t_01", null, null))
    val cards = bot.tafs.app.kiff.ApprovalAsk("ask_1", "cards", "x", "r", kiff = bot.tafs.app.kiff.KiffAsk("dr_1", "v3"))
    assertEquals("tafs.cards.v1|t_01|dr_1|v3", AppModel.expectedApprovalMessage(cards, approvalComponent().copy(draft = "dr_1"), "t_01", null, "v3"))
    assertNull(AppModel.expectedApprovalMessage(cards, approvalComponent().copy(draft = "dr_1"), "t_01", null, "v2"))
    assertNull(AppModel.expectedApprovalMessage(cards, approvalComponent().copy(draft = "dr_1"), "t_01", null, null))
    assertNull(AppModel.expectedApprovalMessage(cards, approvalComponent(), "t_01", null, "v3"))
    assertNull(AppModel.expectedApprovalMessage(connect.copy(step = "resume"), approvalComponent(), "t_01", "d1", null))
  }

  @Test fun tafsCloudUnreachableLeavesTheCardWithAWordAndNothingSigned() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(askRoute, 503, """{"error":"Connect this phone to read its connected apps"}""")
    val m = model()
    coachWithCard(m)
    m.approveCoach(approvalComponent(), digest, null)
    assertTrue(auth.asked.isEmpty())
    assertTrue(m.state.value.coachNotes["a1"]!!.startsWith("Your answer didn't reach Setup."))
    assertNull(m.state.value.connecting)
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

  @Test fun planquesOwnRefusalsSayWhatHappened() {
    assertNotNull(PlanqueError.NotSigning.message)
  }

  // MARK: An app's event on the dial: the owner approves its filters, planque subscribes them, tafs cloud records it

  private val knobRoute = "GET /v1/tafs/taf_1/knob"
  private val record = "POST /v1/tafs/taf_1/knob/subscriptions"
  private val eventTriggers = "POST /v1/accounts/t_01/grants/gs/triggers"
  private val filters = """{"pipeline":"sales","amount_over":500}"""
  private fun eventKnob(state: String = "needed", config: String = filters, subscription: String? = null) =
    """{"version":7,"wakes":[{"id":"trg_e","kind":"app_event","grant":"gs","label":"New deal","words":"A won deal in HubSpot starts a check.","ok":"approve",
        "approval":{"state":"$state","grant":"gs","event":"HUBSPOT_DEAL_WON","config":$config${subscription?.let { ""","subscription":"$it"""" } ?: ""}}}],"rim":{}}"""

  @Test fun anApprovedEventIsSubscribedAtPlanqueThenRecordedByTafsCloudForThatVersion() = runTest {
    val m = slackModel()
    transport.answer(knobRoute, 200, eventKnob())
    transport.answer(knobRoute, 200, eventKnob())
    transport.answer(knobRoute, 200, eventKnob("approved", subscription = "sub_1"))
    transport.answer(eventTriggers, 201, """{"id":"sub_1"}""")
    transport.answer(record, 200, eventKnob("approved", subscription = "sub_1"))
    m.loadKnob("refunds-1")
    assertTrue(m.approveKnobEvent("refunds-1", "trg_e"))
    assertEquals("one confirmation, in tafs cloud's words for it", listOf("A won deal in HubSpot starts a check."), auth.asked)
    val sub = transport.requestsTo(eventTriggers).single()
    assertEquals(
      "the account, the event and its filters as tafs cloud gave them",
      KiffClient.json.parseToJsonElement("""{"account":"ca_1","slug":"HUBSPOT_DEAL_WON","config":$filters}"""),
      KiffClient.json.parseToJsonElement(String(sub.body!!)),
    )
    assertTrue("device-signed", sub.headers.containsKey("Planque-Device-Signature"))
    val recorded = transport.requestsTo(record).single()
    assertEquals(KiffClient.json.parseToJsonElement("""{"trigger":"trg_e","subscription":"sub_1","version":7}"""), KiffClient.json.parseToJsonElement(String(recorded.body!!)))
    assertTrue("planque first", transport.requests.indexOf(sub) < transport.requests.indexOf(recorded))
    assertEquals("the dial as tafs cloud has it", "approved", m.state.value.knobs.getValue("refunds-1").item("trg_e")!!.approval!!.state)
    assertTrue(m.state.value.approvingEvent.isEmpty())
  }

  @Test fun anEventWhoseFiltersChangedSinceTheyWereShownIsNeverApproved() = runTest {
    val m = slackModel()
    transport.answer(knobRoute, 200, eventKnob())
    transport.answer(knobRoute, 200, eventKnob(config = """{"pipeline":"all"}"""))
    m.loadKnob("refunds-1")
    assertFalse(m.approveKnobEvent("refunds-1", "trg_e"))
    assertTrue("nothing asked", auth.asked.isEmpty())
    assertTrue(transport.requestsTo(eventTriggers).isEmpty())
    assertEquals(bot.tafs.app.model.KnobWords.EVENT_CHANGED, m.state.value.knobErrors["refunds-1"])
  }

  @Test fun theSameFiltersInAnotherKeyOrderAreTheSameFilters() = runTest {
    val m = slackModel()
    transport.answer(knobRoute, 200, eventKnob())
    transport.answer(knobRoute, 200, eventKnob(config = """{"amount_over":500,"pipeline":"sales"}"""))
    transport.answer(eventTriggers, 201, """{"id":"sub_1"}""")
    transport.answer(record, 200, eventKnob("approved", subscription = "sub_1"))
    m.loadKnob("refunds-1")
    assertTrue(m.approveKnobEvent("refunds-1", "trg_e"))
  }

  @Test fun anOwnerWhoDoesNotConfirmApprovesNoEvent() = runTest {
    val m = slackModel()
    transport.answer(knobRoute, 200, eventKnob())
    m.loadKnob("refunds-1")
    auth.confirms = false
    assertFalse(m.approveKnobEvent("refunds-1", "trg_e"))
    assertTrue(transport.requestsTo(eventTriggers).isEmpty())
    assertTrue(transport.requestsTo(record).isEmpty())
  }

  @Test fun aSubscriptionTafsCloudDidntRecordIsRecordedAgainWithoutAskingOrSubscribingTwice() = runTest {
    val m = slackModel()
    repeat(4) { transport.answer(knobRoute, 200, eventKnob()) }
    transport.answer(knobRoute, 200, eventKnob("approved", subscription = "sub_1"))
    transport.answer(eventTriggers, 201, """{"id":"sub_1"}""")
    transport.answer(record, 503, """{"error":"try again"}""")
    transport.answer(record, 200, eventKnob("approved", subscription = "sub_1"))
    m.loadKnob("refunds-1")
    assertFalse(m.approveKnobEvent("refunds-1", "trg_e"))
    assertEquals("still needed, as tafs cloud says", "needed", m.state.value.knobs.getValue("refunds-1").item("trg_e")!!.approval!!.state)
    assertTrue(m.approveKnobEvent("refunds-1", "trg_e"))
    assertEquals("asked once", 1, auth.asked.size)
    assertEquals("subscribed once", 1, transport.requestsTo(eventTriggers).size)
    assertEquals(listOf("sub_1", "sub_1"), transport.requestsTo(record).map { KiffClient.json.parseToJsonElement(String(it.body!!)).jsonObject.getValue("subscription").jsonPrimitive.content })
  }

  @Test fun aRefusalFromPlanqueIsSaidInItsWords() = runTest {
    val m = slackModel()
    transport.answer(knobRoute, 200, eventKnob())
    transport.answer(eventTriggers, 400, """{"error":"amount_over isn't a filter of this event"}""")
    m.loadKnob("refunds-1")
    assertFalse(m.approveKnobEvent("refunds-1", "trg_e"))
    assertTrue(m.state.value.knobErrors.getValue("refunds-1").contains("amount_over isn't a filter of this event"))
    assertTrue(transport.requestsTo(record).isEmpty())
  }

  // MARK: A Slack door: planque's subscription first, then tafs cloud's door

  private val slackBot =
    """{"id":"gs","status":"connected","account":"ca_1","entry":"connector:composio:slackbot","text":"Slack bot","digest":"","request":{"server":"","agent":"refunds-1","tools":[]}}"""
  private val slackApp = bot.tafs.app.kiff.DoorApp(id = "slack", label = "Slack", kind = "slack", proof = "proven")

  private suspend fun slackModel(bots: String = slackBot): AppModel {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":true}}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
    transport.answer(grants, 200, """{"grants":[$bots,${grant("g1")}]}""")
    val m = model()
    m.refresh()
    m.refreshConnections()
    return m
  }

  @Test fun theTafsSlackBotIsKnownByItsEntryOrToolkitNeverItsName() = runTest {
    val m = slackModel()
    assertEquals(listOf("gs"), m.state.value.slackBots("refunds-1").map { it.id })
    val byToolkit = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>("""{"id":"g2","status":"connected","request":{"agent":"refunds-1","connector":{"provider":"composio","toolkit":"slackbot"}}}""")
    assertTrue(byToolkit.isSlackBot)
    val named = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>("""{"id":"g3","status":"connected","vendor":"Slack","request":{"agent":"refunds-1"}}""")
    assertFalse("a vendor name doesn't make a Slack bot", named.isSlackBot)
  }

  @Test fun aSlackDoorSubscribesAtPlanqueFirstThenAddsTheDoorWithItsTrigger() = runTest {
    val m = slackModel()
    transport.answer("GET /v1/accounts/t_01/grants/gs/channels", 200, """{"channels":[{"id":"C0REFUNDS","name":"refunds","private":false}]}""")
    m.loadSlackChannels("gs")
    val channel = m.state.value.slackChannels.getValue("gs").single()
    transport.answer("POST /v1/accounts/t_01/grants/gs/triggers", 200, """{"id":"trg_9","slug":"SLACKBOT_CHANNEL_MESSAGE_RECEIVED"}""")
    transport.answer("POST /v1/tafs/taf_1/doors", 200, """{"door":{"id":"door_s","app":"slack","kind":"slack","label":"Slack","grant":"gs","channel":"C0REFUNDS","trigger":"trg_9"}}""")
    transport.answer("GET /v1/tafs/taf_1/doors", 200, """{"doors":[],"apps":[],"knocks":[]}""")
    val door = m.addSlackDoor("refunds-1", slackApp, m.state.value.slackBots("refunds-1").single(), channel)
    assertEquals("door_s", door?.id)
    assertEquals("one confirmation", listOf(bot.tafs.app.model.DoorWords.slackReason("Refunds", "refunds")), auth.asked)
    val sub = transport.requestsTo("POST /v1/accounts/t_01/grants/gs/triggers").single()
    assertEquals(
      "the account, the slug and the channel; no config",
      KiffClient.json.parseToJsonElement("""{"account":"ca_1","slug":"SLACKBOT_CHANNEL_MESSAGE_RECEIVED","channels":["C0REFUNDS"]}"""),
      KiffClient.json.parseToJsonElement(String(sub.body!!)),
    )
    assertTrue("device-signed", sub.headers.containsKey("Planque-Device-Signature"))
    val added = transport.requestsTo("POST /v1/tafs/taf_1/doors").single()
    val body = KiffClient.json.parseToJsonElement(String(added.body!!)).jsonObject
    assertEquals("slack", body.getValue("app").jsonPrimitive.content)
    assertEquals("trg_9", body.getValue("trigger").jsonPrimitive.content)
    assertTrue("planque first", transport.requests.indexOf(sub) < transport.requests.indexOf(added))
  }

  @Test fun aDoorThatCantBeAddedTakesItsSubscriptionAway() = runTest {
    val m = slackModel()
    transport.answer("POST /v1/accounts/t_01/grants/gs/triggers", 200, """{"id":"trg_9"}""")
    transport.answer("POST /v1/tafs/taf_1/doors", 400, """{"error":"connect the Slack bot first"}""")
    transport.answer("DELETE /v1/accounts/t_01/grants/gs/triggers/trg_9", 200, "{}")
    assertNull(m.addSlackDoor("refunds-1", slackApp, m.state.value.slackBots("refunds-1").single(), bot.tafs.app.kiff.SlackChannel("C0REFUNDS", "refunds")))
    val del = transport.requestsTo("DELETE /v1/accounts/t_01/grants/gs/triggers/trg_9").single()
    assertEquals("ca_1", KiffClient.json.parseToJsonElement(String(del.body!!)).jsonObject.getValue("account").jsonPrimitive.content)
  }

  @Test fun removingASlackDoorRemovesItsSubscriptionFirst() = runTest {
    val m = slackModel()
    transport.answer("DELETE /v1/accounts/t_01/grants/gs/triggers/trg_9", 200, "{}")
    transport.answer("DELETE /v1/tafs/taf_1/doors/door_s", 200, "{}")
    m.removeDoor("refunds-1", bot.tafs.app.kiff.Door(id = "door_s", app = "slack", kind = "slack", label = "Slack", grant = "gs", channel = "C0REFUNDS", trigger = "trg_9"))
    val sub = transport.requestsTo("DELETE /v1/accounts/t_01/grants/gs/triggers/trg_9").single()
    val door = transport.requestsTo("DELETE /v1/tafs/taf_1/doors/door_s").single()
    assertTrue(transport.requests.indexOf(sub) < transport.requests.indexOf(door))
  }

  // MARK: A bot door (Telegram): the provider and every word are tafs cloud's; the bot's connection, as its door; nothing to subscribe

  private val telegramBot =
    """{"id":"gt","status":"connected","provider":"telegram","entry":"api:telegram","telegram":{"id":7001,"username":"refunds_desk_bot"},"text":"Telegram bot","digest":"","request":{"server":"","agent":"refunds-1","tools":[],"api":{"url":"https://core.telegram.org/bots/api"}}}"""
  /** The door's app as tafs cloud offers it: the provider to list and the step's words come with it. */
  private val telegramApp = KiffClient.json.decodeFromString<bot.tafs.app.kiff.DoorApp>(
    """{"id":"telegram","label":"Telegram","kind":"telegram","proof":"proven","how":"","bot":{"provider":"telegram","words":{"none":"Connect it first.","pick":"Which bot?"}}}""",
  )

  private suspend fun telegramModel(): AppModel {
    return slackModel("$telegramBot,$slackBot")
  }

  @Test fun aBotDoorListsTheConnectionsFromTheProviderTafsCloudNamesNeverByName() = runTest {
    val m = telegramModel()
    val provider = telegramApp.bot!!.provider
    assertEquals(listOf("gt"), m.state.value.bots("refunds-1", provider).map { it.id })
    assertEquals("@refunds_desk_bot", m.state.value.bots("refunds-1", provider).single().botName)
    assertTrue("another provider lists none of them", m.state.value.bots("refunds-1", "other").isEmpty())
    val noToken = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>("""{"id":"g2","status":"connected","provider":"telegram","request":{"agent":"refunds-1"}}""")
    assertNull("no token given yet: no bot to name", noToken.botName)
    val named = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>("""{"id":"g3","status":"connected","vendor":"Telegram","request":{"agent":"refunds-1"}}""")
    assertFalse("a vendor name isn't a provider", named.isFrom(provider))
  }

  @Test fun aBotDoorIsAddedOnTheBotsGrantWithNothingSubscribedOrSigned() = runTest {
    val m = telegramModel()
    transport.answer("POST /v1/tafs/taf_1/doors", 200, """{"door":{"id":"telegram","app":"telegram","kind":"telegram","label":"Telegram","proof":"proven","grant":"gt"}}""")
    val door = m.addBotDoor("refunds-1", telegramApp, m.state.value.bots("refunds-1", "telegram").single())
    assertEquals("telegram", door?.id)
    val body = KiffClient.json.parseToJsonElement(String(transport.requestsTo("POST /v1/tafs/taf_1/doors").single().body!!)).jsonObject
    assertEquals(KiffClient.json.parseToJsonElement("""{"grant":"gt","bot":"@refunds_desk_bot","app":"telegram"}"""), body)
    assertTrue("nothing asked", auth.asked.isEmpty())
    assertTrue("nothing subscribed at planque", transport.requests.none { it.url.contains("/triggers") })
    assertTrue(m.state.value.doors.getValue("refunds-1").doors.any { it.id == "telegram" })
  }

  @Test fun aBotDoorTafsCloudRefusesIsNotAdded() = runTest {
    val m = telegramModel()
    transport.answer("POST /v1/tafs/taf_1/doors", 400, """{"error":"connect the Telegram bot first"}""")
    assertNull(m.addBotDoor("refunds-1", telegramApp, m.state.value.bots("refunds-1", "telegram").single()))
    assertNotNull(m.state.value.doors.getValue("refunds-1").error)
    assertTrue(m.state.value.doors.getValue("refunds-1").doors.isEmpty())
  }

  @Test fun pairingWordsAreTafsClouds() {
    val door = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Door>(
      """{"id":"telegram","app":"telegram","kind":"telegram","label":"Telegram","proof":"proven","words":{"pair_hint":"Their user id","pair_from_knock":"Ask them to write first."}}""",
    )
    assertEquals("Their user id", bot.tafs.app.model.DoorWords.pairHint(door))
    assertEquals("Ask them to write first.", door.words?.pairFromKnock)
  }

  // MARK: A browser connection's sign-in to a site: planque's live view, signed, opened as it comes (kiff/planque#86)

  private val signInRoute = "POST /v1/accounts/t_01/grants/gs/sign-in"

  @Test fun theSignInViewIsAskedForSignedAndOpenedAsPlanqueSendsIt() = runTest {
    val m = slackModel()
    transport.answer(signInRoute, 200, """{"view":"https://planque.kiff.dev/browser/view/tok_1","expires":"2026-11-05T04:20:00Z","words":"Sign in, then tap Done."}""")
    m.openSiteSignIn("gs")
    val sent = transport.requestsTo(signInRoute).single()
    assertTrue("device-signed", sent.headers.containsKey("Planque-Device-Signature"))
    assertTrue("no fingerprint asked: it grants nothing", auth.asked.isEmpty())
    assertEquals("https://planque.kiff.dev/browser/view/tok_1", m.state.value.siteSignIn?.view)
    assertEquals("Sign in, then tap Done.", m.state.value.siteSignIn?.words)
    assertNull(m.state.value.openingSiteSignIn)
    m.closeSiteSignIn()
    assertNull(m.state.value.siteSignIn)
  }

  @Test fun aViewOffPlanquesOwnAddressIsNeverOpened() = runTest {
    val m = slackModel()
    transport.answer(signInRoute, 200, """{"view":"https://elsewhere.example/browser/view/tok_1","words":"Sign in"}""")
    m.openSiteSignIn("gs")
    assertNull(m.state.value.siteSignIn)
    assertTrue(m.state.value.coachNotes.getValue("gs").contains("wasn't on the connection's own address"))
  }

  @Test fun planquesRefusalIsSaidOnTheConnection() = runTest {
    val m = slackModel()
    transport.answer(signInRoute, 409, """{"error":"a sign-in view is open for this connection: finish it on the phone, or wait for it to lapse"}""")
    m.openSiteSignIn("gs")
    assertNull(m.state.value.siteSignIn)
    assertTrue(m.state.value.coachNotes.getValue("gs").contains("a sign-in view is open for this connection"))
  }

  @Test fun aBrowserConnectionCarriesTafsCloudsSignInAction() {
    val app = KiffClient.json.decodeFromString<bot.tafs.app.kiff.ConnectedApp>(
      """{"id":"gb","name":"Shop","status":"connected","site_sign_in":{"title":"Sign in to a site","words":"You sign in yourself."}}""",
    )
    assertEquals("Sign in to a site", app.siteSignIn?.title)
    assertNull(KiffClient.json.decodeFromString<bot.tafs.app.kiff.ConnectedApp>("""{"id":"g1","name":"Notion","status":"connected"}""").siteSignIn)
  }
}
