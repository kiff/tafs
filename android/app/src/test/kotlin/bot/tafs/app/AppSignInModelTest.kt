package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.AppSignIn
import bot.tafs.app.kiff.AppSignInRefused
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.VendorSignIn
import bot.tafs.app.app.Mode
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Signing in once, inside tafs (docs/onboarding.md, step 2): KIFF's page in the tab, its code traded with the verifier and the
 * device's keys, and today's session. A link back that isn't this attempt's is never used.
 */
class AppSignInModelTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val signer = softwareSigner()
  private val token = """{"access_token":"at_1","token_type":"Bearer","expires_in":3600,"tenant":"t_01","subject":"user_1","approval_mode":"device"}"""
  private val me = """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}"""

  /** KIFF's tab, standing in: it keeps the page asked for and comes back with [back] for its state. */
  private class Tab(val back: (state: String) -> String) : VendorSignIn {
    var asked: String? = null
    override suspend fun signIn(url: String): String {
      asked = url
      return back(query(url).getValue("state"))
    }
  }

  private fun model(tab: Tab) = testModel(transport = transport, secrets = secrets, signer = signer, start = false, appSignInTab = tab)

  @Test fun kiffsCodeIsTradedForTodaysSession() = runTest {
    transport.answer("POST /v1/auth/app/token", 200, token)
    transport.answer("GET /v1/me", 200, me)
    transport.answer("POST /v1/me/device/approval-key", 200, """{"fingerprint":"${KiffClient.fingerprint(signer.approvalPublicKeyDER)}"}""")
    val tab = Tab { state -> "dev.kiff.tafs:/oauth?code=c_1&state=$state" }
    val m = model(tab)
    m.signInInApp("2026-11-14")
    assertEquals(Mode.kiff, m.state.value.mode)
    assertEquals("at_1", secrets.load(AppModel.TOKEN))
    assertEquals("t_01", secrets.load(AppModel.TENANT))
    // The page asked with a challenge; the token step proved it with the verifier, never sent before.
    val asked = query(tab.asked!!)
    assertTrue(tab.asked!!.startsWith("https://api.kiff.dev/oauth/authorize?"))
    val body = (Json.parseToJsonElement(String(transport.requestsTo("POST /v1/auth/app/token").single().body!!)) as JsonObject)
      .mapValues { it.value.jsonPrimitive.content }
    assertEquals("c_1", body["code"])
    assertEquals(asked["code_challenge"], AppSignIn.challengeOf(body.getValue("code_verifier")))
    assertTrue(!tab.asked!!.contains(body.getValue("code_verifier")))
    assertEquals("2026-11-14", body["terms_version"])
    assertEquals("android", body["platform"])
    assertEquals("android_phone", body["kind"])
    assertEquals("Bearer at_1", transport.requestsTo("GET /v1/me").single().headers["Authorization"])
  }

  @Test fun aLinkWithAnotherStateIsNeverUsed() = runTest {
    val m = model(Tab { _ -> "dev.kiff.tafs:/oauth?code=c_1&state=another" })
    refused(AppSignInRefused.STATE_MISMATCH) { m.signInInApp("2026-11-14") }
    assertTrue(transport.requestsTo("POST /v1/auth/app/token").isEmpty())
    assertEquals(Mode.signedOut, m.state.value.mode)
    assertNull(secrets.load(AppModel.TOKEN))
  }

  @Test fun closingKiffsPageSignsNobodyIn() = runTest {
    val m = model(Tab { state -> "dev.kiff.tafs:/oauth?error=access_denied&state=$state" })
    refused("access_denied") { m.signInInApp("2026-11-14") }
    assertTrue(transport.requestsTo("POST /v1/auth/app/token").isEmpty())
    assertNull(secrets.load(AppModel.TOKEN))
  }

  private suspend fun refused(error: String, block: suspend () -> Unit) {
    try { block(); fail("no session without this attempt's code") } catch (e: AppSignInRefused) { assertEquals(error, e.error) }
  }

  private companion object {
    fun query(url: String): Map<String, String> =
      URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
  }
}
