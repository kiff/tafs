package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.AIUse
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
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
 * Go on (kiff/tafs#185): past the AI use bound, the billing admin lets the tafs go on from Settings › AI use, confirmed then
 * signed by the device key, as KIFF takes it; anyone else reads tafs cloud's words. A fake KIFF and tafs cloud stand in.
 */
class GoOnTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private val paused =
    """{"used_pct":100,"asks_at_pct":100,"paused":true,"can_go_on":true,"starts_again":"2026-11-01T00:00:00Z",
       "words":"Your tafs used 100% of this period's AI and wait for you. Go on lets them carry on."}"""
  private val goneOn =
    """{"used_pct":100,"asks_at_pct":200,"paused":false,"can_go_on":true,"starts_again":"2026-11-01T00:00:00Z",
       "words":"Your tafs used 100% of this period's AI. They ask you before going past 200%."}"""
  private val notMine =
    """{"used_pct":100,"asks_at_pct":100,"paused":true,"can_go_on":false,"starts_again":"2026-11-01T00:00:00Z",
       "words":"An admin of this account can let them go on from Settings › AI use. Otherwise it starts again on 1 Nov."}"""

  private suspend fun model(ai: String): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/usage", 200, """{"plan":"solo","period":{"start":"2026-10-01T00:00:00Z","end":"2026-11-01T00:00:00Z"},"tasks":{"counted":1,"included":20,"packs":0,"left":19}}""")
    transport.answer("GET /v1/me/usage/tasks", 200, """{"items":[]}""")
    transport.answer("GET /v1/usage/ai", 200, ai)
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also {
      it.restore()
      it.refreshUsage()
    }
  }

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDER))
    return Signature.getInstance("SHA256withECDSA").run {
      initVerify(key)
      update(message)
      verify(Base64.getDecoder().decode(signatureBase64))
    }
  }

  @Test fun theContractsFieldsAreRead() = runTest {
    val m = model(paused)
    val ai = m.state.value.aiUse!!
    assertTrue(ai.canGoOn)
    assertEquals(Instant.parse("2026-11-01T00:00:00Z"), ai.startsAgain)
    assertTrue(ai.words!!.startsWith("Your tafs used 100%"))
    assertTrue(ai.offersGoOn)
  }

  @Test fun goOnIsShownOnlyWhileTheTafsWaitAndOnlyToWhoMay() {
    assertTrue(AIUse(100, 100, paused = true, canGoOn = true).offersGoOn)
    assertFalse("not waiting", AIUse(50, 100, paused = false, canGoOn = true).offersGoOn)
    assertFalse("not the billing admin", AIUse(100, 100, paused = true, canGoOn = false).offersGoOn)
    // From a tafs cloud before the contract: no Go on.
    assertFalse(KiffClient.json.decodeFromString<AIUse>("""{"used_pct":100,"asks_at_pct":100,"paused":true}""").offersGoOn)
  }

  @Test fun goOnIsConfirmedOnceThenKiffsPutSignedByTheDeviceKeyThenAiUseReadAgain() = runTest {
    val m = model(paused)
    transport.answer("PUT /v1/me/usage/ai", 200, "{}")
    transport.answer("GET /v1/usage/ai", 200, goneOn)
    assertTrue(m.goOnAI())
    assertEquals(listOf("Let your tafs go on past this month's AI use"), auth.asked)
    val put = transport.requestsTo("PUT /v1/me/usage/ai").single()
    assertEquals("https://api.kiff.dev/v1/me/usage/ai", put.url)
    assertEquals("""{"go_on":true}""", String(put.body!!))
    val (at, signature) = put.headers.getValue("KIFF-Device-Signature").split(",").let { it[0].removePrefix("t=") to it[1].removePrefix("v1=") }
    val message = KiffClient.requestMessage("t_01", "PUT", "/v1/me/usage/ai", put.body!!, at)
    assertTrue("KIFF takes it from the device key (a phone-signed route)", verifies(signer.publicKeyDER, message, signature))
    assertFalse("not the approval key, which KIFF would refuse here", verifies(signer.approvalPublicKeyDER, message, signature))
    assertEquals(200, m.state.value.aiUse!!.asksAtPct)
    assertFalse(m.state.value.aiUse!!.paused)
    assertFalse(m.state.value.goingOn)
    assertNull(m.state.value.goOnError)
  }

  @Test fun someoneWhoMayNotGoOnSendsNothing() = runTest {
    val m = model(notMine)
    assertFalse(m.goOnAI())
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo("PUT /v1/me/usage/ai").isEmpty())
    assertEquals("An admin of this account can let them go on from Settings › AI use. Otherwise it starts again on 1 Nov.", m.state.value.aiUse!!.words)
  }

  @Test fun aGoOnNobodyConfirmedSendsNothingAndSaysSo() = runTest {
    val m = model(paused)
    auth.confirms = false
    assertFalse(m.goOnAI())
    assertTrue(transport.requestsTo("PUT /v1/me/usage/ai").isEmpty())
    assertEquals("Not done: it wasn't confirmed.", m.state.value.goOnError)
    assertTrue("still waiting", m.state.value.aiUse!!.paused)
  }

  @Test fun kiffRefusingTheRoleSaysSoInWords() = runTest {
    val m = model(paused)
    transport.answer("PUT /v1/me/usage/ai", 403, """{"error":"not allowed"}""")
    assertFalse(m.goOnAI())
    assertEquals("Your tafs didn't go on. Your role on this account doesn't allow this.", m.state.value.goOnError)
    assertTrue(m.state.value.aiUse!!.paused)
  }
}
