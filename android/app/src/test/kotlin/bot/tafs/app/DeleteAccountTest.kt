package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deleting the account (kiff/tafs#185): tafs cloud, then planque, then KIFF last, behind one confirmation. Fakes stand in for all three. */
class DeleteAccountTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private val cloud = "POST /v1/account/delete"
  private val planque = "DELETE /v1/accounts/t_01"
  private val kiff = "POST /v1/me/delete"

  private fun reads(capabilities: String = """"holds.answer","cards.manage","account.delete"""") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":[$capabilities]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore(); it.refresh() }
  }

  private fun order() = transport.requests.map { it.method + " " + URI(it.url).path }

  @Test fun onlySomeoneWhoseRoleMayDeleteTheAccountIsOfferedIt() = runTest {
    reads(capabilities = """"holds.answer"""")
    assertFalse(model().state.value.canDeleteAccount)
  }

  @Test fun itErasesTafsCloudThenPlanqueThenKiffLastBehindOneConfirmationAndSignsOut() = runTest {
    reads()
    transport.answer(cloud, 200, """{"done":true}""")
    transport.answer(planque, 200)
    transport.answer(kiff, 200)
    val m = model()
    assertTrue(m.state.value.canDeleteAccount)
    assertTrue(m.deleteAccount())
    assertEquals(listOf("Delete your account"), auth.asked)
    val o = order()
    assertTrue(o.indexOf(cloud) < o.indexOf(planque))
    assertTrue("KIFF last: it ends this session", o.indexOf(planque) < o.indexOf(kiff))
    assertNotNull("planque takes it only signed", transport.requestsTo(planque).single().headers["Planque-Device-Signature"])
    assertNotNull("KIFF takes it only signed", transport.requestsTo(kiff).single().headers["KIFF-Device-Signature"])
    assertEquals(Mode.signedOut, m.state.value.mode)
  }

  @Test fun notConfirmedDeletesNothing() = runTest {
    reads()
    val m = model()
    auth.confirms = false
    assertFalse(m.deleteAccount())
    assertTrue(transport.requestsTo(cloud).isEmpty())
    assertNull(m.state.value.deleteAccountError)
    assertEquals(Mode.kiff, m.state.value.mode)
  }

  @Test fun aStepThatFailsStopsTheRestAndSaysWhich() = runTest {
    reads()
    transport.answer(cloud, 200, """{"done":false,"failed":"agents"}""")
    val m = model()
    assertFalse(m.deleteAccount())
    assertEquals("tafs couldn't erase everything yet. Your KIFF account isn't deleted. Try again.", m.state.value.deleteAccountError)
    assertTrue(transport.requestsTo(planque).isEmpty())
    assertTrue(transport.requestsTo(kiff).isEmpty())
    assertEquals(Mode.kiff, m.state.value.mode)
  }

  @Test fun askingAgainGoesOnFromPlanqueWhenItFailed() = runTest {
    reads()
    transport.answer(cloud, 200, """{"done":true}""")
    transport.answer(planque, 503, """{"error":"unavailable"}""")
    transport.answer(planque, 200)
    transport.answer(kiff, 200)
    val m = model()
    assertFalse(m.deleteAccount())
    assertTrue(m.state.value.deleteAccountError!!.startsWith("Your tafs are erased, but your connections aren't yet"))
    assertTrue(transport.requestsTo(kiff).isEmpty())
    // Every step is safe to repeat: the second time goes all the way.
    assertTrue(m.deleteAccount())
    assertEquals(Mode.signedOut, m.state.value.mode)
  }

  @Test fun tafsCloudRefusingSaysOnlyTheOwnerCan() = runTest {
    reads()
    transport.answer(cloud, 403, """{"error":"forbidden"}""")
    val m = model()
    assertFalse(m.deleteAccount())
    assertEquals("Only the account's owner can delete it.", m.state.value.deleteAccountError)
  }

  @Test fun kiffFailingLastLeavesThePersonSignedInToTryAgain() = runTest {
    reads()
    transport.answer(cloud, 200, """{"done":true}""")
    transport.answer(planque, 200)
    transport.answer(kiff, 500, """{"error":"internal"}""")
    val m = model()
    assertFalse(m.deleteAccount())
    assertTrue(m.state.value.deleteAccountError!!.startsWith("Your tafs and connections are erased, but KIFF didn't delete the account"))
    assertEquals(Mode.kiff, m.state.value.mode)
  }
}
