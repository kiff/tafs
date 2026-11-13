package bot.tafs.app

import bot.tafs.app.model.Words
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

  private suspend fun model(signIn: VendorSignIn = FakeSignIn(), keyed: Boolean = true, scope: kotlinx.coroutines.CoroutineScope? = null): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    if (keyed) secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false, vendorSignIn = signIn, scope = scope).also { it.restore() }
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
    // The prompt never names the agent by its id: with no taf of that name here, it names the app alone.
    assertEquals(listOf("Connect mcp.stripe.com"), auth.asked)
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

  /**
   * Sign in again on a connected grant with a profile, from Connect apps (Android 36's phone round): nothing had read KIFF's words,
   * so approving refused before the fingerprint. Sign in again reads them first, as the approval card does, and signs them in.
   */
  @Test fun signInAgainReadsTheProfilesWordsBeforeItApproves() = runTest {
    reads()
    val connected = """{"id":"g1","status":"connected","text":"Connect Stripe: refund a payment.","digest":"${digestOf(profiledRequest)}","request":$profiledRequest}"""
    transport.answer(grants, 200, """{"grants":[$connected]}""")
    transport.answer(words, 200, resource("stripe-refunds.expected"))
    transport.answer(approve, 200, """{"status":"connected","sign_in_url":"https://mcp.stripe.com/oauth?x=1"}""")
    transport.answer(confirm, 200, grant("g1", "connected"))
    val m = model(FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de"))
    m.refreshConnections()
    assertNull("nothing has read KIFF's words yet", m.state.value.profileWordsFor(m.state.value.grants.single()))
    m.reconnect(m.state.value.grants.single())
    assertEquals(1, transport.requestsTo(words).size)
    val body = KiffClient.json.parseToJsonElement(String(transport.requestsTo(approve).single().body!!)).jsonObject
    val expected = KiffClient.json.parseToJsonElement(resource("stripe-refunds.expected")).jsonObject
    assertEquals(expected["words_sha256"], body.getValue("kiff_profile").jsonObject["words_sha256"])
    assertEquals(1, transport.requestsTo(confirm).size)
    assertNull(m.state.value.coachNotes["g1"])
  }

  /** KIFF's words can't be read: Sign in again says why, and nothing is approved. */
  @Test fun signInAgainWhoseWordsCantBeReadApprovesNothing() = runTest {
    reads()
    val connected = """{"id":"g1","status":"connected","text":"Connect Stripe: refund a payment.","digest":"${digestOf(profiledRequest)}","request":$profiledRequest}"""
    transport.answer(grants, 200, """{"grants":[$connected]}""")
    transport.answer(words, 503, """{"error":"unavailable"}""")
    val m = model(FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de"))
    m.refreshConnections()
    m.reconnect(m.state.value.grants.single())
    assertTrue(transport.requestsTo(approve).isEmpty())
    assertEquals(m.state.value.profileWordsErrors["g1"], m.state.value.coachNotes["g1"])
    assertTrue(m.state.value.coachNotes["g1"]!!.startsWith("KIFF's words for this connection couldn't be read."))
  }

  /** A managed provider's connection isn't signed in again in place (planque's 409): it says how, and asks nothing. */
  @Test fun aProvidersConnectionSaysToDisconnectAndConnectAgainInsteadOfSigningIn() = runTest {
    reads()
    val managedRequest = """{"server":"mcp.composio.dev","agent":"refunds-1","connector":{"provider":"composio","toolkit":"stripe"},"tools":[{"tool":"list_charges","read_only":true}]}"""
    val managed = """{"id":"g9","status":"connected","vendor":"Stripe","text":"Connect Stripe.","digest":"${digestOf(managedRequest)}","request":$managedRequest}"""
    transport.answer(grants, 200, """{"grants":[$managed]}""")
    val m = model(FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g9&confirm=c0de"))
    m.refreshConnections()
    val g = m.state.value.grants.single()
    assertTrue(g.signsInThroughProvider)
    m.reconnect(g)
    assertTrue(transport.requestsTo("POST /v1/accounts/t_01/grants/g9/approve").isEmpty())
    assertEquals(
      "Stripe signs in through its connection provider. To sign in again, remove access and connect it again.",
      m.state.value.coachNotes["g9"],
    )
    // Signed out by the provider, it signs in again as any connection does: only a connected one is refused in place.
    assertFalse(g.copy(status = "needs_reconnect").signsInThroughProvider)
  }

  @Test fun aProfileChangedUnderTheSameConnectionIsReadAgainAndOnlyItsWordsAreApproved() = runTest {
    reads()
    // Tools added to a connection change its profile under the same grant id (planque merges the addition into it).
    val slackProfile = resource("slack-messages")
    val changedRequest = request.dropLast(1) + ""","profile":""" + slackProfile + "}"
    val changedGrant = """{"id":"g1","status":"pending","text":"Connect Stripe: refund a payment.","digest":"${digestOf(changedRequest)}","request":$changedRequest}"""
    transport.answer(grants, 200, """{"grants":[${profiledGrant()}]}""")
    transport.answer(words, 200, resource("stripe-refunds.expected"))
    transport.answer(words, 200, resource("slack-messages.expected"))
    transport.answer(approve, 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth?x=1"}""")
    transport.answer(confirm, 200, grant("g1", "connected"))
    val m = model(FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de"))
    m.refreshConnections()
    m.loadProfileWords("g1")
    assertTrue(m.state.value.profileWordsFor(m.state.value.grants.single()) != null)
    // The profile changes: the words read for the earlier one aren't this one's, and nothing can be approved with them.
    transport.answer(grants, 200, """{"grants":[$changedGrant]}""")
    m.refreshConnections()
    assertNull(m.state.value.profileWordsFor(m.state.value.grants.single()))
    val early = m.connect("a1", "g1", digestOf(changedRequest))
    assertTrue(early.toString(), early is AppModel.Connected.Failed)
    assertTrue(transport.requestsTo(approve).isEmpty())
    // Read again, for the profile as it is now.
    m.loadProfileWords("g1")
    assertEquals(2, transport.requestsTo(words).size)
    assertEquals(Jcs.canonical(slackProfile), Jcs.canonical(String(transport.requestsTo(words).last().body!!)))
    val result = m.connect("a1", "g1", digestOf(changedRequest))
    assertTrue(result.toString(), result is AppModel.Connected.Done)
    val signed = KiffClient.json.parseToJsonElement(String(transport.requestsTo(approve).single().body!!)).jsonObject.getValue("kiff_profile").jsonObject
    val expected = KiffClient.json.parseToJsonElement(resource("slack-messages.expected")).jsonObject
    assertEquals(expected["profile_sha256"], signed["profile_sha256"])
    // Read once per profile: asking again reads nothing more.
    m.loadProfileWords("g1")
    assertEquals(2, transport.requestsTo(words).size)
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

  @Test fun signedInWithNoLinkConfirmsWithPlanquesCodeAndGoesOnNeverAFailure() = runTest {
    // The person's sign-in at the provider is already held: planque answers signed_in with a one-use code, no link.
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(approve, 200, """{"status":"signed_in","confirm":"code_once","confirm_by":"2026-10-28T09:15:00Z"}""")
    transport.answer(confirm, 200, grant("g1", "listed"))
    val vendor = FakeSignIn()
    val m = model(vendor)
    m.refreshConnections()
    val result = m.connect("a1", "g1", digest)
    assertTrue(result.toString(), result is AppModel.Connected.Done)
    // No second sign-in page, and the code planque gave is the one confirmed.
    assertNull(vendor.opened)
    val sent = KiffClient.json.parseToJsonElement(String(transport.requestsTo(confirm).single().body!!)).jsonObject
    assertEquals("code_once", sent.getValue("confirm").jsonPrimitive.content)
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
    assertEquals("confirmed with the approval key, never naming the agent id", listOf("Give mcp.stripe.com its key"), auth.asked)
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

  @Test fun aHeldSignInIsConfirmedBeforeTheReportAndASignInNotConfirmedYetShowsTafsCloudsLine() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(askRoute, 200, signConnect())
    transport.answer(approve, 200, """{"status":"signed_in","confirm":"code_once","confirm_by":"2026-10-28T09:15:00Z"}""")
    transport.answer(confirm, 200, grant("g1", "listed"))
    // tafs cloud still reads it as not confirmed: its own line, never "Your answer didn't reach Coach".
    transport.answer(reportRoute, 409, """{"error":"Confirm the sign-in on this phone first.","code":"sign_in_not_confirmed"}""")
    val vendor = FakeSignIn()
    val m = model(vendor)
    coachWithCard(m)
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[],"components":[]}""")
    m.approveCoach(approvalComponent(), digest, null)
    assertNull("no second sign-in page", vendor.opened)
    // Confirmed with planque's code before anything was reported.
    assertEquals("code_once", bodyOf(transport.requestsTo(confirm).single()).getValue("confirm").jsonPrimitive.content)
    assertEquals("listed", bodyOf(transport.requestsTo(reportRoute).single()).getValue("result").jsonPrimitive.content)
    assertEquals("Confirm the sign-in on this phone first.", m.state.value.coachNotes["a1"])
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

  // MARK: Coach's card for an app connected with a key: the key goes in on the card, then the card is answered

  private val keyRequest = """{"kind":"api_key","server":"api.example-bank.test","agent":"invoicing-1","tools":[{"tool":"list_invoices","read_only":true}]}"""
  private fun keyGrant(status: String = "pending") =
    """{"id":"g1","status":"$status","text":"Connect the bank: list invoices.","digest":"${digestOf(keyRequest)}","request":$keyRequest}"""

  @Test fun coachsCardForAKeyAppGivesTheKeyThenAnswersTheCard() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${keyGrant()}]}""")
    transport.answer(key, 200, keyGrant("connected"))
    transport.answer(askRoute, 200, """{"outcome":"answered","answer":"already approved: the bank is connected","session":"s1","run":"r1"}""")
    val m = model()
    coachWithCard(m)
    assertTrue("planque's api_key: the card takes the key", m.state.value.grants.single().takesKey)
    assertTrue(m.approveCoachWithKey(approvalComponent(), digestOf(keyRequest), null, "  test-key-1  "))
    // The key went to planque, signed and confirmed, in its header alone: never planque's plain approve, which it refuses.
    assertEquals("test-key-1", transport.requestsTo(key).single().headers[PlanqueClient.VENDOR_KEY])
    assertTrue(transport.requestsTo(approve).isEmpty())
    // Then the card is answered as any approval: tafs cloud finds it connected, and Coach goes on.
    val ask = transport.requestsTo(askRoute).single()
    assertEquals("g1", bodyOf(ask).getValue("grant").jsonPrimitive.content)
    assertFalse("the key is in no request to tafs cloud", String(ask.body!!).contains("test-key-1"))
    assertFalse("kept nowhere in the app's state", m.state.value.toString().contains("test-key-1"))
  }

  @Test fun coachsCardOpensTheKeySheetAndTheKeyGivenThereAnswersTheCard() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${keyGrant()}]}""")
    transport.answer(key, 200, keyGrant("connected"))
    transport.answer(askRoute, 200, """{"outcome":"answered","answer":"already approved: the bank is connected","session":"s1","run":"r1"}""")
    val m = model(scope = backgroundScope)
    coachWithCard(m)
    // The card opens the Connect sheet's key screen, outside the chat; nothing is given or answered yet.
    m.openCardKeySheet(approvalComponent(), null)
    assertEquals("g1", m.state.value.listApproval)
    assertTrue(m.state.value.listApprovalNeedsKey)
    assertTrue(transport.requestsTo(key).isEmpty())
    // Given on that sheet: the key to planque, then the card's answer, and the sheet closes.
    assertTrue(m.giveKeyOnSheet("g1", digestOf(keyRequest), "test-key-1"))
    assertEquals(1, transport.requestsTo(key).size)
    assertEquals(1, transport.requestsTo(askRoute).size)
    assertNull(m.state.value.listApproval)
    assertNull(m.state.value.keyForCard)
  }

  // MARK: One fingerprint for the key and its Card (kiff.requests.v1, docs/secure-connection.md)

  private val confirmRoute = "POST /v1/me/agent-drafts/dr_1/confirm"
  private val keyAndCards =
    """{"outcome":"sign","ask":{"id":"ask_1","step":"key_and_cards","scheme":"kiff.requests.v1","message":"","reason":"Connect the bank to Invoices","key":"critical",
       "planque":{"grant":"g1","body":{"digest":"DIGEST"}},"kiff":{"draft":"dr_1","version":"v3"}}}"""

  /** A signature header's fields: t, v3, with, d. */
  private fun fields(header: String) = header.split(",").associate { it.substringBefore("=") to it.substringAfter("=") }

  @Test fun oneFingerprintGivesTheKeyAndConfirmsItsCardEachRequestCheckedByItsOwnService() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${keyGrant()}]}""")
    transport.answer(askRoute, 200, keyAndCards.replace("DIGEST", digestOf(keyRequest)))
    transport.answer(key, 200, keyGrant("connected"))
    transport.answer(confirmRoute, 200, draft.replace("\"draft\"", "\"confirmed\""))
    transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"approved","session":"s1","run":"r1"}""")
    val m = model()
    coachWithCard(m)
    transport.answer(grants, 200, """{"grants":[${keyGrant("connected")}]}""")
    assertTrue(m.approveCoachWithKey(approvalComponent().copy(draft = "dr_1"), digestOf(keyRequest), "v3", "  test-key-0001\n"))

    // The phone said it can sign the step; one fingerprint, for both.
    val ask = bodyOf(transport.requestsTo(askRoute).single())
    assertEquals("key_and_cards", ask.getValue("can").toString().trim('[', ']', '"'))
    assertEquals(listOf("Connect the bank to Invoices"), auth.asked)

    // Each request carries the one signature and the other's message hash, at the same time.
    val keyed = transport.requestsTo(key).single()
    val confirmed = transport.requestsTo(confirmRoute).single()
    val toPlanque = fields(keyed.headers.getValue("Planque-Device-Signature"))
    val toKiff = fields(confirmed.headers.getValue("KIFF-Device-Signature"))
    assertEquals(toPlanque.getValue("t"), toKiff.getValue("t"))
    assertEquals(toPlanque.getValue("v3"), toKiff.getValue("v3"))
    assertEquals("dev_1", toPlanque["d"])
    assertNull("never a v1 beside it", toPlanque["v1"] ?: toKiff["v1"])
    assertEquals("the trimmed key, in its header alone", "test-key-0001", keyed.headers[PlanqueClient.VENDOR_KEY])
    assertFalse(String(keyed.body!!).contains("test-key-0001"))

    // Each service rebuilds its own message: the other's hash is what the phone sent beside it.
    val at = toKiff.getValue("t")
    val planqueBase = keyed.url.substringBefore("/v1/accounts")
    val keyHash = KiffClient.messageHash(PlanqueClient.message(planqueBase, "t_01", "POST", "/v1/accounts/t_01/grants/g1/key", keyed.body!!, at))
    val confirmHash = KiffClient.messageHash(KiffClient.requestMessage("t_01", "POST", "/v1/me/agent-drafts/dr_1/confirm", confirmed.body!!, at))
    assertEquals("sha256:$confirmHash", toPlanque.getValue("with"))
    assertEquals("sha256:$keyHash", toKiff.getValue("with"))
    assertEquals("v3", bodyOf(confirmed).getValue("version").jsonPrimitive.content)

    // The approval key signed kiff.requests.v1 over both hashes, sorted.
    val verify = Signature.getInstance("SHA256withECDSA")
    verify.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(signer.approvalPublicKeyDER)))
    verify.update(KiffClient.requestsMessage("t_01", at, confirmHash, keyHash))
    assertTrue(verify.verify(Base64.getDecoder().decode(toKiff.getValue("v3"))))
    assertTrue(String(KiffClient.requestsMessage("t_01", at, "b", "a")).endsWith("\na\nb"))

    // Then the report, as for any step: tafs cloud reads both services before Coach hears "approved".
    assertEquals("confirmed", bodyOf(transport.requestsTo(reportRoute).single()).getValue("result").jsonPrimitive.content)
    assertFalse("the key is kept nowhere in the app's state", m.state.value.toString().contains("test-key-0001"))
  }

  @Test fun aKeyThatDidntConnectNeverHasItsCardConfirmed() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${keyGrant()}]}""")
    transport.answer(askRoute, 200, keyAndCards.replace("DIGEST", digestOf(keyRequest)))
    transport.answer(key, 200, keyGrant("failed"))
    transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"failed: the bank is not connected","session":"s1","run":"r1"}""")
    val m = model()
    coachWithCard(m)
    assertFalse(m.approveCoachWithKey(approvalComponent().copy(draft = "dr_1"), digestOf(keyRequest), "v3", "test-key-0001"))
    assertTrue("no confirm for a key that didn't connect", transport.requestsTo(confirmRoute).isEmpty())
    val report = bodyOf(transport.requestsTo(reportRoute).single())
    assertEquals("not_connected", report.getValue("result").jsonPrimitive.content)
    assertEquals("failed", report.getValue("status").jsonPrimitive.content)
  }

  @Test fun answeredWithTheTwoStepsNothingIsSignedForItAndTheKeyGoesAsBefore() = runTest {
    // tafs cloud with the step off answers the connect step: the phone signs none of it, gives the key, then asks again.
    reads()
    transport.answer(grants, 200, """{"grants":[${keyGrant()}]}""")
    transport.answer(askRoute, 200, """{"outcome":"sign","ask":{"id":"ask_0","step":"connect","message":"tafs.connect.v1|t_01|g1|${digestOf(keyRequest)}","reason":"Connect the bank","planque":{"grant":"g1","body":{"digest":"${digestOf(keyRequest)}"}}}}""")
    transport.answer(askRoute, 200, """{"outcome":"answered","answer":"already approved: the bank is connected","session":"s1","run":"r1"}""")
    transport.answer(key, 200, keyGrant("connected"))
    val m = model()
    coachWithCard(m)
    assertTrue(m.approveCoachWithKey(approvalComponent().copy(draft = "dr_1"), digestOf(keyRequest), "v3", "test-key-0001"))
    val asks = transport.requestsTo(askRoute)
    assertEquals(2, asks.size)
    assertTrue("the first asked for one signature", bodyOf(asks[0]).containsKey("can"))
    assertFalse("the two-step ask doesn't", bodyOf(asks[1]).containsKey("can"))
    assertTrue("the key went as before", fields(transport.requestsTo(key).single().headers.getValue("Planque-Device-Signature")).containsKey("v1"))
    assertEquals("one fingerprint, the key's own", 1, auth.asked.size)
    assertTrue(transport.requestsTo(confirmRoute).isEmpty())
  }

  @Test fun aKeyAndCardsAskForAnotherDraftVersionOrGrantIsNeverSigned() = runTest {
    val wrong = listOf(
      keyAndCards.replace("DIGEST", digestOf(keyRequest)).replace("\"v3\"", "\"v4\""),
      keyAndCards.replace("DIGEST", digestOf(keyRequest)).replace("\"dr_1\"", "\"dr_2\""),
      keyAndCards.replace("DIGEST", digestOf(keyRequest)).replace("\"grant\":\"g1\"", "\"grant\":\"g2\""),
      keyAndCards.replace("DIGEST", "0".repeat(64)),
      keyAndCards.replace("DIGEST", digestOf(keyRequest)).replace("kiff.requests.v1", "tafs.setup.v1"),
      keyAndCards.replace("DIGEST", digestOf(keyRequest)).replace("\"message\":\"\"", "\"message\":\"tafs.cards.v1|t_01|dr_1|v3\""),
    )
    for (answer in wrong) {
      transport.reset()
      auth.asked.clear()
      reads()
      transport.answer(grants, 200, """{"grants":[${keyGrant()}]}""")
      transport.answer(askRoute, 200, answer)
      val m = model()
      coachWithCard(m)
      assertFalse(answer, m.approveCoachWithKey(approvalComponent().copy(draft = "dr_1"), digestOf(keyRequest), "v3", "test-key-0001"))
      assertTrue(answer, auth.asked.isEmpty())
      assertTrue(answer, transport.requestsTo(key).isEmpty())
      assertTrue(answer, transport.requestsTo(confirmRoute).isEmpty())
    }
  }

  @Test fun aCardWithNoDraftNeverAsksForOneSignature() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${keyGrant()}]}""")
    transport.answer(key, 200, keyGrant("connected"))
    transport.answer(askRoute, 200, """{"outcome":"answered","answer":"already approved: the bank is connected","session":"s1","run":"r1"}""")
    val m = model()
    coachWithCard(m)
    assertTrue(m.approveCoachWithKey(approvalComponent(), digestOf(keyRequest), null, "test-key-0001"))
    assertFalse(bodyOf(transport.requestsTo(askRoute).single()).containsKey("can"))
  }

  // MARK: The secure connection sheet (docs/secure-connection.md)

  private val sheetRoute = "GET /v1/connect/grants/g1/sheet"
  private val sheet = """{"vendor":{"name":"Qonto","host":"thirdparty.qonto.com"},"title":"Secure connection to Qonto",
    "lines":[{"kind":"reads_only","text":"Read only: it can't pay, send or change anything in Qonto"},
             {"kind":"sealed","text":"Your key is sealed"},{"kind":"disconnect","text":"You can disconnect it any time"}],
    "paste":{"button":"Paste the key","what":"Paste the API key you created in Qonto","pasted":"Key …{last4}","cleared":"Clipboard cleared"},
    "details":{"title":"Details","lines":[{"label":"Header","value":"Authorization"}]},
    "legal":{"text":"By connecting, you agree to the {terms} and the {privacy}.","terms":{"words":"Terms","url":"https://kiff.dev/terms"},
             "privacy":{"words":"Privacy Policy","url":"https://kiff.dev/privacy"}},
    "button":"Connect","receipt":{"text":"Qonto connected · key …{last4}","disconnect":"Disconnect"}}"""

  @Test fun theSecureSheetsWordsAreTafsCloudsReadOncePerGrant() = runTest {
    reads()
    transport.answer(sheetRoute, 200, sheet)
    val m = model()
    m.loadSecureSheet("g1")
    m.loadSecureSheet("g1")
    assertEquals("read once", 1, transport.requestsTo(sheetRoute).size)
    assertTrue("read as this phone", transport.requestsTo(sheetRoute).single().headers["Planque-Device-Signature"] != null)
    val words = m.state.value.secureSheets.getValue("g1")
    assertEquals("Secure connection to Qonto", words.title)
    assertEquals(listOf("reads_only", "sealed", "disconnect"), words.lines.map { it.kind })
    assertEquals("Paste the key", words.paste.button)
  }

  @Test fun aSheetThatCantBeReadSaysWhyAndTheSheetStaysAsBefore() = runTest {
    reads()
    transport.answer(sheetRoute, 404, """{"error":"not_found"}""")
    val m = model()
    m.loadSecureSheet("g1")
    assertNull(m.state.value.secureSheets["g1"])
    assertTrue(m.state.value.secureSheetErrors["g1"] != null)
  }

  @Test fun afterAPasteTheSheetSaysWhatToDoInTafsCloudsWordsAndConnectIsRightUnderTheKey() {
    val next = KiffClient.json.decodeFromString<bot.tafs.app.kiff.SecureSheet>(
      sheet.replace(""""cleared":"Clipboard cleared"}""", """"cleared":"Clipboard cleared","next":"Key pasted. Tap Connect."}"""),
    )
    assertEquals("Key pasted. Tap Connect.", next.paste.next)
    assertNull("a tafs cloud from before it sends none, and nothing is drawn", KiffClient.json.decodeFromString<bot.tafs.app.kiff.SecureSheet>(sheet).paste.next)
    // On the sheet, Connect comes right under the pasted key, with the legal line just above it, and Details after it.
    val view = java.io.File("src/main/kotlin/bot/tafs/app/views/SecureKeySheet.kt").readText()
    val connect = view.indexOf("{ actions.give(grant, key) }")
    assertTrue(connect > view.indexOf("sheet.paste.next"))
    assertTrue(view.indexOf("sheet.legal?.let") in view.indexOf("sheet.paste.next")..connect)
    assertTrue(connect < view.indexOf("sheet.details?.let"))
  }

  @Test fun onlyTheLast4OfAPastedKeyShowAndTheVendorIsTafsCloudsName() {
    val words = KiffClient.json.decodeFromString<bot.tafs.app.kiff.SecureSheet>(sheet)
    assertEquals("the outer whitespace a paste adds isn't the key's", "sk_live_abcd1234", bot.tafs.app.model.SecureSheetWords.cleaned("  sk_live_abcd1234\n"))
    assertEquals("Key …1234", bot.tafs.app.model.SecureSheetWords.pasted(words, "sk_live_abcd1234"))
    assertEquals("Qonto connected · key …1234", bot.tafs.app.model.SecureSheetWords.receipt(words, "1234"))
    assertEquals("a key under 12 characters shows none of itself", "Key …", bot.tafs.app.model.SecureSheetWords.pasted(words, "abcd-1234"))
  }

  @Test fun withTheSecureSheetOpenTheKeyEndsOnItsReceiptWithTheLast4Only() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${keyGrant()}]}""")
    transport.answer(key, 200, keyGrant("connected"))
    transport.answer(sheetRoute, 200, sheet)
    val m = model()
    m.loadConnectApps()
    m.openReplaceKey(m.state.value.grants.single())
    m.loadSecureSheet("g1")
    assertTrue(m.giveKeyOnSheet("g1", digestOf(keyRequest), "test-key-0001"))
    assertEquals("the sheet stays open on its receipt", "g1", m.state.value.listApproval)
    assertEquals(bot.tafs.app.app.KeyReceipt("g1", "0001"), m.state.value.keyReceipt)
    assertFalse("the key is kept nowhere in the app's state", m.state.value.toString().contains("test-key-0001"))
    m.closeListApproval()
    assertNull("nothing keeps the last 4 after the sheet closes", m.state.value.keyReceipt)
  }

  @Test fun readOnlyHoldsOnlyWhenTheGrantThisPhoneSignsListsReadsAlone() {
    val words = KiffClient.json.decodeFromString<bot.tafs.app.kiff.SecureSheet>(sheet)
    fun grant(tools: String) = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>(
      """{"id":"g1","status":"pending","digest":"d","request":{"kind":"api_key","server":"https://thirdparty.qonto.com","agent":"a","tools":[$tools]}}""",
    )
    assertTrue(bot.tafs.app.model.SecureSheetWords.firstLineHolds(words, grant("""{"tool":"list","read_only":true},{"tool":"get","read_only":true}""")))
    assertFalse("a write among them", bot.tafs.app.model.SecureSheetWords.firstLineHolds(words, grant("""{"tool":"list","read_only":true},{"tool":"pay","read_only":false}""")))
    assertFalse("a tool with no flag", bot.tafs.app.model.SecureSheetWords.firstLineHolds(words, grant("""{"tool":"list","read_only":true},{"tool":"pay"}""")))
    assertFalse("no tools at all", bot.tafs.app.model.SecureSheetWords.firstLineHolds(words, grant("")))
    val mayChange = words.copy(lines = words.lines.map { if (it.kind == "reads_only") it.copy(kind = "may_change") else it })
    assertTrue("a sheet that says it may change things claims nothing to check", bot.tafs.app.model.SecureSheetWords.firstLineHolds(mayChange, grant("")))
  }

  @Test fun theSheetHasNoTypingFieldAndClearsTheClipboardOnPaste() {
    val view = java.io.File("src/main/kotlin/bot/tafs/app/views/SecureKeySheet.kt").readText()
    assertFalse("never a typing field", view.contains("TextField"))
    assertTrue("the clipboard is cleared once read", view.contains("clearPrimaryClip()"))
    assertTrue("only the last 4 are drawn", view.contains("SecureSheetWords.pasted(sheet, key)"))
  }

  @Test fun anApprovalWhoseConnectionIsDoneShowsItsLineNotTheConnectionAgain() {
    val c = KiffClient.json.decodeFromString<SetupComponent>(
      """{"type":"approval","id":"a2","grant":"g1","draft":"dr_1","connection":{"done":true,"words":"Qonto is connected (read only)"}}""",
    )
    assertEquals("Qonto is connected (read only)", c.connection!!.words)
    val views = java.io.File("src/main/kotlin/bot/tafs/app/views/CoachViews.kt").readText().replace("\r", "")
    val card = views.substring(views.indexOf("private fun ApprovalCard("))
    assertTrue("planque's text only while not done", card.contains("if (done == null) {\n          Text(grant.text"))
  }

  /** The key sheet says the host the key is used with: the server's, never the vendor's name. */
  @Test fun theKeySheetNamesTheServersHostNotTheVendorsName() {
    fun grant(server: String) = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>(
      """{"id":"g1","status":"pending","vendor":"Qonto","digest":"d","request":{"kind":"api_key","server":"$server","agent":"a"}}""",
    )
    assertEquals("thirdparty.qonto.com", grant("https://thirdparty.qonto.com/v2").keyHost)
    assertEquals("api.example-bank.test", grant("api.example-bank.test").keyHost)
    assertEquals("Qonto", grant("https://thirdparty.qonto.com/v2").vendor)
  }

  /** The chat never takes a secret: Coach's approval card has no text field at all, so no key can be typed into it. */
  @Test fun coachsApprovalCardHasNoFieldForAKey() {
    val views = java.io.File("src/main/kotlin/bot/tafs/app/views/CoachViews.kt").readText()
    val start = views.indexOf("private fun ApprovalCard(")
    val end = views.indexOf("\nprivate fun ", start + 1).takeIf { it > 0 } ?: views.length
    val card = views.substring(start, end)
    assertTrue(start >= 0)
    assertFalse("no text field on Coach's card", card.contains("TextField("))
    assertFalse("nothing marked secret on Coach's card", card.contains(".secret()"))
    assertTrue("it opens the key sheet instead", card.contains("actions.openKeySheet("))
  }

  @Test fun aKeyNotConfirmedOnCoachsCardGivesNothingAndAnswersNothing() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${keyGrant()}]}""")
    val m = model()
    coachWithCard(m)
    auth.confirms = false
    assertFalse(m.approveCoachWithKey(approvalComponent(), digestOf(keyRequest), null, "test-key-1"))
    assertTrue(transport.requestsTo(key).isEmpty())
    assertTrue("the card isn't answered", transport.requestsTo(askRoute).isEmpty())
    assertEquals("Not done: it wasn't confirmed.", m.state.value.coachNotes[bot.tafs.app.model.ConnectWords.cardKey("g1")])
  }

  @Test fun disconnectingConfirmsThenDeletesThenReadsAgain() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant(status = "connected")}]}""")
    transport.answer("DELETE /v1/accounts/t_01/grants/g1", 200, grant("g1", "disconnected"))
    val m = model()
    m.refreshConnections()
    assertTrue(m.disconnect("g1"))
    assertEquals("no taf listed: the question names the vendor alone", listOf("Remove access to mcp.stripe.com?"), auth.asked)
    assertEquals(1, transport.requestsTo("DELETE /v1/accounts/t_01/grants/g1").size)
    assertTrue("read again", transport.requestsTo(grants).size >= 2)
    auth.confirms = false
    assertFalse(m.disconnect("g1"))
    assertEquals(1, transport.requestsTo("DELETE /v1/accounts/t_01/grants/g1").size)
  }

  // MARK: Plain words for ending access (the owner, 12 Nov: "connection" and "disconnect" assume too much)

  @Test fun aRequestNotAnsweredIsCancelledAndASignInNeverBackTooElseAccessIsRemoved() {
    fun g(status: String, extra: String = "") = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>(grant("g1", status, extra = ",\"vendor\":\"Stripe\"$extra"))
    val words = bot.tafs.app.model.ConnectionWords
    val pending = g("pending")
    assertTrue(pending.cancellable)
    assertEquals("Cancel the request", words.endButton(pending))
    assertEquals("Cancel the request", words.cancelButton(pending))
    assertEquals("Cancel the request for Stripe?", words.cancelTitle(pending))
    val waiting = g("approved", ",\"signed_in\":false")
    assertTrue(waiting.waitingSignIn && waiting.cancellable)
    assertEquals("Cancel the sign-in to Stripe?", words.cancelTitle(waiting))
    val connected = g("connected")
    assertFalse(connected.cancellable)
    assertEquals("Remove access", words.endButton(connected))
    // Pending, but holding a sign-in already, or connected by this phone since: not a request to cancel (as iOS reads it).
    assertFalse(g("pending", ",\"signed_in\":true").pendingRequest)
    assertFalse(pending.copy(connectedNow = true).pendingRequest)
    listOf("approved", "failed", "disconnected", "listed").forEach { assertFalse(it, g(it).pendingRequest) }
  }

  @Test fun removingAKeysAccessSaysTheKeyIsDeletedFromTafsNeverRevokedAtTheVendor() {
    val words = bot.tafs.app.model.ConnectionWords
    val key = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>(keyGrant("connected"))
    assertTrue(key.takesKey)
    val body = words.disconnectBody(key, "Overdue invoices")
    // Deleted once no taf uses it: a key another taf's connection shares stays for that taf.
    assertTrue(body, body.contains("The key is deleted from tafs once no taf uses it."))
    assertFalse(body, body.contains("The key is deleted from tafs."))
    assertFalse(body, body.contains("revoked"))
    assertFalse(body, body.contains("leave KIFF"))
    // A sign-in, by contrast, is revoked at the vendor when nothing else uses it.
    val signIn = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>(grant("g1", "connected", extra = ",\"vendor\":\"Stripe\""))
    assertTrue(words.disconnectBody(signIn, null).contains("it's revoked at Stripe"))
  }

  // MARK: A removed taf takes its connections with it (the owner, 5 Nov: they stayed connected and Coach offered them again)

  @Test fun theRemoveQuestionSaysWhatGoesWithItInTafsCloudsWordsBeforeTheFingerprint() = runTest {
    reads()
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"working","status":"active","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""",
    )
    transport.answer("GET /v1/tafs/taf_1/removal", 200, """{"taf":"taf_1","goes":["Qonto"],"stays":[{"app":"Stripe","used_by":["Chargebacks"]}],"words":"Qonto goes with it. Stripe stays: Chargebacks uses it."}""")
    val m = model()
    m.refresh()
    assertEquals("Qonto goes with it. Stripe stays: Chargebacks uses it.", m.removalWords("refunds-1"))
    assertTrue("read only: nothing is asked, ended or removed", auth.asked.isEmpty())
    assertTrue(transport.requests.none { it.method == "DELETE" || it.method == "POST" && it.url.contains("/v1/tafs/") })
    // A tafs cloud from before the route, or with nothing to say: the question says only that the taf goes.
    transport.answer("GET /v1/tafs/taf_1/removal", 404, """{"error":"not_found"}""")
    assertNull(m.removalWords("refunds-1"))
  }

  private val halfMade = """{"tafs":[],"others":[],"removing":[],"setting_up":[{"name":"Invoicing day test","kiff_agent":"inv-1","color":2,""" +
    """"discard":{"cards":["c9"],"words":{"button":"Discard","title":"Discard this setup?","text":"Its Card is revoked and the chat that set it up ends. Nothing runs.","yes":"Discard","no":"Keep it"}}}]}"""

  @Test fun discardingATafBeingSetUpRevokesItsCardsThenAsksTafsCloudSignedAndReadsHomeAgain() = runTest {
    reads()
    transport.answer("GET /v1/home", 200, halfMade)
    transport.answer("POST /v1/me/mandates/c9/revoke", 200)
    transport.answer("POST /v1/home/setting-up/inv-1/discard", 200, """{"discarded":true,"kiff_agent":"inv-1"}""")
    val m = model()
    m.refresh()
    assertEquals("Discard", m.state.value.settingUp.single().discard?.words?.button)
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[],"setting_up":[]}""")
    assertTrue(m.discardSetup("inv-1"))
    // The fingerprint first, under tafs cloud's question; then the Card at KIFF; then tafs cloud, signed as a removal is.
    assertEquals(listOf("Discard this setup?"), auth.asked)
    val revoke = transport.requests.indexOfFirst { it.url.endsWith("/v1/me/mandates/c9/revoke") }
    val discard = transport.requests.indexOfFirst { it.url.endsWith("/v1/home/setting-up/inv-1/discard") }
    assertTrue("the Card is revoked before tafs cloud is asked", revoke in 0 until discard)
    assertTrue(transport.requests[discard].headers.getValue("KIFF-Device-Signature").contains(",v1="))
    assertTrue("Home is read again, and lists it no more", m.state.value.settingUp.isEmpty())
  }

  @Test fun aCardKiffDidntRevokeIsSaidWithTafsCloudsRefusal() = runTest {
    reads()
    transport.answer("GET /v1/home", 200, halfMade)
    transport.answer("POST /v1/me/mandates/c9/revoke", 500, """{"error":"internal"}""")
    // tafs cloud checks KIFF itself and refuses while a Card is in force: it decides, and the phone says both.
    transport.answer("POST /v1/home/setting-up/inv-1/discard", 409, """{"error":"1 Card still in force on this agent: revoke it first","code":"cards_in_force","cards":["c9"]}""")
    val m = model()
    m.refresh()
    assertFalse(m.discardSetup("inv-1"))
    assertEquals(1, transport.requestsTo("POST /v1/home/setting-up/inv-1/discard").size)
    val said = m.state.value.lastError!!
    assertTrue(said, said.startsWith(Words.Taf.Remove.cardsLeft(count = 1)))
    assertEquals("still listed until tafs cloud drops it", 1, m.state.value.settingUp.size)
  }

  @Test fun aCardOnlyTafsCloudsRefusalNamesIsRevokedOnTheNextTry() = runTest {
    reads()
    transport.answer("GET /v1/home", 200, halfMade)
    transport.answer("POST /v1/me/mandates/c9/revoke", 200)
    // A Card confirmed after Home was read: only the refusal names it.
    transport.answer("POST /v1/home/setting-up/inv-1/discard", 409, """{"error":"1 Card still in force on this agent: revoke it first","code":"cards_in_force","cards":["c10"]}""")
    val m = model()
    m.refresh()
    val homeReads = transport.requestsTo("GET /v1/home").size
    assertFalse(m.discardSetup("inv-1"))
    assertTrue("Home is read again after the refusal", transport.requestsTo("GET /v1/home").size > homeReads)
    assertTrue("no second ask on the same signature", transport.requestsTo("POST /v1/home/setting-up/inv-1/discard").size == 1)
    // The next tap revokes the Card the refusal named, then asks again with its own fingerprint.
    transport.answer("POST /v1/me/mandates/c10/revoke", 200)
    transport.answer("POST /v1/home/setting-up/inv-1/discard", 200, """{"discarded":true,"kiff_agent":"inv-1"}""")
    assertTrue(m.discardSetup("inv-1"))
    assertEquals(1, transport.requestsTo("POST /v1/me/mandates/c10/revoke").size)
    assertEquals(2, auth.asked.size)
  }

  @Test fun onceTafsCloudDropsItNoCardIsSaidLeftEvenIfARevokeFailed() = runTest {
    reads()
    transport.answer("GET /v1/home", 200, halfMade)
    // Already revoked on an earlier try: KIFF refuses the revoke, and tafs cloud, which checks, drops it.
    transport.answer("POST /v1/me/mandates/c9/revoke", 409, """{"error":"already revoked"}""")
    transport.answer("POST /v1/home/setting-up/inv-1/discard", 200, """{"discarded":true,"kiff_agent":"inv-1"}""")
    val m = model()
    m.refresh()
    assertTrue(m.discardSetup("inv-1"))
    assertNull(m.state.value.lastError)
  }

  @Test fun withoutTafsCloudsDiscardNothingIsAskedOrSent() = runTest {
    reads()
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[],"setting_up":[{"name":"Invoicing day test","kiff_agent":"inv-1","color":2}]}""")
    val m = model()
    m.refresh()
    assertNull(m.state.value.settingUp.single().discard)
    assertFalse(m.discardSetup("inv-1"))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requests.none { it.method == "POST" && (it.url.contains("/discard") || it.url.contains("/revoke")) })
  }

  @Test fun removingATafLeavesItsConnectionsToTafsCloudUnderTheSameConfirmation() = runTest {
    reads()
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"working","status":"active","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer(grants, 200, """{"grants":[${grant("g1", "connected")}]}""")
    transport.answer("POST /v1/me/mandates/c1/revoke", 200)
    transport.answer("DELETE /v1/tafs/taf_1", 200, """{"done":true,"removed":["schedule","connections"],"failed":[]}""")
    val m = model()
    m.refresh()
    m.refreshConnections()
    val words = m.removeTaf("refunds-1")!!
    // One confirmation, the removal's. tafs cloud ends the connections; the phone sends none of its own.
    assertEquals(listOf("Remove Refunds for good"), auth.asked)
    assertTrue("no disconnect from the phone", transport.requests.none { it.method == "DELETE" && it.url.contains("/grants/") })
    assertEquals(1, transport.requestsTo("DELETE /v1/tafs/taf_1").size)
    assertTrue(words, words.startsWith("Its Cards are revoked in KIFF. Its access to apps is removed."))
  }

  @Test fun connectionsTafsCloudCouldntEndAreNamedAndTheRemovalTriesAgain() = runTest {
    reads()
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"working","status":"active","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("POST /v1/me/mandates/c1/revoke", 200)
    transport.answer("DELETE /v1/tafs/taf_1", 200, """{"done":false,"removed":["schedule"],"failed":["connections"]}""")
    val m = model()
    m.refresh()
    val words = m.removeTaf("refunds-1")!!
    assertTrue(words, words.contains("Not finished: its access to apps couldn't be removed."))
    assertFalse(words, words.contains("Its access to apps is removed."))
  }

  @Test fun aRemovalThatEndedNoConnectionSaysNothingOfThem() = runTest {
    reads()
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"working","status":"active","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("POST /v1/me/mandates/c1/revoke", 200)
    transport.answer("DELETE /v1/tafs/taf_1", 200, """{"done":true,"removed":["schedule"],"failed":[]}""")
    val m = model()
    m.refresh()
    val words = m.removeTaf("refunds-1")!!
    assertFalse(words, words.contains("access to apps"))
  }

  /** Refunds asked for g1; Chargebacks, when listed, draws on its refund tool through its own Card. */
  private fun sharedHome(withChargebacks: Boolean) =
    """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"working","status":"active","card":{"ids":["c1"],"active":true}}""" +
      (if (withChargebacks) "," + """{"id":"taf_2","name":"Chargebacks","kiff_agent":"chargebacks-1","color":2,"state":"working","status":"active",""" +
        """"card":{"ids":["c2"],"active":true,"rules":[{"action":"create_refund_repg","label":"Refund a payment","outside":"hold","window":"day"}]}}""" else "") +
      """],"others":[],"removing":[]}"""

  @Test fun aConnectedAppListsItsConnectionsEachWithItsTafOrNone() = runTest {
    reads()
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"working","status":"active","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""",
    )
    val gone = """{"server":"","agent":"invoice-reminders-x","tools":[]}"""
    transport.answer(
      grants, 200,
      """{"grants":[${grant("g1", "connected", extra = ",\"entry\":\"connector:composio:stripe\"")},{"id":"g4","status":"connected","text":"","digest":"","entry":"connector:composio:stripe","request":$gone,"events":[]},{"id":"g5","status":"disconnected","text":"","digest":"","entry":"connector:composio:stripe","request":$gone,"events":[]}]}""",
    )
    val m = model()
    m.refresh()
    m.refreshConnections()
    val app = bot.tafs.app.kiff.ConnectApps.App(entry = "connector:composio:stripe", title = "Stripe", state = "connected")
    val listed = bot.tafs.app.views.connectionsOf(m.state.value, app)
    // The live ones: Refunds', and one whose taf is gone (no taf uses it); a disconnected one isn't listed.
    assertEquals(listOf("g1", "g4"), listed.map { it.id })
    assertEquals("Refunds", m.state.value.tafs.firstOrNull { it.id == listed[0].request.agent }?.name)
    assertNull(m.state.value.tafs.firstOrNull { it.id == listed[1].request.agent })
  }

  /** Disconnect names the one taf it ends, says other tafs' connections stay, and the fingerprint prompt asks the same. */
  @Test fun disconnectNamesTheOneTafItEndsInTheConfirmAndThePrompt() = runTest {
    val g = KiffClient.json.decodeFromString<bot.tafs.app.kiff.Grant>(grant("g1", "connected", extra = ",\"vendor\":\"Stripe\""))
    assertEquals("Remove access to Stripe for Invoice reminders?", bot.tafs.app.model.ConnectionWords.disconnectTitle(g, "Invoice reminders"))
    assertEquals("Remove access to Stripe?", bot.tafs.app.model.ConnectionWords.disconnectTitle(g, null))
    val body = bot.tafs.app.model.ConnectionWords.disconnectBody(g, "Invoice reminders")
    assertTrue(body, body.startsWith("Invoice reminders' Stripe tools stop working.") && body.contains("Other tafs that use Stripe keep their access."))
    // Another taf whose Card draws on these tools stops too, and is named (qa-kiff on tafs#546).
    assertEquals("Remove access to Stripe for Refunds? Chargebacks stops too.", bot.tafs.app.model.ConnectionWords.disconnectTitle(g, "Refunds", listOf("Chargebacks")))
    val shared = bot.tafs.app.model.ConnectionWords.disconnectBody(g, "Refunds", listOf("Chargebacks"))
    assertTrue(shared, shared.contains("Chargebacks' Card also uses these tools, so it stops too."))
    assertEquals("Coach's", bot.tafs.app.model.Words.possessive("Coach"))
    assertTrue(bot.tafs.app.model.ConnectionWords.disconnectBody(g, null).startsWith("No taf uses Stripe now."))
    // The fingerprint prompt: the taf the connection was set up for, by name.
    reads()
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"working","status":"active","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""",
    )
    transport.answer(grants, 200, """{"grants":[${grant("g1", "connected", extra = ",\"vendor\":\"Stripe\"")}]}""")
    transport.answer("DELETE /v1/accounts/t_01/grants/g1", 200, grant("g1", "disconnected"))
    val m = model()
    m.refresh()
    m.refreshConnections()
    assertTrue(m.disconnect("g1"))
    assertEquals(listOf("Remove access to Stripe for Refunds?"), auth.asked)
  }

  /** The fingerprint prompt names another taf whose Card draws on the connection's tools: it stops too. */
  @Test fun disconnectsPromptNamesAnotherTafWhoseCardUsesTheTools() = runTest {
    reads()
    transport.answer("GET /v1/home", 200, sharedHome(withChargebacks = true))
    transport.answer(grants, 200, """{"grants":[${grant("g1", "connected", extra = ",\"vendor\":\"Stripe\"")}]}""")
    transport.answer("DELETE /v1/accounts/t_01/grants/g1", 200, grant("g1", "disconnected"))
    val m = model()
    m.refresh()
    m.refreshConnections()
    assertTrue(m.disconnect("g1"))
    assertEquals(listOf("Remove access to Stripe for Refunds? Chargebacks stops too."), auth.asked)
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

  @Test fun aViewOnPlanquesHostButAnotherPortIsNeverOpened() = runTest {
    val m = slackModel()
    transport.answer(signInRoute, 200, """{"view":"https://planque.kiff.dev:8443/browser/view/tok_1","words":"Sign in"}""")
    m.openSiteSignIn("gs")
    assertNull(m.state.value.siteSignIn)
    assertTrue(bot.tafs.app.views.sameHttpsOrigin("https://planque.kiff.dev/browser/view/t", "https://planque.kiff.dev:443"))
    assertFalse(bot.tafs.app.views.sameHttpsOrigin("http://planque.kiff.dev/browser/view/t", "https://planque.kiff.dev"))
    assertFalse(bot.tafs.app.views.sameHttpsOrigin("https://planque.kiff.dev.example/x", "https://planque.kiff.dev"))
  }

  // MARK: The approval window (kiff/tafs#494)

  private val win = "win_abcdefghijklmnopqrstuvwxyz"
  private val windowOpened = """{"window":{"id":"$win","expires":"2026-10-28T09:05:00Z","open":true,"words":"No fingerprint for the next 5 minutes"}}"""

  /** tafs cloud's connection ask, with its hint that the call takes a window. */
  private fun signConnectInWindow() = signConnect().replace(""""reason":"Connect Stripe to Refunds",""", """"reason":"Connect Stripe to Refunds","key":"window",""")

  @Test fun aWindowAskOpensAWindowWithOneFingerprintAndTheConnectionGoesAsATap() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(askRoute, 200, signConnectInWindow())
    transport.answer("POST /v1/me/window", 201, windowOpened)
    transport.answer(approve, 200, """{"status":"connected"}""")
    transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"approved","session":"s1","run":"r1"}""")
    transport.answer("GET /v1/me/window", 200, windowOpened.replace("5 minutes", "4 minutes"))
    val m = model()
    coachWithCard(m)
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[],"components":[]}""")
    m.approveCoach(approvalComponent(), digest, null)
    // One fingerprint, the window's, in tafs cloud's words: the connection's own prompt isn't asked.
    assertEquals(listOf("Connect Stripe to Refunds"), auth.asked)
    val opening = transport.requestsTo("POST /v1/me/window").single()
    assertEquals("kiff.window.v1", bodyOf(opening).getValue("message").jsonPrimitive.content)
    assertTrue(opening.headers.getValue("KIFF-Device-Signature").contains(",v1="))
    // planque's approve goes as a tap: the device key, with the window's id.
    val header = transport.requestsTo(approve).single().headers.getValue("Planque-Device-Signature")
    assertTrue(header, header.contains(",v2=") && header.contains(",w=$win,"))
    // The report is signed as it always was (v1), and KIFF's words for the next tap are read again.
    assertTrue(transport.requestsTo(reportRoute).single().headers.getValue("KIFF-Device-Signature").contains(",v1="))
    assertEquals("No fingerprint for the next 4 minutes", m.state.value.windowWords)
  }

  @Test fun aSecondWindowAskInsideTheWindowAsksNoFingerprint() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    repeat(2) {
      transport.answer(askRoute, 200, signConnectInWindow())
      transport.answer(approve, 200, """{"status":"connected"}""")
      transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"approved","session":"s1","run":"r1"}""")
      transport.answer("GET /v1/me/window", 200, windowOpened)
    }
    transport.answer("POST /v1/me/window", 201, windowOpened)
    val m = model()
    coachWithCard(m)
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[],"components":[]}""")
    m.approveCoach(approvalComponent(), digest, null)
    m.approveCoach(approvalComponent(), digest, null)
    assertEquals(1, auth.asked.size)
    assertEquals(1, transport.requestsTo("POST /v1/me/window").size)
    assertEquals(2, transport.requestsTo(approve).count { it.headers.getValue("Planque-Device-Signature").contains(",w=$win,") })
  }

  @Test fun planqueSayingTheWindowEndedOpensANewOneAndTriesOnceMore() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(askRoute, 200, signConnectInWindow())
    transport.answer("POST /v1/me/window", 201, windowOpened)
    transport.answer("POST /v1/me/window", 201, windowOpened)
    transport.answer(approve, 403, """{"error":"Your approval window has ended.","code":"window_required"}""")
    transport.answer(approve, 200, """{"status":"connected"}""")
    transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"approved","session":"s1","run":"r1"}""")
    transport.answer("GET /v1/me/window", 200, windowOpened)
    val m = model()
    coachWithCard(m)
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[],"components":[]}""")
    m.approveCoach(approvalComponent(), digest, null)
    assertEquals(2, transport.requestsTo("POST /v1/me/window").size)
    assertEquals(2, auth.asked.size)
    assertEquals(2, transport.requestsTo(approve).size)
    assertEquals("connected", bodyOf(transport.requestsTo(reportRoute).single()).getValue("result").jsonPrimitive.content)
  }

  @Test fun aKiffWithoutWindowsLeavesTheStepAsItWas() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(askRoute, 200, signConnectInWindow())
    transport.answer("POST /v1/me/window", 404, """{"error":"not_found"}""")
    transport.answer(approve, 200, """{"status":"connected"}""")
    transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"approved","session":"s1","run":"r1"}""")
    val m = model()
    coachWithCard(m)
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[],"components":[]}""")
    m.approveCoach(approvalComponent(), digest, null)
    val header = transport.requestsTo(approve).single().headers.getValue("Planque-Device-Signature")
    assertTrue(header, header.contains(",v1=") && !header.contains(",w="))
    assertNull(m.state.value.windowWords)
  }

  @Test fun anAskWithNoHintOpensNoWindow() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(askRoute, 200, signConnect())
    transport.answer(approve, 200, """{"status":"connected"}""")
    transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"approved","session":"s1","run":"r1"}""")
    val m = model()
    coachWithCard(m)
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[],"components":[]}""")
    m.approveCoach(approvalComponent(), digest, null)
    assertTrue(transport.requestsTo("POST /v1/me/window").isEmpty())
  }

  @Test fun theWindowClosesAfterThirtySecondsInTheBackgroundAndAtOnceWhenTheScreenTurnsOff() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    repeat(2) {
      transport.answer(askRoute, 200, signConnectInWindow())
      transport.answer("POST /v1/me/window", 201, windowOpened)
      transport.answer(approve, 200, """{"status":"connected"}""")
      transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"approved","session":"s1","run":"r1"}""")
      transport.answer("GET /v1/me/window", 200, windowOpened)
      transport.answer("POST /v1/me/window/$win/close", 200, """{"window":{"id":"$win","open":false}}""")
    }
    val m = model(scope = backgroundScope)
    coachWithCard(m)
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[],"components":[]}""")
    m.approveCoach(approvalComponent(), digest, null)
    // Back within 30 seconds: it stays.
    m.wentToBackground()
    testScheduler.advanceTimeBy(20_000)
    m.cameToForeground()
    testScheduler.advanceTimeBy(60_000)
    assertTrue(transport.requestsTo("POST /v1/me/window/$win/close").isEmpty())
    // Away for longer: closed, device-signed, and its words gone.
    m.wentToBackground()
    testScheduler.advanceTimeBy(31_000)
    testScheduler.runCurrent()
    val closed = transport.requestsTo("POST /v1/me/window/$win/close").single()
    assertTrue(closed.headers.getValue("KIFF-Device-Signature").contains(",v1="))
    assertNull(m.state.value.windowWords)
    // A new window, then the screen turns off: closed at once.
    m.approveCoach(approvalComponent(), digest, null)
    m.screenLocked()
    testScheduler.runCurrent()
    assertEquals(2, transport.requestsTo("POST /v1/me/window/$win/close").size)
  }

  @Test fun aNewSignInForgetsTheLastSessionsWindow() = runTest {
    reads()
    transport.answer(grants, 200, """{"grants":[${grant()}]}""")
    transport.answer(askRoute, 200, signConnectInWindow())
    transport.answer("POST /v1/me/window", 201, windowOpened)
    transport.answer(approve, 200, """{"status":"connected"}""")
    transport.answer(reportRoute, 200, """{"outcome":"answered","answer":"approved","session":"s1","run":"r1"}""")
    transport.answer("GET /v1/me/window", 200, windowOpened)
    transport.answer("POST /v1/me/window/$win/close", 200, """{"window":{"id":"$win","open":false}}""")
    val m = model(scope = backgroundScope)
    coachWithCard(m)
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[],"components":[]}""")
    m.approveCoach(approvalComponent(), digest, null)
    assertEquals("No fingerprint for the next 5 minutes", m.state.value.windowWords)
    reads()
    runCatching { m.useSession("at_2", "t_02") }
    assertNull(m.state.value.windowWords)
    // Nothing of the last session's window is closed or signed in the new one.
    m.screenLocked()
    testScheduler.runCurrent()
    assertTrue(transport.requestsTo("POST /v1/me/window/$win/close").isEmpty())
  }

  @Test fun aVendorThatRefusesPlanquesClientIsSaidInPlanquesWords() = runTest {
    val words = "mcp.vendor.example doesn't allow tafs to sign in yet, so nothing was connected. It isn't something you can change from here."
    transport.answer("GET /v1/accounts/t_01/grants", 502, """{"error":"$words","code":"vendor_refused_client"}""")
    try {
      planque().grants(); fail("refused")
    } catch (e: PlanqueError.Server) {
      assertEquals("vendor_refused_client", e.code)
      // planque's words, which say whose it is, not "it isn't working on our side".
      assertEquals(words, bot.tafs.app.model.ErrorWords.of(e))
    }
    // Any other 502 is still the app's line for a service that's down.
    transport.answer("GET /v1/accounts/t_01/grants", 502, """{"error":"upstream timeout"}""")
    try {
      planque().grants(); fail("refused")
    } catch (e: PlanqueError.Server) {
      assertEquals(null, e.code)
      assertEquals(bot.tafs.app.model.ErrorWords.DOWN, bot.tafs.app.model.ErrorWords.of(e))
    }
  }

  // The signed text in the person's language (docs/android-app.md › Connect apps): with digest_v 2 the digest covers
  // {"lang", "request", "text"}. The digests below were computed apart (SHA-256 of the JCS bytes), not by this app.
  private val v2Request =
    """{"agent":"books","asked_by":"setup-agent","created_at":"2026-11-11T16:00:00Z","grant":"g_02","server":"https://mcp.vendor.example/mcp","tenant":"t_01","tools":[{"read_only":true,"tool":"list_invoices"}],"v":"planque.grant.v1"}"""
  private val v2Text = "books pourra lire vos factures sur mcp.vendor.example"
  private val v1Digest = "8aff552abc7aa96bf12361f93dabacf39fd0f70a32d31ba3397e83b8642547b5"
  private val v2DigestFr = "b82a762ea92a268da2c458a10b797cc58e09c2b67238c04d0fb5b06e05adca64"

  private fun v2Grant(digest: String, lang: String? = "fr", version: String? = "2", text: String = v2Text) =
    """{"id":"g_02","status":"pending","text":"$text",""" + (lang?.let { "\"lang\":\"$it\"," } ?: "") +
      (version?.let { "\"digest_v\":$it," } ?: "") + """"digest":"$digest","request":$v2Request,"audience":"https://planque.kiff.dev/mcp/t_01/g_02","events":[]}"""

  private suspend fun readGrant(json: String): bot.tafs.app.kiff.Grant {
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[$json]}""")
    return planque().grants().single()
  }

  @Test fun aV2GrantsDigestCoversItsWordsAndTheirLanguage() = runTest {
    assertTrue(readGrant(v2Grant(v2DigestFr)).digestMatches)
    // One word changed, or the language relabelled: it no longer matches, so nothing is signed.
    assertFalse(readGrant(v2Grant(v2DigestFr, text = "books pourra payer vos factures sur mcp.vendor.example")).digestMatches)
    assertFalse(readGrant(v2Grant(v2DigestFr, lang = "en")).digestMatches)
    // The v2 digest of the same request alone isn't accepted as v2, nor v1's as v2.
    assertFalse(readGrant(v2Grant(v1Digest)).digestMatches)
  }

  @Test fun withoutDigestVTheRequestAloneAsBefore() = runTest {
    assertTrue(readGrant(v2Grant(v1Digest, lang = null, version = null)).digestMatches)
    // A grant with its words in a language but no digest_v is still v1: the words aren't what it covers.
    assertTrue(readGrant(v2Grant(v1Digest, lang = "fr", version = null)).digestMatches)
  }

  @Test fun anUnknownVersionOrAV2GrantWithoutItsLanguageMatchesNothing() = runTest {
    assertFalse(readGrant(v2Grant(v2DigestFr, version = "3")).digestMatches)
    assertFalse(readGrant(v2Grant(v1Digest, version = "3")).digestMatches)
    assertFalse(readGrant(v2Grant(v2DigestFr, lang = null)).digestMatches)
  }

  @Test fun everyRequestToPlanqueSaysThePersonsLanguage() = runTest {
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    planque().grants()
    assertEquals(bot.tafs.app.kiff.KiffClient.screenLanguage(), transport.requests.single().headers["Accept-Language"])
  }

  @Test fun theSharedVectorIsTheSameOnEveryApp() = runTest {
    // The vector planque and iOS pin (kiff/planque#132, kiff/tafs#709), byte for byte: the three agree on what's signed.
    val request = """{"agent":"books","asked_by":"setup-agent","created_at":"2026-11-11T16:00:00Z","grant":"g_02","server":"https://mcp.qonto.com/mcp","tenant":"t_01","tools":[{"read_only":true,"tool":"list_invoices"}],"v":"planque.grant.v1"}"""
    val grant = """{"id":"g_02","status":"pending","text":"books pourra lire vos factures sur mcp.qonto.com","lang":"fr","digest_v":2,""" +
      """"digest":"f1ca3ab371ecf97a82d134204e4931ef39ea474b7cd6a3e233b36f97499fbb32","request":$request,"audience":"https://planque.kiff.dev/mcp/t_01/g_02","events":[]}"""
    assertTrue(readGrant(grant).digestMatches)
  }
}
