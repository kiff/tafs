@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.signing.ApprovalSigning
import bot.tafs.app.watch.WatchRelay
import bot.tafs.watchlink.NotAccepted
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Refuse and Halt from the Wear OS watch (docs/watch-wear.md): only the watch's requests, fresh, are acted on; Refuse is signed
 * with the signing key and no prompt on the phone, never the approval key; a call already answered isn't answered again; and
 * the watch is told KIFF's or tafs cloud's real result. A fake KIFF and tafs cloud stand in.
 */
class WatchRefuseHaltTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = Watched(softwareSigner(auth))
  private val t0 = "2026-10-28T09:00:00Z"
  private val now = 1_763_244_000_000L

  /** The phone's signer, counting what it signs, and failing the test if the approval key is ever asked on this path. */
  private class Watched(private val inner: ApprovalSigning) : ApprovalSigning by inner {
    var signed = 0
    override suspend fun sign(reason: String, built: () -> ByteArray): String = inner.sign(reason, built).also { signed++ }
    override fun signConfirmed(message: ByteArray): String = inner.signConfirmed(message).also { signed++ }
    override suspend fun signApproval(reason: String, built: () -> ByteArray): String =
      throw AssertionError("the approval key was asked on the watch's path")
  }

  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}"""
  private val home =
    """{"tafs":[{"id":"id_a-1","name":"Refunds","kiff_agent":"a-1","color":1,"state":"waiting","card":{"ids":["c_a-1"],"active":true,"tools":[],"rules":[]}}],"others":[],"removing":[]}"""
  private val held =
    """{"id":"ce_1","subject":"a-1","action":"create_refund","title":"Refund a payment","entity_id":"tc-ce_1","proposal_id":"prop_ce_1","accounting_version":1,
       "next_steps":[],"breaches":[{"outcome":"per_action_exceeded","reason":"over","quantity":"sum(amount)","limit":10000,"unit":"EUR","scale":2}],
       "parameters":{"amount":15000},"parameter_units":{"amount":{"unit":"EUR","scale":2}},"arguments":{"payment_intent":"pi_ce_1"},
       "created_at":"$t0","expires_at":"2099-01-01T00:00:00Z"}"""
  private val activity =
    """{"proposal_id":"prop_ce_1","action_name":"create_refund","title":"Refund a payment","actor_id":"a-1","outcome":"owner_required","created_at":"$t0"}"""

  private fun reads(withHeld: Boolean = true) {
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[${if (withHeld) held else ""}]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[${if (withHeld) activity else ""}]}""")
  }

  private fun haltBody(state: String, account: Boolean = false) =
    """{"halt":{"state":"$state","runtime":true,"kiff":${state == "halted"},"planque":${state == "halted"},"account":$account}}"""

  private suspend fun TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, start = false, scope = backgroundScope).also { it.restore() }
  }

  private suspend fun WatchRelay.ask(r: WatchRequest): WatchReply = WatchLink.encode(r).let { (path, data) -> answer(path, data) }

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDER))
    return Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(Base64.getDecoder().decode(signatureBase64)) }
  }

  // MARK: Refuse

  @Test fun aRefusalFromTheWristIsSignedByTheSigningKeyWithNoPromptAndKiffsAnswerIsShown() = runTest {
    reads(); transport.answer("POST /v1/me/card-exceptions/ce_1/reject", 200, "{}")
    val m = model()
    val hold = m.state.value.held.single().id
    val reply = WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Refuse(now, hold))
    assertEquals(WatchReply.Done("Refused"), reply)
    val sent = KiffClient.json.parseToJsonElement(String(transport.requestsTo("POST /v1/me/card-exceptions/ce_1/reject").single().body!!)).jsonObject
    val signature = sent.getValue("signature").jsonObject
    val message = "kiff.answer.v1\nt_01\nce_1\nreject\n\n\n1\n${signature.getValue("signed_at").jsonPrimitive.content}".toByteArray()
    assertTrue("the signing key signed it", verifies(signer.publicKeyDER, message, signature.getValue("value").jsonPrimitive.content))
    assertTrue("the phone asked nothing: the watch's own unlock confirmed it", auth.asked.isEmpty() && auth.fingerprints.isEmpty())
  }

  @Test fun aCallAlreadyAnsweredIsNotAnsweredAgain() = runTest {
    reads()
    val m = model()
    val hold = m.state.value.held.single().id
    // Answered elsewhere meanwhile: KIFF no longer holds it when the phone reads again.
    transport.reset(); reads(withHeld = false)
    assertEquals(WatchReply.AlreadyAnswered, WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Refuse(now, hold)))
    assertEquals(WatchReply.AlreadyAnswered, WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Refuse(now, "no_such_hold")))
    assertTrue(transport.requests.none { it.method == "POST" })
    assertEquals(0, signer.signed)
  }

  @Test fun aRefusalKiffDidntTakeSaysSoAndNothingIsShownRefused() = runTest {
    reads(); transport.answer("POST /v1/me/card-exceptions/ce_1/reject", 502, """{"error":"unavailable"}""")
    val m = model()
    val hold = m.state.value.held.single().id
    val reply = WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Refuse(now, hold))
    assertTrue(reply.toString(), reply is WatchReply.Failed)
    assertTrue("still waiting on the phone", m.state.value.held.any { it.id == hold })
  }

  @Test fun whatTheGateRefusesIsNeverSigned() = runTest {
    reads()
    val m = model()
    val hold = m.state.value.held.single().id
    val relay = WatchRelay(model = { m }, now = { now })
    assertEquals(WatchReply.Refused(NotAccepted.UNKNOWN_PATH), relay.answer(WatchLink.PREFIX + "approve", """{"made_at":$now,"hold_id":"$hold"}""".encodeToByteArray()))
    assertEquals(WatchReply.Refused(NotAccepted.MALFORMED), relay.answer(WatchLink.REFUSE, """{"made_at":$now}""".encodeToByteArray()))
    assertEquals(WatchReply.Refused(NotAccepted.MALFORMED), relay.answer(WatchLink.REFUSE, """{"made_at":$now,"hold_id":"$hold","answer":"approve_once"}""".encodeToByteArray()))
    assertEquals(WatchReply.Refused(NotAccepted.TOO_OLD), relay.answer(WatchLink.REFUSE, """{"made_at":${now - 31_000},"hold_id":"$hold"}""".encodeToByteArray()))
    assertEquals(0, signer.signed)
    assertTrue(transport.requests.none { it.method == "POST" })
  }

  // MARK: Halt

  @Test fun haltingATafFromTheWristAsksNothingAndShowsWhereItStands() = runTest {
    reads(withHeld = false); transport.answer("POST /v1/tafs/id_a-1/halt", 200, haltBody("halting"))
    val m = model()
    val taf = m.state.value.tafs.single().id
    assertEquals(WatchReply.Done("Nothing new starts. Halted shows once KIFF and the connection both confirm."), WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Halt(now, taf)))
    assertEquals(1, transport.requestsTo("POST /v1/tafs/id_a-1/halt").size)
    assertTrue(auth.asked.isEmpty())
  }

  @Test fun haltAllFromTheWristIsTheAccountsHalt() = runTest {
    reads(withHeld = false); transport.answer("POST /v1/halt", 200, haltBody("halted", account = true))
    val m = model()
    assertEquals(WatchReply.Done("All your tafs halted"), WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Halt(now, null)))
    assertEquals(1, transport.requestsTo("POST /v1/halt").size)
  }

  @Test fun aLateHaltIsDroppedAndAFailedOneSaysWhy() = runTest {
    reads(withHeld = false); transport.answer("POST /v1/tafs/id_a-1/halt", 502, """{"error":"kiff/agents is unreachable"}""")
    val m = model()
    val taf = m.state.value.tafs.single().id
    val relay = WatchRelay(model = { m }, now = { now })
    assertEquals(WatchReply.Refused(NotAccepted.TOO_OLD), relay.answer(WatchLink.HALT, """{"made_at":${now - 31_000},"taf_id":"$taf"}""".encodeToByteArray()))
    assertTrue(transport.requestsTo("POST /v1/tafs/id_a-1/halt").isEmpty())
    val failed = relay.ask(WatchRequest.Halt(now, taf))
    assertTrue(failed.toString(), failed is WatchReply.Failed && failed.words.isNotBlank())
    assertEquals(WatchReply.Failed("Open on phone"), relay.ask(WatchRequest.Halt(now, "no_such_taf")))
  }

  // MARK: Asked twice, or of a call the phone can't answer

  @Test fun aRefusalSentTwiceIsAnsweredOnce() = runTest {
    reads(); transport.answer("POST /v1/me/card-exceptions/ce_1/reject", 200, "{}")
    val m = model()
    val hold = m.state.value.held.single().id
    val relay = WatchRelay(model = { m }, now = { now })
    assertEquals(WatchReply.Done("Refused"), relay.ask(WatchRequest.Refuse(now, hold)))
    // KIFF no longer holds it: the same request again, within 30 s, is already answered, and nothing is sent or signed again.
    val signed = signer.signed
    transport.reset(); reads(withHeld = false)
    assertEquals(WatchReply.AlreadyAnswered, relay.ask(WatchRequest.Refuse(now + 5_000, hold)))
    assertTrue(transport.requests.none { it.method == "POST" })
    assertEquals(signed, signer.signed)
  }

  @Test fun aHaltSentTwiceAsksTafsCloudOnce() = runTest {
    reads(withHeld = false)
    transport.answer("GET /v1/tafs/id_a-1/halt", 200, haltBody("running"))
    transport.answer("POST /v1/tafs/id_a-1/halt", 200, haltBody("halting"))
    transport.answer("GET /v1/tafs/id_a-1/halt", 200, haltBody("halting"))
    val m = model()
    val taf = m.state.value.tafs.single().id
    val relay = WatchRelay(model = { m }, now = { now })
    val first = relay.ask(WatchRequest.Halt(now, taf))
    val again = relay.ask(WatchRequest.Halt(now + 5_000, taf))
    assertEquals(first, again)
    assertEquals("one halt, however often the wrist asks", 1, transport.requestsTo("POST /v1/tafs/id_a-1/halt").size)
  }

  @Test fun whetherACallMayBeRefusedIsThePhonesOwnRead() = runTest {
    // KIFF holds it but keeps no accounting version this phone could sign over: the phone can't answer it from here.
    val unsigned = held.replace("\"accounting_version\":1,", "")
    transport.answer("GET /v1/me", 200, me)
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[$unsigned]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[$activity]}""")
    val m = model()
    val call = m.state.value.held.single()
    assertTrue("not refusable from the wrist", !WatchRelay.needsYou(m.state.value).calls.single().refusable)
    // The watch's request carries no say in it (the gate refuses any extra field), and the phone answers from its own read.
    val relay = WatchRelay(model = { m }, now = { now })
    assertEquals(
      WatchReply.Refused(NotAccepted.MALFORMED),
      relay.answer(WatchLink.REFUSE, """{"made_at":$now,"hold_id":"${call.id}","refusable":true}""".encodeToByteArray()),
    )
    // Said plainly on the watch (row 2): not refused, and where to refuse it. Nothing reached KIFF.
    assertEquals(WatchReply.Failed("Not refused. Refuse it on your phone."), relay.ask(WatchRequest.Refuse(now, call.id)))
    assertTrue(transport.requests.none { it.method == "POST" })
    assertEquals(0, signer.signed)
  }
}
