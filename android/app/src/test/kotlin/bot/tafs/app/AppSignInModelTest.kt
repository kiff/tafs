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
    var opened = 0
    override suspend fun signIn(url: String): String {
      opened += 1
      asked = url
      return back(query(url).getValue("state"))
    }
  }

  private var now = java.time.Instant.parse("2026-11-20T13:00:00Z")
  private fun model(tab: Tab) = testModel(transport = transport, secrets = secrets, signer = signer, start = false, appSignInTab = tab, clock = { now })

  private fun signedIn() {
    transport.answer("POST /v1/auth/app/token", 200, token)
    transport.answer("GET /v1/me", 200, me)
    transport.answer("POST /v1/me/device/approval-key", 200, """{"fingerprint":"${KiffClient.fingerprint(signer.approvalPublicKeyDER)}"}""")
  }

  private fun tokenBodies() = transport.requestsTo("POST /v1/auth/app/token").map {
    (Json.parseToJsonElement(String(it.body!!)) as JsonObject).mapValues { v -> v.value.jsonPrimitive.content }
  }

  /** KIFF refuses the terms before it spends the code: ticked again within the code's minute, the same code goes with the new version. */
  @Test fun termsThatChangedKeepTheCodeForTheNewTick() = runTest {
    transport.answer("POST /v1/auth/app/token", 409, """{"error":"terms_outdated","terms_version":"2026-11-20"}""")
    val tab = Tab { state -> "dev.kiff.tafs:/oauth?code=c_1&state=$state" }
    val m = model(tab)
    refused(AppModel.TERMS_OUTDATED) { m.signInInApp("2026-11-14") }
    assertNull(secrets.load(AppModel.TOKEN))
    now = now.plusSeconds(30)
    signedIn()
    m.signInInApp("2026-11-20")
    assertEquals("the tab isn't opened twice", 1, tab.opened)
    val (first, second) = tokenBodies()
    assertEquals(first["code"], second["code"])
    assertEquals(first["code_verifier"], second["code_verifier"])
    assertEquals("2026-11-20", second["terms_version"])
    assertEquals("at_1", secrets.load(AppModel.TOKEN))
  }

  /** Past 50 of its 60 seconds the kept code is dropped, and the next Continue opens KIFF's page again with a new attempt. */
  @Test fun aKeptCodePastItsMinuteIsNeverSent() = runTest {
    transport.answer("POST /v1/auth/app/token", 409, """{"error":"terms_outdated","terms_version":"2026-11-20"}""")
    var n = 0
    val tab = Tab { state -> n += 1; "dev.kiff.tafs:/oauth?code=c_$n&state=$state" }
    val m = model(tab)
    refused(AppModel.TERMS_OUTDATED) { m.signInInApp("2026-11-14") }
    now = now.plusSeconds(50)
    signedIn()
    m.signInInApp("2026-11-20")
    assertEquals(2, tab.opened)
    val (first, second) = tokenBodies()
    assertEquals("c_1", first["code"])
    assertEquals("c_2", second["code"])
    assertTrue(first["code_verifier"] != second["code_verifier"])
  }

  /** Any other refusal of the code keeps nothing: the next Continue is a new attempt. */
  @Test fun anotherRefusalKeepsNoCode() = runTest {
    transport.answer("POST /v1/auth/app/token", 400, """{"error":"invalid_grant"}""")
    val tab = Tab { state -> "dev.kiff.tafs:/oauth?code=c_1&state=$state" }
    val m = model(tab)
    try { m.signInInApp("2026-11-14"); fail() } catch (e: bot.tafs.app.kiff.KiffError.Server) { assertEquals("invalid_grant", e.message) }
    signedIn()
    m.signInInApp("2026-11-14")
    assertEquals(2, tab.opened)
  }

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
