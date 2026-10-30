package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.AccountTime
import bot.tafs.app.model.zone
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account's time zone (docs/accounts.md): every time shown is in it, and this device's is offered once when it differs. */
class TimeZoneTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val device = ZoneId.systemDefault().id
  /** A zone that isn't this device's, whatever machine runs the test. */
  private val other = if (device == "Asia/Tokyo") "Europe/Paris" else "Asia/Tokyo"

  @After fun forgetTheAccountsZone() = AccountTime.use(null)

  private suspend fun model(accountZone: String?): AppModel {
    val tz = accountZone?.let { ""","time_zone":"$it"""" } ?: ""
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme"$tz}""")
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

  @Test fun timesAreInTheAccountsZoneAndUtcWhenKiffNamesNone() = runTest {
    model(other)
    assertEquals(ZoneId.of(other), zone)
    AccountTime.use(null)
    model(null)
    assertEquals(ZoneId.of("UTC"), zone)
  }

  @Test fun signingOutGoesBackToThisDevicesZone() = runTest {
    val m = model(other)
    m.signOut(tellKiff = false)
    assertEquals(ZoneId.systemDefault(), zone)
  }

  @Test fun thisDevicesZoneIsOfferedWhenItDiffers() = runTest {
    assertEquals(device, model(other).state.value.timeZoneOffer)
  }

  @Test fun theSameZoneOffersNothing() = runTest {
    assertNull(model(device).state.value.timeZoneOffer)
  }

  @Test fun notNowIsRememberedForThatZoneAndAccount() = runTest {
    val m = model(other)
    m.answerTimeZoneOffer(use = false)
    assertNull(m.state.value.timeZoneOffer)
    assertTrue(transport.requestsTo("PUT /v1/me/time-zone").isEmpty())
    // Signed in again on the same account: not asked again.
    val again = testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore(); it.refresh() }
    assertNull(again.state.value.timeZoneOffer)
  }

  @Test fun useSetsItSignedBehindOneConfirmationAndReadsTheAccountAgain() = runTest {
    transport.answer("PUT /v1/me/time-zone", 200, """{"time_zone":"$device"}""")
    val m = model(other)
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","time_zone":"$device"}""")
    m.answerTimeZoneOffer(use = true)
    assertEquals(listOf("Set your account's time zone to $device"), auth.asked)
    val put = transport.requestsTo("PUT /v1/me/time-zone").single()
    assertEquals("""{"time_zone":"$device"}""", String(put.body!!))
    assertNotNull(put.headers["KIFF-Device-Signature"])
    assertEquals(device, m.state.value.kiffAccount?.timeZone)
    assertEquals(ZoneId.of(device), zone)
  }
}
