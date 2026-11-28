package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.TafSetup
import bot.tafs.app.kiff.VendorSignIn
import bot.tafs.app.kiff.VendorSignInCancelled
import bot.tafs.app.kiff.Jcs
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A taf's own Connect again (docs/api.md, `connect_again`): tafs cloud names the connection whose sign-in expired, in its words, on
 * the taf's Setup page. The tap signs that same connection in again (planque's approve), and once planque says it's connected,
 * tafs cloud's `done_words` show on the taf. The phone decides nothing about whether it's needed.
 */
class ConnectAgainTest {
  private val t0 = Instant.parse("2026-11-14T01:00:00Z")
  private fun digestOf(json: String) = MessageDigest.getInstance("SHA-256").digest(Jcs.canonical(json).toByteArray()).joinToString("") { "%02x".format(it) }
  private class FakeSignIn(var back: String? = null) : VendorSignIn {
    override suspend fun signIn(url: String): String = back ?: throw VendorSignInCancelled()
  }
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val signer = softwareSigner()
  private val grants = "GET /v1/accounts/t_01/grants"
  private val approve = "POST /v1/accounts/t_01/grants/g1/approve"
  private val confirm = "POST /v1/accounts/t_01/grants/g1/confirm"
  private val setup = "GET /v1/tafs/taf_1/setup"
  private val request = """{"server":"https://gmail.mcp.example","agent":"mail-1","tools":[{"tool":"read_message","read_only":true}]}"""

  private fun grant(status: String) =
    """{"id":"g1","status":"$status","vendor":"Gmail","text":"Connect Gmail.","digest":"${digestOf(request)}","request":$request}"""

  private val connectAgain =
    """"connect_again":{"grant":"g1","app":"Gmail","title":"Connect again","words":"Gmail's sign-in has expired, so Mail can't use it. Connect again to sign in.","done_words":"Gmail is connected again. Mail can use it."}"""

  private fun reads(setupBody: String) {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["holds.answer","members.manage"]}""")
    transport.answer(
      "GET /v1/home", 200,
      """{"tafs":[{"id":"taf_1","name":"Mail","kiff_agent":"mail-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""",
    )
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"mail-1":{"use":true,"oversee":true,"manage":true}}}""")
    transport.answer("GET /v1/me/devices", 200, """{"devices":[{"id":"dev_1","name":"tafs on Android phone","this_device":true}]}""")
    transport.answer(setup, 200, setupBody)
  }

  private suspend fun model(signIn: VendorSignIn = FakeSignIn(back = "tafs://planque/signed-in?tenant=t_01&grant=g1&confirm=c0de")): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false, vendorSignIn = signIn).also { it.restore() }
  }

  @Test fun theSetupPageReadsConnectAgainLenientlyAndOnlyWithAConnectionAndAButton() {
    fun read(body: String) = KiffClient.json.decodeFromString<TafSetup>(body).connectAgain
    assertEquals("g1", read("{$connectAgain}")?.grant)
    assertEquals("Gmail is connected again. Mail can use it.", read("{$connectAgain}")?.doneWords)
    assertNull("none sent", read("{}"))
    assertNull("no connection named", read("""{"connect_again":{"grant":"","title":"Connect again"}}"""))
    assertNull("a shape this build doesn't know", read("""{"connect_again":"g1"}"""))
  }

  @Test fun connectAgainSignsThatSameConnectionInAgainAndSaysTafsCloudsWordsOnceConnected() = runTest {
    reads("{$connectAgain}")
    transport.answer(grants, 200, """{"grants":[${grant("needs_reconnect")}]}""")
    transport.answer(approve, 200, """{"status":"approved","sign_in_url":"https://accounts.example/oauth?x=1"}""")
    transport.answer(confirm, 200, grant("connected"))
    val m = model()
    m.refresh()
    m.loadTafSetup("mail-1")
    assertEquals("g1", m.state.value.tafSetups["mail-1"]?.connectAgain?.grant)
    // planque now says it's connected; tafs cloud no longer sends Connect again.
    transport.answer(grants, 200, """{"grants":[${grant("connected")}]}""")
    transport.answer(setup, 200, "{}")
    m.connectAgain("mail-1")
    assertEquals("the same connection, signed in again on itself", 1, transport.requestsTo(approve).size)
    assertEquals(1, transport.requestsTo(confirm).size)
    assertEquals("Gmail is connected again. Mail can use it.", m.state.value.connectedAgain["mail-1"])
    assertNull(m.state.value.tafSetups["mail-1"]?.connectAgain)
    assertNull(m.state.value.coachNotes["g1"])
  }

  /**
   * Cancel before the vendor's page opens: the approval still on its way (a Home press mid-approval in the Qonto re-test left the
   * card on "Opening the sign-in…", and Cancel did nothing, since it only ended a page's wait). The attempt ends, the card is free
   * to start again, and nothing else is asked of planque.
   */
  @Test fun cancelEndsASignInStillWaitingOnItsApproval() = kotlinx.coroutines.test.runTest {
    reads("{$connectAgain}")
    transport.answer(grants, 200, """{"grants":[${grant("needs_reconnect")}]}""")
    transport.hold(approve)
    val m = model()
    m.refresh()
    m.loadTafSetup("mail-1")
    val attempt = launch { m.connectAgain("mail-1") }
    testScheduler.runCurrent()
    assertEquals("on its way", "g1", m.state.value.connecting)
    m.cancelConnecting()
    testScheduler.runCurrent()
    assertTrue("the attempt ended", attempt.isCompleted)
    assertNull("the card is free to start again", m.state.value.connecting)
    assertEquals("nothing else asked of planque", 0, transport.requestsTo(confirm).size)
  }

  // planque can't sign it in again on its old account (`reconnect: fresh`): a new sign-in brings back every connection signed out
  // on it. The tap goes to connect-fresh straight away, never to an approve planque would refuse first; confirm is as before.
  @Test fun aFreshConnectAgainStartsANewSignInNeverAReconnectFirst() = runTest {
    val fresh = """"connect_again":{"grant":"g1","app":"Gmail","title":"Connect again","fresh":true,"words":"Gmail no longer takes the old sign-in, so Mail can't use it. Connect again with a new sign-in: one sign-in brings back every taf that used it.","done_words":"Gmail is connected again on the new sign-in. Every taf that used it can use it again."}"""
    reads("{$fresh}")
    val connectFresh = "POST /v1/accounts/t_01/grants/g1/connect-fresh"
    transport.answer(grants, 200, """{"grants":[${grant("needs_reconnect")}]}""")
    transport.answer(connectFresh, 200, """{"status":"approved","sign_in_url":"https://accounts.example/oauth?x=1"}""")
    transport.answer(confirm, 200, grant("connected").dropLast(1) + ""","grants":["g1","g2","g3"]}""")
    val m = model()
    m.refresh()
    m.loadTafSetup("mail-1")
    assertTrue(m.state.value.tafSetups["mail-1"]?.connectAgain?.fresh == true)
    transport.answer(grants, 200, """{"grants":[${grant("connected")}]}""")
    // Read again after it, tafs cloud names the account it went to and the tafs on it.
    val named = "Gmail is connected again as ana@example.com. These tafs use it now: Mail and Win-back. If that isn't the right account, connect again and sign in to the right one."
    transport.answer(setup, 200, """{"connected_again":{"grant":"g1","words":"$named"}}""")
    m.connectAgain("mail-1")
    assertEquals(1, transport.requestsTo(connectFresh).size)
    assertTrue("never a reconnect first", transport.requestsTo(approve).isEmpty())
    assertEquals(1, transport.requestsTo(confirm).size)
    assertEquals("connected_again=g1", java.net.URI.create(transport.requestsTo(setup).last().url).query)
    assertEquals(named, m.state.value.connectedAgain["mail-1"])
  }

  // Without tafs cloud's named words (no account name from planque), the fresh done words stay.
  @Test fun aFreshSignInWithoutTheAccountsNameKeepsTheDoneWords() = runTest {
    val fresh = """"connect_again":{"grant":"g1","app":"Gmail","title":"Connect again","fresh":true,"words":"w","done_words":"Gmail is connected again on the new sign-in. Every taf that used it can use it again."}"""
    reads("{$fresh}")
    transport.answer(grants, 200, """{"grants":[${grant("needs_reconnect")}]}""")
    transport.answer("POST /v1/accounts/t_01/grants/g1/connect-fresh", 200, """{"status":"approved","sign_in_url":"https://accounts.example/oauth?x=1"}""")
    transport.answer(confirm, 200, grant("connected"))
    val m = model()
    m.refresh()
    m.loadTafSetup("mail-1")
    transport.answer(grants, 200, """{"grants":[${grant("connected")}]}""")
    transport.answer(setup, 200, """{"connected_again":7}""")
    m.connectAgain("mail-1")
    assertEquals("Gmail is connected again on the new sign-in. Every taf that used it can use it again.", m.state.value.connectedAgain["mail-1"])
  }

  // planque's own grant says fresh, whatever tafs cloud sent (Connect apps' Sign in again reads only the grant).
  @Test fun aGrantPlanqueMarksFreshIsSignedInAfreshFromItsOwnMark() = runTest {
    reads("{$connectAgain}")
    val connectFresh = "POST /v1/accounts/t_01/grants/g1/connect-fresh"
    transport.answer(grants, 200, """{"grants":[${grant("needs_reconnect").dropLast(1) + ""","reconnect":"fresh"}"""}]}""")
    transport.answer(connectFresh, 200, """{"status":"approved","sign_in_url":"https://accounts.example/oauth?x=1"}""")
    transport.answer(confirm, 200, grant("connected"))
    val m = model()
    m.refresh()
    m.loadTafSetup("mail-1")
    m.connectAgain("mail-1")
    assertEquals(1, transport.requestsTo(connectFresh).size)
    assertTrue(transport.requestsTo(approve).isEmpty())
  }

  @Test fun aSignInThatDidntConnectSaysNothingDoneAndKeepsConnectAgain() = runTest {
    reads("{$connectAgain}")
    transport.answer(grants, 200, """{"grants":[${grant("needs_reconnect")}]}""")
    transport.answer(approve, 200, """{"status":"approved","sign_in_url":"https://accounts.example/oauth?x=1"}""")
    val m = model(FakeSignIn(back = null))
    m.refresh()
    m.loadTafSetup("mail-1")
    m.connectAgain("mail-1")
    assertTrue(transport.requestsTo(confirm).isEmpty())
    assertNull(m.state.value.connectedAgain["mail-1"])
    assertEquals("g1", m.state.value.tafSetups["mail-1"]?.connectAgain?.grant)
  }
}
