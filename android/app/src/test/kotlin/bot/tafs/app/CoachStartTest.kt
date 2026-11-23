@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import bot.tafs.app.app.AppModel
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.VendorSignIn
import bot.tafs.app.kiff.VendorSignInCancelled
import bot.tafs.app.model.CoachWords
import java.time.Instant
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

  private suspend fun model(signIn: VendorSignIn = FakeSignIn(), scope: kotlinx.coroutines.CoroutineScope? = null): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false, vendorSignIn = signIn, scope = scope).also { it.restore() }
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

  private fun startAnswers(keys: Pair<Int, String> = 200 to """{"plaintext":"kk_connector"}""", enrol: Pair<Int, String> = 200 to """{"tenant":"t_01","device":"ab12"}""", keyless: Pair<Int, String>? = 409 to """{"error":"no connector key stands for this account","code":"needs_key"}""") {
    // Before the start, no Coach; once started, its tools still to give, read by the start and by the tools panel; once given, given.
    transport.answer("GET /v1/setup", 200, """{"setup":null}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$toGive}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$toGive}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":{"state":"given"}}""")
    transport.answer("GET /v1/accounts/t_01/grants", 403, """{"error":"this device is not enrolled"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    transport.answer("POST /v1/keys", keys.first, keys.second)
    // Asked first with no key: nothing enrolled yet, so planque needs one; then the minted key goes in.
    // No keyless answer: the network dropped it.
    if (keyless == null) transport.drop("POST /v1/accounts/t_01/enrolment") else transport.answer("POST /v1/accounts/t_01/enrolment", keyless.first, keyless.second)
    transport.answer("POST /v1/accounts/t_01/enrolment", enrol.first, enrol.second)
    transport.answer("POST /v1/me/agent-drafts", 200, """{"id":"dr_setup","status":"confirmed","claim_secret":"cs_1"}""")
    transport.answer("POST /v1/setup", 200, """{"setup":$coach}""")
    transport.answer("POST /v1/connections/batch", 200)
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[]}""")
  }

  /** The device id planque was sent with the latest signed read of [account]'s grants. */
  private fun planqueDevice(account: String): String =
    transport.requestsTo("GET /v1/accounts/$account/grants").last().headers.getValue("Planque-Device-Signature").split(",")[2].removePrefix("d=")

  @Test fun afterSigningInToAnotherAccountPlanqueGetsThisDevicesIdThere() = runTest {
    reads()
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    transport.answer("GET /v1/accounts/t_02/grants", 200, """{"grants":[]}""")
    val m = model()
    assertEquals("dev_1", planqueDevice("t_01"))
    // Signed in to another account, with no relaunch: KIFF lists this phone there under another id.
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_2","name":"tafs on Android phone","this_device":true}]}""")
    m.useSession("at_2", "t_02")
    assertEquals("dev_2", planqueDevice("t_02"))
  }

  @Test fun startingCoachAsksOnceEnrolsWithPlanqueAndStartsCoachFromADraftWithNoTools() = runTest {
    reads()
    startAnswers()
    val m = model()
    // The start's refresh read planque's grants: planque doesn't know this phone yet.
    assertFalse(m.state.value.planqueReady)
    assertTrue(m.startCoach())
    // One fingerprint, the draft's, behind the approval key, before anything is made: no other prompt, no Card yet.
    assertEquals(listOf("Start Coach"), auth.asked)
    assertEquals(listOf("Start Coach"), auth.fingerprints)
    // Coach's tools are the step that ends the Start: asked in their sheet, behind their own fingerprint, or put off.
    assertTrue(m.state.value.coachTools?.toGive == true)
    assertTrue(m.state.value.coachToolsAsked)
    m.notNowCoachTools()
    assertFalse(m.state.value.coachToolsAsked)
    assertTrue(transport.requestsTo("POST /v1/connections/batch").isEmpty())
    assertEquals(1, transport.requestsTo("POST /v1/me/agent-drafts").size)
    assertEquals("setup_1", m.state.value.coachCloudId)
    assertEquals("setup-acme", m.state.value.coachAgent)
    assertTrue(m.state.value.planqueReady)
    assertNull(m.state.value.coachStartError)
    // planque keeps the connector key; the phone keeps nothing of it.
    // Asked first with no key, then the minted one: the phone keeps nothing of it.
    assertNull(body("POST /v1/accounts/t_01/enrolment").get("connector_key"))
    val enrol = body("POST /v1/accounts/t_01/enrolment", 1)
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
    assertEquals(2, transport.requestsTo("POST /v1/accounts/t_01/enrolment").count { it.headers["Planque-Device-Signature"] != null })
  }

  @Test fun coachsToolsAndCardGoToKiffExactlyAsTafsCloudGivesThemOnce() = runTest {
    reads()
    startAnswers()
    val m = model()
    m.refreshConnections()
    assertTrue(m.startCoach())
    assertTrue(m.giveCoachTools())
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
    assertEquals(listOf("Start Coach", "Give Coach its Card"), auth.asked)
    assertEquals(1, transport.requestsTo("POST /v1/connections/batch").size)
    assertEquals(2, transport.requestsTo("POST /v1/me/agent-drafts").size)
  }

  /**
   * The batch goes and the gateway takes it (200: every tool "already" connected), yet tafs cloud still asks for that same batch:
   * nothing was connected. Give says so, in tafs cloud's words when it sends them, never a silent redraw of the same step.
   */
  @Test fun aBatchThatConnectsNothingSaysSoInsteadOfRedrawingSilently() = runTest {
    reads()
    val connectOnly = """{"state":"to_give","connect":{"body":$connectBody,"lines":["Read your tafs"]}}"""
    val withWords = """{"state":"to_give","connect":{"body":$connectBody,"lines":["Read your tafs"]},"coach_tools_step":{"not_connected":"Nothing new was connected."}}"""
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$connectOnly}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$connectOnly}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$connectOnly}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$connectOnly}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$withWords}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$withWords}""")
    transport.answer("POST /v1/connections/batch", 200, """{"connected":[],"already":["tafs_list_tafs"],"replaced":[]}""")
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[]}""")
    val m = model()
    m.loadCoach()
    // No words from tafs cloud yet: the app's own line stands in.
    assertFalse(m.giveCoachTools())
    assertEquals(1, transport.requestsTo("POST /v1/connections/batch").size)
    assertEquals(bot.tafs.app.model.Words.Coach.toolsNotGiven(why = "").trim(), m.state.value.coachToolsError)
    // tafs cloud's words, once it sends them.
    assertFalse(m.giveCoachTools())
    assertEquals("Nothing new was connected.", m.state.value.coachToolsError)
  }

  @Test fun aCardDraftedOnlyAfterTheBatchIsShownFirstThenGivenOnTheNextGive() = runTest {
    reads()
    val connectOnly = """{"state":"to_give","connect":{"body":$connectBody,"lines":["Read your tafs"]}}"""
    val cardOnly = """{"state":"to_give","card":{"body":$cardBody,"card_title":"Coach","cards":[{"title":"Install a version","lines":["Always asks you"]}]}}"""
    // The step shows the batch alone, and Give's read agrees; once the batch is in, tafs cloud drafts the Card over its tools.
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$connectOnly}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$connectOnly}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$cardOnly}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$cardOnly}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":{"state":"given"}}""")
    transport.answer("POST /v1/connections/batch", 200)
    transport.answer("POST /v1/me/agent-drafts", 200, """{"id":"dr_c","status":"confirmed"}""")
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[]}""")
    val m = model()
    m.loadCoach()
    // The first Give: the batch it showed, and not the Card it never showed. The step is drawn again with the Card.
    assertFalse(m.giveCoachTools())
    assertEquals(1, transport.requestsTo("POST /v1/connections/batch").size)
    assertTrue(transport.requestsTo("POST /v1/me/agent-drafts").isEmpty())
    assertFalse(auth.asked.contains("Give Coach its Card"))
    assertNotNull(m.state.value.coachTools?.card)
    assertNull(m.state.value.coachToolsError)
    // The second Give: the Card, as shown, under its own fingerprint.
    assertTrue(m.giveCoachTools())
    assertEquals(KiffClient.json.parseToJsonElement(cardBody), KiffClient.json.parseToJsonElement(String(transport.requestsTo("POST /v1/me/agent-drafts").single().body!!)))
    assertEquals("Give Coach its Card", auth.asked.last())
    assertEquals(1, transport.requestsTo("POST /v1/connections/batch").size)
  }

  @Test fun aBatchThatChangedSinceTheStepWasShownSignsNothingAndDrawsTheStepAgain() = runTest {
    reads()
    val more = connectBody.replace("\"connections\":[", "\"connections\":[{\"name\":\"tafs_send\",\"url\":\"https://cloud.tafs.kiff.dev/v1/mcp\",\"tool\":\"tafs_send\"},")
    val changed = toGive.replace(connectBody, more)
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$toGive}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$changed}""")
    val m = model()
    m.loadCoach()
    assertFalse(m.giveCoachTools())
    assertTrue("no fingerprint", auth.asked.isEmpty())
    assertTrue(transport.requestsTo("POST /v1/connections/batch").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/me/agent-drafts").isEmpty())
    assertEquals(bot.tafs.app.model.VersionWords.OUT_OF_DATE_CARD, m.state.value.coachToolsError)
    // The step is drawn again from the read: the next Give is to what it shows now.
    assertEquals(KiffClient.json.parseToJsonElement(more), m.state.value.coachTools?.connect?.body)
  }

  @Test fun aCardDraftThatChangedAloneSignsNothingEither() = runTest {
    reads()
    val changed = toGive.replace(cardBody, cardBody.replace("\"limit\":0", "\"limit\":5"))
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$toGive}""")
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$changed}""")
    val m = model()
    m.loadCoach()
    assertFalse(m.giveCoachTools())
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo("POST /v1/connections/batch").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/me/agent-drafts").isEmpty())
    assertEquals(bot.tafs.app.model.VersionWords.OUT_OF_DATE_CARD, m.state.value.coachToolsError)
  }

  @Test fun theToolsStepReadsInTafsCloudsWordsAndAnOlderOneWithout() {
    val step = """{"title":"Give Coach its tools","intro":"Coach needs a few tools.","groups":[{"name":"Watch your tafs","line":"See your tafs.","tools":[{"name":"See your tafs","does":"Read each taf. Up to 500 reads a day.","why":"It reads your account."}]}],"details":"Each tool and its limits","ask":"Give Coach these tools?","give":"Give Coach its tools","not_now":"Not now","row":"Coach has no tools yet · Give them"}"""
    val tools = KiffClient.json.decodeFromString<bot.tafs.app.kiff.CoachTools>("""{"state":"to_give","coach_tools_step":$step}""")
    val s = tools.step!!
    assertEquals("Each tool and its limits", s.details)
    assertEquals("Not now", s.notNow)
    assertEquals("It reads your account.", s.groups.single().tools.single().why)
    assertNull(KiffClient.json.decodeFromString<bot.tafs.app.kiff.CoachTools>(toGive).step)
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
    // A finger not recognized says what any approval says: try again.
    assertEquals(bot.tafs.app.model.Words.Approval.notRecognized, m.state.value.coachStartError)
    assertFalse(m.state.value.startingCoach)
  }

  @Test fun aClosedPromptStartsNothingAndSaysCancelled() = runTest {
    reads()
    startAnswers()
    val m = model()
    auth.fails = bot.tafs.app.signing.PromptClosed()
    assertFalse(m.startCoach())
    assertTrue(transport.requestsTo("POST /v1/keys").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/setup").isEmpty())
    // Nothing is waiting on it, so: "Cancelled. Nothing changed."
    assertEquals("Cancelled. Nothing changed.", m.state.value.coachStartError)
    assertFalse(m.state.value.startingCoach)
  }

  // MARK: The first message starts Coach

  private fun coachMessages() = transport.requestsTo("POST /v1/tafs/setup_1/messages").map { String(it.body!!) }

  @Test fun aFirstMessageToCoachStartsItBehindOneFingerprintThenGoesOnce() = runTest {
    reads()
    startAnswers()
    transport.answer("POST /v1/tafs/setup_1/messages", 200, """{"session":"s1","new_session":false}""")
    val m = model(scope = backgroundScope)
    val back = mutableListOf<String>()
    m.sendChat("Refund orders under 50 euros", COACH_CHAT) { back += it }
    testScheduler.runCurrent()
    assertEquals(listOf("Start Coach"), auth.fingerprints)
    assertEquals(listOf("Start Coach"), auth.asked)
    assertEquals("setup_1", m.state.value.coachCloudId)
    assertEquals(1, coachMessages().size)
    assertTrue(coachMessages().single().contains("Refund orders under 50 euros"))
    assertTrue(back.isEmpty())
  }

  @Test fun aStartTheOwnerClosesKeepsTheWordsAndSendsNothing() = runTest {
    reads()
    startAnswers()
    transport.answer("POST /v1/tafs/setup_1/messages", 200, """{"session":"s1","new_session":false}""")
    val m = model(scope = backgroundScope)
    auth.confirms = false
    val back = mutableListOf<String>()
    m.sendChat("Refund orders under 50 euros", COACH_CHAT) { back += it }
    testScheduler.runCurrent()
    assertEquals(listOf("Refund orders under 50 euros"), back)
    assertTrue(transport.requestsTo("POST /v1/me/agent-drafts").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/keys").isEmpty())
    assertTrue(coachMessages().isEmpty())
    assertNull(m.state.value.coachCloudId)
    assertEquals(bot.tafs.app.model.Words.Approval.notRecognized, m.state.value.coachStartError)
  }

  @Test fun aStartThatFailsKeepsTheWordsAndSaysWhy() = runTest {
    reads()
    startAnswers(keys = 403 to """{"error":"forbidden"}""")
    val m = model(scope = backgroundScope)
    val back = mutableListOf<String>()
    m.sendChat("Refund orders under 50 euros", COACH_CHAT) { back += it }
    testScheduler.runCurrent()
    assertEquals(listOf("Refund orders under 50 euros"), back)
    assertEquals("Only an admin of this KIFF account can connect apps.", m.state.value.coachStartError)
    assertTrue(coachMessages().isEmpty())
    assertFalse(m.state.value.startingCoach)
  }

  @Test fun aCoachAlreadyThereOnAPhoneNewToPlanqueAsksYesOnceAndMakesNoDraft() = runTest {
    reads()
    // Coach was started from another phone: this one enrols with planque, behind the owner's yes.
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":{"state":"given"}}""")
    transport.answer("GET /v1/accounts/t_01/grants", 403, """{"error":"this device is not enrolled"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    transport.answer("POST /v1/keys", 200, """{"plaintext":"kk_connector"}""")
    transport.answer("POST /v1/accounts/t_01/enrolment", 200, """{"tenant":"t_01","device":"ab12","kept":true}""")
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[]}""")
    val m = model()
    m.loadCoach()
    assertTrue(m.startCoach())
    assertEquals(listOf("Connect your apps"), auth.asked)
    assertFalse("a Coach already there asks no tools step", m.state.value.coachToolsAsked)
    assertTrue(transport.requestsTo("POST /v1/me/agent-drafts").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/setup").isEmpty())
    assertTrue(m.state.value.planqueReady)
  }

  @Test fun aCoachAlreadyThereWithToolsStillToGiveAsksTheirStepAndGivesNothingInsideTheStart() = runTest {
    reads()
    // Coach was started from another phone, its tools not all given: this phone's Start asks their own step instead.
    transport.answer("GET /v1/setup", 200, """{"setup":$coach,"tools":$toGive}""")
    transport.answer("GET /v1/accounts/t_01/grants", 403, """{"error":"this device is not enrolled"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    transport.answer("POST /v1/keys", 200, """{"plaintext":"kk_connector"}""")
    transport.answer("POST /v1/accounts/t_01/enrolment", 200, """{"tenant":"t_01","device":"ab12","kept":true}""")
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[]}""")
    val m = model()
    m.loadCoach()
    assertTrue(m.startCoach())
    assertEquals(listOf("Connect your apps"), auth.asked)
    assertTrue(m.state.value.coachToolsAsked)
    assertTrue(transport.requestsTo("POST /v1/connections/batch").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/me/agent-drafts").isEmpty())
  }

  @Test fun aMemberWhoIsNotAnAdminIsToldOnlyAnAdminConnectsTools() = runTest {
    reads()
    startAnswers(keys = 403 to """{"error":"forbidden"}""")
    val m = model()
    assertFalse(m.startCoach())
    assertEquals("Only an admin of this KIFF account can connect apps.", m.state.value.coachStartError)
    assertTrue(transport.requestsTo("POST /v1/setup").isEmpty())
  }

  @Test fun anotherDeviceThatConnectsTheToolsIsNamed() = runTest {
    reads()
    startAnswers(enrol = 409 to """{"error":"enrolled"}""")
    val m = model()
    assertFalse(m.startCoach())
    assertEquals("Another device connects this account's apps for now. Connect them from that one.", m.state.value.coachStartError)
  }

  @Test fun planqueNotRecognizingThePhoneAtEnrolmentIsntASignOut() = runTest {
    reads()
    startAnswers(enrol = 401 to """{"error":"device not recognized"}""")
    val m = model()
    assertFalse(m.startCoach())
    // KIFF's session works: the app stays signed in and says connecting the tools failed, with Start to try again.
    assertEquals(CoachWords.CONNECTING_FAILED, m.state.value.coachStartError)
    assertEquals(bot.tafs.app.app.Mode.kiff, m.state.value.mode)
    assertEquals("at_1", secrets.load(AppModel.TOKEN))
  }

  /**
   * The account's enrolled connector key still stands (another device enrolled it): asked first with no key, planque answers
   * `kept`, and no key is minted, since a phone can't revoke one and a new one would only be left live in KIFF.
   */
  @Test fun aKeptEnrolmentMintsNoConnectorKey() = runTest {
    reads()
    startAnswers(keyless = 200 to """{"kept":true}""")
    val m = model()
    assertTrue(m.startCoach())
    assertTrue(transport.requestsTo("POST /v1/keys").isEmpty())
    val enrolment = transport.requestsTo("POST /v1/accounts/t_01/enrolment").single()
    assertNotNull(enrolment.headers["Planque-Device-Signature"])
    assertNull(body("POST /v1/accounts/t_01/enrolment").get("connector_key"))
    assertTrue(m.state.value.planqueReady)
    assertEquals(listOf("Start Coach"), auth.fingerprints)
  }

  /**
   * A 2xx to the keyless ask without `kept: true` mints nothing and stops, saying so; the next Start asks again. Only `kept: true`
   * is set up, and only `409 needs_key` mints.
   */
  @Test fun aKeylessTwoHundredWithoutKeptMintsNothingAndStops() = runTest {
    for (answer in listOf(201 to """{"tenant":"t_01","device":"ab12"}""", 200 to """{"kept":false}""")) {
      transport.reset()
      auth.fingerprints.clear()
      reads()
      startAnswers(keyless = answer)
      val m = model()
      assertFalse("$answer", m.startCoach())
      assertTrue("$answer", transport.requestsTo("POST /v1/keys").isEmpty())
      assertEquals("$answer", 1, transport.requestsTo("POST /v1/accounts/t_01/enrolment").size)
      assertFalse("$answer", m.state.value.planqueReady)
      assertEquals("$answer", "Connecting your apps didn't work. Tap Start to try again.", m.state.value.coachStartError)
    }
  }

  /** No key stands (`needs_key`), and only then: one key is minted and handed over. */
  @Test fun withNoStandingKeyOneIsMintedAndHandedOver() = runTest {
    for (refusal in listOf(409 to """{"error":"no connector key stands for this account","code":"needs_key"}""")) {
      transport.reset()
      auth.fingerprints.clear()
      reads()
      startAnswers(keyless = refusal)
      val m = model()
      assertTrue(m.startCoach())
      assertEquals(1, transport.requestsTo("POST /v1/keys").size)
      val keys = transport.requestsTo("POST /v1/accounts/t_01/enrolment").map {
        KiffClient.json.parseToJsonElement(String(it.body!!)).jsonObject["connector_key"]?.jsonPrimitive?.content
      }
      assertEquals(listOf(null, "kk_connector"), keys)
      assertTrue(m.state.value.planqueReady)
      assertEquals(listOf("Start Coach"), auth.fingerprints)
    }
  }

  /**
   * Any other answer to the keyless ask mints nothing: planque not reached, a 5xx, a replayed signature, a 400 (a body planque
   * couldn't read, which an enrolment with a key would fail the same way). A key minted then,
   * its enrolment failing too, would be left live in KIFF, held by no device. Start says why, and the next Start asks again.
   */
  @Test fun aKeylessAskPlanqueDidntAnswerMintsNoKey() = runTest {
    val answers: List<Pair<Int, String>?> = listOf(
      null,
      503 to """{"error":"planque is unavailable"}""",
      409 to """{"error":"this signature was already used","code":"signature_replayed"}""",
      400 to """{"error":"the body can't be read"}""",
    )
    for (keyless in answers) {
      transport.reset()
      reads()
      startAnswers(keyless = keyless)
      val m = model()
      assertFalse("$keyless", m.startCoach())
      assertTrue("$keyless", transport.requestsTo("POST /v1/keys").isEmpty())
      val enrolment = transport.requestsTo("POST /v1/accounts/t_01/enrolment").single()
      assertFalse("$keyless", String(enrolment.body!!).contains("connector_key"))
      assertFalse(m.state.value.planqueReady)
      assertNotNull("$keyless", m.state.value.coachStartError)
      // A coded 409 is planque's why, never "another device", which only a planque that enrolled one phone said (no code).
      assertNotEquals("$keyless", "Another device connects this account's apps for now. Connect them from that one.", m.state.value.coachStartError)
    }
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

  // tafs cloud may answer the card before Home lists the taf: the list is read again a few seconds apart until it does, so the
  // card never stays on "Setting up…" and the Tafs tab empty until a pull.
  @Test fun aHandOverAnsweredBeforeHomeListsTheTafReadsTheListAgainUntilItDoes() = runTest {
    reads()
    coachThread(handOver, answer = """{"state":"answered","words":"","by":"tafs cloud","outcome":{"taf_id":"c_9"}}""")
    val m = model(scope = backgroundScope)
    m.refresh()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    assertTrue("Home doesn't list it yet", m.state.value.tafs.none { it.id == "refunds-2" })
    transport.answer("GET /v1/home", 200, refunds)
    testScheduler.advanceTimeBy(AppModel.SET_UP_READ_MS + 100)
    testScheduler.runCurrent()
    assertTrue("listed once Home has it", m.state.value.tafs.any { it.id == "refunds-2" })
    // Listed: no more reads for it.
    val homes = transport.requestsTo("GET /v1/home").size
    testScheduler.advanceTimeBy(AppModel.SET_UP_READ_MS * AppModel.SET_UP_READS)
    testScheduler.runCurrent()
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

  @Test fun aNewCardUnderAnIdUsedBeforeWaitsForItsOwnAnswer() {
    val connect = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"approval","id":"connect","grant":"g1","title":"Connect Stripe"}""")
    val reads = connect.copy(grant = "g2", title = "Refunds' setup reads")
    val first = bot.tafs.app.kiff.ShownComponent(t0, "s1", "r1", connect, bot.tafs.app.kiff.CardAnswer(words = "Approved"))
    val second = bot.tafs.app.kiff.ShownComponent(t0.plusSeconds(60), "s1", "r2", reads)
    val folded = CoachWords.fold(listOf(first, second)).single()
    assertEquals("Refunds' setup reads", folded.component.title)
    assertNull("a different card under the same id isn't answered by the first", folded.answer)
  }

  @Test fun twoConversationsReusingACardIdKeepTheirOwnCards() {
    val rules = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"confirm","id":"rules","title":"Use these rules?","options":[{"value":"agree","label":"Agree"}]}""")
    // Refunds' conversation answered its rules card; Chargebacks', later, sent its own under the same id.
    val refunds = bot.tafs.app.kiff.ShownComponent(t0, "s_refunds", "r1", rules, bot.tafs.app.kiff.CardAnswer(words = "Agree"))
    val chargebacks = bot.tafs.app.kiff.ShownComponent(t0.plusSeconds(600), "s_chargebacks", "r9", rules.copy(title = "Use these rules for disputes?"))
    val folded = CoachWords.fold(listOf(refunds, chargebacks))
    assertEquals(2, folded.size)
    assertEquals("Agree", folded[0].answer?.words)
    assertNull("Chargebacks' card waits for its own answer", folded[1].answer)
    assertEquals(t0.plusSeconds(600), folded[1].firstAt)
    // tafs cloud's key, where it sends one, tells cards apart; else the conversation and id. The list's rows never share a key.
    assertEquals("s_refunds#rules", refunds.ref)
    assertEquals("k-2", chargebacks.copy(key = "k-2").ref)
    val items = CoachWords.items(emptyList(), folded)
    assertEquals(items.size, items.map { it.id }.toSet().size)
  }

  @Test fun anAnsweredCardStaysAfterTheWordsThatAskedForItAndCoachsLaterWordsComeBelow() {
    val connect = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"approval","id":"connect","waits":"owner","grant":"g1"}""")
    fun said(at: Long, text: String) = bot.tafs.app.model.ChatMessage(t0.plusSeconds(at), bot.tafs.app.model.ChatMessage.From.taf, text, session = "s1")
    // Shown at 0 s; Coach's words with it are dated when written (2 s); answered at 10 s; Coach's next words at 12 s.
    val asked = said(2, "Approve the connection to Stripe.")
    val next = said(12, "Connected. Next, the Card.")
    val answered = bot.tafs.app.kiff.ShownComponent(t0, "s1", "r1", connect, bot.tafs.app.kiff.CardAnswer(words = "Approved", atText = t0.plusSeconds(10).toString()))
    val order = CoachWords.items(listOf(asked, next), listOf(answered)).map { (it as? bot.tafs.app.model.ChatItem.Message)?.message?.text ?: "card" }
    assertEquals(listOf("Approve the connection to Stripe.", "card", "Connected. Next, the Card."), order)
    // One that waits sits after the words too.
    val waiting = CoachWords.items(listOf(asked), listOf(answered.copy(answer = null))).map { (it as? bot.tafs.app.model.ChatItem.Message)?.message?.text ?: "card" }
    assertEquals(listOf("Approve the connection to Stripe.", "card"), waiting)
  }

  /** A drive card waits for the person too: Coach's words with it come before it, never under "Approved" (as on iOS). */
  @Test fun aDriveCardComesAfterTheWordsThatAskedForIt() {
    val drive = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"drive","id":"d1","waits":"owner","text":"Show where Refunds' daily limit is set"}""")
    fun said(at: Long, text: String) = bot.tafs.app.model.ChatMessage(t0.plusSeconds(at), bot.tafs.app.model.ChatMessage.From.taf, text, session = "s1")
    val asked = said(1, "I'm waiting for you to approve the drive.")
    val next = said(30, "The daily limit for Refunds is on its Card tab.")
    val approved = bot.tafs.app.kiff.ShownComponent(t0, "s1", "r1", drive, bot.tafs.app.kiff.CardAnswer(words = "Approved", atText = t0.plusSeconds(5).toString()))
    val order = CoachWords.items(listOf(asked, next), listOf(approved)).map { (it as? bot.tafs.app.model.ChatItem.Message)?.message?.text ?: "card" }
    assertEquals(listOf("I'm waiting for you to approve the drive.", "card", "The daily limit for Refunds is on its Card tab."), order)
  }

  /** A drive's end sits in the thread at the time it ended (kiff/tafs#568): Coach's words after it come below, never above. */
  @Test fun aDrivesEndComesBeforeCoachsNextWords() {
    val drive = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"drive","id":"d1","waits":"owner","text":"Show where Refunds' daily limit is set"}""")
    fun said(at: Long, text: String) = bot.tafs.app.model.ChatMessage(t0.plusSeconds(at), bot.tafs.app.model.ChatMessage.From.taf, text, session = "s1")
    val asked = said(1, "I'm waiting for you to approve the drive.")
    val reply = said(62, "I left it on Refunds' Card tab.")
    val approved = bot.tafs.app.kiff.ShownComponent(t0, "s1", "r1", drive, bot.tafs.app.kiff.CardAnswer(words = "Approved", atText = t0.plusSeconds(5).toString()))
    val recap = bot.tafs.app.drive.DriveRecap("dv_1", "s1", "Show where Refunds' daily limit is set", "done", "Coach is done", listOf(bot.tafs.app.drive.DriveStep(t0.plusSeconds(30).toString(), "Opening Refunds")), t0.plusSeconds(60).toString())
    val order = CoachWords.items(listOf(asked, reply), listOf(approved), listOf(recap)).map {
      when (it) { is bot.tafs.app.model.ChatItem.Message -> it.message.text; is bot.tafs.app.model.ChatItem.Drive -> "recap"; else -> "card" }
    }
    assertEquals(listOf("I'm waiting for you to approve the drive.", "card", "recap", "I left it on Refunds' Card tab."), order)
  }

  /** A card is placed by what kiff/agents marks it (`waits`), never by its type: tafs cloud marks every card, old ones too. */
  @Test fun aCardWaitsAsKiffAgentsMarksIt() {
    fun said(at: Long, text: String) = bot.tafs.app.model.ChatMessage(t0.plusSeconds(at), bot.tafs.app.model.ChatMessage.From.taf, text, session = "s1")
    val words = said(2, "Read this below.")
    fun card(json: String) = bot.tafs.app.kiff.ShownComponent(t0, "s1", "r1", KiffClient.json.decodeFromString<SetupComponent>(json))
    fun order(json: String) = CoachWords.items(listOf(words), listOf(card(json))).map { (it as? bot.tafs.app.model.ChatItem.Message)?.message?.text ?: "card" }
    // Marked as waited on: after Coach's words, whatever its type.
    assertEquals(listOf("Read this below.", "card"), order("""{"type":"notice","id":"n1","waits":"owner","text":"x"}"""))
    assertEquals(listOf("Read this below.", "card"), order("""{"type":"result","id":"r1","waits":"service"}"""))
    // Not marked: the run goes on past it, so it's at its own time, whatever its type. The app keeps no list of its own.
    assertEquals(listOf("card", "Read this below."), order("""{"type":"notice","id":"n1","text":"x"}"""))
    assertEquals(listOf("card", "Read this below."), order("""{"type":"approval","id":"a1","grant":"g1"}"""))
  }

  /** Coach's thread reads each drive's end from `items`, apart: one it can't read is left out, never the thread. */
  @Test fun theThreadReadsItsDrivesEnds() = runTest {
    val transport = FakeTransport()
    transport.answer(
      "GET /v1/tafs/setup_1/conversation", 200,
      """{"messages":[{"at":"2026-10-28T09:00:00Z","from":"taf","text":"Done.","session":"s1"}],"components":[],
        "items":[{"type":"message","message":{}},
                 {"type":"drive","drive":{"id":"dv_1","session":"s1","task":"Run Chargebacks","why":"owner_took_over","words":"You touched the app, so it's yours now","steps":[{"at":"2026-10-28T09:00:30Z","line":"Opening Chargebacks","touched":{"words":"Chargebacks"}}],"at":"2026-10-28T09:01:00Z"}},
                 {"type":"drive","drive":{"id":"dv_2"}},
                 {"type":"drive","drive":{"id":"dv_3","at":"not a time"}}]}""",
    )
    val thread = bot.tafs.app.kiff.TafsCloud(transport).apply { token = "tok" }.conversation("setup_1")
    assertEquals(listOf("dv_1"), thread.drives.map { it.id })
    val recap = thread.drives.single()
    assertEquals("You touched the app, so it's yours now", recap.words)
    assertEquals("Chargebacks", recap.steps.single().touched?.words)
    assertEquals(1, thread.messages.size)
  }

  @Test fun aCardsAnswerOutlivesTheCardShownAgainWithoutIt() {
    val card = KiffClient.json.decodeFromString<SetupComponent>(handOver)
    val first = bot.tafs.app.kiff.ShownComponent(t0, "s1", "r1", card, bot.tafs.app.kiff.CardAnswer(outcome = bot.tafs.app.kiff.ResultOutcome(tafId = "c_9")))
    val again = first.copy(answer = null)
    assertEquals("c_9", CoachWords.fold(listOf(first, again)).single().answer?.outcome?.tafId)
  }

  private val improvement = """{"type":"result","id":"r2","taf":{"agent":"refunds-2","name":"Refunds","improves":"c_9","note":"Asks before refunds over 50","draft":"dr_v","try":"Refund order 1001"}}"""

  @Test fun anImprovementsVersionIsTafsCloudsAndThePhoneOnlyShowsIt() = runTest {
    reads(home = refunds)
    // tafs cloud made version 3 in the hand-over and answered the card with it.
    coachThread(improvement, answer = """{"state":"answered","by":"tafs cloud","outcome":{"version":3}}""")
    transport.answer("GET /v1/tafs/c_9/versions", 200, """{"versions":[]}""")
    val m = model()
    m.refresh()
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    val shown = m.state.value.coachComponents.single()
    assertEquals(CoachWords.Improvement.Made(3), CoachWords.improvement(shown.answer))
    // The phone makes no version and nothing goes live; it reads the taf's Versions once, so version 3 is there to try.
    m.loadChat(COACH_CHAT)
    assertTrue(transport.requestsTo("POST /v1/tafs/c_9/versions").isEmpty())
    assertEquals(1, transport.requestsTo("GET /v1/tafs/c_9/versions").size)
    assertTrue(transport.requests.none { it.url.contains("/live") || it.url.contains("/switch") })
    assertTrue(transport.requestsTo("POST /v1/tafs").isEmpty())
  }

  @Test fun anImprovementTafsCloudCouldntMakeSaysWhyAndOneNotYetAnsweredIsPreparing() {
    assertEquals(CoachWords.Improvement.Preparing, CoachWords.improvement(null))
    val failed = bot.tafs.app.kiff.CardAnswer(by = "tafs cloud", outcome = bot.tafs.app.kiff.ResultOutcome(failed = "You can't manage this taf's Cards."))
    assertEquals(CoachWords.Improvement.Failed("You can't manage this taf's Cards."), CoachWords.improvement(failed))
    assertEquals("Refunds' new version wasn't made: You can't manage this taf's Cards.", CoachWords.notImproved("Refunds", "You can't manage this taf's Cards."))
    assertEquals("Coach's new version wasn't made: no draft.", CoachWords.notImproved("Coach", "no draft"))
  }

  // MARK: Signing in again

  private val digest = run {
    val request = """{"server":"mcp.stripe.com","agent":"refunds-2","tools":[{"tool":"create_refund","read_only":false}]}"""
    java.security.MessageDigest.getInstance("SHA-256").digest(bot.tafs.app.kiff.Jcs.canonical(request).toByteArray()).joinToString("") { "%02x".format(it) } to request
  }
  private val signInCard = """{"type":"sign_in","id":"s1","grant":"g1","service":"mcp.stripe.com"}"""
  private fun grant(status: String) =
    """{"id":"g1","status":"$status","text":"Connect Stripe.","digest":"${digest.first}","request":${digest.second},"events":[]}"""

  @Test fun aSignInGoesOnWhenTheScreenThatStartedItIsTornDown() = runTest {
    reads()
    coachThread(signInCard)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth"}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/confirm", 200, grant("connected"))
    // planque's confirm is held: the screen goes away while the flow is at it, as when the vendor's page opens.
    transport.hold("POST /v1/accounts/t_01/grants/g1/confirm")
    val m = model(FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de"), scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    val screen = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
    screen.launch { m.startSignInAgain(KiffClient.json.decodeFromString(signInCard)) }
    testScheduler.runCurrent()
    screen.cancel()
    transport.release("POST /v1/accounts/t_01/grants/g1/confirm")
    repeat(20) { testScheduler.advanceTimeBy(100); testScheduler.runCurrent() }
    // The flow went on: planque confirmed, Coach got its answer, and the card isn't left sending.
    assertEquals(1, transport.requestsTo("POST /v1/accounts/t_01/grants/g1/confirm").size)
    assertEquals("signed in", answered("s1"))
    assertTrue(m.state.value.cardsTaken.any { it.endsWith("#s1") })
    assertTrue(m.state.value.cardsAnswering.isEmpty())
    assertTrue(transport.requestsTo("GET /v1/tafs/setup_1/conversation").size >= 2)
  }

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

  /** Gap 13: a connection that's still connected signs in again in place, approved, opened and confirmed like any sign-in. */
  @Test fun aConnectedConnectionSignsInAgainInPlace() = runTest {
    reads()
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("connected")}]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"connected","sign_in_url":"https://mcp.stripe.com/oauth"}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/confirm", 200, grant("connected"))
    val vendor = FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de")
    val m = model(vendor)
    m.reconnect(KiffClient.json.decodeFromString(grant("connected")))
    assertEquals(1, transport.requestsTo("POST /v1/accounts/t_01/grants/g1/approve").size)
    assertEquals("https://mcp.stripe.com/oauth", vendor.opened)
    assertEquals(1, transport.requestsTo("POST /v1/accounts/t_01/grants/g1/confirm").size)
    assertNull(m.state.value.coachNotes["g1"])
  }

  @Test fun aSignInThatCameBackButDidNotConnectIsNeverSignedIn() = runTest {
    reads()
    coachThread(signInCard)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth"}""")
    // The page closed at once and came back with a code, but planque doesn't hold the connection as connected.
    transport.answer("POST /v1/accounts/t_01/grants/g1/confirm", 200, grant("needs_reconnect"))
    val m = model(FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de"))
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.signInAgain(KiffClient.json.decodeFromString(signInCard))
    assertTrue(answered("s1").startsWith("failed: "))
    assertEquals(CoachWords.notConnected("mcp.stripe.com"), m.state.value.coachNotes["s1"])
    assertNull(m.state.value.connectStep)
  }

  @Test fun aSignInThatCameBackListedIsSignedInNeverTheRedError() = runTest {
    reads()
    coachThread(signInCard)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth"}""")
    // Signed in, and planque listed the vendor's tools for Setup to choose from: that's connected, with nothing in KIFF yet.
    transport.answer("POST /v1/accounts/t_01/grants/g1/confirm", 200, grant("listed"))
    val m = model(FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de"))
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.signInAgain(KiffClient.json.decodeFromString(signInCard))
    assertEquals("signed in", answered("s1"))
    assertNull(m.state.value.coachNotes["s1"])
  }

  // The owner's card read "it didn't go through: This connection is listed.": the phone worded planque's status itself. A
  // connection that takes no approval now is tafs cloud's to answer, in the person's words.
  @Test fun aSignInWhoseConnectionTakesNoApprovalShowsTafsCloudsWords() = runTest {
    reads()
    coachThread(signInCard)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("listed")}]}""")
    transport.answer("POST /v1/tafs/setup_1/components/s1/approval", 200,
      """{"outcome":"answered","answer":"signed in","note":"You're already signed in to Stripe on this connection, so there's nothing to sign in to here.","session":"s1","run":"r1"}""")
    val m = model(FakeSignIn(back = null))
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.signInAgain(KiffClient.json.decodeFromString(signInCard))
    assertEquals("You're already signed in to Stripe on this connection, so there's nothing to sign in to here.", m.state.value.coachNotes["s1"])
    // tafs cloud told Coach itself: the phone sends no answer of its own, and nothing was approved at planque.
    assertTrue(transport.requestsTo("POST /v1/tafs/setup_1/components/s1/answer").isEmpty())
    assertTrue(transport.requestsTo("POST /v1/accounts/t_01/grants/g1/approve").isEmpty())
  }

  @Test fun whereTafsCloudCantBeAskedTheSignInSaysWhyAsBefore() = runTest {
    reads()
    coachThread(signInCard)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("listed")}]}""")
    transport.answer("POST /v1/tafs/setup_1/components/s1/approval", 503, """{"error":"down"}""")
    val m = model(FakeSignIn(back = null))
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.signInAgain(KiffClient.json.decodeFromString(signInCard))
    assertTrue(answered("s1").startsWith("failed: This connection is listed"))
  }

  // A grant planque ended reads, to the person, in planque's words for its state, never planque's English `reason`, which is for
  // the log (and for Coach, as `told`): on 18 Nov planque began adding a code in brackets to it.
  @Test fun aFailedGrantsReasonNeverReachesThePerson() = runTest {
    reads()
    coachThread(signInCard)
    val failed = grant("failed").removeSuffix("}") + ""","reason":"KIFF answered 403: approval_refused (profile_not_approved)","status_words":"Fehlgeschlagen"}"""
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[$failed]}""")
    transport.answer("POST /v1/tafs/setup_1/components/s1/approval", 503, """{"error":"down"}""")
    val m = model(FakeSignIn(back = null))
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.signInAgain(KiffClient.json.decodeFromString(signInCard))
    val shown = listOfNotNull(m.state.value.coachNotes["s1"], m.state.value.lastError).joinToString(" | ")
    assertTrue(shown, "approval_refused" !in shown && "403" !in shown && "KIFF answered" !in shown)
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

  // The owner on Android 95: "If the vendor's sign-in page opened, finish it there" showed under Approving… on a reuse that opened
  // no page. The line is drawn only while the vendor's page is open.
  @Test fun theCardSaysFinishItThereOnlyWhileTheVendorsPageIsOpen() = runTest {
    reads()
    coachThread(signInCard)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth"}""")
    var m: AppModel? = null
    var openWhileThere: Boolean? = null
    val m1 = model(object : VendorSignIn {
      override suspend fun signIn(url: String): String { openWhileThere = m!!.state.value.vendorPageOpen; throw VendorSignInCancelled() }
    })
    m = m1
    m1.loadCoach()
    m1.loadChat(COACH_CHAT)
    assertFalse(m1.state.value.vendorPageOpen)
    m1.signInAgain(KiffClient.json.decodeFromString(signInCard))
    assertEquals(true, openWhileThere)
    assertFalse(m1.state.value.vendorPageOpen)
  }

  @Test fun aSignInThatNeedsNoPageNeverSaysFinishItThere() = runTest {
    reads()
    coachThread(signInCard)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("listed")}]}""")
    transport.answer("POST /v1/tafs/setup_1/components/s1/approval", 200,
      """{"outcome":"answered","answer":"signed in","note":"Signed in already.","session":"s1","run":"r1"}""")
    val m = model(object : VendorSignIn { override suspend fun signIn(url: String): String = throw AssertionError("no page opens") })
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.signInAgain(KiffClient.json.decodeFromString(signInCard))
    assertFalse(m.state.value.vendorPageOpen)
  }

  // The owner on Android 95: the approval card said "Reading what it asks for…" until a tab switch read the grants. tafs cloud
  // can list the card before planque lists its grant: the phone reads the grants again until it does.
  @Test fun anApprovalWhoseGrantIsntListedYetIsReadAgain() = runTest {
    reads()
    coachThread("""{"type":"approval","id":"a1","grant":"g1","title":"Connect Stripe"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    val m = model(scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    assertTrue(m.state.value.grants.none { it.id == "g1" })
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    advanceTimeBy(AppModel.SET_UP_READ_MS + 1)
    runCurrent()
    assertTrue(m.state.value.grants.any { it.id == "g1" })
  }

  // A live test on Android 104: the card said "Leyendo lo que pide…" for over a minute after the grant was made, until the chat
  // was opened again. The phone read the grants three times, nine seconds in all, and Coach's run was done, so nothing read
  // them again. They're read while the card waits on its grant: a minute in, planque lists it and the card has it.
  @Test fun anApprovalWhoseGrantIsListedAMinuteLaterIsStillRead() = runTest {
    reads()
    coachThread("""{"type":"approval","id":"a1","grant":"g1","title":"Connect Stripe"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    val m = model(scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    advanceTimeBy(60_000)
    runCurrent()
    assertTrue(m.state.value.grants.none { it.id == "g1" })
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    // Past the first minute, read 10 s apart.
    advanceTimeBy(10_000 + 1)
    runCurrent()
    assertTrue(m.state.value.grants.any { it.id == "g1" })
    // Listed: the reads stop.
    val read = transport.requestsTo("GET /v1/accounts/t_01/grants").size
    advanceTimeBy(10 * AppModel.SET_UP_READ_MS)
    runCurrent()
    assertEquals(read, transport.requestsTo("GET /v1/accounts/t_01/grants").size)
  }

  // The owner, 18 Nov (P0): the card stayed on "Reading what it asks for…" for minutes and filled in only once the screen was left
  // and opened again. The card can be written before it names its grant, and Coach's run is done by then, so no stream reads the
  // thread again: the phone reads the thread again while a card can't be drawn, and the card fills in on its own.
  @Test fun aCardThatNamesItsGrantLaterFillsInWithoutLeavingTheScreen() = runTest {
    reads()
    coachThread("""{"type":"approval","id":"a1","title":"Connect Stripe"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    val m = model(scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    assertEquals(setOf("a1"), AppModel.cardsWaiting(m.state.value.coachComponents, m.state.value.grants))
    // tafs cloud now names the card's grant; nothing on the phone opens the chat again.
    coachThread("""{"type":"approval","id":"a1","grant":"g1","title":"Connect Stripe"}""")
    advanceTimeBy(3_000 + 1)
    runCurrent()
    assertTrue("the card is drawn", AppModel.cardsWaiting(m.state.value.coachComponents, m.state.value.grants).isEmpty())
    assertTrue(m.state.value.cardsNotRead.isEmpty())
  }

  // A grant id this phone followed before (an earlier card, its ten minutes over) on a later card is followed again: a set of
  // followed ids kept it from being read at all, and the card said "Reading…" until the chat was opened again.
  @Test fun aGrantFollowedBeforeIsFollowedAgainOnALaterRead() = runTest {
    reads()
    coachThread("""{"type":"approval","id":"a1","grant":"g1","title":"Connect Stripe"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    val m = model(scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    advanceTimeBy(AppModel.CARD_GRANT_READ_DELAYS.sum() + 1)
    runCurrent()
    // A later read of the chat, planque still without the grant: the reads start again.
    m.loadChat(COACH_CHAT)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    advanceTimeBy(3_000 + 1)
    runCurrent()
    assertTrue(m.state.value.grants.any { it.id == "g1" })
  }

  // Never "Reading…" for ever: a minute in, a card still not drawable says it couldn't be read, with Try again; the reads go on,
  // and once the card can be drawn the line goes.
  @Test fun aCardStillUnreadAfterAMinuteSaysSoWithTryAgain() = runTest {
    reads()
    coachThread("""{"type":"approval","id":"a1","grant":"g1","title":"Connect Stripe"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    val m = model(scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    advanceTimeBy(AppModel.CARD_NOT_READ_MS - 3_000)
    runCurrent()
    assertTrue("not before the minute", m.state.value.cardsNotRead.isEmpty())
    advanceTimeBy(3_000 + 1)
    runCurrent()
    assertEquals(setOf("a1"), m.state.value.cardsNotRead)
    // Try again reads the chat at once and starts a new minute: still nothing, and it isn't said again at the next tick.
    m.retryCards()
    runCurrent()
    assertTrue(m.state.value.cardsNotRead.isEmpty())
    advanceTimeBy(10_000 + 1)
    runCurrent()
    assertTrue("a new minute, not the old follower's next tick", m.state.value.cardsNotRead.isEmpty())
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    advanceTimeBy(10_000 + 1)
    runCurrent()
    assertTrue(m.state.value.grants.any { it.id == "g1" })
    assertTrue(m.state.value.cardsNotRead.isEmpty())
  }

  // The quiet check's own read: Coach's run went quiet on a phone (its stream's lines through `flowOn`), and the stream's 30 s
  // check reads the chat, which now holds the connect card. That read closes the stream, after the card's grant is read:
  // closing it first ended the read too, and the card stayed on "Reading what it asks for…".
  @Test fun theQuietChecksReadReadsTheCardBeforeItClosesTheStream() = runTest {
    reads()
    transport.answer("GET /v1/setup", 200, """{"setup":{"id":"setup_1","name":"Coach","kiff_agent":"setup-acme"}}""")
    transport.answer("GET /v1/tafs/setup_1/conversation", 200, """{"messages":[],"working":{"session":"s1","run":"r1"}}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    transport.openStream = { request ->
      if (!request.url.contains("/runs/r1/stream")) null
      else {
        transport.send(request)
        bot.tafs.app.kiff.StreamedResponse(200, kotlinx.coroutines.flow.flow {
          emit("event: activity")
          emit("data: {\"tool\":\"show\",\"call_id\":\"c1\"}")
          kotlinx.coroutines.awaitCancellation()
        }.buffer())
      }
    }
    val m = model(scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    advanceTimeBy(1_000)
    runCurrent()
    // The run is over; the chat holds its card, and planque lists the card's grant.
    transport.answer(
      "GET /v1/tafs/setup_1/conversation", 200,
      """{"messages":[],"components":[{"at":"2026-10-28T08:00:00Z","session":"s1","run":"r1","component":{"type":"approval","id":"a1","grant":"g1","title":"Connect Stripe"}}]}""",
    )
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    advanceTimeBy(AppModel.STREAM_CHECK_MS + 1)
    runCurrent()
    assertTrue("the card's grant was read by the check's read", m.state.value.grants.any { it.id == "g1" })
  }

  // A grant planque never lists: read for ten minutes at most, never for as long as the chat stays open.
  @Test fun aCardsGrantIsReadForTenMinutesAtMost() = runTest {
    reads()
    coachThread("""{"type":"approval","id":"a1","grant":"g1","title":"Connect Stripe"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    val m = model(scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    // 3 s apart for the first minute, then 10 s apart: ten minutes in all, 74 reads where 3 s throughout took 200.
    assertEquals(10L * 60 * 1000, AppModel.CARD_GRANT_READ_DELAYS.sum())
    assertEquals(74, AppModel.CARD_GRANT_READ_DELAYS.size)
    assertEquals(60_000L, AppModel.CARD_GRANT_READ_DELAYS.takeWhile { it == 3_000L }.sum())
    val before = transport.requestsTo("GET /v1/accounts/t_01/grants").size
    advanceTimeBy(AppModel.CARD_GRANT_READ_DELAYS.sum() + 1)
    runCurrent()
    val read = transport.requestsTo("GET /v1/accounts/t_01/grants").size
    assertEquals(before + 74, read)
    advanceTimeBy(60_000)
    runCurrent()
    assertEquals(read, transport.requestsTo("GET /v1/accounts/t_01/grants").size)
  }

  // tafs#1218: a card for a sign-in planque couldn't narrow carries `wider: {words}`, drawn above its buttons. Read leniently.
  @Test fun aWiderSignInIsReadFromTheCardLeniently() {
    fun card(wider: String) = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"approval","id":"c","grant":"g1"$wider}""")
    val words = "The sign-in for Google Sheets can't be narrowed to this job: it asks for more access than the taf uses. The taf still uses only its own tools."
    assertEquals(words, card(""","wider":{"words":"$words"}""").wider)
    assertNull("no mark", card("").wider)
    assertNull("blank words", card(""","wider":{"words":" "}""").wider)
    assertNull("another shape is no mark, never a failed card", card(""","wider":true""").wider)
    assertNull(card(""","wider":{"words":3}""").wider)
  }

  // Which grants a card waits on: an unanswered approval or sign-in card's, while planque doesn't list it.
  @Test fun theGrantsCardsWaitOn() {
    val connect = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"approval","id":"connect","grant":"g1","title":"Connect Stripe"}""")
    val signIn = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"sign_in","id":"again","grant":"g2","title":"Sign in"}""")
    val notice = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"notice","id":"n","grant":"g4","title":"Note"}""")
    val shown = { c: SetupComponent, answer: bot.tafs.app.kiff.CardAnswer? -> bot.tafs.app.kiff.ShownComponent(t0, "s1", "r1", c, answer) }
    val cards = listOf(shown(connect, null), shown(signIn, null), shown(connect.copy(id = "done", grant = "g3"), bot.tafs.app.kiff.CardAnswer(words = "Approved")), shown(notice, null))
    assertEquals(setOf("g1", "g2"), AppModel.grantsCardsWaitOn(cards, emptyList()))
    assertEquals(setOf("g2"), AppModel.grantsCardsWaitOn(cards, listOf(bot.tafs.app.kiff.Grant(id = "g1"))))
  }

  // Which drafts a card waits on: an unanswered approval card's, while KIFF hasn't given it to this phone.
  @Test fun theDraftsCardsWaitOn() {
    val card = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"approval","id":"card","grant":"g1","draft":"dr_1","title":"Its Card"}""")
    val signIn = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"sign_in","id":"again","grant":"g2","draft":"dr_9","title":"Sign in"}""")
    val shown = { c: SetupComponent, answer: bot.tafs.app.kiff.CardAnswer? -> bot.tafs.app.kiff.ShownComponent(t0, "s1", "r1", c, answer) }
    val cards = listOf(
      shown(card, null),
      shown(card.copy(id = "other", draft = "dr_2"), null),
      shown(card.copy(id = "done", draft = "dr_3"), bot.tafs.app.kiff.CardAnswer(words = "Approved")),
      shown(card.copy(id = "none", draft = null), null),
      shown(signIn, null),
    )
    assertEquals(setOf("dr_1", "dr_2"), AppModel.draftsCardsWaitOn(cards, emptySet()))
    assertEquals(setOf("dr_2"), AppModel.draftsCardsWaitOn(cards, setOf("dr_1")))
  }

  // Review of Android 96: a grant was marked followed on a read planque refused, so no later thread read followed it.
  @Test fun aGrantFirstReadWhilePlanqueRefusedIsStillReadAgainLater() = runTest {
    reads()
    coachThread("""{"type":"approval","id":"a1","grant":"g1","title":"Connect Stripe"}""")
    transport.answer("GET /v1/accounts/t_01/grants", 403, """{"error":"unknown device"}""")
    val m = model(scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    assertFalse(m.state.value.planqueReady)
    // planque answers again, without the grant yet: this read starts the re-reads.
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[]}""")
    m.loadChat(COACH_CHAT)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    advanceTimeBy(AppModel.SET_UP_READ_MS + 1)
    runCurrent()
    assertTrue(m.state.value.grants.any { it.id == "g1" })
  }

  // A live test: the owner came back to tafs with no link after Stripe's sign-in had reached planque, and Open again opened the
  // same link, which planque takes once, so the page said it was used. Open again now ends that wait quietly and runs the card's
  // approval again: planque, holding the sign-in, answers signed_in with its confirm code, and the card is signed in, no page.
  @Test fun openAgainRunsTheApprovalAgainAndTakesTheSignedInPath() = runTest {
    reads()
    coachThread(signInCard)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth"}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"signed_in","confirm":"c0de"}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/confirm", 200, grant("connected"))
    val left = kotlinx.coroutines.flow.MutableStateFlow(false)
    var waiting = kotlinx.coroutines.CompletableDeferred<String>()
    val opened = mutableListOf<String>()
    var reopened = 0
    val signIn = object : VendorSignIn {
      override suspend fun signIn(url: String): String { opened += url; waiting = kotlinx.coroutines.CompletableDeferred(); return waiting.await() }
      override val left = left
      override fun reopen() { reopened++ }
      override fun replace() { waiting.completeExceptionally(bot.tafs.app.kiff.VendorSignInReplaced()) }
    }
    val m = model(signIn, scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.startSignInAgain(KiffClient.json.decodeFromString(signInCard))
    runCurrent()
    assertEquals(listOf("https://mcp.stripe.com/oauth"), opened)
    left.value = true
    runCurrent()
    m.reopenSignIn()
    repeat(20) { advanceTimeBy(100); runCurrent() }
    // The same link was never opened again; the approval ran once more, and planque's signed-in answer was confirmed.
    assertEquals(0, reopened)
    assertEquals(listOf("https://mcp.stripe.com/oauth"), opened)
    assertEquals(2, transport.requestsTo("POST /v1/accounts/t_01/grants/g1/approve").size)
    assertEquals(1, transport.requestsTo("POST /v1/accounts/t_01/grants/g1/confirm").size)
    // Coach heard one answer, the second approval's: never "failed" for the page given up.
    assertEquals(1, transport.requestsTo("POST /v1/tafs/setup_1/components/s1/answer").size)
    assertEquals("signed in", answered("s1"))
    assertNull(m.state.value.connecting)
  }

  /** Two sign-ins, g1's and g2's, each waiting on its vendor's page until replaced; [left] is the owner back with no link. */
  private class TwoPages : VendorSignIn {
    val opened = mutableListOf<String>()
    override val left = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var waiting = kotlinx.coroutines.CompletableDeferred<String>()
    override suspend fun signIn(url: String): String { opened += url; waiting = kotlinx.coroutines.CompletableDeferred(); left.value = false; return waiting.await() }
    override fun replace() { waiting.completeExceptionally(bot.tafs.app.kiff.VendorSignInReplaced()) }
  }

  private fun twoSignIns() {
    coachThread(signInCard)
    val g2 = grant("approved").replace(""""id":"g1"""", """"id":"g2"""")
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")},$g2]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth/1"}""")
    transport.answer("POST /v1/accounts/t_01/grants/g2/approve", 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth/2"}""")
  }

  private val secondSignIn get() = KiffClient.json.decodeFromString<SetupComponent>(signInCard.replace(""""id":"s1","grant":"g1"""", """"id":"s2","grant":"g2""""))

  // The owner on the emulator, 21 Nov: a sign-in closed on its vendor's page with no link held every other approval card, greyed
  // with no word why, until the app was opened again. A new approval now takes over from it, as Open again does: the left one's
  // wait ends quietly (Coach hears nothing from it), and the new one goes on.
  @Test fun aSignInLeftOnItsVendorsPageGivesWayToANewApproval() = runTest {
    reads()
    twoSignIns()
    val pages = TwoPages()
    val m = model(pages, scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.startSignInAgain(KiffClient.json.decodeFromString(signInCard))
    runCurrent()
    assertEquals("g1", m.state.value.connecting)
    pages.left.value = true
    runCurrent()
    // Left, it holds no other card: the second card's buttons are on.
    assertFalse(bot.tafs.app.views.approvalHeld(m.state.value, "g2"))
    m.startSignInAgain(secondSignIn)
    repeat(20) { advanceTimeBy(100); runCurrent() }
    assertEquals(listOf("https://mcp.stripe.com/oauth/1", "https://mcp.stripe.com/oauth/2"), pages.opened)
    assertEquals("g2", m.state.value.connecting)
    assertNull(m.state.value.coachNotes["s2"])
    // The first card was given up quietly: no "failed" answer went to Coach for it.
    assertEquals(0, transport.requestsTo("POST /v1/tafs/setup_1/components/s1/answer").size)
  }

  // Still on the vendor's page (the owner may be signing in there), a sign-in keeps the turn: the other card says so.
  @Test fun aSignInStillOnItsVendorsPageHoldsTheNextApprovalAndSaysSo() = runTest {
    reads()
    twoSignIns()
    val pages = TwoPages()
    val m = model(pages, scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    m.startSignInAgain(KiffClient.json.decodeFromString(signInCard))
    runCurrent()
    assertTrue(bot.tafs.app.views.approvalHeld(m.state.value, "g2"))
    m.startSignInAgain(secondSignIn)
    repeat(5) { advanceTimeBy(100); runCurrent() }
    assertEquals(listOf("https://mcp.stripe.com/oauth/1"), pages.opened)
    assertEquals("g1", m.state.value.connecting)
    assertEquals(bot.tafs.app.model.Words.Connect.anotherApproval, m.state.value.coachNotes["s2"])
  }

  // With no approval waiting on the page (none this model started), Open again opens the same page, as before.
  @Test fun openAgainWithNoApprovalToRunOpensTheSamePage() = runTest {
    reads()
    var reopened = 0
    val signIn = object : VendorSignIn {
      override suspend fun signIn(url: String): String = kotlinx.coroutines.awaitCancellation()
      override fun reopen() { reopened++ }
    }
    val m = model(signIn, scope = backgroundScope)
    m.reopenSignIn()
    assertEquals(1, reopened)
  }

  // The owner on Android 97: back in tafs with no link from Google's page, the card offers the page again rather than drop it.
  @Test fun aSignInTheOwnerCameBackFromWithNoLinkIsOfferedAgain() = runTest {
    reads()
    coachThread(signInCard)
    transport.answer("GET /v1/accounts/t_01/grants", 200, """{"grants":[${grant("approved")}]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/approve", 200, """{"status":"approved","sign_in_url":"https://mcp.stripe.com/oauth"}""")
    val left = kotlinx.coroutines.flow.MutableStateFlow(false)
    val back = kotlinx.coroutines.CompletableDeferred<String>()
    var reopened = 0
    val signIn = object : VendorSignIn {
      override suspend fun signIn(url: String): String = back.await()
      override val left = left
      override fun reopen() { reopened++; left.value = false }
    }
    val m = model(signIn, scope = backgroundScope)
    m.loadCoach()
    m.loadChat(COACH_CHAT)
    val going = backgroundScope.launch { m.signInAgain(KiffClient.json.decodeFromString(signInCard)) }
    runCurrent()
    assertTrue(m.state.value.vendorPageOpen)
    assertFalse(m.state.value.vendorPageLeft)
    left.value = true
    runCurrent()
    assertTrue("the card offers the page again", m.state.value.vendorPageLeft)
    signIn.reopen()
    runCurrent()
    assertFalse(m.state.value.vendorPageLeft)
    assertEquals(1, reopened)
    back.completeExceptionally(VendorSignInCancelled())
    going.join()
    assertFalse(m.state.value.vendorPageOpen || m.state.value.vendorPageLeft)
  }
}
