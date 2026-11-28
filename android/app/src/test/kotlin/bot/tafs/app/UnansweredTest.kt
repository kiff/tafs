@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.signing.ApprovalSigning
import java.security.MessageDigest
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
 * A door's writer the taf couldn't answer (docs/doors.md): tafs cloud lists them apart from the replies, with no text, its reason
 * and its words. The owner's own answer is the approval key behind the fingerprint, signed over the action, the text exactly as
 * typed and this phone's hash of it; Ignore signs nothing. A fake KIFF and tafs cloud stand in: nothing is sent to anyone.
 */
class UnansweredTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val t0 = Instant.parse("2026-11-28T09:00:00Z")
  private val signer: ApprovalSigning = softwareSigner(auth) { t0 }
  private val doors = "GET /v1/tafs/taf_1/doors"
  private val answer = "POST /v1/tafs/taf_1/doors/replies/run_9"
  private val line = "Refunds can't do what Ben asked: the app it needs isn't connected. Their message waits here, and they haven't been answered."

  private fun writer(cantDo: String = "no_app", words: Boolean = true): String {
    val connect = if (cantDo == "no_app") """"connect":"Connect an app",""" else ""
    val w = if (!words) "" else
      ""","words":{"line":"$line",$connect"reply":"Answer them yourself","ignore":"Ignore","prompt":"Send this reply to Ben","changed":"It changed."}"""
    return """{"run":"run_9","door":"door_1","to":"Ben","subject":"Invoice 118","at":"2026-11-28T08:59:00Z","cant_do":"$cantDo"$w}"""
  }

  private fun reads(unanswered: String = "[${writer()}]") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme"}""")
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""",
    )
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":true}}}""")
    transport.answer(doors, 200, """{"doors":[],"apps":[],"knocks":[],"replies":[],"unanswered":$unanswered}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also {
      it.restore()
      it.refresh()
      it.loadDoors("refunds-1")
    }
  }

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(publicDER))
    return java.security.Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(Base64.getDecoder().decode(signatureBase64)) }
  }

  @Test fun theDoorsReadListsTheWriterApartFromTheRepliesAndLeavesOutOneItCantRead() = runTest {
    reads("""[${writer()},{"run":"run_x"}]""")
    val d = model().state.value.doors.getValue("refunds-1")
    assertTrue(d.replies.isEmpty())
    val u = d.unanswered.single()
    assertEquals("no_app", u.cantDo)
    assertEquals("Connect an app", u.words!!.connect)
    assertEquals("Answer them yourself", u.words!!.reply)
  }

  @Test fun onlyAMissingAppOffersConnect() = runTest {
    reads("[${writer("other")}]")
    assertNull(model().state.value.doors.getValue("refunds-1").unanswered.single().words!!.connect)
  }

  @Test fun theOwnersAnswerIsSignedOverTheTextAsTypedAndItsHash() = runTest {
    reads()
    transport.answer(answer, 200, """{"run":"run_9","state":"sent"}""")
    val m = model()
    auth.asked.clear()
    // Exactly as typed: the trailing space and the accent are sent and hashed as they are.
    val typed = "Hello Ben, I'll send invoice 118 myself today. Zoé "
    assertTrue(m.answerUnanswered("refunds-1", m.state.value.doors.getValue("refunds-1").unanswered.single(), typed))
    assertEquals(listOf("Send this reply to Ben"), auth.asked)
    val req = transport.requestsTo(answer).single()
    val body = KiffClient.json.parseToJsonElement(String(req.body!!)).jsonObject
    assertEquals("send", body.getValue("action").jsonPrimitive.content)
    assertEquals(typed, body.getValue("text").jsonPrimitive.content)
    val sha = MessageDigest.getInstance("SHA-256").digest(typed.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    assertEquals(sha, body.getValue("text_sha256").jsonPrimitive.content)
    // KIFF's request message over exactly these bytes, with the approval key.
    val header = req.headers.getValue("KIFF-Device-Signature")
    val message = KiffClient.requestMessage("t_01", "POST", "/v1/tafs/taf_1/doors/replies/run_9", req.body!!, header.substringAfter("t=").substringBefore(","))
    assertTrue(verifies(signer.approvalPublicKeyDER, message, header.substringAfter("v1=")))
    assertTrue(m.state.value.doors.getValue("refunds-1").unanswered.isEmpty())
  }

  @Test fun closingTheFingerprintOrAnEmptyAnswerSendsNothing() = runTest {
    reads()
    val m = model()
    val u = m.state.value.doors.getValue("refunds-1").unanswered.single()
    assertFalse(m.answerUnanswered("refunds-1", u, "  \n"))
    auth.confirms = false
    assertFalse(m.answerUnanswered("refunds-1", u, "Hello Ben"))
    assertTrue(transport.requestsTo(answer).isEmpty())
    assertEquals(1, m.state.value.doors.getValue("refunds-1").unanswered.size)
  }

  @Test fun aRefusalSaysWhyAndKeepsTheWriter() = runTest {
    reads()
    transport.answer(answer, 400, """{"error":"text is your own answer, at most 4000 characters"}""")
    val m = model()
    assertFalse(m.answerUnanswered("refunds-1", m.state.value.doors.getValue("refunds-1").unanswered.single(), "Hello Ben"))
    assertTrue(!m.state.value.doors.getValue("refunds-1").error.isNullOrBlank())
    assertEquals(1, m.state.value.doors.getValue("refunds-1").unanswered.size)
  }

  @Test fun ignoreSignsNothing() = runTest {
    reads()
    transport.answer(answer, 200, """{"run":"run_9","state":"not_sent"}""")
    val m = model()
    auth.asked.clear()
    assertTrue(m.ignoreUnanswered("refunds-1", m.state.value.doors.getValue("refunds-1").unanswered.single()))
    assertTrue(auth.asked.isEmpty())
    val req = transport.requestsTo(answer).single()
    assertEquals("discard", KiffClient.json.parseToJsonElement(String(req.body!!)).jsonObject.getValue("action").jsonPrimitive.content)
    assertNull(req.headers["KIFF-Device-Signature"])
    assertTrue(m.state.value.doors.getValue("refunds-1").unanswered.isEmpty())
  }

  @Test fun aWriterWithoutTafsCloudsWordsIsntAnsweredFromHere() = runTest {
    reads("[${writer(words = false)}]")
    val m = model()
    assertFalse(m.answerUnanswered("refunds-1", m.state.value.doors.getValue("refunds-1").unanswered.single(), "Hello Ben"))
    assertTrue(transport.requestsTo(answer).isEmpty())
  }
}
