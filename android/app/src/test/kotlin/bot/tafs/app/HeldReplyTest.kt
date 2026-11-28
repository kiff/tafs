@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.Home
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
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
 * A door's reply held for the owner (docs/doors.md): tafs cloud lists it on the taf's doors with the exact text, its hash and its
 * words; Send is the approval key behind the fingerprint, signed over the action and that hash, so only the text read goes; Don't
 * send signs nothing. A fake KIFF and tafs cloud stand in: no real reply is sent.
 */
class HeldReplyTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val signer: ApprovalSigning = softwareSigner(auth) { t0 }
  private val doors = "GET /v1/tafs/taf_1/doors"
  private val reply = "POST /v1/tafs/taf_1/doors/replies/run_7"
  private val text = "Hello Ben,\n\nYour refund for order T-3001 is on its way.\n\nAcme"
  private val sha = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

  private val homeBody =
    """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true},
        "doors":[{"app":"email","knocks":0,"replies":1,"reply_line":"Refunds wrote a reply to Ben. It waits for you to send it."}]}],
        "others":[],"removing":[],"doors_offered":["email"]}"""

  private fun replyJson(words: Boolean = true) =
    """{"run":"run_7","door":"door_1","to":"Ben","subject":"Re: refund","text":${KiffClient.json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))},
        "at":"2026-10-28T08:59:00Z","text_sha256":"$sha"${if (words) ""","words":{"line":"Refunds wrote a reply to Ben. It waits for you to send it.","send":"Send","discard":"Don't send","prompt":"Send this reply to Ben","changed":"This reply changed since you read it. Read it again before sending."}""" else ""}}"""

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme"}""")
    transport.answer("GET /v1/home", 200, homeBody)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":true}}}""")
    transport.answer(doors, 200, """{"doors":[],"apps":[],"knocks":[],"replies":[${replyJson()}]}""")
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

  @Test fun homesLightGivesTheTafsHeldRepliesAndTheNewestLine() {
    val taf = KiffMapping.taf(KiffClient.json.decodeFromString<Home>(homeBody).tafs.single(), "You")
    assertEquals(1, taf.heldReplies)
    assertEquals("Refunds wrote a reply to Ben. It waits for you to send it.", taf.heldReplyLine)
  }

  @Test fun theDoorsReadListsTheReplyWithItsExactTextAndWords() = runTest {
    reads()
    val r = model().state.value.doors.getValue("refunds-1").replies.single()
    assertEquals(text, r.text)
    assertEquals(sha, r.textSha256)
    assertEquals("Send this reply to Ben", r.words!!.prompt)
  }

  @Test fun sendSignsTheActionAndTheTextsHashWithTheApprovalKeyBehindTafsCloudsPrompt() = runTest {
    reads()
    transport.answer(reply, 200, """{"run":"run_7","state":"sent"}""")
    val m = model()
    auth.asked.clear()
    assertTrue(m.sendHeldReply("refunds-1", m.state.value.doors.getValue("refunds-1").replies.single()))
    assertEquals(listOf("Send this reply to Ben"), auth.asked)
    val req = transport.requestsTo(reply).single()
    val body = KiffClient.json.parseToJsonElement(String(req.body!!)).jsonObject
    assertEquals("send", body.getValue("action").jsonPrimitive.content)
    assertEquals(sha, body.getValue("text_sha256").jsonPrimitive.content)
    // KIFF's request message over exactly these bytes, with the approval key.
    val header = req.headers.getValue("KIFF-Device-Signature")
    val signedAt = header.substringAfter("t=").substringBefore(",")
    val sig = header.substringAfter("v1=")
    val message = KiffClient.requestMessage("t_01", "POST", "/v1/tafs/taf_1/doors/replies/run_7", req.body!!, signedAt)
    assertTrue(verifies(signer.approvalPublicKeyDER, message, sig))
    assertTrue(m.state.value.doors.getValue("refunds-1").replies.isEmpty())
  }

  @Test fun closingTheFingerprintSendsNothing() = runTest {
    reads()
    val m = model()
    auth.confirms = false
    assertFalse(m.sendHeldReply("refunds-1", m.state.value.doors.getValue("refunds-1").replies.single()))
    assertTrue(transport.requestsTo(reply).isEmpty())
  }

  @Test fun aReplyThatChangedSaysSoInTafsCloudsWordsAndIsReadAgain() = runTest {
    reads()
    transport.answer(reply, 409, """{"error":"This reply changed since you read it. Read it again before sending.","code":"reply_changed"}""")
    val m = model()
    val doorReads = transport.requestsTo(doors).size
    assertFalse(m.sendHeldReply("refunds-1", m.state.value.doors.getValue("refunds-1").replies.single()))
    assertEquals("This reply changed since you read it. Read it again before sending.", m.state.value.doors.getValue("refunds-1").error)
    assertEquals(doorReads + 1, transport.requestsTo(doors).size)
  }

  @Test fun dontSendSignsNothing() = runTest {
    reads()
    transport.answer(reply, 200, """{"run":"run_7","state":"not_sent"}""")
    val m = model()
    auth.asked.clear()
    assertTrue(m.discardHeldReply("refunds-1", m.state.value.doors.getValue("refunds-1").replies.single()))
    assertTrue(auth.asked.isEmpty())
    val req = transport.requestsTo(reply).single()
    assertEquals("discard", KiffClient.json.parseToJsonElement(String(req.body!!)).jsonObject.getValue("action").jsonPrimitive.content)
    assertNull(req.headers["KIFF-Device-Signature"])
  }

  @Test fun aReplyWithoutTafsCloudsWordsIsntSentFromHere() = runTest {
    reads()
    transport.answer(doors, 200, """{"doors":[],"apps":[],"knocks":[],"replies":[${replyJson(words = false)}]}""")
    val m = model()
    m.loadDoors("refunds-1")
    assertFalse(m.sendHeldReply("refunds-1", m.state.value.doors.getValue("refunds-1").replies.single()))
    assertTrue(transport.requestsTo(reply).isEmpty())
  }
}
