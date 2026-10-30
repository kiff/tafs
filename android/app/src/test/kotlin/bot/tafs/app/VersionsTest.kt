package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.AgentDraft
import bot.tafs.app.kiff.BlockingRun
import bot.tafs.app.kiff.DraftCard
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.TrialSummary
import bot.tafs.app.kiff.Version
import bot.tafs.app.model.Money
import bot.tafs.app.model.VersionWords
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A taf's versions: read, and installed, forward or back, behind one confirmation. A fake KIFF and tafs cloud stand
 * in: no real taf's version is installed and no real Card is confirmed.
 */
class VersionsTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}"""
  private val home =
    """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}"""

  private val versions = "GET /v1/tafs/taf_1/versions"
  private val switch2 = "POST /v1/tafs/taf_1/versions/2/switch"
  private val live2 = "POST /v1/tafs/taf_1/versions/2/live"
  private val cancel = "DELETE /v1/tafs/taf_1/switching"
  private val draftGet = "GET /v1/me/agent-drafts/dr_1"
  private val draftConfirm = "POST /v1/me/agent-drafts/dr_1/confirm"

  private fun v1(live: Boolean) = """{"n":1,"note":"First","created":"2026-10-20T09:00:00Z","live":$live}"""
  private fun v2(live: Boolean, draft: Boolean) =
    """{"n":2,"note":"Refund up to 100","by":"setup","created":"2026-10-27T09:00:00Z","live":$live${if (draft) ""","draft":"dr_1"""" else ""}}"""
  private fun state(vararg v: String, switching: String = "null") = """{"versions":[${v.joinToString(",")}],"switching":$switching}"""
  private fun switching(draft: String?) = """{"switching":{"n":2,"draft":${draft?.let { "\"$it\"" } ?: "null"},"at":"2026-10-28T09:00:00Z"}}"""

  private fun draft(status: String = "draft", version: String = "v3") =
    """{"id":"dr_1","agent_id":"refunds-1","status":"$status","version":"$version","label":"Refunds",
        "cards":[{"grants":[{"action":"create_refund","title":"Refund a payment","per_action_max":[{"quantity":"sum(amount)","max":10000,"unit":"EUR","scale":2}]}],
                  "aggregates":[{"quantity":"sum(amount)","limit":100000,"window":"day","unit":"EUR","scale":2}],
                  "revises":"c1","current":{"grants":[{"action":"create_refund","title":"Refund a payment","per_action_max":[{"quantity":"sum(amount)","max":5000,"unit":"EUR","scale":2}]}]}}]}"""

  private fun reads(manage: Boolean = true) {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":$manage}}}""")
  }

  private suspend fun model(keyed: Boolean = true): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    if (keyed) secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore() }
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

  // MARK: Who manages a taf

  @Test fun aTafIsManagedOnlyWhereKiffSaysSo() = runTest {
    reads(manage = true)
    assertTrue(model().state.value.manages("refunds-1"))
  }

  @Test fun aViewerManagesNothingAndNeitherDoesAnUnreadAnswer() = runTest {
    reads(manage = false)
    val viewer = model().state.value
    assertFalse(viewer.manages("refunds-1"))
    assertFalse(viewer.manages("nobody"))
    val unread = FakeTransport().also {
      it.answer("GET /v1/me", 200, me); it.answer("GET /v1/home", 200, home)
      it.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}"""); it.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    }
    secrets.save("at_1", AppModel.TOKEN); secrets.save("t_01", AppModel.TENANT); secrets.save("1", AppModel.KEYED)
    val m = testModel(auth = auth, transport = unread, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore() }
    assertFalse("KIFF didn't say: nothing is shown", m.state.value.manages("refunds-1"))
  }

  // MARK: Reading

  @Test fun theVersionsAreReadFromTafsCloudAndASwitchUnderWayWithThem() = runTest {
    reads()
    transport.answer(versions, 200, state(v1(true), v2(false, true), switching = """{"n":2,"draft":"dr_1","at":"2026-10-28T08:00:00Z"}"""))
    val m = model()
    m.loadVersions("refunds-1")
    val s = m.state.value
    assertEquals(listOf(1, 2), s.versions["refunds-1"]!!.map { it.n })
    assertTrue(s.versions["refunds-1"]!![0].live)
    assertEquals("dr_1", s.versions["refunds-1"]!![1].draft)
    assertEquals(2, s.switches["refunds-1"]!!.n)
    assertEquals("Bearer at_1", sent(versions).single().headers["Authorization"])
    assertEquals("https://cloud.tafs.kiff.dev/v1/tafs/taf_1/versions", sent(versions).single().url)
  }

  @Test fun versionsThatCannotBeReadSayWhy() = runTest {
    reads()
    transport.answer(versions, 500, """{"error":"later"}""")
    val m = model()
    m.loadVersions("refunds-1")
    assertEquals("Its versions couldn't be loaded. It isn't working on our side right now. Nothing to do on your side; try again later.", m.state.value.versionErrors["refunds-1"])
    assertNull(m.state.value.versions["refunds-1"])
  }

  @Test fun aSwitchThatEndedIsForgotten() = runTest {
    reads()
    transport.answer(versions, 200, state(v1(true), v2(false, true), switching = """{"n":2,"draft":"dr_1"}"""))
    transport.answer(versions, 200, state(v1(false), v2(true, false)))
    val m = model()
    m.loadVersions("refunds-1")
    m.loadVersions("refunds-1")
    assertTrue(m.state.value.switches.isEmpty())
    assertTrue(m.state.value.versions["refunds-1"]!![1].live)
  }

  // MARK: Installing a version with no Card to change

  @Test fun aVersionWithNoCardIsConfirmedOnceThenMadeLive() = runTest {
    reads()
    transport.answer(versions, 200, state(v1(true), v2(false, false)))
    transport.answer(versions, 200, state(v1(false), v2(true, false)))
    transport.answer(switch2, 200, switching(null))
    transport.answer(live2, 200, """{"live":2}""")
    val m = model()
    m.loadVersions("refunds-1")
    val version = m.state.value.versions["refunds-1"]!![1]
    assertTrue(m.prepareSwitch(version, "refunds-1")!!.draft == null)
    assertTrue(m.useVersion(version, "refunds-1"))
    assertEquals(listOf("Use version 2 of Refunds"), auth.asked)
    assertTrue("no Card to read or confirm", transport.requests.none { it.url.contains("/agent-drafts") })
    assertTrue(m.state.value.versions["refunds-1"]!![1].live)
    assertTrue(m.state.value.switchingVersion.isEmpty())
    assertNull(m.state.value.versionErrors["refunds-1"])
    val order = transport.requests.map { it.method + " " + java.net.URI(it.url).path }
    assertTrue(order.toString(), order.indexOf("POST /v1/tafs/taf_1/versions/2/switch") < order.indexOf("POST /v1/tafs/taf_1/versions/2/live"))
  }

  @Test fun theSwitchIsBegunWithNoRedraftAndNoStop() = runTest {
    reads()
    transport.answer(switch2, 200, switching(null))
    transport.answer(live2, 200, """{"live":2}""")
    transport.answer(versions, 200, state(v1(false), v2(true, false)))
    val m = model()
    m.useVersion(Version(2, "n", null, t0, false), "refunds-1")
    val body = KiffClient.json.parseToJsonElement(String(sent(switch2).first().body!!)).jsonObject
    assertEquals("false", body.getValue("redraft").jsonPrimitive.content)
    assertEquals("false", body.getValue("stop_blocking").jsonPrimitive.content)
  }

  @Test fun anInstallTheOwnerDidNotConfirmDropsTheSwitchSoTheTafRunsAgain() = runTest {
    reads()
    auth.confirms = false
    transport.answer(switch2, 200, switching(null))
    transport.answer(cancel, 200, """{"cancelled":true}""")
    transport.answer(versions, 200, state(v1(true), v2(false, false)))
    val m = model()
    assertFalse(m.useVersion(Version(2, "n", null, t0, false), "refunds-1"))
    assertEquals(1, sent(cancel).size)
    assertTrue("never made live", sent(live2).isEmpty())
    assertTrue(m.state.value.versions["refunds-1"]!![0].live)
  }

  @Test fun aLiveVersionIsNotInstalledAgain() = runTest {
    reads()
    val m = model()
    assertFalse(m.useVersion(Version(1, "n", null, t0, true), "refunds-1"))
    assertNull(m.prepareSwitch(Version(1, "n", null, t0, true), "refunds-1"))
    assertTrue(transport.requests.none { it.url.contains("/switch") })
  }

  @Test fun aVersionThatCannotGoLiveSaysSoAndTheSwitchStaysForTheOwnerToFinishOrCancel() = runTest {
    reads()
    transport.answer(switch2, 200, switching(null))
    transport.answer(live2, 409, """{"error":"not ready"}""")
    val m = model()
    assertFalse(m.useVersion(Version(2, "n", null, t0, false), "refunds-1"))
    assertEquals("Version 2 isn't live. It changed in the meantime. What's shown now is how it is.", m.state.value.versionErrors["refunds-1"])
    assertTrue(m.state.value.switchingVersion.isEmpty())
    assertTrue(sent(cancel).isEmpty())
  }

  @Test fun aSwitchThatCannotBeginSaysSo() = runTest {
    reads()
    transport.answer(switch2, 500, """{"error":"down"}""")
    val m = model()
    assertFalse(m.useVersion(Version(2, "n", null, t0, false), "refunds-1"))
    assertEquals("Version 2 can't go live now. It isn't working on our side right now. Nothing to do on your side; try again later.", m.state.value.versionErrors["refunds-1"])
    assertTrue(auth.asked.isEmpty())
  }

  // MARK: Installing a version that brings a Card

  @Test fun theCardIsReadFromKiffBeforeAnythingIsSigned() = runTest {
    reads()
    transport.answer(switch2, 200, switching("dr_1"))
    transport.answer(draftGet, 200, draft())
    val m = model()
    val version = Version(2, "n", "setup", t0, false, "dr_1")
    assertEquals("dr_1", m.prepareSwitch(version, "refunds-1")!!.draft)
    val card = m.versionCard(version, "refunds-1")!!
    assertEquals("v3", card.version)
    assertEquals("https://api.kiff.dev/v1/me/agent-drafts/dr_1", sent(draftGet).single().url)
    assertTrue(auth.asked.isEmpty())
    assertTrue(sent(draftConfirm).isEmpty())
  }

  @Test fun aVersionWithACardIsConfirmedInKiffByTheApprovalKeyOnTheDraftTheOwnerSawThenMadeLive() = runTest {
    reads()
    transport.answer(switch2, 200, switching("dr_1"))
    transport.answer(draftGet, 200, draft())
    transport.answer(draftConfirm, 200, draft("confirmed"))
    transport.answer(live2, 200, """{"live":2}""")
    transport.answer(versions, 200, state(v1(false), v2(true, false)))
    val m = model()
    assertTrue(m.useVersion(Version(2, "n", "setup", t0, false, "dr_1"), "refunds-1", shownDraft = "v3"))
    assertEquals(listOf("Use version 2 of Refunds, with its Card"), auth.asked)
    val confirm = sent(draftConfirm).single()
    assertEquals("https://api.kiff.dev/v1/me/agent-drafts/dr_1/confirm", confirm.url)
    assertEquals("v3", KiffClient.json.parseToJsonElement(String(confirm.body!!)).jsonObject.getValue("version").jsonPrimitive.content)
    val parts = confirm.headers.getValue("KIFF-Device-Signature").split(",")
    val at = parts[0].removePrefix("t=")
    val message = KiffClient.requestMessage("t_01", "POST", "/v1/me/agent-drafts/dr_1/confirm", confirm.body!!, at)
    assertTrue("it issues Cards: the approval key signs it", verifies(signer.approvalPublicKeyDER, message, parts[1].removePrefix("v1=")))
    assertFalse(verifies(signer.publicKeyDER, message, parts[1].removePrefix("v1=")))
    val order = transport.requests.map { it.method + " " + java.net.URI(it.url).path }
    assertTrue(order.indexOf("POST /v1/me/agent-drafts/dr_1/confirm") < order.indexOf("POST /v1/tafs/taf_1/versions/2/live"))
    assertTrue(m.state.value.versions["refunds-1"]!![1].live)
  }

  @Test fun aVersionWithACardAndNothingShownSignsNothing() = runTest {
    reads()
    transport.answer(switch2, 200, switching("dr_1"))
    val m = model()
    assertFalse(m.useVersion(Version(2, "n", "setup", t0, false, "dr_1"), "refunds-1", shownDraft = null))
    assertTrue(auth.asked.isEmpty())
    assertTrue(sent(draftConfirm).isEmpty())
    assertTrue(sent(live2).isEmpty())
  }

  @Test fun aCardThatChangedSinceItWasShownIsPutBackOnScreenAndNothingIsSigned() = runTest {
    reads()
    transport.answer(switch2, 200, switching("dr_1"))
    transport.answer(draftGet, 200, draft(version = "v4"))
    transport.answer(cancel, 200, """{"cancelled":true}""")
    transport.answer(versions, 200, state(v1(true), v2(false, true)))
    val m = model()
    assertFalse(m.useVersion(Version(2, "n", "setup", t0, false, "dr_1"), "refunds-1", shownDraft = "v3"))
    assertEquals("v4", m.state.value.changedVersionCards["refunds-1"]!!.version)
    assertEquals(VersionWords.OUT_OF_DATE_CARD, m.state.value.versionErrors["refunds-1"])
    assertTrue(auth.asked.isEmpty())
    assertTrue(sent(draftConfirm).isEmpty())
    assertEquals("the switch is dropped: the taf runs again", 1, sent(cancel).size)
  }

  @Test fun aCardKiffAlreadyHasIsNotConfirmedAgainAndOnlyTheOwnersPresenceIsAsked() = runTest {
    reads()
    transport.answer(switch2, 200, switching("dr_1"))
    transport.answer(draftGet, 200, draft("confirmed"))
    transport.answer(live2, 200, """{"live":2}""")
    transport.answer(versions, 200, state(v1(false), v2(true, false)))
    val m = model()
    assertTrue(m.useVersion(Version(2, "n", "setup", t0, false, "dr_1"), "refunds-1", shownDraft = "v3"))
    assertEquals(listOf("Use version 2 of Refunds"), auth.asked)
    assertTrue(sent(draftConfirm).isEmpty())
  }

  @Test fun aCardAMomentOldKiffRefusesIsDraftedAgain() = runTest {
    reads()
    transport.answer(switch2, 200, switching("dr_1"))
    transport.answer(draftGet, 200, draft())
    transport.answer(draftConfirm, 409, """{"error":"card changed","reason":"card_revised"}""")
    val m = model()
    assertFalse(m.useVersion(Version(2, "n", "setup", t0, false, "dr_1"), "refunds-1", shownDraft = "v3"))
    assertEquals(2, m.state.value.staleVersionCards["refunds-1"])
    assertTrue(m.state.value.versionErrors["refunds-1"]!!.startsWith("Version 2's Card is out of date"))
    assertTrue(sent(live2).isEmpty())
  }

  @Test fun anyOtherRefusalLeavesTheSwitchBegunAndSaysNothingWentLive() = runTest {
    reads()
    transport.answer(switch2, 200, switching("dr_1"))
    transport.answer(draftGet, 200, draft())
    transport.answer(draftConfirm, 500, """{"error":"kiff is down"}""")
    val m = model()
    assertFalse(m.useVersion(Version(2, "n", "setup", t0, false, "dr_1"), "refunds-1", shownDraft = "v3"))
    assertEquals(
      "Installing version 2 didn't finish: KIFF didn't confirm its Card. It isn't working on our side right now. Nothing to do on your side; try again later. Your taf doesn't run until you finish or cancel it.",
      m.state.value.versionErrors["refunds-1"],
    )
    assertNull(m.state.value.staleVersionCards["refunds-1"])
    assertTrue(sent(live2).isEmpty())
    assertTrue(sent(cancel).isEmpty())
  }

  @Test fun anOwnerWhoBacksOutBeforeTheCardChangesDropsTheSwitch() = runTest {
    reads()
    auth.confirms = false
    transport.answer(switch2, 200, switching("dr_1"))
    transport.answer(draftGet, 200, draft())
    transport.answer(cancel, 200, """{"cancelled":true}""")
    transport.answer(versions, 200, state(v1(true), v2(false, true)))
    val m = model()
    assertFalse(m.useVersion(Version(2, "n", "setup", t0, false, "dr_1"), "refunds-1", shownDraft = "v3"))
    assertTrue(sent(draftConfirm).isEmpty())
    assertEquals(1, sent(cancel).size)
  }

  @Test fun aSessionThatCannotSignForThePhoneDropsTheSwitchInsteadOfLeavingTheTafStopped() = runTest {
    reads()
    transport.answer(switch2, 200, switching("dr_1"))
    transport.answer(cancel, 200, """{"cancelled":true}""")
    transport.answer(versions, 200, state(v1(true), v2(false, true)))
    val m = model(keyed = false)
    assertFalse(m.useVersion(Version(2, "n", "setup", t0, false, "dr_1"), "refunds-1", shownDraft = "v3"))
    assertEquals("Sign in with KIFF again: this sign-in can't sign for the phone.", m.state.value.versionErrors["refunds-1"])
    assertEquals(1, sent(cancel).size)
    assertTrue(auth.asked.isEmpty())
  }

  // MARK: A run in the way, and cancelling

  @Test fun aRunInTheWayIsNamedWithTheOneStepThatClearsIt() = runTest {
    reads()
    transport.answer(switch2, 409, """{"error":"a run is going","blocking":[{"run":"r1","session":"s1","status":"PAUSED","trigger":"chat"}]}""")
    val m = model()
    assertNull(m.prepareSwitch(Version(2, "n", null, t0, false), "refunds-1"))
    assertEquals(2, m.state.value.blockedInstalls["refunds-1"])
    assertEquals("Version 2 can't go live while a task waits on your answer. Stop it and install, or wait until it's done.", m.state.value.versionErrors["refunds-1"])
  }

  @Test fun stoppingWhatIsInTheWayAsksTafsCloudToStopIt() = runTest {
    reads()
    transport.answer(switch2, 200, switching(null))
    val m = model()
    m.prepareSwitch(Version(2, "n", null, t0, false), "refunds-1", stopBlocking = true)
    assertEquals("true", KiffClient.json.parseToJsonElement(String(sent(switch2).single().body!!)).jsonObject.getValue("stop_blocking").jsonPrimitive.content)
    assertTrue(m.state.value.blockedInstalls.isEmpty())
  }

  @Test fun cancellingASwitchDropsItAndReadsTheVersionsAgain() = runTest {
    reads()
    transport.answer(cancel, 200, """{"cancelled":true}""")
    transport.answer(versions, 200, state(v1(true), v2(false, true)))
    val m = model()
    m.cancelSwitch("refunds-1")
    assertEquals(1, sent(cancel).size)
    assertEquals(2, m.state.value.versions["refunds-1"]!!.size)
    assertTrue(m.state.value.switches.isEmpty())
  }

  // MARK: What KIFF and tafs cloud send, and the words

  @Test fun aDraftsCardsReadInTheOwnersWordsNowAndAfter() {
    val d = KiffClient.json.decodeFromString<AgentDraft>(draft())
    val card = d.cards!!.single()
    assertEquals(listOf("Refund a payment: up to ${Money.of(10000, "EUR", 2).formatted} each", "${Money.of(100000, "EUR", 2).formatted} in total a day"), d.cardLines.single())
    assertEquals(listOf("Refund a payment: up to ${Money.of(5000, "EUR", 2).formatted} each"), card.current!!.lines)
    assertFalse(card.isUnchanged)
    assertEquals("c1", card.revises)
    assertFalse(d.confirmed)
    assertTrue(KiffClient.json.decodeFromString<AgentDraft>(draft("confirmed")).confirmed)
  }

  @Test fun aCountIsCallsAndABareNumberStaysBare() {
    val card = KiffClient.json.decodeFromString<DraftCard>(
      """{"grants":[{"action":"list_charges","per_action_max":[{"quantity":"count","max":5}]}],"aggregates":[{"quantity":"count","limit":1,"window":"day"},{"quantity":"count","limit":20,"window":"week"}]}""",
    )
    assertEquals(listOf("list charges: up to 5 each", "1 call a day", "20 calls a week"), card.lines)
  }

  private fun asking(onExceed: String?, count: Long, window: String = "day") = KiffClient.json.decodeFromString<DraftCard>(
    """{"grants":[{"action":"create_refund","title":"Refund a payment","per_action_max":[{"quantity":"sum(amount)","max":4000,"unit":"USD","scale":2}]}],
        "aggregates":[{"quantity":"sum(amount)","limit":30000,"window":"$window","unit":"USD","scale":2},{"quantity":"count","limit":$count,"window":"$window"}]
        ${onExceed?.let { ""","on_exceed":"$it"""" } ?: ""}}""",
  )

  @Test fun aCardThatAsksPastItsLimitsSaysSoOnEveryLine() {
    val each = Money.of(4000, "USD", 2).formatted
    val total = Money.of(30000, "USD", 2).formatted
    for ((window, words) in listOf("day" to "a day", "week" to "a week", "month" to "a month")) {
      assertEquals(
        listOf("Refund a payment: up to $each each; more asks you first", "$total in total $words, then it asks you", "Each call asks you first"),
        asking("owner", 0, window).lines,
      )
      assertEquals("1 call $words, then it asks you", asking("owner", 1, window).lines.last())
      assertEquals("20 calls $words, then it asks you", asking("owner", 20, window).lines.last())
    }
  }

  @Test fun aCardThatRefusesPastItsLimitsOrDoesntSayReadsAsBefore() {
    val each = Money.of(4000, "USD", 2).formatted
    val total = Money.of(30000, "USD", 2).formatted
    for (onExceed in listOf("refuse", null, "something_new")) {
      assertEquals(listOf("Refund a payment: up to $each each", "$total in total a day", "0 calls a day"), asking(onExceed, 0).lines)
      assertEquals("1 call a day", asking(onExceed, 1).lines.last())
      assertEquals("20 calls a day", asking(onExceed, 20).lines.last())
    }
  }

  @Test fun kiffsOwnLinesAreShownAsTheyCome() {
    val card = KiffClient.json.decodeFromString<DraftCard>(
      """{"on_exceed":"owner","aggregates":[{"quantity":"count","limit":0,"window":"day"}],"lines":["Each call asks you first, in KIFF's words"],
          "current":{"aggregates":[{"quantity":"count","limit":20,"window":"day"}],"lines":["20 calls a day"]}}""",
    )
    assertEquals(listOf("Each call asks you first, in KIFF's words"), card.lines)
    assertEquals(listOf("Each call asks you first, in KIFF's words"), card.lines(setOf("anything")))
    assertEquals(listOf("20 calls a day"), card.current!!.lines)
  }

  @Test fun aRevisionReadsEachSidesOwnPolicyNowAndAfter() {
    val card = KiffClient.json.decodeFromString<DraftCard>(
      """{"revises":"c1","on_exceed":"owner","aggregates":[{"quantity":"count","limit":0,"window":"day"}],
          "current":{"on_exceed":"refuse","aggregates":[{"quantity":"count","limit":20,"window":"day"}]}}""",
    )
    assertEquals(listOf("Each call asks you first"), card.lines)
    assertEquals(listOf("20 calls a day"), card.current!!.lines)
  }

  @Test fun twoGrantsThatReadAlikeEachAddTheirActionInWords() {
    val d = KiffClient.json.decodeFromString<AgentDraft>(
      """{"id":"d","status":"draft","version":"v1","cards":[
          {"grants":[{"action":"create_refund","title":"Write Stripe API data"}]},
          {"grants":[{"action":"update_customer","title":"Write Stripe API data"}]}]}""",
    )
    assertEquals(listOf(listOf("Write Stripe API data · create refund"), listOf("Write Stripe API data · update customer")), d.cardLines)
  }

  @Test fun aCardKiffKeepsAsItIsReadsAsUnchangedOnlyWhereKiffSaysSo() {
    val unchanged = KiffClient.json.decodeFromString<DraftCard>("""{"grants":[],"unchanged":true,"current":{"grants":[]}}""")
    assertTrue(unchanged.isUnchanged)
    assertFalse("a Card with nothing to compare isn't", KiffClient.json.decodeFromString<DraftCard>("""{"grants":[],"unchanged":true}""").isUnchanged)
    assertFalse(KiffClient.json.decodeFromString<DraftCard>("""{"grants":[],"current":{"grants":[]}}""").isUnchanged)
  }

  @Test fun aDraftsUnknownFieldsAreIgnoredAndAnUnreadCardIsNull() {
    val d = KiffClient.json.decodeFromString<AgentDraft>("""{"id":"d","status":"draft","version":"v1","brand_new":1,"retires":[{"id":"c9","current":{"grants":[{"action":"create_refund"}]}}]}""")
    assertNull(d.cards)
    assertEquals("create refund", d.retires!!.single().current!!.lines.single())
  }

  @Test fun theBylineSaysWhoMadeItAndWhetherItBringsACard() {
    val made = Instant.parse("2026-10-27T09:00:00Z")
    fun line(by: String?, draft: String?) = VersionWords.byline(Version(2, null, by, made, false, draft))
    assertFalse(line(null, null).contains("By"))
    assertTrue(line("setup", null).startsWith("By Coach mode · "))
    assertTrue(line("someone", null).startsWith("By you · "))
    assertTrue(line("setup", "dr_1").endsWith(" · brings a new Card"))
    assertFalse(line("setup", null).contains("brings a new Card"))
  }

  @Test fun anEndedTrialIsOneLine() {
    fun tried(why: String, calls: Int, held: Int, refused: Int) = VersionWords.tried(TrialSummary(t0, t0, why, calls, held, refused))
    assertTrue(tried("back", 3, 1, 0).endsWith(" · 3 calls, 1 held · went back"))
    assertTrue(tried("installed", 1, 0, 2).endsWith(" · 1 call, 2 refused · installed"))
    assertTrue(tried("idle", 0, 0, 0).endsWith(" · 0 calls · ended unused"))
    assertTrue(tried("replaced", 2, 0, 0).endsWith(" · ended"))
    assertTrue(tried("back", 3, 1, 0).startsWith("Tried "))
  }

  @Test fun aRunInTheWayIsSaidAsItIs() {
    fun run(status: String, trigger: String? = null) = BlockingRun("r", "s", status, trigger)
    assertEquals("Version 3 can't go live while a task waits on your answer. Stop it and install, or wait until it's done.", VersionWords.blockedWords(3, listOf(run("PAUSED")), false))
    assertEquals("Version 3 can't go live while its scheduled run is at work. Stop it and install, or wait until it's done.", VersionWords.blockedWords(3, listOf(run("RUNNING", "schedule")), false))
    assertEquals("Version 3 can't go live while a task is at work. Stop it and install, or wait until it's done.", VersionWords.blockedWords(3, listOf(run("RUNNING", "chat")), false))
    assertEquals("Version 3 can't go live while 2 tasks are going, one waiting on your answer. Stop it and install, or wait until it's done.", VersionWords.blockedWords(3, listOf(run("PAUSED"), run("RUNNING")), false))
    assertEquals("Version 3 can't go live while 2 tasks are at work. Stop it and install, or wait until it's done.", VersionWords.blockedWords(3, listOf(run("RUNNING"), run("RUNNING")), false))
    assertEquals("Version 3 isn't installed yet: a task is at work and hasn't stopped. Try again in a moment.", VersionWords.blockedWords(3, listOf(run("RUNNING")), true))
  }

  @Test fun theCardNoteSaysWhetherCardsAreRemoved() {
    assertTrue(VersionWords.cardNote(true).contains("removes the ones this version doesn't have"))
    assertFalse(VersionWords.cardNote(false).contains("removes"))
  }

  @Test fun aSwitchesDraftIsTheSwitchesOwnWhenItIsForThatVersion() {
    val s = bot.tafs.app.app.AppState(
      switches = mapOf("a" to bot.tafs.app.kiff.Switching(2, "redrafted", t0)),
    )
    val v = Version(2, null, null, t0, false, "original")
    assertEquals("redrafted", s.switchDraft(v, "a"))
    assertEquals("original", s.switchDraft(v, "b"))
    assertEquals("original", s.switchDraft(v.copy(n = 3), "a"))
    assertNull("a switch with no Card says none, whatever the version held", s.copy(switches = mapOf("a" to bot.tafs.app.kiff.Switching(2, null, t0))).switchDraft(v, "a"))
  }
}
