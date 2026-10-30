package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.Halt
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.HaltWords
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Halting a taf, or every taf, and resuming it from the phone. A fake KIFF and tafs cloud stand in: no real taf
 * of the owner's is halted or resumed.
 */
class HaltTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}"""
  private val home =
    """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],
        "others":[{"name":"Billing bot","kiff_agent":"billing-x","color":2,"state":"idle","card":{"ids":["c9"],"active":true}}],"removing":[]}"""

  private fun reads() {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
  }

  private fun halt(state: String, runtime: Boolean = false, kiff: Boolean = false, planque: Boolean = false, account: Boolean = false, at: String? = null) =
    """{"halt":{"state":"$state","runtime":$runtime,"kiff":$kiff,"planque":$planque,"account":$account${at?.let { ""","halted_at":"$it"""" } ?: ""}}}"""

  private suspend fun model(keyed: Boolean = true, approvalKey: Boolean = true): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    if (keyed) secrets.save("1", AppModel.KEYED)
    if (approvalKey) secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore() }
  }

  // MARK: Halting

  @Test fun haltingATafAsksTafsCloudWithNoConfirmationAndShowsWhereItStands() = runTest {
    reads()
    transport.answer("POST /v1/tafs/taf_1/halt", 200, halt("halting", runtime = true))
    val m = model()
    m.halt("refunds-1")
    val request = transport.requestsTo("POST /v1/tafs/taf_1/halt").single()
    assertEquals("https://cloud.tafs.kiff.dev/v1/tafs/taf_1/halt", request.url)
    assertEquals("Bearer at_1", request.headers["Authorization"])
    assertTrue("halting only takes authority away: nothing to confirm", auth.asked.isEmpty())
    val h = m.state.value.haltOf("refunds-1")
    assertTrue(h.halting)
    assertTrue(h.runtime)
    assertFalse(h.kiff)
    assertTrue("the account's halt is its own", m.state.value.haltOf(null).running)
  }

  @Test fun haltingEveryTafIsTheAccountsHalt() = runTest {
    reads()
    transport.answer("POST /v1/halt", 200, halt("halted", runtime = true, kiff = true, planque = true, account = true, at = "2026-10-28T09:00:00Z"))
    val m = model()
    m.halt(null)
    assertEquals(1, transport.requestsTo("POST /v1/halt").size)
    assertTrue(m.state.value.haltOf(null).halted)
    assertEquals(t0, m.state.value.haltOf(null).haltedAt)
    assertTrue("the list is read again, so it reads halted at once", transport.requestsTo("GET /v1/home").size >= 2)
  }

  @Test fun aHaltThatFailsSaysSoAndShowsNothingHalted() = runTest {
    reads()
    transport.answer("POST /v1/tafs/taf_1/halt", 502, """{"error":"kiff/agents is unreachable"}""")
    val m = model()
    m.halt("refunds-1")
    assertEquals("tafs couldn't halt it. It isn't working on our side right now. Nothing to do on your side; try again later.", m.state.value.lastError)
    assertTrue(m.state.value.haltOf("refunds-1").running)
  }

  @Test fun everyTafHaltedFailingSaysYourTafs() = runTest {
    reads()
    transport.answer("POST /v1/halt", 500, """{"error":"nope"}""")
    val m = model()
    m.halt(null)
    assertEquals("tafs couldn't halt your tafs. It isn't working on our side right now. Nothing to do on your side; try again later.", m.state.value.lastError)
  }

  @Test fun anAgentTafsDoesNotRunCannotBeHaltedHere() = runTest {
    reads()
    val m = model()
    assertTrue(m.state.value.canHalt("refunds-1"))
    assertTrue(m.state.value.canHalt(null))
    assertFalse(m.state.value.canHalt("billing-x"))
    m.halt("billing-x")
    assertTrue(transport.requests.none { it.method == "POST" && it.url.endsWith("/halt") })
  }

  @Test fun theSampleHasNothingToHalt() = runTest {
    val m = testModel(transport = transport)
    assertFalse(m.state.value.canHalt(null))
    m.halt(null)
    assertTrue(transport.requests.isEmpty())
  }

  @Test fun whereAHaltStandsIsReadAndAnUnreadableAnswerLeavesWhatWasKnown() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/halt", 200, halt("halting", runtime = true, kiff = true))
    transport.answer("GET /v1/tafs/taf_1/halt", 500, """{"error":"later"}""")
    val m = model()
    m.refreshHalt("refunds-1")
    assertTrue(m.state.value.haltOf("refunds-1").kiff)
    m.refreshHalt("refunds-1")
    assertTrue("a failed read changes nothing", m.state.value.haltOf("refunds-1").kiff)
    assertNull(m.state.value.lastError)
  }

  // MARK: Resuming

  private fun header(request: bot.tafs.app.kiff.HttpRequest): Pair<String, String> {
    val parts = request.headers.getValue("KIFF-Device-Signature").split(",")
    return parts[0].removePrefix("t=") to parts[1].removePrefix("v1=")
  }

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDER))
    return Signature.getInstance("SHA256withECDSA").run {
      initVerify(key)
      update(message)
      verify(Base64.getDecoder().decode(signatureBase64))
    }
  }

  @Test fun resumingATafIsConfirmedThenSignedByTheApprovalKeyForKiffThenReleasedAtTafsCloud() = runTest {
    reads()
    transport.answer("POST /v1/agents/refunds-1/resume", 200, "{}")
    transport.answer("DELETE /v1/tafs/taf_1/halt", 200, halt("running"))
    val m = model()
    assertTrue(m.resumeHalt("refunds-1"))
    assertEquals(listOf("Resume Refunds"), auth.asked)
    val resume = transport.requestsTo("POST /v1/agents/refunds-1/resume").single()
    assertEquals("https://api.kiff.dev/v1/agents/refunds-1/resume", resume.url)
    val (at, signature) = header(resume)
    val message = KiffClient.requestMessage("t_01", "POST", "/v1/agents/refunds-1/resume", resume.body ?: ByteArray(0), at)
    assertTrue("it gives authority back: the approval key signs it", verifies(signer.approvalPublicKeyDER, message, signature))
    assertFalse("not the signing key", verifies(signer.publicKeyDER, message, signature))
    // KIFF has it, then tafs cloud lets kiff/agents run it again, in that order.
    assertEquals(1, transport.requestsTo("DELETE /v1/tafs/taf_1/halt").size)
    assertTrue(transport.requests.indexOf(resume) < transport.requests.indexOf(transport.requestsTo("DELETE /v1/tafs/taf_1/halt").single()))
    assertTrue(m.state.value.haltOf("refunds-1").running)
    assertNull(m.state.value.lastError)
  }

  @Test fun resumingEveryTafIsKiffsResumeAndTheAccountsRelease() = runTest {
    reads()
    transport.answer("POST /v1/resume", 200, "{}")
    transport.answer("DELETE /v1/halt", 200, halt("running"))
    val m = model()
    assertTrue(m.resumeHalt(null))
    assertEquals(listOf("Resume all your tafs"), auth.asked)
    assertTrue(transport.requestsTo("POST /v1/resume").single().headers.containsKey("KIFF-Device-Signature"))
  }

  @Test fun aResumeNobodyConfirmedSendsNothing() = runTest {
    reads()
    auth.confirms = false
    val m = model()
    assertFalse(m.resumeHalt("refunds-1"))
    assertEquals("Not resumed: it wasn't confirmed.", m.state.value.lastError)
    assertTrue(transport.requests.none { it.url.endsWith("/resume") || it.method == "DELETE" })
  }

  @Test fun ifKiffWillNotResumeItTafsCloudIsNotAsked() = runTest {
    reads()
    transport.answer("POST /v1/agents/refunds-1/resume", 403, """{"error":"not allowed"}""")
    val m = model()
    assertFalse(m.resumeHalt("refunds-1"))
    assertEquals("KIFF didn't resume Refunds. Your role on this account doesn't allow this.", m.state.value.lastError)
    assertTrue(transport.requests.none { it.method == "DELETE" })
  }

  @Test fun ifTafsCloudCannotReleaseItAfterKiffResumedItSaysWhichPartIsDone() = runTest {
    reads()
    transport.answer("POST /v1/agents/refunds-1/resume", 200, "{}")
    transport.answer("DELETE /v1/tafs/taf_1/halt", 409, """{"error":"KIFF still halts it"}""")
    val m = model()
    assertFalse(m.resumeHalt("refunds-1"))
    assertEquals("KIFF resumed Refunds, but tafs cloud didn't yet. It changed in the meantime. What's shown now is how it is.", m.state.value.lastError)
  }

  @Test fun aSessionWithNoKeyOfThisPhonesCannotSignAResume() = runTest {
    reads()
    val m = model(keyed = false)
    assertFalse(m.resumeHalt("refunds-1"))
    assertEquals("Sign in with KIFF again: this sign-in can't sign for the phone.", m.state.value.lastError)
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requests.none { it.url.endsWith("/resume") })
  }

  @Test fun theSignatureIsNotKeptForAnotherRequest() = runTest {
    reads()
    transport.answer("POST /v1/agents/refunds-1/resume", 200, "{}")
    transport.answer("DELETE /v1/tafs/taf_1/halt", 200, halt("running"))
    val m = model()
    m.resumeHalt("refunds-1")
    // Setup signing is for the step the owner confirmed: a later call goes out unsigned.
    transport.answer("DELETE /v1/me/devices/dev_1", 200, "{}")
    val kiff = KiffClient(transport, token = "at_1")
    kiff.tenant = "t_01"
    kiff.removeDevice("dev_1")
    assertNull(transport.requestsTo("DELETE /v1/me/devices/dev_1").single().headers["KIFF-Device-Signature"])
  }

  // MARK: Words

  @Test fun aHaltReadsRunningHaltingOrHaltedAndAnUnknownStateReadsHalting() {
    assertEquals("Running", HaltWords.headline(Halt.Running))
    assertEquals("Halting…", HaltWords.headline(Halt(state = "halting")))
    assertEquals("Halted", HaltWords.headline(Halt(state = "halted")))
    assertEquals("Halting…", HaltWords.headline(Halt(state = "something-new")))
    assertEquals("Stop it until you resume", HaltWords.rowLine(Halt.Running))
    assertEquals("Halted", HaltWords.rowLine(Halt(state = "halted")))
  }

  @Test fun theExplanationNamesWhoIsStoppedAndWhatDoesNotComeBack() {
    assertTrue(HaltWords.explanation(Halt.Running, all = true).startsWith("Halting stops your tafs:"))
    assertTrue(HaltWords.explanation(Halt.Running, all = false).startsWith("Halting stops it:"))
    assertEquals("Nothing new starts. Halted shows once KIFF and planque both confirm.", HaltWords.explanation(Halt(state = "halting"), all = false))
    val halted = HaltWords.explanation(Halt(state = "halted", haltedAt = Instant.parse("2026-10-28T09:00:00Z")), all = false)
    assertTrue(halted, halted.startsWith("Halted since "))
    assertTrue(halted, halted.endsWith("nothing from before the halt picks up again."))
    assertTrue(HaltWords.explanation(Halt(state = "halted"), all = false).startsWith("Halted. Nothing reaches your tools."))
  }

  @Test fun aTafHaltedWithTheAccountIsResumedWithIt() {
    val h = Halt(state = "halted", account = true)
    assertTrue(HaltWords.resumesWithAccount(h, "refunds-1"))
    assertFalse(HaltWords.resumesWithAccount(h, null))
    assertFalse(HaltWords.resumesWithAccount(Halt(state = "halted"), "refunds-1"))
  }
}
