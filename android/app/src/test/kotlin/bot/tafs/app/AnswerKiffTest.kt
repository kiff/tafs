package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.AppState
import bot.tafs.app.kiff.AnswerRefusals
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.model.Answer
import bot.tafs.app.model.Decisions
import bot.tafs.app.model.HeldCall
import bot.tafs.app.model.RecordEntry
import bot.tafs.app.model.kiffName
import bot.tafs.app.signing.ApprovalKeyRenewed
import bot.tafs.app.signing.ApprovalSigning
import bot.tafs.app.signing.DeviceSigner
import bot.tafs.app.signing.SignerException
import bot.tafs.app.signing.SigningKeys
import bot.tafs.app.signing.SoftwareKeys
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answering a call KIFF holds from the phone: confirmed first, signed on KIFF's own message with the key that
 * suits it, sent, and only then shown as answered. A fake KIFF stands in: no real held call is touched.
 */
class AnswerKiffTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer: ApprovalSigning = softwareSigner(auth)
  private val t0 = "2026-10-28T09:00:00Z"

  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}"""
  private val account = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay","capabilities":["holds.answer","cards.manage"]}"""

  private fun taf(agent: String, state: String = "waiting") =
    """{"id":"id_$agent","name":"A","kiff_agent":"$agent","color":1,"state":"$state","card":{"ids":["c_$agent"],"active":true,"tools":[],"rules":[]}}"""

  private fun held(id: String = "ce_1", extra: String = "") =
    """{"id":"$id","subject":"a-1","action":"create_refund","title":"Refund a payment","entity_id":"tc-$id","proposal_id":"prop_$id","accounting_version":1,
       "next_steps":["return first"],"breaches":[{"outcome":"per_action_exceeded","reason":"over","quantity":"sum(amount)","limit":10000,"unit":"EUR","scale":2}],
       "parameters":{"amount":15000},"parameter_units":{"amount":{"unit":"EUR","scale":2}},"arguments":{"payment_intent":"pi_$id"},
       "created_at":"$t0","expires_at":"2099-01-01T00:00:00Z"$extra}"""

  private fun activityHeld(id: String = "ce_1") =
    """{"proposal_id":"prop_$id","action_name":"create_refund","title":"Refund a payment","actor_id":"a-1","outcome":"owner_required","created_at":"$t0"}"""

  private fun reads(held: String = held(), activity: String = activityHeld(), meBody: String = me) {
    transport.answer("GET /v1/me", 200, meBody)
    transport.answer("GET /v1/home", 200, """{"tafs":[${taf("a-1")}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$held]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[$activity]}""")
  }

  private fun answeredOk(id: String = "ce_1", route: String = "approve-once") =
    transport.answer("POST /v1/me/card-exceptions/$id/$route", 200, """{}""")

  /** Signed in with this phone's key, and its approval key the one KIFF holds. */
  private suspend fun model(approvalKey: Boolean = true, keyed: Boolean = true): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    if (keyed) secrets.save("1", AppModel.KEYED)
    if (approvalKey) secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, start = false).also { it.restore() }
  }

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDER))
    return Signature.getInstance("SHA256withECDSA").run {
      initVerify(key)
      update(message)
      verify(Base64.getDecoder().decode(signatureBase64))
    }
  }

  private fun body(route: String, id: String = "ce_1"): JsonObject =
    KiffClient.json.parseToJsonElement(String(transport.requestsTo("POST /v1/me/card-exceptions/$id/$route").single().body!!)).jsonObject

  private fun signature(body: JsonObject) = body.getValue("signature").jsonObject
  private fun signedAt(body: JsonObject) = signature(body).getValue("signed_at").jsonPrimitive.content
  private fun value(body: JsonObject) = signature(body).getValue("value").jsonPrimitive.content

  private fun message(answer: String, nextStep: String = "", at: String) =
    "kiff.answer.v1\nt_01\nce_1\n$answer\n$nextStep\n\n1\n$at".toByteArray()

  // MARK: Approving

  @Test fun approvingOnceIsSignedByTheApprovalKeyOnKiffsMessageAndSent() = runTest {
    reads(); answeredOk()
    val m = model()
    val call = m.state.value.held.single()
    assertTrue(m.answer(call, Answer.ApproveOnce))
    val sent = body("approve-once")
    val at = signedAt(sent)
    // Seconds, RFC 3339, in UTC: as KIFF's own times are.
    assertEquals(t0, at)
    assertTrue("signed by the approval key", verifies(signer.approvalPublicKeyDER, message("approve-once", at = at), value(sent)))
    assertFalse("not by the signing key", verifies(signer.publicKeyDER, message("approve-once", at = at), value(sent)))
    assertNull("the signature is in the body, not a header", transport.requestsTo("POST /v1/me/card-exceptions/ce_1/approve-once").single().headers["KIFF-Device-Signature"])
    assertEquals("Bearer at_1", transport.requestsTo("POST /v1/me/card-exceptions/ce_1/approve-once").single().headers["Authorization"])
  }

  @Test fun theOwnerIsAskedFirstNamingWhatTheyAnswer() = runTest {
    reads(); answeredOk()
    val m = model()
    m.answer(m.state.value.held.single(), Answer.ApproveOnce)
    assertEquals(listOf("Answer Refund a payment"), auth.asked)
  }

  @Test fun aRefusalIsSignedByTheCurrentKeyBecauseItTakesAuthorityAway() = runTest {
    reads(); answeredOk(route = "reject")
    val m = model()
    assertTrue(m.answer(m.state.value.held.single(), Answer.Refuse))
    val sent = body("reject")
    assertTrue(verifies(signer.publicKeyDER, message("reject", at = signedAt(sent)), value(sent)))
    assertFalse(verifies(signer.approvalPublicKeyDER, message("reject", at = signedAt(sent)), value(sent)))
    assertNull("no next step", sent["next_step"])
  }

  @Test fun aNextStepIsARefusalWithItsStepInTheMessageAndTheBody() = runTest {
    reads(); answeredOk(route = "reject")
    val m = model()
    assertTrue(m.answer(m.state.value.held.single(), Answer.NextStep("return first")))
    val sent = body("reject")
    assertEquals("return first", sent.getValue("next_step").jsonPrimitive.content)
    assertTrue(verifies(signer.publicKeyDER, message("reject", "return first", signedAt(sent)), value(sent)))
  }

  @Test fun extraRoomIsKiffsChangeCardSignedByTheApprovalKey() = runTest {
    reads(held = held(extra = ""), activity = activityHeld()); answeredOk(route = "change-card")
    val m = model()
    assertTrue(m.answer(m.state.value.held.single(), Answer.ExtraRoom))
    val sent = body("change-card")
    assertTrue(verifies(signer.approvalPublicKeyDER, message("change-card", at = signedAt(sent)), value(sent)))
    assertEquals("change-card", Answer.ExtraRoom.kiffName)
  }

  @Test fun aHoldBackAfterExtraRoomSaysTheFirstStepWentThroughAndWhyASecondIsNeeded() = runTest {
    // Over a day's total and the limit for one call: extra room first, then KIFF holds the same call again for the one call.
    reads(held = held(extra = ""","can_approve_once":false,"reason_text":"$60 would go over today's $50"""")); answeredOk(route = "change-card")
    val again = held(extra = ""","can_approve_once":true,"reason_text":"$60 is over the $50 limit for one call"""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$again]}""")
    val m = model()
    assertEquals("$60 would go over today's $50", m.state.value.held.single().reason)
    assertTrue(m.answer(m.state.value.held.single(), Answer.ExtraRoom))
    assertTrue("ce_1" in m.state.value.roomGiven)
    m.refresh()
    val back = m.state.value.held.single()
    assertEquals("ce_1", back.kiffId)
    assertTrue(back.approvableOnce)
    // KIFF's own words, said plainly as the second step.
    assertEquals("$60 is over the $50 limit for one call", back.reason)
    assertEquals(
      "Extra room went through. This call is held again for a limit of its own: $60 is over the $50 limit for one call. Approve it once to let it go, or refuse it.",
      bot.tafs.app.model.SecondStep.words(back.reason),
    )
  }

  @Test fun eachAnswerNamesItselfAsKiffDoes() {
    assertEquals("approve-once", Answer.ApproveOnce.kiffName)
    assertEquals("reject", Answer.Refuse.kiffName)
    assertEquals("reject", Answer.NextStep("x").kiffName)
    assertEquals("change-card", Answer.ExtraRoom.kiffName)
  }

  @Test fun theBodyHasNoSignatureOnlyAsTheClientsOwnShapeAndAFieldKiffDoesNotKnowIsNotSent() = runTest {
    reads(); answeredOk()
    val m = model()
    m.answer(m.state.value.held.single(), Answer.ApproveOnce)
    assertEquals(setOf("signature"), body("approve-once").keys)
    assertEquals(setOf("signed_at", "value"), signature(body("approve-once")).keys)
  }

  // MARK: What changes, and when

  @Test fun theCallIsAnsweredOnlyOnceKiffHasTakenIt() = runTest {
    reads(); answeredOk()
    val m = model()
    val call = m.state.value.held.single()
    assertTrue(m.answer(call, Answer.ApproveOnce))
    // The answer went before anything changed on screen: the POST is before the read that follows it.
    val order = transport.requests.map { "${it.method} ${java.net.URI.create(it.url).path}" }
    assertTrue(order.toString(), order.indexOf("POST /v1/me/card-exceptions/ce_1/approve-once") < order.lastIndexOf("GET /v1/home"))
  }

  @Test fun anAnsweredCallIsInTheRecordAsTheOwnersWordsUnderKiffsIdUntilKiffHasItsOutcome() = runTest {
    reads(); answeredOk()
    val m = model()
    val call = m.state.value.held.single()
    // KIFF's next read has the call gone from its held list, but its activity still has it held.
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    m.answer(call, Answer.ApproveOnce)
    // KIFF's list, read just after, still has the call as held in its activity: the owner's answer stands.
    val line = m.state.value.record.single()
    assertEquals(KiffMapping.recordId("prop_ce_1"), line.id)
    // KIFF's list, read after the answer, no longer holds the call.
    assertTrue(m.state.value.held.isEmpty())
    assertEquals(RecordEntry.Kind.approved, line.kind)
    assertTrue(line.title, line.title.startsWith("Approved once by you · "))
    assertTrue(line.title, line.title.endsWith(" · Refund a payment"))
    assertNotNull("the signature made on this phone", line.signature)
    assertEquals(true, line.ownerApproved)
    assertFalse(m.state.value.isWaiting(line))
  }

  @Test fun onceKiffRecordsTheSameOutcomeTheLineKeepsTheOwnersWords() = runTest {
    reads(); answeredOk()
    val m = model()
    m.answer(m.state.value.held.single(), Answer.ApproveOnce)
    val owners = m.state.value.record.single().title
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer(
      "GET /v1/me/activity", 200,
      """{"calls":[{"proposal_id":"prop_ce_1","action_name":"create_refund","title":"Refund a payment","actor_id":"a-1","outcome":"allowed","held":true,"tool_outcome":{"status":"done"},"created_at":"$t0"}]}""",
    )
    m.refresh()
    val line = m.state.value.record.single()
    assertEquals(RecordEntry.Kind.approved, line.kind)
    assertEquals(owners, line.title)
  }

  @Test fun anOutcomeKiffRecordsThatDiffersFromWhatTheOwnerDecidedSettlesTheirLine() = runTest {
    reads(); answeredOk()
    val m = model()
    m.answer(m.state.value.held.single(), Answer.ApproveOnce)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer(
      "GET /v1/me/activity", 200,
      """{"calls":[{"proposal_id":"prop_ce_1","action_name":"create_refund","actor_id":"a-1","outcome":"denied","reason":"expired","created_at":"$t0"}]}""",
    )
    m.refresh()
    // KIFF's own word wins; and the owner's line is gone for good.
    assertEquals(RecordEntry.Kind.refused, m.state.value.record.single().kind)
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    m.refresh()
    assertTrue(m.state.value.record.isEmpty())
  }

  @Test fun theTafsCardIsNotDrawnOnHereKiffCountsIt() = runTest {
    reads(); answeredOk()
    val m = model()
    val before = m.state.value.taf("a-1")!!.used
    m.answer(m.state.value.held.single(), Answer.ApproveOnce)
    assertEquals(before, m.state.value.taf("a-1")!!.used)
  }

  @Test fun theHeldCallIsGoneOnceAnswered() = runTest {
    reads(); answeredOk()
    val m = model()
    val call = m.state.value.held.single()
    // KIFF's next read has it gone.
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    m.answer(call, Answer.ApproveOnce)
    assertTrue(m.state.value.held.isEmpty())
  }

  // MARK: Not answering

  @Test fun anAnswerTheOwnerDidNotConfirmSendsNothingAndTheCallStillWaits() = runTest {
    reads()
    val m = model()
    auth.confirms = false
    assertFalse(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    assertTrue(transport.requests.none { it.method == "POST" })
    assertEquals(1, m.state.value.held.size)
    // A fingerprint not given, with the key still good: try again, or sign in again if the fingerprints changed.
    assertEquals(bot.tafs.app.signing.DeviceSigner.NOT_RECOGNIZED, m.state.value.lastError)
    assertTrue(m.state.value.record.none { it.signature != null })
  }

  @Test fun aPhoneWithNoScreenLockAnswersNothing() = runTest {
    reads()
    val m = model()
    auth.available = false
    assertFalse(m.answer(m.state.value.held.single(), Answer.Refuse))
    assertTrue(transport.requests.none { it.method == "POST" })
  }

  @Test fun aSessionWithoutThisPhonesKeyDoesNotAnswerFromHere() = runTest {
    reads()
    val m = model(keyed = false)
    val call = m.state.value.held.single()
    assertFalse(m.state.value.sessionHasKey)
    assertFalse(m.state.value.canAnswer(call))
    assertFalse(m.answer(call, Answer.Refuse))
    assertEquals("Sign out and in again to answer from this phone, so KIFF knows its key. Or answer this one in KIFF.", m.state.value.lastError)
    assertTrue(auth.asked.isEmpty())
  }

  @Test fun aHoldWithNoAccountingVersionIsAnsweredInKiff() = runTest {
    reads(held = held().replace(""""accounting_version":1,""", ""))
    val m = model()
    val call = m.state.value.held.single()
    assertNull(call.accountingVersion)
    assertFalse(m.state.value.canAnswer(call))
    assertFalse(m.answer(call, Answer.Refuse))
    assertEquals("Answer this one in KIFF. Answering from the phone arrives once KIFF checks the phone's signature.", m.state.value.lastError)
    assertTrue(transport.requests.none { it.method == "POST" })
  }

  @Test fun aCallThatWentFromTheListIsNotAnsweredAgain() = runTest {
    reads()
    val m = model()
    val stale = m.state.value.held.single().copy(id = "gone")
    assertFalse(m.answer(stale, Answer.Refuse))
    assertTrue(auth.asked.isEmpty())
  }

  @Test fun anAnswerForTheAccountThatWasIsNotSentOnceTheOwnerHasSignedOut() = runTest {
    reads()
    secrets.save("at_1", AppModel.TOKEN); secrets.save("t_01", AppModel.TENANT); secrets.save("1", AppModel.KEYED)
    // The owner is slow at the prompt, and signs out meanwhile.
    lateinit var model: AppModel
    val slow = object : bot.tafs.app.signing.Authenticator {
      override val canAuthenticate = true
      override suspend fun authenticate(reason: String): Boolean {
        model.signOut()
        return true
      }

      override suspend fun authenticate(reason: String, signature: java.security.Signature) = authenticate(reason)
    }
    model = testModel(
      transport = transport, secrets = secrets,
      signer = bot.tafs.app.signing.DeviceSigner(bot.tafs.app.signing.SoftwareKeys(), slow), start = false,
    )
    model.restore()
    val call = model.state.value.held.single()
    assertFalse(model.answer(call, Answer.Refuse))
    // Nothing of that answer went anywhere, and nothing is said against the account now signed out.
    assertTrue(transport.requests.none { it.url.contains("/card-exceptions/") && it.method == "POST" })
    assertNull(model.state.value.lastError)
    assertFalse(model.state.value.signedIn)
  }

  // MARK: Kiff refusing

  private fun refusal(code: String, status: Int = 403) =
    transport.answer("POST /v1/me/card-exceptions/ce_1/approve-once", status, """{"error":"$code"}""")

  @Test fun aReasonThePersonMayNeverAnswerIsSaidInWordsAndKept() = runTest {
    reads(); refusal("self_review")
    val m = model()
    val call = m.state.value.held.single()
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertEquals("You asked for this, so someone else on the account approves it. You can still refuse it.", m.state.value.lastError)
    assertEquals("self_review", m.state.value.unanswerable[call.id])
    // The call still waits, and was not recorded as answered.
    assertEquals(1, m.state.value.held.size)
    assertTrue(m.state.value.record.none { it.kind == RecordEntry.Kind.approved })
  }

  @Test fun aRefusalOfOnlyTheApprovalLeavesRefusingOpen() = runTest {
    reads(); refusal("self_review"); answeredOk(route = "reject")
    val m = model()
    val call = m.state.value.held.single()
    m.answer(call, Answer.ApproveOnce)
    assertTrue(AnswerRefusals.refusesOnly(m.state.value.unanswerable.getValue(call.id)))
    m.dismissError()
    assertTrue(m.answer(call, Answer.Refuse))
    assertTrue(m.state.value.held.isEmpty() || m.state.value.record.any { it.kind == RecordEntry.Kind.refused })
  }

  @Test fun aRefusalForAReasonAMomentLaterCouldClearIsNotKept() = runTest {
    reads(); refusal("hold_check_unavailable", status = 503)
    val m = model()
    val call = m.state.value.held.single()
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertEquals("KIFF couldn't check who may answer just now. Try again.", m.state.value.lastError)
    assertTrue(m.state.value.unanswerable.isEmpty())
  }

  @Test fun anyOtherRefusalIsKiffsMessage() = runTest {
    reads(); refusal("boom", status = 500)
    val m = model()
    assertFalse(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    assertEquals("KIFF didn't take the answer: boom", m.state.value.lastError)
    assertTrue(m.state.value.unanswerable.isEmpty())
  }

  @Test fun theReasonCanBeInTheReasonFieldToo() {
    val error = bot.tafs.app.kiff.KiffError.Server(403, "forbidden", reason = "role_forbidden")
    assertEquals("Your role on this account doesn't answer held calls. Someone who approves on it does.", AnswerRefusals.answerRefusedWords(error))
    assertEquals("role_forbidden", AnswerRefusals.lastingRefusal(error))
  }

  @Test fun aRefusalIsForgottenOnceItsCallIsNoLongerHeld() = runTest {
    reads(); refusal("role_forbidden")
    val m = model()
    val call = m.state.value.held.single()
    m.answer(call, Answer.ApproveOnce)
    assertEquals(1, m.state.value.unanswerable.size)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    m.refresh()
    assertTrue(m.state.value.unanswerable.isEmpty())
  }

  // MARK: The approval key

  @Test fun anActKiffTakesOnlyFromTheApprovalKeyMeansSigningInAgainAndRefusingStillWorks() = runTest {
    reads()
    transport.answer("POST /v1/me/card-exceptions/ce_1/approve-once", 403, """{"error":"approval_key_required"}""")
    answeredOk(route = "reject")
    val m = model()
    val call = m.state.value.held.single()
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertEquals("Sign in with KIFF again to approve from this phone.", m.state.value.lastError)
    assertTrue(m.state.value.approvalKeyLost)
    assertNull(secrets.load(AppModel.APPROVAL_KEYED))
    // Approving is refused here from now, with no prompt; refusing takes authority away and goes on.
    auth.asked.clear()
    m.dismissError()
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertTrue(auth.asked.isEmpty())
    assertEquals(1, transport.requestsTo("POST /v1/me/card-exceptions/ce_1/approve-once").size)
    assertTrue(m.answer(call, Answer.Refuse))
  }

  @Test fun anApprovalKeyKiffHoldsThatIsAnotherPhonesMeansApprovingWaitsForANewSignIn() = runTest {
    reads()
    transport.answer("POST /v1/me/device/approval-key", 409, """{"error":"approval_key_exists","fingerprint":"0000 1111 2222 3333"}""")
    val m = model(approvalKey = false)
    assertTrue(m.state.value.approvalKeyLost)
    val call = m.state.value.held.single()
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertEquals("Sign in with KIFF again to approve from this phone.", m.state.value.lastError)
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requests.none { it.url.endsWith("approve-once") })
  }

  @Test fun anApprovalAsksForItsOwnFingerprintAndNothingElse() = runTest {
    reads(); answeredOk()
    val m = model()
    assertTrue(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    // One prompt, the fingerprint that unlocked the approval key for this one signature.
    assertEquals(listOf("Answer Refund a payment"), auth.fingerprints)
    assertEquals(auth.fingerprints, auth.asked)
  }

  @Test fun anAnswerIsSignedAtTheTimeThePersonConfirmedNotWhenThePromptCameUp() = runTest {
    reads(); answeredOk(); answeredOk(id = "ce_2", route = "reject")
    var now = Instant.parse(t0)
    secrets.save("at_1", AppModel.TOKEN); secrets.save("t_01", AppModel.TENANT); secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    val m = testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { now }, start = false).also { it.restore() }
    // The person takes 90 s at the prompt.
    auth.whilePrompted = { now = now.plusSeconds(90) }
    assertTrue(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    val sent = body("approve-once")
    assertEquals("2026-10-28T09:01:30Z", signedAt(sent))
    assertTrue(verifies(signer.approvalPublicKeyDER, message("approve-once", at = "2026-10-28T09:01:30Z"), value(sent)))
  }

  @Test fun aRefusalIsSignedAtTheTimeThePersonConfirmedToo() = runTest {
    reads(); answeredOk(route = "reject")
    var now = Instant.parse(t0)
    secrets.save("at_1", AppModel.TOKEN); secrets.save("t_01", AppModel.TENANT); secrets.save("1", AppModel.KEYED)
    val m = testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { now }, start = false).also { it.restore() }
    auth.whilePrompted = { now = now.plusSeconds(45) }
    assertTrue(m.answer(m.state.value.held.single(), Answer.Refuse))
    assertEquals("2026-10-28T09:00:45Z", signedAt(body("reject")))
  }

  @Test fun aKeyTurnedOffWhileTheAppWasClosedIsFoundAtLaunchWithNoTapAndNoPrompt() = runTest {
    reads()
    val keys = SoftwareKeys()
    val signer = DeviceSigner(keys, auth)
    secrets.save("at_1", AppModel.TOKEN); secrets.save("t_01", AppModel.TENANT); secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    // A fingerprint was added while tafs was closed: the key KIFF holds is off.
    keys.invalidateApproval = true
    val m = testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, start = false).also { it.restore() }
    // Known at launch, before any tap: the screens say so, and nothing was asked.
    assertTrue(m.state.value.approvalKeyLost)
    assertTrue(auth.asked.isEmpty())
    assertNull(secrets.load(AppModel.APPROVAL_KEYED))
  }

  @Test fun aKeyStillValidAtLaunchIsUsedAsBefore() = runTest {
    reads()
    val m = model()
    assertFalse(m.state.value.approvalKeyLost)
    assertTrue(signer.approvalKeyUsable())
  }

  @Test fun aKeyTurnedOffByANewFingerprintSendsNothingSaysWhyAndRefusingStillWorks() = runTest {
    reads()
    answeredOk(route = "reject")
    val keys = SoftwareKeys()
    val signer = DeviceSigner(keys, auth)
    secrets.save("at_1", AppModel.TOKEN); secrets.save("t_01", AppModel.TENANT); secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    val m = testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, start = false).also { it.restore() }
    val call = m.state.value.held.single()
    keys.invalidateApproval = true
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertEquals(ApprovalKeyRenewed.WORDS, m.state.value.lastError)
    assertTrue(m.state.value.approvalKeyLost)
    assertNull(secrets.load(AppModel.APPROVAL_KEYED))
    assertTrue(transport.requests.none { it.url.endsWith("approve-once") })
    // Approving waits for a new sign-in, with no prompt.
    m.dismissError()
    auth.asked.clear()
    assertFalse(m.answer(call, Answer.ApproveOnce))
    assertTrue(auth.asked.isEmpty())
    // Refusing is the signing key's, which a new fingerprint doesn't touch.
    assertTrue(m.answer(call, Answer.Refuse))
    val sent = body("reject")
    assertTrue(verifies(signer.publicKeyDER, message("reject", at = signedAt(sent)), value(sent)))
  }

  @Test fun aPhoneWithNoFingerprintDoesNotApproveWithItsScreenLock() = runTest {
    reads(); answeredOk()
    val keys = object : SigningKeys {
      val inner = SoftwareKeys()
      override val publicKeyDER get() = inner.publicKeyDER
      override val approvalPublicKeyDER get() = ByteArray(0)
      override fun sign(message: ByteArray) = inner.sign(message)
      override fun approvalSignature(): Signature = throw SignerException(DeviceSigner.NO_FINGERPRINT)
    }
    secrets.save("at_1", AppModel.TOKEN); secrets.save("t_01", AppModel.TENANT); secrets.save("1", AppModel.KEYED)
    val m = testModel(auth = auth, transport = transport, secrets = secrets, signer = DeviceSigner(keys, auth), start = false).also { it.restore() }
    assertFalse(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    assertEquals(DeviceSigner.NO_FINGERPRINT, m.state.value.lastError)
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requests.none { it.url.endsWith("approve-once") })
  }

  // MARK: Sign in again

  @Test fun signingInAgainEndsTheSessionAtKiffFirstThenSignsOutHere() = runTest {
    reads()
    transport.answer("POST /v1/auth/device/logout", 200, "{}")
    val m = model()
    assertTrue(m.signOutAtKiff())
    assertEquals(1, transport.requestsTo("POST /v1/auth/device/logout").size)
    assertFalse(m.state.value.signedIn)
    assertNull(secrets.load(AppModel.TOKEN))
  }

  @Test fun ifKiffDoesNotEndTheSessionNothingChangesHere() = runTest {
    reads()
    transport.answer("POST /v1/auth/device/logout", 503, """{"error":"unavailable"}""")
    val m = model()
    assertFalse(m.signOutAtKiff())
    assertTrue(m.state.value.signedIn)
    assertEquals("at_1", secrets.load(AppModel.TOKEN))
    assertEquals(AppModel.NOT_SIGNED_OUT, m.state.value.signOutError)
  }

  @Test fun aSignOutKiffDoesntConfirmStaysSignedInSaysSoAndKeepsTryingUntilItDoes() = runTest {
    reads()
    // KIFF can't be reached twice, then ends the session.
    transport.answer("POST /v1/auth/device/logout", 503, """{"error":"unavailable"}""")
    transport.answer("POST /v1/auth/device/logout", 503, """{"error":"unavailable"}""")
    transport.answer("POST /v1/auth/device/logout", 200, "{}")
    secrets.save("at_1", AppModel.TOKEN); secrets.save("t_01", AppModel.TENANT); secrets.save("1", AppModel.KEYED)
    val m = testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, start = false, scope = backgroundScope).also { it.restore() }
    assertFalse(m.signOutAtKiff())
    // Never shown signed out while KIFF still accepts the session.
    assertTrue(m.state.value.signedIn)
    assertEquals("at_1", secrets.load(AppModel.TOKEN))
    assertEquals("Not signed out at KIFF yet. Try again, or sign this phone out from another device's Devices.", m.state.value.signOutError)
    // It tries again by itself: after 2 s (KIFF still away), then 10 s more, when KIFF ends it.
    testScheduler.advanceTimeBy(2_001); testScheduler.runCurrent()
    assertTrue(m.state.value.signedIn)
    testScheduler.advanceTimeBy(10_001); testScheduler.runCurrent()
    assertEquals(3, transport.requestsTo("POST /v1/auth/device/logout").size)
    assertFalse(m.state.value.signedIn)
    assertNull(secrets.load(AppModel.TOKEN))
  }

  @Test fun signingOutOfTheSampleClearsItWithNothingToAskKiff() = runTest {
    val m = testModel()
    assertTrue(m.signOutAtKiff())
    assertFalse(m.state.value.signedIn)
    assertTrue(transport.requests.isEmpty())
  }

  @Test fun withoutAnApprovalKeyTheCurrentKeySignsAnApprovalToo() = runTest {
    reads(); answeredOk()
    // KIFF has no approval key for this session yet: the phone's key signs everything, as before.
    transport.answer("POST /v1/me/device/approval-key", 404, """{"error":"not_found"}""")
    val m = model(approvalKey = false)
    assertTrue(m.answer(m.state.value.held.single(), Answer.ApproveOnce))
    val sent = body("approve-once")
    // Which key signed it is the signer's: here the approval key exists on the phone but KIFF doesn't hold it.
    assertTrue(
      verifies(signer.publicKeyDER, message("approve-once", at = signedAt(sent)), value(sent)),
    )
  }

  // MARK: Who may answer

  @Test fun whoMayAnswerAndWhoManagesCardsIsKiffsSayAndOpenWhenKiffDoesntSay() {
    val withCaps = bot.tafs.app.kiff.Account("t", "s", capabilities = listOf("holds.answer"))
    assertTrue(AppState(kiffAccount = withCaps).mayAnswerHolds)
    assertFalse(AppState(kiffAccount = withCaps).canManageCards)
    val viewer = bot.tafs.app.kiff.Account("t", "s", capabilities = listOf("tafs.use"))
    assertFalse(AppState(kiffAccount = viewer).mayAnswerHolds)
    // An account from before roles lists none: it may.
    val old = bot.tafs.app.kiff.Account("t", "s")
    assertTrue(AppState(kiffAccount = old).mayAnswerHolds)
    assertTrue(AppState(kiffAccount = old).canManageCards)
    assertTrue(AppState().mayAnswerHolds)
  }

  @Test fun theSamplesCallsAreAlwaysAnswerableFromThePhone() {
    val call = HeldCall(tafId = "t", proposed = bot.tafs.app.model.ProposedAction("a", "s"), reason = "r", heldAt = Instant.EPOCH, expiresAt = Instant.EPOCH)
    assertTrue(AppState().canAnswer(call))
    assertFalse(AppState(sessionHasKey = false).canAnswer(call.copy(kiffId = "ce_1", accountingVersion = 1)))
    assertTrue(AppState(sessionHasKey = true).canAnswer(call.copy(kiffId = "ce_1", accountingVersion = 1)))
    assertFalse(AppState(sessionHasKey = true).canAnswer(call.copy(kiffId = "ce_1", accountingVersion = null)))
  }

  // MARK: Merging the owner's answers into KIFF's record

  private fun entry(id: String, kind: RecordEntry.Kind, title: String, at: Long = 0) =
    RecordEntry(id = id, tafId = "t", kind = kind, title = title, detail = "", at = Instant.ofEpochSecond(at))

  @Test fun anAnswerKiffHasNoLineForYetIsAdded() {
    val (record, settled) = Decisions.merge(listOf(entry("a", RecordEntry.Kind.done, "A", 1)), mapOf("b" to entry("b", RecordEntry.Kind.approved, "B", 5)))
    assertEquals(listOf("b", "a"), record.map { it.id })
    assertTrue(settled.isEmpty())
  }

  @Test fun aLineKiffStillHasAsHeldReadsAsTheOwnersAnswer() {
    val (record, settled) = Decisions.merge(listOf(entry("a", RecordEntry.Kind.held, "Held")), mapOf("a" to entry("a", RecordEntry.Kind.approved, "Mine")))
    assertEquals("Mine", record.single().title)
    assertTrue(settled.isEmpty())
  }

  @Test fun aLineKiffHasTheSameOutcomeForKeepsTheOwnersWords() {
    val (record, settled) = Decisions.merge(listOf(entry("a", RecordEntry.Kind.approved, "KIFF's")), mapOf("a" to entry("a", RecordEntry.Kind.approved, "Mine")))
    assertEquals("Mine", record.single().title)
    assertTrue(settled.isEmpty())
  }

  @Test fun aLineKiffHasADifferentOutcomeForSettlesTheOwnersLine() {
    val (record, settled) = Decisions.merge(listOf(entry("a", RecordEntry.Kind.refused, "KIFF's")), mapOf("a" to entry("a", RecordEntry.Kind.approved, "Mine")))
    assertEquals("KIFF's", record.single().title)
    assertEquals(setOf("a"), settled)
  }
}
