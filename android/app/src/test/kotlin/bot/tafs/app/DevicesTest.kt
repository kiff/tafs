package bot.tafs.app

import bot.tafs.app.app.AppModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings, Devices: the devices signed in as this person, and signing another one out behind a confirmation. */
class DevicesTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private val devices = """{"devices":[
    {"id":"dev_1","name":"tafs on Android phone","last_used_at":"2026-10-28T08:00:00Z","this_device":true},
    {"id":"dev_2","name":"Ana's iPhone","last_seen_at":"2026-10-27T08:00:00Z"}]}"""

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDER))
    return Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(Base64.getDecoder().decode(signatureBase64)) }
  }

  private fun signature(request: bot.tafs.app.kiff.HttpRequest): Pair<String, String> =
    request.headers.getValue("KIFF-Device-Signature").split(",").let { it[0].removePrefix("t=") to it[1].removePrefix("v1=") }

  private suspend fun model(approvalKey: Boolean = true): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":[]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    if (approvalKey) secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore(); it.refresh() }
  }

  @Test fun theListShowsEachDeviceAndWhichIsThisOne() = runTest {
    transport.answer("GET /v1/me/devices", 200, devices)
    val m = model()
    m.refreshDevices()
    val list = m.state.value.devices
    assertEquals(listOf("dev_1", "dev_2"), list.map { it.id })
    assertTrue(list[0].thisDevice)
    assertEquals("2026-10-27T08:00:00Z", list[1].lastSeenAt)
  }

  @Test fun anotherDeviceIsSignedOutBehindAConfirmationAndTheListIsReadAgain() = runTest {
    transport.answer("GET /v1/me/devices", 200, devices)
    transport.answer("DELETE /v1/me/devices/dev_2", 204, "")
    val m = model()
    m.refreshDevices()
    val reads = transport.requestsTo("GET /v1/me/devices").size
    assertTrue(m.removeDevice(m.state.value.devices[1]))
    // One fingerprint, the approval key's: removing a device that holds an approval key takes an approval at KIFF.
    assertEquals(listOf("Sign Ana's iPhone out of your account"), auth.asked)
    assertEquals(listOf("Sign Ana's iPhone out of your account"), auth.fingerprints)
    val sent = transport.requestsTo("DELETE /v1/me/devices/dev_2").single()
    val (at, sig) = signature(sent)
    val message = KiffClient.requestMessage("t_01", "DELETE", "/v1/me/devices/dev_2", ByteArray(0), at)
    assertTrue("the approval key signs it", verifies(signer.approvalPublicKeyDER, message, sig))
    assertFalse(verifies(signer.publicKeyDER, message, sig))
    assertEquals("read again after", reads + 1, transport.requestsTo("GET /v1/me/devices").size)
  }

  @Test fun anOpenApprovalWindowNeverCoversSigningADeviceOut() = runTest {
    transport.answer("DELETE /v1/me/devices/dev_2", 204, "")
    val signed = mutableListOf<String>()
    val c = KiffClient(transport, token = "tok").also {
      it.tenant = "t_01"
      it.setupSigner = { m -> signed += "device:" + String(m).lines().first(); "dev" }
      it.approvalSigner = { built -> signed += "approval:" + String(built()).lines().first(); "app" }
      it.window = bot.tafs.app.kiff.ApprovalWindow("win_abcdefghijklmnopqrstuvwxyz", open = true)
    }
    c.removeDevice("dev_2")
    assertEquals(listOf("approval:kiff.request.v1"), signed)
    assertTrue(KiffClient.grantsAuthority("DELETE", "/v1/me/devices/dev_2"))
    assertFalse("the list isn't a device", KiffClient.grantsAuthority("DELETE", "/v1/me/devices/"))
    assertFalse(KiffClient.grantsAuthority("DELETE", "/v1/me/devices/dev_2/extra"))
  }

  @Test fun aSessionWithNoApprovalKeyConfirmsHereAndTheDeviceKeySigns() = runTest {
    transport.answer("GET /v1/me/devices", 200, devices)
    transport.answer("DELETE /v1/me/devices/dev_2", 204, "")
    val m = model(approvalKey = false)
    m.refreshDevices()
    assertTrue(m.removeDevice(m.state.value.devices[1]))
    assertEquals(listOf("Sign Ana's iPhone out of your account"), auth.asked)
    val sent = transport.requestsTo("DELETE /v1/me/devices/dev_2").single()
    val (at, sig) = signature(sent)
    assertTrue("KIFF decides whether it takes it", verifies(signer.publicKeyDER, KiffClient.requestMessage("t_01", "DELETE", "/v1/me/devices/dev_2", ByteArray(0), at), sig))
  }

  @Test fun kiffAskingForTheApprovalKeySaysApprovingNeedsANewSignIn() = runTest {
    transport.answer("GET /v1/me/devices", 200, devices)
    transport.answer("DELETE /v1/me/devices/dev_2", 403, """{"error":"approval_key_required"}""")
    val m = model(approvalKey = false)
    m.refreshDevices()
    assertFalse(m.removeDevice(m.state.value.devices[1]))
    assertEquals(KiffClient.APPROVAL_KEY_LOST, m.state.value.devicesError)
  }

  @Test fun eachDeviceIsCalledWhatKiffNamesItInTheAppsLanguage() = runTest {
    // KIFF's display_name, drawn from the device's kind in the app's language; the name it gave at sign-in (in whatever
    // language the app had then) only from a KIFF that sends none.
    transport.answer("GET /v1/me/devices", 200, """{"devices":[
      {"id":"dev_1","name":"tafs en iPad","display_name":"tafs on iPad","this_device":true},
      {"id":"dev_2","name":"Ana's iPhone"}]}""")
    val m = model()
    m.refreshDevices()
    assertEquals(listOf("tafs on iPad", "Ana's iPhone"), m.state.value.devices.map { it.shown })
  }

  @Test fun thisDeviceIsNeverSignedOutFromTheList() = runTest {
    transport.answer("GET /v1/me/devices", 200, devices)
    val m = model()
    m.refreshDevices()
    assertFalse(m.removeDevice(m.state.value.devices[0]))
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo("DELETE /v1/me/devices/dev_1").isEmpty())
  }

  @Test fun backingOutSignsNothingOut() = runTest {
    transport.answer("GET /v1/me/devices", 200, devices)
    val m = model()
    m.refreshDevices()
    auth.confirms = false
    assertFalse(m.removeDevice(m.state.value.devices[1]))
    assertTrue(transport.requestsTo("DELETE /v1/me/devices/dev_2").isEmpty())
  }

  @Test fun aRefusalSaysWhatDidntGoThrough() = runTest {
    transport.answer("GET /v1/me/devices", 200, devices)
    transport.answer("DELETE /v1/me/devices/dev_2", 403, """{"error":"forbidden"}""")
    val m = model()
    m.refreshDevices()
    assertFalse(m.removeDevice(m.state.value.devices[1]))
    assertTrue(m.state.value.devicesError!!.startsWith("Sign Ana's iPhone out of your account didn't go through"))
  }
}
