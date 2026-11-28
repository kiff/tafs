@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.signing.ApprovalSigning
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Your turn" (docs/approvals.md): a step a taf handed its owner in planque's hosted browser, listed by Home in tafs cloud's words.
 * Open asks planque for its live view, signed by this device, fresh each time, and opens it only on planque's own address; Not now
 * is signed too. A step that no longer waits (410) leaves the list. A fake KIFF, tafs cloud and planque stand in.
 */
class YourTurnTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val t0 = Instant.parse("2026-11-28T06:30:00Z")
  private val signer: ApprovalSigning = softwareSigner(auth) { t0 }
  private val view = "POST /v1/accounts/t_01/grants/g_7/hands/hh_1/view"
  private val decline = "POST /v1/accounts/t_01/grants/g_7/hands/hh_1/decline"

  private val turn = """{"grant":"g_7","hand":"hh_1","site":"shop.example","since":"2026-11-28T06:20:00Z","expires":"2026-11-28T06:40:00Z",
      "taf":{"id":"taf_1","name":"Refunds"},"words":{"title":"Your turn on shop.example","ask":"“Accept the cookies, please”",
      "until":"Open until 07:40","open":"Open","not_now":"Not now"}}"""

  private fun reads(turns: String = "[$turn]") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[],"your_turns":$turns}""")
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
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also {
      it.restore()
      it.refresh()
    }
  }

  @Test fun homeListsTheTurnInTafsCloudsWordsAndOneWithoutWordsIsLeftOut() = runTest {
    reads("""[$turn,{"grant":"g_8","hand":"hh_2","words":{"title":""}},{"bad":true}]""")
    val turns = model().state.value.yourTurns
    assertEquals(listOf("hh_1"), turns.map { it.hand })
    assertEquals("Your turn on shop.example", turns.single().words.title)
    assertEquals("Not now", turns.single().words.notNow)
  }

  @Test fun openAsksForTheViewSignedAndOpensItOnPlanqueOnly() = runTest {
    reads()
    transport.answer(view, 200, """{"view":"https://planque.kiff.dev/browser/view/tok_1","expires":"2026-11-28T06:40:00Z","site":"shop.example","words":"“Accept the cookies, please”"}""")
    val m = model()
    m.openYourTurn(m.state.value.yourTurns.single())
    val sent = transport.requestsTo(view).single()
    assertTrue("device-signed", sent.headers.getValue("Planque-Device-Signature").startsWith("t="))
    assertEquals("https://planque.kiff.dev/browser/view/tok_1", m.state.value.siteSignIn?.view)
    assertNull(m.state.value.answeringTurn)
    // Each Open asks again: a view URL is single-use.
    m.closeSiteSignIn()
    m.openYourTurn(m.state.value.yourTurns.single())
    assertEquals(2, transport.requestsTo(view).size)
  }

  @Test fun aViewOffPlanquesOwnAddressIsNeverOpened() = runTest {
    reads()
    transport.answer(view, 200, """{"view":"https://elsewhere.example/browser/view/tok_1"}""")
    val m = model()
    m.openYourTurn(m.state.value.yourTurns.single())
    assertNull(m.state.value.siteSignIn)
  }

  @Test fun notNowIsSignedAndTheRowGoes() = runTest {
    reads()
    transport.answer(decline, 200, """{"ok":true}""")
    val m = model()
    m.declineYourTurn(m.state.value.yourTurns.single())
    assertTrue(transport.requestsTo(decline).single().headers.containsKey("Planque-Device-Signature"))
    assertTrue(m.state.value.yourTurns.isEmpty())
  }

  @Test fun aStepThatNoLongerWaitsLeavesTheList() = runTest {
    reads()
    transport.answer(view, 410, """{"error":"this step is no longer waiting for you"}""")
    val m = model()
    m.openYourTurn(m.state.value.yourTurns.single())
    assertNull(m.state.value.siteSignIn)
    assertTrue(m.state.value.yourTurns.isEmpty())
  }
}
