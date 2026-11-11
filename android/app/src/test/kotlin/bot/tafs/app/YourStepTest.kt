package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.Home
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Your step (docs/api.md › Your step): a step a taf may never take itself, handed to its owner. Needs you draws it in tafs
 * cloud's words; its link's button shows only as sent. Done and Skip it are KIFF's `kiff.handoff.v1`, signed by this phone over
 * the hand-off as KIFF has it now, and sent to KIFF. The phone decides nothing about the step.
 */
class YourStepTest {
  private val t0 = Instant.parse("2026-11-11T17:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val signer = softwareSigner()
  private val read = "GET /v1/me/handoffs/ho_1"
  private val answer = "POST /v1/me/handoffs/ho_1/answer"

  private fun step(
    link: String = ""","link":"https://dashboard.stripe.com/payouts/po_1","link_host":"dashboard.stripe.com"""",
    open: String = ""","open":"Open in Stripe"""",
  ) =
    """{"handoff":"ho_1","run":"run_1","taf":{"id":"taf_1","name":"Payouts"},"what":"Approve the payout to Acme","why":"A payout needs your own approval.",""" +
      """"since":"2026-11-11T16:00:00Z","expires":"2026-11-18T16:00:00Z"$link,""" +
      """"words":{"title":"Your step","done":"Done","skip":"Skip it"$open,"line":"Your step: Approve the payout to Acme"}}"""

  private fun reads(steps: String = step()) {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["holds.answer"]}""")
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"taf_1","name":"Payouts","kiff_agent":"payouts-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],""" +
        """"others":[],"removing":[],"your_steps":[$steps]}""",
    )
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"payouts-1":{"use":true,"oversee":true,"manage":true}}}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore() }
  }

  private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  @Test fun theMessageIsKiffsByteForByte() {
    // KIFF's pinned vector: the same message and hash both apps and KIFF agree on.
    val message = KiffClient.handoffMessage("t_vector", "ho_0123456789abcdef01234567", "done", 1, "2026-11-11T17:00:00Z")
    assertEquals("kiff.handoff.v1\nt_vector\nho_0123456789abcdef01234567\ndone\n1\n2026-11-11T17:00:00Z", String(message))
    assertEquals("1b9f7f923489891b736af1fbc494c9348690494d4ae4901844aeb9a0b6df3b6f", sha256Hex(message))
    // A hold's answer to the same id never reads as a hand-off's.
    val hold = KiffClient.answerMessage("t_vector", "ho_0123456789abcdef01234567", "done", null, null, 1, "2026-11-11T17:00:00Z")
    assertFalse(String(hold).startsWith("kiff.handoff.v1"))
  }

  @Test fun kiffsVectorSignatureVerifiesWithItsKeyAndOnlyForThatAnswer() {
    // The vector's key: its private scalar is sha256 of the seed, so its public half is derived here, and pinned by its digest.
    val d = BigInteger(1, MessageDigest.getInstance("SHA-256").digest("kiff.handoff.v1 test vector".toByteArray()))
    val q = ECNamedCurveTable.getByName("secp256r1").g.multiply(d).normalize()
    val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    val point = ECPoint(q.affineXCoord.toBigInteger(), q.affineYCoord.toBigInteger())
    val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
    assertEquals("85c559569edf864d4b3186426f3541f8bbf8c424d96de17f707b919b2c593970", sha256Hex(key.encoded))
    val signature = "MEUCIQDJLYymcVMzMDhv+kRXK1BS2DhZvJROyBCjJLzzTy5NDgIgXe7a2OeeShmHHI5HazLS+/BzMjLVBYpjX7JT0Uy67RA="
    fun verifies(message: ByteArray) =
      Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(Base64.getDecoder().decode(signature)) }
    val at = "2026-11-11T17:00:00Z"
    assertTrue(verifies(KiffClient.handoffMessage("t_vector", "ho_0123456789abcdef01234567", "done", 1, at)))
    assertFalse(verifies(KiffClient.handoffMessage("t_vector", "ho_0123456789abcdef01234567", "skipped", 1, at)))
    assertFalse(verifies(KiffClient.handoffMessage("t_vector", "ho_other", "done", 1, at)))
    assertFalse(verifies(KiffClient.handoffMessage("t_other", "ho_0123456789abcdef01234567", "done", 1, at)))
    assertFalse(verifies(KiffClient.handoffMessage("t_vector", "ho_0123456789abcdef01234567", "done", 2, at)))
  }

  @Test fun theButtonShowsOnlyWithTafsCloudsLinkAndWords() {
    fun home(s: String) = KiffClient.json.decodeFromString<Home>("""{"tafs":[],"your_steps":[$s]}""").yourSteps!!.single()
    assertEquals("Open in Stripe" to "https://dashboard.stripe.com/payouts/po_1", home(step()).open)
    assertNull("no link: words only", home(step(link = "")).open)
    assertNull("no words for the button: none", home(step(open = "")).open)
  }

  @Test fun stepsAreReadFromHomeAndCountedInNeedsYou() = runTest {
    reads()
    val m = model()
    m.refresh()
    assertEquals(listOf("ho_1"), m.state.value.yourSteps.map { it.handoff })
    assertEquals("Payouts", m.state.value.yourSteps.single().taf?.name)
    assertEquals(1, m.state.value.needsYouCount)
  }

  @Test fun doneSignsKiffsMessageOverTheHandoffAsKiffHasItAndGoesToKiff() = runTest {
    reads()
    transport.answer(read, 200, """{"id":"ho_1","agent":"payouts-1","version":1,"status":"waiting"}""")
    transport.answer(answer, 200, "{}")
    val m = model()
    m.refresh()
    m.answerYourStep(m.state.value.yourSteps.single(), done = true)
    val sent = KiffClient.json.parseToJsonElement(String(transport.requestsTo(answer).single().body!!)).jsonObject
    assertEquals("done", sent["answer"]!!.jsonPrimitive.content)
    val sig = sent["signature"]!!.jsonObject
    val signedAt = sig["signed_at"]!!.jsonPrimitive.content
    assertEquals("2026-11-11T17:00:00Z", signedAt)
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(signer.publicKeyDER))
    val message = KiffClient.handoffMessage("t_01", "ho_1", "done", 1, signedAt)
    val value = Base64.getDecoder().decode(sig["value"]!!.jsonPrimitive.content)
    assertTrue("signed by the device key", Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(value) })
    // Never the approval key: answering grants nothing, so no fingerprint is asked for it.
    if (signer.approvalPublicKeyDER.isNotEmpty()) {
      val approval = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(signer.approvalPublicKeyDER))
      assertFalse(Signature.getInstance("SHA256withECDSA").run { initVerify(approval); update(message); verify(value) })
    }
    // Gone from Needs you at once.
    assertTrue(m.state.value.yourSteps.isEmpty())
    assertTrue(m.state.value.answeringSteps.isEmpty())
  }

  @Test fun skipSignsSkipped() = runTest {
    reads()
    transport.answer(read, 200, """{"id":"ho_1","version":1,"status":"waiting"}""")
    transport.answer(answer, 200, "{}")
    val m = model()
    m.refresh()
    m.answerYourStep(m.state.value.yourSteps.single(), done = false)
    val sent = KiffClient.json.parseToJsonElement(String(transport.requestsTo(answer).single().body!!)).jsonObject
    assertEquals("skipped", sent["answer"]!!.jsonPrimitive.content)
  }

  @Test fun aStepKiffNoLongerWaitsOnIsNotAnsweredAndLeaves() = runTest {
    reads()
    transport.answer(read, 200, """{"id":"ho_1","version":1,"status":"no_answer"}""")
    val m = model()
    m.refresh()
    m.answerYourStep(m.state.value.yourSteps.single(), done = true)
    assertTrue(transport.requestsTo(answer).isEmpty())
    assertTrue(m.state.value.yourSteps.isEmpty())
    assertEquals(bot.tafs.app.model.Words.NeedsYou.YourStep.ended, m.state.value.lastError)
  }

  @Test fun anAnswerKiffRefusesKeepsTheStepAndSaysWhy() = runTest {
    reads()
    transport.answer(read, 200, """{"id":"ho_1","version":1,"status":"waiting"}""")
    transport.answer(answer, 403, """{"error":"answering needs holds.answer"}""")
    val m = model()
    m.refresh()
    m.answerYourStep(m.state.value.yourSteps.single(), done = true)
    assertEquals(listOf("ho_1"), m.state.value.yourSteps.map { it.handoff })
    assertTrue(m.state.value.lastError!!.isNotBlank())
    assertTrue(m.state.value.answeringSteps.isEmpty())
  }

  @Test fun aStepsPushIsTaggedByItsHandoffAndOpensTheTaf() {
    val alert = bot.tafs.app.push.AlertPayload.parse(
      mapOf("kind" to "alert", "title" to "Payouts", "body" to "Your step: Approve the payout to Acme", "open" to "taf", "taf_id" to "taf_1", "handoff" to "ho_1"),
      t0,
    ) as bot.tafs.app.push.Alert.Show
    assertEquals("step:ho_1", alert.tag)
    assertEquals("taf_1", bot.tafs.app.push.AlertPayload.tafOf(alert.open))
  }
}
