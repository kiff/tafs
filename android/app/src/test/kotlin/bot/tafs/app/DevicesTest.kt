package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import java.time.Instant
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

  private suspend fun model(): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":[]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
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
    assertEquals(listOf("Sign Ana's iPhone out of your account"), auth.asked)
    assertNotNull(transport.requestsTo("DELETE /v1/me/devices/dev_2").single())
    assertEquals("read again after", reads + 1, transport.requestsTo("GET /v1/me/devices").size)
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
