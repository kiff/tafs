package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.isTrialChat
import bot.tafs.app.app.tafOfTrialChat
import bot.tafs.app.app.trialChatId
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.OpenTrial
import bot.tafs.app.kiff.Version
import bot.tafs.app.model.VersionWords
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trying a version beside the live taf, under a trial Card of its own. A fake KIFF and tafs cloud stand in: no real
 * taf is tried, no real Card is confirmed, and no real trial is started, installed or ended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrialsTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val chat = trialChatId("refunds-1")

  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}"""
  private val home =
    """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}"""

  private val trialGet = "GET /v1/tafs/taf_1/trial"
  private val tryDraft = "POST /v1/tafs/taf_1/versions/2/try"
  private val openPut = "PUT /v1/tafs/taf_1/trial"
  private val draftGet = "GET /v1/me/agent-drafts/dr_t"
  private val confirm = "POST /v1/me/agent-drafts/dr_t/confirm"
  private val install = "POST /v1/tafs/taf_1/trial/install"
  private val kiffEnd = "DELETE /v1/me/agent-drafts/dr_t/trial"
  private val cloudEnd = "DELETE /v1/tafs/taf_1/trial"
  private val versions = "GET /v1/tafs/taf_1/versions"

  private val trialJson =
    """{"id":"t1","n":2,"draft":"dr_t","session":"s9","started":"2026-10-28T09:00:00Z","used":"2026-10-28T09:00:00Z","label":"Trial","title":"Trying version 2","changed":"Raises the limit","try":"a refund of 80","ends":"2026-11-04T09:00:00Z"}"""
  private val summary = """{"started":"2026-10-28T09:00:00Z","ended":"2026-10-28T10:00:00Z","why":"installed","calls":2,"held":0,"refused":0}"""
  private val version2 = Version(2, "Raises the limit", "setup", t0, false, "dr_1")

  private fun draft(status: String = "draft", version: String = "v3") =
    """{"id":"dr_t","agent_id":"refunds-1","status":"$status","version":"$version","label":"Refunds",
        "cards":[{"grants":[{"action":"create_refund","title":"Refund a payment","per_action_max":[{"quantity":"sum(amount)","max":10000,"unit":"EUR","scale":2}]}]}]}"""

  private fun reads() {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":true}}}""")
    transport.answer(versions, 200, """{"versions":[{"n":1,"created":"2026-10-20T09:00:00Z","live":true},{"n":2,"created":"2026-10-27T09:00:00Z","live":false}],"switching":null}""")
  }

  private suspend fun TestScope.model(keyed: Boolean = true): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    if (keyed) secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0.plusMillis(currentTime) }, start = false, scope = backgroundScope)
      .also { it.restore() }
  }

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDER))
    return Signature.getInstance("SHA256withECDSA").run {
      initVerify(key)
      update(message)
      verify(Base64.getDecoder().decode(signatureBase64))
    }
  }

  private fun sent(key: String) = transport.requestsTo(key)

  private fun body(key: String) = KiffClient.json.parseToJsonElement(String(sent(key).single().body!!)).jsonObject

  /** A model with the trial of version 2 already open, as tafs cloud says. */
  private suspend fun TestScope.trying(): AppModel {
    reads()
    transport.answer(trialGet, 200, """{"trial":$trialJson}""")
    val m = model()
    m.loadTrial("refunds-1")
    return m
  }

  // MARK: Reading

  @Test fun anOpenTrialIsReadFromTafsCloud() = runTest {
    reads()
    transport.answer(trialGet, 200, """{"trial":$trialJson}""")
    val m = model()
    m.loadTrial("refunds-1")
    val trial = m.state.value.openTrials["refunds-1"]!!
    assertEquals(2, trial.n)
    assertEquals("dr_t", trial.draft)
    assertEquals("a refund of 80", trial.tryWith)
    assertEquals("https://cloud.tafs.kiff.dev/v1/tafs/taf_1/trial", sent(trialGet).single().url)
  }

  @Test fun aTrialThatEndedElsewhereLeavesHereToo() = runTest {
    val m = trying()
    assertNotNull(m.state.value.openTrials["refunds-1"])
    transport.answer(trialGet, 200, """{"trial":null}""")
    m.loadTrial("refunds-1")
    assertNull(m.state.value.openTrials["refunds-1"])
  }

  @Test fun aTrialThatCannotBeReadSaysSoAndKeepsWhatWasKnown() = runTest {
    val m = trying()
    transport.answer(trialGet, 500, """{"error":"later"}""")
    m.loadTrial("refunds-1")
    assertEquals("The trial couldn't be read: later", m.state.value.trialErrors["refunds-1"])
    assertNotNull(m.state.value.openTrials["refunds-1"])
  }

  @Test fun theTrialsChatIsTheTafsChatViewOnItsOwnId() = runTest {
    val m = trying()
    assertEquals("refunds-1#trial", chat)
    assertTrue(isTrialChat(chat))
    assertFalse(isTrialChat("refunds-1"))
    assertEquals("refunds-1", tafOfTrialChat(chat))
    val shown = m.state.value.taf(chat)!!
    assertEquals(chat, shown.id)
    assertEquals("Refunds · v2", shown.name)
    assertEquals("Trial", shown.badge)
    assertTrue(shown.now.startsWith("Trying version 2 · ends "))
    assertEquals("taf_1", shown.cloudId)
    assertTrue(m.state.value.canChat(chat))
    assertEquals("Refunds", m.state.value.taf("refunds-1")!!.name)
    assertNull("no trial open: no such chat", m.state.value.taf("other#trial"))
  }

  // MARK: Starting

  @Test fun theTrialsCardIsReadFromKiffBeforeAnythingIsSigned() = runTest {
    reads()
    transport.answer(tryDraft, 200, """{"draft":"dr_t"}""")
    transport.answer(draftGet, 200, draft())
    val m = model()
    assertEquals("v3", m.trialCard(version2, "refunds-1")!!.version)
    assertTrue(auth.asked.isEmpty())
    assertTrue(sent(confirm).isEmpty())
  }

  @Test fun aTrialCardThatCannotBeReadSaysSo() = runTest {
    reads()
    transport.answer(tryDraft, 409, """{"error":"no card to try"}""")
    val m = model()
    assertNull(m.trialCard(version2, "refunds-1"))
    assertEquals("Version 2's trial Card couldn't be read: no card to try", m.state.value.trialErrors["refunds-1"])
  }

  @Test fun startingATrialIsConfirmedInKiffAsATrialByTheApprovalKeyThenOpened() = runTest {
    reads()
    transport.answer(tryDraft, 200, """{"draft":"dr_t"}""")
    transport.answer(draftGet, 200, draft())
    transport.answer(confirm, 200, draft("trying"))
    transport.answer(openPut, 200, """{"trial":$trialJson}""")
    val m = model()
    assertEquals(chat, m.startTrying(version2, "refunds-1", "v3"))
    assertEquals(listOf("Try version 2 of Refunds, with its own Card"), auth.asked)
    val request = sent(confirm).single()
    assertEquals("trial", body(confirm).getValue("as").jsonPrimitive.content)
    assertEquals("v3", body(confirm).getValue("version").jsonPrimitive.content)
    val parts = request.headers.getValue("KIFF-Device-Signature").split(",")
    val message = KiffClient.requestMessage("t_01", "POST", "/v1/me/agent-drafts/dr_t/confirm", request.body!!, parts[0].removePrefix("t="))
    assertTrue("it issues Cards: the approval key signs it", verifies(signer.approvalPublicKeyDER, message, parts[1].removePrefix("v1=")))
    assertEquals("2", body(openPut).getValue("n").jsonPrimitive.content)
    assertEquals("dr_t", body(openPut).getValue("draft").jsonPrimitive.content)
    assertEquals(2, m.state.value.openTrials["refunds-1"]!!.n)
    assertTrue(m.state.value.trialBusy.isEmpty())
    val order = transport.requests.map { it.method + " " + java.net.URI(it.url).path }
    assertTrue(order.indexOf("POST /v1/me/agent-drafts/dr_t/confirm") < order.indexOf("PUT /v1/tafs/taf_1/trial"))
  }

  @Test fun aKiffThatDoesNotRunTrialsIsNeverTakenForOne() = runTest {
    reads()
    transport.answer(tryDraft, 200, """{"draft":"dr_t"}""")
    transport.answer(draftGet, 200, draft())
    transport.answer(confirm, 200, draft("confirmed"))
    val m = model()
    assertNull(m.startTrying(version2, "refunds-1", "v3"))
    assertEquals(VersionWords.notATrial("confirmed"), m.state.value.trialErrors["refunds-1"])
    assertTrue(m.state.value.trialErrors["refunds-1"]!!.contains("issued these Cards as the taf's own"))
    assertTrue(sent(openPut).isEmpty())
    assertNull(m.state.value.openTrials["refunds-1"])
    assertTrue(m.state.value.trialBusy.isEmpty())
  }

  @Test fun anyOtherAnswerToTheConfirmIsSaidAsItIs() = runTest {
    reads()
    transport.answer(tryDraft, 200, """{"draft":"dr_t"}""")
    transport.answer(draftGet, 200, draft())
    transport.answer(confirm, 200, draft("failed"))
    val m = model()
    assertNull(m.startTrying(version2, "refunds-1", "v3"))
    assertEquals("KIFF didn't start a trial for this version (it answered failed). Nothing runs under it.", m.state.value.trialErrors["refunds-1"])
    assertTrue(sent(openPut).isEmpty())
  }

  @Test fun aCardThatChangedSinceItWasShownIsReadAgainAndNothingIsSigned() = runTest {
    reads()
    transport.answer(tryDraft, 200, """{"draft":"dr_t"}""")
    transport.answer(draftGet, 200, draft(version = "v4"))
    val m = model()
    assertNull(m.startTrying(version2, "refunds-1", "v3"))
    assertEquals("v4", m.state.value.changedVersionCards["refunds-1"]!!.version)
    assertEquals("What you're asked to approve changed since it was shown. Read it again, then start.", m.state.value.trialErrors["refunds-1"])
    assertTrue(auth.asked.isEmpty())
    assertTrue(sent(confirm).isEmpty())
  }

  @Test fun anOwnerWhoDoesNotConfirmStartsNothing() = runTest {
    reads()
    auth.confirms = false
    transport.answer(tryDraft, 200, """{"draft":"dr_t"}""")
    transport.answer(draftGet, 200, draft())
    val m = model()
    assertNull(m.startTrying(version2, "refunds-1", "v3"))
    assertTrue(sent(confirm).isEmpty())
    assertTrue(sent(openPut).isEmpty())
    assertTrue(m.state.value.trialBusy.isEmpty())
  }

  @Test fun theLiveVersionIsNotTried() = runTest {
    reads()
    val m = model()
    assertNull(m.startTrying(version2.copy(live = true), "refunds-1", "v3"))
    assertTrue(transport.requests.none { it.url.contains("/try") })
  }

  @Test fun aSessionThatCannotSignForThePhoneDoesNotStart() = runTest {
    reads()
    val m = model(keyed = false)
    assertNull(m.startTrying(version2, "refunds-1", "v3"))
    assertEquals("Sign in with KIFF again: this sign-in can't sign for the phone.", m.state.value.trialErrors["refunds-1"])
    assertTrue(transport.requests.none { it.url.contains("/try") })
  }

  @Test fun aTrialThatKiffTookButTafsCloudCouldNotOpenSaysSo() = runTest {
    reads()
    transport.answer(tryDraft, 200, """{"draft":"dr_t"}""")
    transport.answer(draftGet, 200, draft())
    transport.answer(confirm, 200, draft("trying"))
    transport.answer(openPut, 409, """{"error":"KIFF has no trial yet"}""")
    val m = model()
    assertNull(m.startTrying(version2, "refunds-1", "v3"))
    assertEquals("Trying version 2 didn't start: KIFF has no trial yet", m.state.value.trialErrors["refunds-1"])
    assertNull(m.state.value.openTrials["refunds-1"])
  }

  // MARK: Chatting with the version

  @Test fun theTrialsChatIsReadFromItsOwnConversation() = runTest {
    val m = trying()
    transport.answer("GET /v1/tafs/taf_1/trial/conversation", 200, """{"messages":[{"at":"2026-10-28T09:01:00Z","from":"taf","text":"Ready to try.","session":"s9"}]}""")
    m.loadChat(chat)
    assertEquals(listOf("Ready to try."), m.state.value.chats[chat].orEmpty().map { it.text })
    assertTrue("the live thread isn't touched", transport.requestsTo("GET /v1/tafs/taf_1/conversation").isEmpty())
    assertTrue(m.state.value.chats["refunds-1"].isNullOrEmpty())
  }

  @Test fun aMessageToTheTrialGoesToItsOwnRouteNeverSteeringTheLiveRun() = runTest {
    val m = trying()
    transport.answer("GET /v1/tafs/taf_1/trial/conversation", 200, """{"messages":[],"working":{"session":"s9","run":"r9"}}""")
    m.loadChat(chat)
    transport.answer("POST /v1/tafs/taf_1/trial/messages", 200, """{"session":"s9","run":"r10"}""")
    assertTrue(m.deliverChat("refund order 4502", chat))
    assertEquals("refund order 4502", body("POST /v1/tafs/taf_1/trial/messages").getValue("text").jsonPrimitive.content)
    assertTrue(transport.requests.none { it.url.endsWith("/v1/tafs/taf_1/messages") || it.url.endsWith("/steer") })
  }

  // MARK: Install and Go back

  @Test fun installingConfirmsTheTrialsCardLiveThenMakesTheVersionLiveAndEndsTheTrial() = runTest {
    val m = trying()
    transport.answer(draftGet, 200, draft())
    transport.answer(confirm, 200, draft("confirmed"))
    transport.answer(install, 200, """{"live":2,"ended":$summary}""")
    transport.answer("GET /v1/tafs/taf_1/trial/conversation", 200, """{"messages":[{"at":"2026-10-28T09:01:00Z","from":"taf","text":"Hi","session":"s9"}]}""")
    m.loadChat(chat)
    assertTrue(m.installTrial("refunds-1"))
    assertEquals(listOf("Install version 2 of Refunds, with its Card"), auth.asked)
    assertEquals("v3", body(confirm).getValue("version").jsonPrimitive.content)
    assertNull("a plain confirm, not as a trial", body(confirm)["as"])
    val order = transport.requests.map { it.method + " " + java.net.URI(it.url).path }
    assertTrue(order.indexOf("POST /v1/me/agent-drafts/dr_t/confirm") < order.indexOf("POST /v1/tafs/taf_1/trial/install"))
    assertNull(m.state.value.openTrials["refunds-1"])
    assertNull("its chat is gone with it", m.state.value.chats[chat])
    assertTrue(transport.requestsTo(versions).isNotEmpty())
    assertTrue(m.state.value.trialBusy.isEmpty())
  }

  @Test fun aTrialCardKiffAlreadyHasLiveIsNotConfirmedAgain() = runTest {
    val m = trying()
    transport.answer(draftGet, 200, draft("confirmed"))
    transport.answer(install, 200, """{"live":2,"ended":$summary}""")
    assertTrue(m.installTrial("refunds-1"))
    assertTrue(auth.asked.isEmpty())
    assertTrue(sent(confirm).isEmpty())
  }

  @Test fun anInstallTheOwnerDidNotConfirmLeavesTheTrialOpen() = runTest {
    val m = trying()
    auth.confirms = false
    transport.answer(draftGet, 200, draft())
    assertFalse(m.installTrial("refunds-1"))
    assertTrue(sent(confirm).isEmpty())
    assertTrue(sent(install).isEmpty())
    assertNotNull(m.state.value.openTrials["refunds-1"])
  }

  @Test fun anInstallKiffOrTafsCloudRefusesSaysSoAndLeavesTheTrialOpen() = runTest {
    val m = trying()
    transport.answer(draftGet, 200, draft())
    transport.answer(confirm, 200, draft("confirmed"))
    transport.answer(install, 409, """{"error":"KIFF hasn't confirmed it"}""")
    assertFalse(m.installTrial("refunds-1"))
    assertEquals("Installing version 2 didn't finish: KIFF hasn't confirmed it", m.state.value.trialErrors["refunds-1"])
    assertNotNull(m.state.value.openTrials["refunds-1"])
  }

  @Test fun goingBackEndsKiffsTrialCardsThenTafsCloudsTrialWithNoConfirmation() = runTest {
    val m = trying()
    transport.answer(kiffEnd, 200, "{}")
    transport.answer(cloudEnd, 200, """{"ended":${summary.replace("installed", "back")}}""")
    assertTrue(m.goBackFromTrial("refunds-1"))
    assertTrue("it takes authority away: nothing to confirm", auth.asked.isEmpty())
    val end = sent(kiffEnd).single()
    assertEquals("https://api.kiff.dev/v1/me/agent-drafts/dr_t/trial", end.url)
    val parts = end.headers.getValue("KIFF-Device-Signature").split(",")
    val message = KiffClient.requestMessage("t_01", "DELETE", "/v1/me/agent-drafts/dr_t/trial", end.body ?: ByteArray(0), parts[0].removePrefix("t="))
    assertTrue("signed by the plain key", verifies(signer.publicKeyDER, message, parts[1].removePrefix("v1=")))
    val order = transport.requests.map { it.method + " " + java.net.URI(it.url).path }
    assertTrue(order.indexOf("DELETE /v1/me/agent-drafts/dr_t/trial") < order.indexOf("DELETE /v1/tafs/taf_1/trial"))
    assertNull(m.state.value.openTrials["refunds-1"])
    assertTrue(m.state.value.trialBusy.isEmpty())
  }

  @Test fun aTrialKiffHadAlreadyEndedCountsAsEnded() = runTest {
    val m = trying()
    transport.answer(kiffEnd, 404, """{"error":"no trial"}""")
    transport.answer(cloudEnd, 200, """{"ended":$summary}""")
    assertTrue(m.goBackFromTrial("refunds-1"))
    assertNull(m.state.value.openTrials["refunds-1"])
  }

  @Test fun aGoBackKiffRefusesLeavesTheTrialOpenAndSaysSo() = runTest {
    val m = trying()
    transport.answer(kiffEnd, 500, """{"error":"kiff is down"}""")
    assertFalse(m.goBackFromTrial("refunds-1"))
    assertEquals("The trial didn't end: kiff is down", m.state.value.trialErrors["refunds-1"])
    assertTrue(sent(cloudEnd).isEmpty())
    assertNotNull(m.state.value.openTrials["refunds-1"])
  }

  @Test fun withNoTrialOpenThereIsNothingToInstallOrEnd() = runTest {
    reads()
    val m = model()
    assertFalse(m.installTrial("refunds-1"))
    assertFalse(m.goBackFromTrial("refunds-1"))
    assertTrue(transport.requests.none { it.url.contains("/trial") })
  }

  // MARK: What tafs cloud sends

  @Test fun aTrialsGuideReadsLeniently() {
    val t = KiffClient.json.decodeFromString<OpenTrial>("""{"n":3,"draft":"d","brand_new":1}""")
    assertEquals(3, t.n)
    assertNull(t.label)
    assertNull(t.tryWith)
    assertNull(t.ends)
  }

  @Test fun theGuideSaysWhatIsNewWhatToTryAndWhenItEnds() {
    val ends = Instant.parse("2026-11-04T09:00:00Z")
    val full = VersionWords.trialGuide("Raises the limit", "a refund of 80", "Refunds", ends)
    assertEquals("What's new: Raises the limit", full[0])
    assertEquals("Try it with: a refund of 80", full[1])
    assertTrue(full[2].startsWith("Refunds keeps working as it is."))
    assertTrue(full[2].contains("The trial ends "))
    assertTrue(full[2].endsWith("Install makes it the live version; Go back ends the trial."))
    val bare = VersionWords.trialGuide(null, null, "Refunds", null)
    assertEquals("Give it a task that uses the change, as you would the live Refunds.", bare[0])
    assertFalse(bare[1].contains("The trial ends"))
  }

  @Test fun whatTryingMeansIsSaidBeforeTheConfirmation() {
    val lines = VersionWords.whileYouTry(2)
    assertEquals(4, lines.size)
    assertTrue(lines[0].startsWith("You chat with version 2 in its own window."))
    assertTrue(lines[3].contains("after 7 days unused"))
  }
}
