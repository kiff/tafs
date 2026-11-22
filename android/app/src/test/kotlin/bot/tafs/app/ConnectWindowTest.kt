package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.ApprovalWindow
import bot.tafs.app.kiff.PlanqueClient
import bot.tafs.app.kiff.TafsCloud
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.Jcs
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.VendorSignIn
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  /** KIFF's client, kept by the test so a window held from an earlier approval can be set up. */
  private val kiff = KiffClient(transport)
  private val grants = "GET /v1/accounts/t_01/grants"
  private val approve = "POST /v1/accounts/t_01/grants/g1/approve"
  private val confirm = "POST /v1/accounts/t_01/grants/g1/confirm"
  private val open = "POST /v1/me/window"
  private val setup = "GET /v1/tafs/taf_1/setup"
  private var request = """{"server":"https://gmail.mcp.example","agent":"mail-1","tools":[{"tool":"read_message","read_only":true}]}"""

  private fun grant(status: String) =
    """{"id":"g1","status":"$status","vendor":"Gmail","text":"Connect Gmail.","digest":"${digestOf(request)}","request":$request}"""

  /** A grant that uses the owner's own paired Chrome, limited to [sites]. */
  private fun ownChrome(sites: String = """["shop.example","paypal.com"]""") {
    request = """{"server":"https://gmail.mcp.example","agent":"mail-1","tools":[{"tool":"read_message","read_only":true}],""" +
      """"browser":{"driver":"extension","chrome":"chr_01HZX5","sites":$sites}}"""
  }

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
    return AppModel(
      null, signer, kiff, TafsCloud(transport), secrets, clock = { t0 }, sampleReplyDelayMs = 0,
      planque = PlanqueClient(transport), vendorSignIn = signIn, openedFiles = java.nio.file.Files.createTempDirectory("opened").toFile(),
    ).also {
      it.restore()
      it.refresh()
      it.loadTafSetup("mail-1")
    }
  }

  private fun signatureOf(route: String, n: Int = 0) = transport.requestsTo(route)[n].headers["Planque-Device-Signature"]!!
  private fun signaturesTo(route: String) = transport.requestsTo(route).map { it.headers["Planque-Device-Signature"]!! }

  /** Connected, as planque and tafs cloud say once the sign-in is confirmed. */
  private fun connectedAfter() {
    transport.answer(grants, 200, """{"grants":[${grant("connected")}]}""")
    transport.answer(setup, 200, "{}")
  }

  /**
   * A grant for the owner's own Chrome is never a window's tap: its one fingerprint signs the grant's sites with the approval key,
   * and the approve body carries that signature as `chrome_sites_sig`, raw r‖s over `tafs-chrome/sites/v1` of `{chrome_id, grant, sites}`.
   */
  @Test fun aGrantForTheOwnersChromeSignsItsSitesUnderTheOneFingerprintAndOpensNoWindow() = runTest {
    ownChrome()
    transport.answer(open, 201, windowOpened())
    transport.answer(confirm, 200, grant("connected"))
    val m = model()
    connectedAfter()
    m.connectAgain("mail-1")
    assertEquals("no window", 0, transport.requestsTo(open).size)
    assertEquals("one fingerprint", 1, auth.fingerprints.size)
    val body = KiffClient.json.parseToJsonElement(String(transport.requestsTo(approve).single().body!!)).jsonObject
    assertEquals(digestOf(request), body["digest"]!!.jsonPrimitive.content)
    val raw = bot.tafs.app.kiff.ChromePairing.base64UrlDecoded(body["chrome_sites_sig"]!!.jsonPrimitive.content)!!
    val signed = bot.tafs.app.kiff.ChromePairing.sitesBytes("chr_01HZX5", "g1", listOf("shop.example", "paypal.com"))
    val approvalKey = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(signer.approvalPublicKeyDER))
    assertTrue(
      "the approval key's signature over the grant's sites",
      java.security.Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(approvalKey); update(signed); verify(raw) },
    )
    // Signed by the device key as an approval outside a window (`v1`), never as a tap.
    assertTrue(signatureOf(approve), signatureOf(approve).contains(",v1=") && !signatureOf(approve).contains(",w="))
  }

  /** The refused case: a grant for the owner's Chrome with no sites is never signed, and nothing is approved. */
  @Test fun aGrantForTheOwnersChromeWithNoSitesIsRefusedAndNothingIsSigned() = runTest {
    ownChrome(sites = "[]")
    transport.answer(open, 201, windowOpened())
    val m = model()
    m.connectAgain("mail-1")
    assertEquals(0, auth.fingerprints.size)
    assertEquals(0, transport.requestsTo(open).size)
    assertEquals(0, transport.requestsTo(approve).size)
  }

  @Test fun approveAndConfirmAreTapsInsideTheWindowTheFingerprintOpened() = runTest {
    transport.answer(open, 201, windowOpened())
    transport.answer(confirm, 200, grant("connected"))
    val m = model()
    // planque now says it's connected; tafs cloud no longer sends Connect again.
    transport.answer(grants, 200, """{"grants":[${grant("connected")}]}""")
    transport.answer(setup, 200, "{}")
    m.connectAgain("mail-1")
    assertEquals("one fingerprint, one window", 1, transport.requestsTo(open).size)
    // The window is opened at KIFF signed by the approval key, behind that one fingerprint; nothing else asked for one.
    assertEquals(1, auth.fingerprints.size)
    assertTrue(transport.requestsTo(open).single().headers["KIFF-Device-Signature"]?.contains(",v1=") == true)
    val inWindow = Regex("^t=[^,]+,v2=[^,]+,w=$window,d=dev_1$")
    assertTrue(signatureOf(approve), inWindow.matches(signatureOf(approve)))
    assertTrue(signatureOf(confirm), inWindow.matches(signatureOf(confirm)))
    assertEquals("Gmail is connected again.", m.state.value.connectedAgain["mail-1"])
    // The connection's window ends with it: closed at KIFF, and the approvals after it ask for their own fingerprint.
    assertEquals(1, transport.requestsTo("POST /v1/me/window/$window/close").size)
    assertEquals(null, kiff.window)
  }

  @Test fun aWindowHeldFromAnEarlierApprovalIsClosedAndTheConnectionsOwnFingerprintOpensAFreshOne() = runTest {
    transport.answer(open, 201, windowOpened())
    transport.answer(confirm, 200, grant("connected"))
    val m = model()
    connectedAfter()
    val earlier = "win_heldfromanearlierapproval"
    kiff.window = ApprovalWindow(earlier, open = true)
    m.connectAgain("mail-1")
    assertEquals("the held window is closed first", 1, transport.requestsTo("POST /v1/me/window/$earlier/close").size)
    assertEquals("the connection asks for its own fingerprint", 1, auth.fingerprints.size)
    assertEquals(1, transport.requestsTo(open).size)
    assertTrue(signatureOf(approve), signatureOf(approve).contains(",w=$window,"))
    assertTrue((signaturesTo(approve) + signaturesTo(confirm)).none { it.contains(earlier) })
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
    // Each in the window this phone opened at KIFF, the one KIFF named in its answer, and none ever as `v1`, the retry included.
    assertTrue(signatureOf(approve), signatureOf(approve).contains(",v2=") && signatureOf(approve).contains(",w=$window,"))
    assertTrue(signatureOf(confirm, 0), signatureOf(confirm, 0).contains(",w=$window,"))
    assertTrue(signatureOf(confirm, 1), signatureOf(confirm, 1).contains(",w=win_zyxwvutsrqponmlkjihgfedcba,"))
    assertTrue((signaturesTo(approve) + signaturesTo(confirm)).none { it.contains(",v1=") })
    assertEquals("Gmail is connected again.", m.state.value.connectedAgain["mail-1"])
  }

  @Test fun anyOtherRefusalToOpenTheWindowStopsTheConnectionWithNothingSentToPlanque() = runTest {
    // Only a KIFF without the route (404, 405) leaves the phone's own fingerprint and `v1`; a 500 is no such KIFF, nor is KIFF's
    // own refusal of an open the approval key didn't sign (403 `approval_key_required`), which must never become a `v1` approval.
    for ((status, body) in listOf(500 to """{"error":"no"}""", 403 to """{"error":"approval_key_required"}""")) {
      transport.answer(open, status, body)
      val m = model()
      m.connectAgain("mail-1")
      assertTrue("$status: nothing approved", transport.requestsTo(approve).isEmpty())
      assertTrue("$status: nothing confirmed", transport.requestsTo(confirm).isEmpty())
      assertEquals(null, m.state.value.connectedAgain["mail-1"])
    }
  }

  @Test fun aRefusedFingerprintForTheNewWindowStopsThereWithNothingSentAgain() = runTest {
    transport.answer(open, 201, windowOpened())
    transport.answer(open, 201, windowOpened("win_zyxwvutsrqponmlkjihgfedcba"))
    transport.answer(confirm, 403, """{"error":"Your approval window has ended.","code":"window_required"}""")
    // The first fingerprint opens the window; the second, for a new one, isn't given.
    auth.whilePrompted = { if (auth.fingerprints.size >= 1) auth.confirms = false }
    val m = model()
    m.connectAgain("mail-1")
    assertEquals(1, transport.requestsTo(open).size)
    assertEquals(1, transport.requestsTo(approve).size)
    assertEquals("the refused confirm isn't sent again", 1, transport.requestsTo(confirm).size)
    assertEquals(null, m.state.value.connectedAgain["mail-1"])
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
