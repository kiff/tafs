@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.PurchaseHold
import bot.tafs.app.model.Answer
import bot.tafs.app.model.HoldAllow
import bot.tafs.app.model.Words
import bot.tafs.app.signing.ApprovalSigning
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A held purchase (docs/purchases.md), read from tafs's own contract (contracts/purchase.json): KIFF serves the contract's
 * `approval.value` on the hold as `purchase`, the card draws its words, and every answer signs the case's line last, byte for byte
 * the contract's vector. Approve once only from the purchase's own card, with the case it drew; Refuse always; nothing else.
 */
class PurchaseHoldTest {
  private val contract: JsonObject = KiffClient.json.parseToJsonElement(File("../../contracts/purchase.json").readText()).jsonObject
  private val approval = contract.getValue("approval").jsonObject.getValue("value").jsonObject
  private val vector = contract.getValue("answer").jsonObject.getValue("vector").jsonObject
  private fun v(key: String) = vector.getValue(key).jsonPrimitive.content

  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val clock = { Instant.parse(v("signed_at")) }
  private val signer: ApprovalSigning = softwareSigner(auth, clock)
  private val id = v("exception_id")
  private val approve = "POST /v1/me/card-exceptions/$id/approve-once"
  private val reject = "POST /v1/me/card-exceptions/$id/reject"

  /** The hold KIFF serves for the contract's case: an ordinary held call, plus `purchase` (the contract's approval). */
  private fun held(purchase: String = approval.toString(), canApproveOnce: Boolean = true) =
    """{"id":"$id","subject":"a-1","action":"issue_card","title":"Buy","entity_id":"","proposal_id":"prop_1",
       "accounting_version":${vector.getValue("hold_version").jsonPrimitive.int},"can_approve_once":$canApproveOnce,
       "next_steps":["return_first"],"breaches":[{"outcome":"per_action_exceeded"}],"created_at":"${v("signed_at")}",
       "expires_at":"2099-01-01T00:00:00Z","purchase":$purchase}"""

  private suspend fun kotlinx.coroutines.test.TestScope.model(held: String = held()): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"${v("tenant")}","tenant_slug":"acme","tenant_display_name":"Acme"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"id_a-1","name":"A","kiff_agent":"a-1","color":1,"state":"waiting","card":{"ids":["c_a-1"],"active":true,"tools":[],"rules":[]}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$held]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
    transport.answer(approve, 200, "{}")
    transport.answer(reject, 200, "{}")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save(v("tenant"), AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, clock = clock, transport = transport, secrets = secrets, signer = signer, start = false, scope = backgroundScope)
      .also { it.restore() }
  }

  private fun signature(route: String): JsonObject =
    KiffClient.json.parseToJsonElement(String(transport.requestsTo(route).single().body!!)).jsonObject.getValue("signature").jsonObject

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(publicDER))
    return java.security.Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(Base64.getDecoder().decode(signatureBase64)) }
  }

  @Test fun theAnswerMessageIsTheContractsVectorByteForByte() {
    val line = PurchaseHold.line(
      approval.getValue("case_id").jsonPrimitive.content, approval.getValue("version").jsonPrimitive.int, approval.getValue("digest").jsonPrimitive.content,
    )
    val message = KiffClient.answerMessage(
      v("tenant"), v("exception_id"), v("answer"), v("next_step").ifEmpty { null }, v("note").ifEmpty { null },
      vector.getValue("hold_version").jsonPrimitive.int, v("signed_at"), purchase = line,
    )
    assertEquals(v("message"), String(message, Charsets.UTF_8))
    val sha = MessageDigest.getInstance("SHA-256").digest(message).joinToString("") { "%02x".format(it) }
    assertEquals(v("message_sha256"), sha)
  }

  @Test fun aPurchaseNeverHasABrowserLine() {
    val failed = runCatching { KiffClient.answerMessage("t", "e", "reject", null, null, 1, "x", browserSha256 = "abc", purchase = "purchase:pc_1:1:" + "0".repeat(64)) }
    assertTrue(failed.isFailure)
  }

  @Test fun theHoldDrawsKiffsWordsAsTheContractWritesThem() = runTest {
    val call = model().state.value.held.single()
    assertTrue(call.isPurchase)
    val p = assertNotNull(call.purchase).let { call.purchase!! }
    val words = approval.getValue("words").jsonObject
    fun w(key: String) = words.getValue(key).jsonPrimitive.content
    assertEquals(approval.getValue("case_id").jsonPrimitive.content, p.caseId)
    assertEquals(w("title"), p.words.title)
    assertEquals(w("total"), p.words.total)
    assertEquals(w("delivery"), p.words.delivery)
    assertEquals(w("grant"), p.words.grant)
    assertEquals(w("buys_now"), p.words.buysNow)
    assertEquals(w("nothing_until"), p.words.nothingUntil)
    assertEquals(w("prompt"), p.words.prompt)
    assertEquals(listOf("A4 paper, 80 g, 5 reams", "Ballpoint pens, blue, box of 50"), p.words.lines.map { it.name })
    assertEquals(listOf("€9.50"), p.words.charges.map { it.amount })
    // The merchant's "è" arrives as it was written.
    assertTrue(p.words.title.contains("Lumière"))
  }

  @Test fun approvingFromItsCardSignsTheCasesLineBehindKiffsPrompt() = runTest {
    val m = model()
    auth.asked.clear()
    val call = m.state.value.held.single()
    assertTrue(m.answer(call, Answer.ApproveOnce, shownPurchase = call.purchase))
    // The fingerprint prompt is KIFF's words: the shop and the total.
    assertEquals(listOf(call.purchase!!.words.prompt), auth.asked)
    val sig = signature(approve)
    assertEquals(v("signed_at"), sig.getValue("signed_at").jsonPrimitive.content)
    // The approval key signed exactly the contract's vector.
    assertTrue(verifies(signer.approvalPublicKeyDER, v("message").toByteArray(Charsets.UTF_8), sig.getValue("value").jsonPrimitive.content))
  }

  @Test fun approvingWithoutTheCaseItsCardDrewSendsNothing() = runTest {
    val m = model()
    auth.asked.clear()
    val call = m.state.value.held.single()
    // From any other screen (a taf's page, a Card's confirm): no case shown, so nothing is signed.
    assertFalse(m.answer(call, Answer.ApproveOnce))
    // A card that drew another version of the case.
    assertFalse(m.answer(call, Answer.ApproveOnce, shownPurchase = call.purchase!!.copy(version = 2)))
    assertFalse(m.answer(call, Answer.ApproveOnce, shownPurchase = call.purchase, confirmed = true))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo(approve).isEmpty())
    assertEquals(Words.Purchase.unreadable, m.state.value.lastError)
  }

  @Test fun extraRoomAndNextStepsAreNeverAnswersToAPurchase() = runTest {
    val m = model()
    auth.asked.clear()
    val call = m.state.value.held.single()
    assertFalse(m.answer(call, Answer.ExtraRoom))
    assertFalse(m.answer(call, Answer.NextStep("return_first")))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requests.none { it.method == "POST" })
    // Nor a standing rule: a purchase is answered once.
    assertFalse(HoldAllow.allowable(call))
  }

  @Test fun refusingSignsTheCasesLineToo() = runTest {
    val m = model()
    assertTrue(m.answer(m.state.value.held.single(), Answer.Refuse))
    val sig = signature(reject)
    val message = KiffClient.answerMessage(
      v("tenant"), id, "reject", null, null, 1, sig.getValue("signed_at").jsonPrimitive.content,
      purchase = "purchase:${approval.getValue("case_id").jsonPrimitive.content}:1:${approval.getValue("digest").jsonPrimitive.content}",
    )
    assertTrue(verifies(signer.publicKeyDER, message, sig.getValue("value").jsonPrimitive.content))
  }

  @Test fun unreadableWordsAreRefusedOnlyAndAnUnreadableCaseIsntAnswered() = runTest {
    // Words without a total: the case is read, so it can be refused, never approved.
    val noTotal = approval.toString().replace("\"total\":\"€84.20\",", "")
    val m = model(held(purchase = noTotal))
    val call = m.state.value.held.single()
    assertTrue(call.isPurchase)
    assertNull(call.purchase)
    assertNotNull(call.purchaseLine)
    auth.asked.clear()
    assertFalse(m.answer(call, Answer.ApproveOnce, shownPurchase = null))
    assertTrue(auth.asked.isEmpty())
    assertTrue(m.answer(call, Answer.Refuse))

    // A digest that isn't one: no line can be signed, so nothing is answered at all.
    assertEquals(PurchaseHold.Read.Unreadable(null), PurchaseHold.read(KiffClient.json.parseToJsonElement(approval.toString().replace(Regex("\"digest\":\"[0-9a-f]+\""), "\"digest\":\"x\""))))
    assertEquals(PurchaseHold.Read.Unreadable(null), PurchaseHold.read(KiffClient.json.parseToJsonElement("\"not an object\"")))
    // No block, or null: any other hold.
    assertNull(PurchaseHold.read(null))
    assertNull(PurchaseHold.read(kotlinx.serialization.json.JsonNull))
  }

  @Test fun aHoldKiffSaysCantBeApprovedOnceOffersRefuseOnly() = runTest {
    val m = model(held(canApproveOnce = false))
    val call = m.state.value.held.single()
    assertFalse(call.approvableOnce)
    // The card offers Refuse only (NeedsYouView, purchaseRefuseOnly); Extra room, which would otherwise be offered here, is refused.
    assertFalse(m.answer(call, Answer.ExtraRoom))
    assertTrue(transport.requests.none { it.method == "POST" })
  }

  @Test fun withNoReasonOfItsOwnTheBadgeIsTheHoldsKiffReason() = runTest {
    val noReason = approval.toString().replace(Regex(""","reason":"[^"]*""""), "")
    val m = model(held(purchase = noReason).replace("\"next_steps\"", "\"reason_text\":\"Your Card asks each time.\",\"next_steps\""))
    assertEquals("Your Card asks each time.", m.state.value.held.single().purchase!!.words.reason)
  }

  @Test fun anyOtherHoldIsAsBefore() = runTest {
    val m = model(held().replace(Regex(""","purchase":\{.*\}\}$""", RegexOption.DOT_MATCHES_ALL), "}"))
    val call = m.state.value.held.single()
    assertFalse(call.isPurchase)
    assertNull(call.purchaseLine)
  }
}
