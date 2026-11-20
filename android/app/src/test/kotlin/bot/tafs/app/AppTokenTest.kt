package bot.tafs.app

import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffError
import bot.tafs.app.signing.SoftwareKeys
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Row 39's token step (`POST /v1/auth/app/token`): the code, its verifier and the terms, with the device as today's sign-in sends it. */
class AppTokenTest {
  private val transport = FakeTransport()
  private val client = KiffClient(transport, token = null)

  @Test fun theCodeIsTradedWithItsVerifierAndTheDevicesKeys() = runTest {
    transport.answer("POST /v1/auth/app/token", 200, """{"access_token":"tok_1","token_type":"Bearer","expires_in":3600,"tenant":"t_1","subject":"u_1","approval_mode":"device"}""")
    val keys = SoftwareKeys()
    val token = client.appToken("c_1", "v".repeat(43), "2026-11", keys.publicKeyDER, keys.approvalPublicKeyDER, "tafs on Android phone", "android", "android_phone")
    assertEquals("tok_1", token.accessToken)
    assertEquals("t_1", token.tenant)
    val request = transport.requests.single()
    assertEquals("https://api.kiff.dev/v1/auth/app/token", request.url)
    assertNull(request.headers["Authorization"])
    val body = Json.parseToJsonElement(String(request.body!!)) as JsonObject
    val b64 = Base64.getEncoder()
    assertEquals(
      mapOf(
        "code" to "c_1", "code_verifier" to "v".repeat(43), "redirect_uri" to "dev.kiff.tafs:/oauth", "client_id" to "tafs-app",
        "terms_version" to "2026-11", "public_key" to b64.encodeToString(keys.publicKeyDER),
        "approval_public_key" to b64.encodeToString(keys.approvalPublicKeyDER), "device_name" to "tafs on Android phone",
        "platform" to "android", "kind" to "android_phone",
      ),
      body.mapValues { it.value.jsonPrimitive.content },
    )
  }

  @Test fun kiffsRefusalKeepsItsCode() = runTest {
    transport.answer("POST /v1/auth/app/token", 409, """{"error":"terms_outdated","code":"terms_outdated"}""")
    try {
      client.appToken("c_1", "v".repeat(43), "2025-01")
      fail("a refusal answers no session")
    } catch (e: KiffError.Server) {
      assertEquals(409, e.status)
      assertEquals("terms_outdated", e.code)
    }
  }
}
