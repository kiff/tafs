package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.VendorSignIn
import bot.tafs.app.kiff.VendorSignInCancelled
import bot.tafs.app.model.CoachAnswer
import bot.tafs.app.model.CoachWords
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Starting Coach on an account, and what its cards hand over: a new taf, a taf's next version, a vendor sign-in. Fakes stand in for KIFF, planque and tafs cloud. */
class CoachStartTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private class FakeSignIn(var back: String? = null, var opened: String? = null) : VendorSignIn {
    override suspend fun signIn(url: String): String { opened = url; return back ?: throw VendorSignInCancelled() }
  }

  private fun reads(home: String = """{"tafs":[],"others":[],"removing":[]}""") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["members.manage","cards.manage"]}""")
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
  }

  private suspend fun model(signIn: VendorSignIn = FakeSignIn()): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false, vendorSignIn = signIn).also { it.restore() }
  }

  private fun body(key: String, i: Int = 0): JsonObject = KiffClient.json.parseToJsonElement(String(transport.requestsTo(key)[i].body!!)).jsonObject

  // MARK: Starting Coach

  /** Coach's tools as tafs cloud answers them: the bodies the phone signs and sends exactly as they come. */
  private val connectBody = """{"connections":[{"name":"tafs_state","url":"https://cloud.tafs.kiff.dev/v1/mcp","tool":"tafs_state","mode":"verify","audience":"https://cloud.tafs.kiff.dev/v1/mcp","control":true,"idempotency_argument":"tafs_op","read_only":true},{"name":"taf_results","url":"https://cloud.tafs.kiff.dev/v1/mcp","tool":"taf_results","mode":"verify","audience":"https://cloud.tafs.kiff.dev/v1/mcp","control":true,"idempotency_argument":"tafs_op","replace":true}]}"""
  private val cardBody = """{"agent_id":"setup-acme","label":"Coach","cards":[{"id":"coach-version-install","subject":"setup-acme","domain":"gateway","on_exceed":"owner","hold_expiry":"10m","grants":[{"action":"version_install"}],"aggregates":[{"quantity":"count","limit":0,"window":"calendar_day"}]}]}"""
  private val toGive =
    """{"state":"to_give","connect":{"body":$connectBody,"lines":["Read your tafs","Read a taf's results · replaces the earlier connection"]},
        "card":{"body":$cardBody,"card_title":"Coach","cards":[{"title":"Install a version","lines":["Always asks you"]},{"id":7}]}}"""
  private val coach = """{"id":"setup_1","name":"Coach","kiff_agent":"setup-acme"}"""

  private fun startAnswers(keys: Pair<Int, String> = 200 to """{"plaintext":"kk_connector"}""", enrol: Pair<Int, String> = 200 to """{"tenant":"t_01","device":"ab12"}""") {
    // Before the start, no Coach; once started, its tools still to give; once given, given.
    transport.answer("GET /v1/setup", 200, """{"setup":null}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$toGive}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":{"state":"given"}}""")
    transport.answer("GET /v1/accounts/t_01/grants", 403, """{"error":"this device is not enrolled"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    transport.answer("POST /v1/keys", keys.first, keys.second)
    transport.answer("POST /v1/accounts/t_01/enrolment", enrol.first, enrol.second)
    transport.answer("POST /v1/me/agent-drafts", 200, """{"id":"dr_setup","status":"confirmed","claim_secret":"cs_1"}""")
    transport.answer("POST /v1/setup", 200, """{"setup":$coach}""")
    transport.answer("POST /v1/connections/batch", 200)
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[]}""")
  }

  @Test fun startingCoachAsksOnceEnrolsWithPlanqueAndStartsCoachFromADraftWithNoTools() = runTest {
    reads()
    startAnswers()
    val m = model()
    m.refreshConnections()
    assertFalse(m.state.value.planqueReady)
    assertTrue(m.startCoach())
    // Yes before anything is made, then a fingerprint for each of Coach's drafts, which grant authority.
    assertEquals(listOf("Connect your tools", "Start Coach", "Give Coach its Card"), auth.asked)
    assertEquals("setup_1", m.state.value.coachCloudId)
    assertEquals("setup-acme", m.state.value.coachAgent)
    assertTrue(m.state.value.planqueReady)
    assertNull(m.state.value.coachStartError)
    // planque keeps the connector key; the phone keeps nothing of it.
    val enrol = body("POST /v1/accounts/t_01/enrolment")
    assertEquals("kk_connector", enrol.getValue("connector_key").jsonPrimitive.content)
    assertEquals(java.util.Base64.getEncoder().encodeToString(signer.publicKeyDER), enrol.getValue("device_key").jsonPrimitive.content)
    assertEquals("connector", body("POST /v1/keys").getValue("roles").jsonArray.single().jsonPrimitive.content)
    // Coach's agent has no tools of a vendor's; tafs cloud claims its key with the secret KIFF showed once.
    val draft = body("POST /v1/me/agent-drafts")
    assertEquals("setup", draft.getValue("agent_id").jsonPrimitive.content)
    assertEquals("true", draft.getValue("no_tools").jsonPrimitive.content)
    val setup = body("POST /v1/setup")
    assertEquals("dr_setup", setup.getValue("draft_id").jsonPrimitive.content)
    assertEquals("cs_1", setup.getValue("claim_secret").jsonPrimitive.content)
    // Each setup call is signed, under the one confirmation.
    for (key in listOf("POST /v1/keys", "POST /v1/me/agent-drafts")) assertNotNull(key, transport.requestsTo(key).first().headers["KIFF-Device-Signature"])
    assertNotNull(transport.requestsTo("POST /v1/accounts/t_01/enrolment").single().headers["Planque-Device-Signature"])
  }

  @Test fun coachsToolsAndCardGoToKiffExactlyAsTafsCloudGivesThemOnce() = runTest {
    reads()
    startAnswers()
    val m = model()
    m.refreshConnections()
    assertTrue(m.startCoach())
    // The connect batch, signed, to KIFF's gateway, byte for byte what tafs cloud gave.
    val batch = transport.requestsTo("POST /v1/connections/batch").single()
    assertEquals("https://mcp.kiff.dev/v1/connections/batch", batch.url)
    assertNotNull("signed", batch.headers["KIFF-Device-Signature"])
    assertEquals(KiffClient.json.parseToJsonElement(connectBody), KiffClient.json.parseToJsonElement(String(batch.body!!)))
    // Then Coach's Card, as drafted, under its own fingerprint.
    val card = transport.requestsTo("POST /v1/me/agent-drafts")[1]
    assertNotNull("signed", card.headers["KIFF-Device-Signature"])
    assertEquals(KiffClient.json.parseToJsonElement(cardBody), KiffClient.json.parseToJsonElement(String(card.body!!)))
    assertEquals("Give Coach its Card", auth.asked.last())
    assertEquals("given", m.state.value.coachTools?.state)
    assertNull(m.state.value.coachToolsError)
    // Started and given, it asks nothing again and sends nothing again.
    assertTrue(m.startCoach())
    assertEquals(3, auth.asked.size)
    assertEquals(1, transport.requestsTo("POST /v1/connections/batch").size)
    assertEquals(2, transport.requestsTo("POST /v1/me/agent-drafts").size)
  }

  @Test fun theCardsReadInKiffsWordsAndTheConnectionsInTafsClouds() {
    val tools = KiffClient.json.decodeFromString<bot.tafs.app.kiff.CoachTools>(toGive)
    assertTrue(tools.toGive)
    assertEquals(listOf("Read your tafs", "Read a taf's results · replaces the earlier connection"), tools.connect?.lines)
    assertEquals("Coach", tools.card?.cardTitle)
    // A Card the app can't read is left out, not guessed at.
    assertEquals(listOf("Install a version" to listOf("Always asks you")), tools.card?.words)
  }

  private suspend fun startedWith(tools: String): AppModel {
    reads()
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$tools}""")
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[]}""")
    return model().also { it.loadCoach() }
  }

  @Test fun aToolNameAnotherServerHoldsIsNamedAndNothingIsSent() = runTest {
    val m = startedWith("""{"state":"taken","taken":"taf_task"}""")
    assertFalse(m.giveCoachTools())
    assertEquals(CoachWords.taken("taf_task"), m.state.value.coachToolsError)
    assertTrue(transport.requestsTo("POST /v1/connections/batch").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/me/agent-drafts").isEmpty())
    assertTrue(auth.asked.isEmpty())
  }

  @Test fun toolsKiffDidNotAnswerForAskToTryAgainAndNothingIsSent() = runTest {
    val m = startedWith("""{"state":"unknown"}""")
    assertFalse(m.giveCoachTools())
    assertEquals(CoachWords.TOOLS_UNKNOWN, m.state.value.coachToolsError)
    assertTrue(transport.requestsTo("POST /v1/connections/batch").isEmpty())
    assertTrue(auth.asked.isEmpty())
  }

  @Test fun anOwnerWhoDoesNotConfirmGivesNoCardAndCanGiveItLater() = runTest {
    val m = startedWith(toGive)
    transport.answer("POST /v1/connections/batch", 200)
    auth.confirms = false
    assertFalse(m.giveCoachTools())
    assertTrue(transport.requestsTo("POST /v1/me/agent-drafts").isEmpty())
    assertTrue(m.state.value.coachTools?.toGive == true)
  }

  @Test fun anOwnerWhoDoesNotConfirmStartsNothing() = runTest {
    reads()
    startAnswers()
    val m = model()
    auth.confirms = false
    assertFalse(m.startCoach())
    assertTrue(transport.requestsTo("POST /v1/keys").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/setup").isEmpty())
    assertNull(m.state.value.coachStartError)
    assertFalse(m.state.value.startingCoach)
  }

  @Test fun aMemberWhoIsNotAnAdminIsToldOnlyAnAdminConnectsTools() = runTest {
    reads()
    startAnswers(keys = 403 to """{"error":"forbidden"}""")
    val m = model()
    assertFalse(m.startCoach())
    assertEquals("Only an admin of this KIFF account can connect tools.", m.state.value.coachStartError)
    assertTrue(transport.requestsTo("POST /v1/setup").isEmpty())
  }

  @Test fun anotherDeviceThatConnectsTheToolsIsNamed() = runTest {
    reads()
    startAnswers(enrol = 409 to """{"error":"enrolled"}""")
    val m = model()
    assertFalse(m.startCoach())
    assertEquals("Another device connects this account's tools for now. Connect them from that one.", m.state.value.coachStartError)
  }

  // MARK: Hand-overs

  private fun coachThread(component: String, messages: String = "[]", answer: String? = null) {
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach","kiff_agent":"setup-acme"}}""")
    val answered = answer?.let { ""","answer":$it""" } ?: ""
    transport.answer(
      "GET /v1/tafs/setup_1/conversation", 200,
      """{"messages":$messages,"components":[{"at":"2026-10-28T08:00:00Z","session":"s1","run":"r1","component":$component$answered}]}""",
    )
    transport.answer("POST /v1/tafs/setup_1/messages", 200, """{"session":"s1","new_session":false}""")
    transport.answer("POST /v1/tafs/setup_1/components/s1/answer", 200, """{"session":"s1","run":"r1","status":"running"}""")
  }

  private val handOver = """{"type":"result","id":"r1","text":"Refunds is ready.","taf":{"agent":"refunds-2","name":"Refunds","job":"refunds","draft":"dr_t","unscheduled":true}}"""
  private val refunds = """{"tafs":[{"id":"c_9","name":"Refunds","kiff_agent":"refunds-2","job":"refunds","state":"idle"}],"others":[],"removing":[]}"""

  /** The answer the phone gave a card, as data through tafs cloud's route: never a message in the chat. */
  private fun answered(card: String) = KiffClient.json.parseToJsonElement(String(transport.requestsTo("POST /v1/tafs/setup_1/components/$card/answer").last().body!!)).jsonObject.getValue("answer").jsonPrimitive.content

  private fun handOver(m: AppModel) = m.state.value.coachComponents.single { it.id == "r1" }.let { CoachWords.handOver(it.answer, m.state.value.tafs.any { t -> t.id == "refunds-2" }) }

  /** The phone never starts a new taf, nor answers its card: that's tafs cloud's. */
  private fun nothingStartedHere() {
    assertTrue(transport.requestsTo("POST /v1/tafs").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/tafs/setup_1/messages").isEmpty())
    assertTrue(auth.asked.isEmpty())
  }

  @Test fun aHandOverTafsCloudSetUpReadsTheListOnceAndOpensTheTaf() = runTest {
    reads()
    coachThread(handOver, answer = """{"state":"answered","words":"","by":"tafs cloud","outcome":{"taf_id":"c_9"}}""")
    val m = model()
    m.refresh()
    transport.answer("GET /v1/home", 200, refunds)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    assertEquals("c_9", m.state.value.coachComponents.single().answer?.outcome?.tafId)
    assertEquals(CoachWords.HandOver.SetUp, handOver(m))
    assertTrue(m.state.value.tafs.any { it.id == "refunds-2" })
    // Read again, the list isn't read again for it.
    val homes = transport.requestsTo("GET /v1/home").size
    m.loadChat(COACH_CHAT)
    assertEquals(homes, transport.requestsTo("GET /v1/home").size)
    nothingStartedHere()
  }

  @Test fun aHandOverTafsCloudCouldNotSetUpSaysWhyWithNoTryAgain() = runTest {
    reads()
    coachThread(handOver, answer = """{"state":"answered","words":"","by":"tafs cloud","outcome":{"failed":"the account already has a taf named Refunds"}}""")
    val m = model()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    assertEquals(CoachWords.HandOver.Failed("the account already has a taf named Refunds"), handOver(m))
    assertEquals("Refunds wasn't set up: the account already has a taf named Refunds.", CoachWords.notSetUp("Refunds", "the account already has a taf named Refunds"))
    nothingStartedHere()
  }

  @Test fun aHandOverWithNoAnswerYetIsBeingSetUpAndThePhoneDoesNothing() = runTest {
    reads()
    coachThread(handOver)
    val m = model()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    assertNull(m.state.value.coachComponents.single().answer)
    assertEquals(CoachWords.HandOver.SettingUp, handOver(m))
    assertEquals("Setting up Refunds…", CoachWords.settingUp("Refunds"))
    nothingStartedHere()
  }

  @Test fun aCardsAnswerOutlivesTheCardShownAgainWithoutIt() {
    val card = KiffClient.json.decodeFromString<SetupComponent>(handOver)
    val first = bot.tafs.app.kiff.ShownComponent(t0, "s1", "r1", card, bot.tafs.app.kiff.CardAnswer(outcome = bot.tafs.app.kiff.ResultOutcome(tafId = "c_9")))
    val again = first.copy(answer = null)
    assertEquals("c_9", CoachWords.fold(listOf(first, again)).single().answer?.outcome?.tafId)
  }

  private val improvement = """{"type":"result","id":"r2","taf":{"agent":"refunds-2","name":"Refunds","improves":"c_9","note":"Asks before refunds over 50","draft":"dr_v","try":"Refund order 1001"}}"""

  @Test fun anImprovementMakesTheTafsNextVersionOnceAndNothingGoesLive() = runTest {
    reads(home = refunds)
    coachThread(improvement)
    transport.answer("POST /v1/tafs/c_9/versions", 200, """{"version":3}""")
    transport.answer("GET /v1/tafs/c_9/versions", 200, """{"versions":[]}""")
    val m = model()
    m.refresh()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    val made = body("POST /v1/tafs/c_9/versions")
    assertEquals("dr_v", made.getValue("draft").jsonPrimitive.content)
    assertEquals("r2|dr_v", made.getValue("from").jsonPrimitive.content)
    assertEquals("refunds-2", made.getValue("agent").jsonPrimitive.content)
    assertEquals("Refund order 1001", made.getValue("try").jsonPrimitive.content)
    assertEquals(3, m.state.value.improvedVersions["r2|dr_v"])
    m.loadChat(COACH_CHAT)
    assertEquals(1, transport.requestsTo("POST /v1/tafs/c_9/versions").size)
    assertTrue(transport.requests.none { it.url.contains("/live") || it.url.contains("/switch") })
    assertTrue(transport.requestsTo("POST /v1/tafs").isEmpty())
  }

  @Test fun anImprovementWithNoDraftIsKeyedByItsNote() {
    val a = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"result","id":"r","taf":{"agent":"a","improves":"c","note":"one"}}""")
    val b = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"result","id":"r","taf":{"agent":"a","improves":"c","note":"two"}}""")
    assertTrue(CoachWords.improvementKey(a).startsWith("r|"))
    assertEquals(2 + 16, CoachWords.improvementKey(a).length)
    assertFalse(CoachWords.improvementKey(a) == CoachWords.improvementKey(b))
  }

  // MARK: Signing in again

  private val digest = run {
    val request = """{"server":"mcp.stripe.com","agent":"refunds-2","tools":[{"tool":"create_refund","read_only":false}]}"""
    java.security.MessageDigest.getInstance("SHA-256").digest(bot.tafs.app.kiff.Jcs.canonical(request).toByteArray()).joinToString("") { "%02x".format(it) } to request
  }
  private val signInCard = """{"type":"sign_in","id":"s1","grant":"g1","service":"mcp.stripe.com"}"""
  private fun grant(status: String) =
    """{"id":"g1","status":"$status","text":"Connect Stripe.","digest":"${digest.first}","request":${digest.second},"events":[]}"""

  @Test fun signingInAgainApprovesOpensTheVendorAndTellsCoach() = runTest {
    reads()
    coachThread(signInCard)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth"}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/confirm", 200, grant("connected"))
    val vendor = FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de")
    val m = model(vendor)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.signInAgain(KiffClient.json.decodeFromString(signInCard))
    assertEquals("https://mcp.stripe.com/oauth", vendor.opened)
    assertEquals("signed in", answered("s1"))
    assertTrue(transport.requestsTo("POST /v1/tafs/setup_1/messages").isEmpty())
    assertEquals(1, auth.asked.size)
  }

  @Test fun aSignInClosedAtTheVendorIsToldToCoachAsFailed() = runTest {
    reads()
    coachThread(signInCard)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth"}""")
    val m = model(FakeSignIn(back = null))
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.signInAgain(KiffClient.json.decodeFromString(signInCard))
    assertEquals("failed: The sign-in was closed. Nothing was connected.", answered("s1"))
  }

}
