package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.Jcs
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.VendorSignIn
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A connection approved outside Coach's step (a taf's Connect again, an approval card's Connect, Connect apps) goes through
 * KIFF's approval window (kiff/tafs#494), as Coach's step does: one fingerprint opens it, then planque's approve and confirm are
 * taps signed by the device key with the window's id (`v2`). planque, asking KIFF, decides whether the window is open; when it
 * says the window ended, a new one is opened and only that call is made again. A KIFF with no windows signs as before (`v1`).
 */
class ConnectWindowTest {
  private val t0 = Instant.parse("2026-11-16T21:00:00Z")
  private val window = "win_abcdefghijklmnopqrstuvwxyz"
  private fun digestOf(json: String) = MessageDigest.getInstance("SHA-256").digest(Jcs.canonical(json).toByteArray()).joinToString("") { "%02x".format(it) }
  private class FakeSignIn(val back: String) : VendorSignIn {
    override suspend fun signIn(url: String): String = back
  }
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val signer = softwareSigner()
  private val grants = "GET /v1/accounts/t_01/grants"
  private val approve = "POST /v1/accounts/t_01/grants/g1/approve"
  private val confirm = "POST /v1/accounts/t_01/grants/g1/confirm"
  private val open = "POST /v1/me/window"
  private val setup = "GET /v1/tafs/taf_1/setup"
  private val request = """{"server":"https://gmail.mcp.example","agent":"mail-1","tools":[{"tool":"read_message","read_only":true}]}"""

  private fun grant(status: String) =
    """{"id":"g1","status":"$status","vendor":"Gmail","text":"Connect Gmail.","digest":"${digestOf(request)}","request":$request}"""

  private val connectAgain =
    """{"connect_again":{"grant":"g1","app":"Gmail","title":"Connect again","words":"Gmail's sign-in has expired.","done_words":"Gmail is connected again."}}"""

  private fun windowOpened(id: String = window) =
    """{"window":{"id":"$id","expires":"2026-11-16T21:05:00Z","open":true,"words":"No fingerprint for the next 4 minutes"}}"""

  private suspend fun model(): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["holds.answer","members.manage"]}""")
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"taf_1","name":"Mail","kiff_agent":"mail-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""",
    )
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"mail-1":{"use":true,"oversee":true,"manage":true}}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
    transport.answer(setup, 200, connectAgain)
    transport.answer(grants, 200, """{"grants":[${grant("needs_reconnect")}]}""")
    transport.answer(approve, 200, """{"status":"approved","sign_in_url":"https://accounts.example/oauth?x=1"}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    val signIn = FakeSignIn("tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de")
    return testModel(transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false, vendorSignIn = signIn).also {
      it.restore()
      it.refresh()
      it.loadTafSetup("mail-1")
    }
  }

  private fun signatureOf(route: String, n: Int = 0) = transport.requestsTo(route)[n].headers["Planque-Device-Signature"]!!

  @Test fun approveAndConfirmAreTapsInsideTheWindowTheFingerprintOpened() = runTest {
    transport.answer(open, 201, windowOpened())
    transport.answer(confirm, 200, grant("connected"))
    val m = model()
    // planque now says it's connected; tafs cloud no longer sends Connect again.
    transport.answer(grants, 200, """{"grants":[${grant("connected")}]}""")
    transport.answer(setup, 200, "{}")
    m.connectAgain("mail-1")
    assertEquals("one fingerprint, one window", 1, transport.requestsTo(open).size)
    val inWindow = Regex("^t=[^,]+,v2=[^,]+,w=$window,d=dev_1$")
    assertTrue(signatureOf(approve), inWindow.matches(signatureOf(approve)))
    assertTrue(signatureOf(confirm), inWindow.matches(signatureOf(confirm)))
    assertEquals("Gmail is connected again.", m.state.value.connectedAgain["mail-1"])
  }

  @Test fun aWindowThatEndedDuringTheVendorsSignInIsOpenedAgainAndOnlyTheConfirmIsMadeAgain() = runTest {
    transport.answer(open, 201, windowOpened())
    transport.answer(open, 201, windowOpened("win_zyxwvutsrqponmlkjihgfedcba"))
    transport.answer(confirm, 403, """{"error":"Your approval window has ended.","code":"window_required"}""")
    transport.answer(confirm, 200, grant("connected"))
    val m = model()
    // planque now says it's connected; tafs cloud no longer sends Connect again.
    transport.answer(grants, 200, """{"grants":[${grant("connected")}]}""")
    transport.answer(setup, 200, "{}")
    m.connectAgain("mail-1")
    assertEquals("a second fingerprint, for a new window", 2, transport.requestsTo(open).size)
    assertEquals("the approval isn't made again", 1, transport.requestsTo(approve).size)
    assertEquals(2, transport.requestsTo(confirm).size)
    assertTrue(signatureOf(confirm, 1), signatureOf(confirm, 1).contains(",w=win_zyxwvutsrqponmlkjihgfedcba,"))
    assertEquals("Gmail is connected again.", m.state.value.connectedAgain["mail-1"])
  }

  @Test fun aKiffWithNoWindowsSignsAsBeforeWithTheDeviceKeyAlone() = runTest {
    // Nothing answers POST /v1/me/window: a KIFF from before windows (404). The phone's own fingerprint, then `v1`.
    transport.answer(confirm, 200, grant("connected"))
    val m = model()
    // planque now says it's connected; tafs cloud no longer sends Connect again.
    transport.answer(grants, 200, """{"grants":[${grant("connected")}]}""")
    transport.answer(setup, 200, "{}")
    m.connectAgain("mail-1")
    assertEquals(1, transport.requestsTo(open).size)
    assertTrue(signatureOf(approve), Regex("^t=[^,]+,v1=[^,]+,d=dev_1$").matches(signatureOf(approve)))
    assertTrue(signatureOf(confirm), Regex("^t=[^,]+,v1=[^,]+,d=dev_1$").matches(signatureOf(confirm)))
    assertEquals("Gmail is connected again.", m.state.value.connectedAgain["mail-1"])
  }
}
