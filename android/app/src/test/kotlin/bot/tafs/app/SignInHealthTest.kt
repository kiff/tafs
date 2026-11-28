@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.ConnectApps
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.signing.ApprovalSigning
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cloud browser sign-in that needs the owner (docs/api.md, "Sign-in health" and "Needs you, and the run that waits"): Home lists
 * each in tafs cloud's words, Later and Skip this run are requests tafs cloud answers, and Connect apps reads each saved sign-in's
 * health. The app works out nothing: not the state, not "soon", not whether a run waits. A fake KIFF and tafs cloud stand in.
 */
class SignInHealthTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val t0 = Instant.parse("2026-11-28T06:30:00Z")
  private val signer: ApprovalSigning = softwareSigner(auth) { t0 }
  private val later = "POST /v1/needs-you/sign-in/g_7/supplier-portal.example/later"
  private val skip = "POST /v1/tafs/taf_1/held-sign-in/skip"

  private val soon = """{"kind":"sign_in_soon","grant":"g_7","site":"supplier-portal.example","needs_you_by":"2026-12-02T09:12:00Z",
      "taf":{"id":"taf_1","name":"File invoices"},"words":{"title":"supplier-portal.example asks you to sign in again by Tue 2 Dec",
      "line":"File invoices runs there every day at 8:00. Until then it works as now.","sign_in":"Sign in again","later":"Later",
      "why":"tafs opened one page with your saved sign-in this morning. The site set your saved sign-in to end on 2 Dec."}}"""
  private val stopped = """{"kind":"sign_in_stopped","grant":"g_8","site":"tax-office.example","checked_at":"2026-11-28T06:55:00Z",
      "run_at":"2026-11-28T07:00:00Z","taf":{"id":"taf_2","name":"VAT return"},"words":{"title":"tax-office.example asked to sign in again",
      "line":"VAT return's 8:00 run waits for you. It won't run with a broken sign-in, and it's skipped only if you skip it.",
      "sign_in":"Sign in again","skip":"Skip this run","why":"tafs opened one page with your saved sign-in at 7:55."}}"""

  private fun reads(signIns: String = "[$soon]") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[],"sign_ins":$signIns}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{}}""")
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

  @Test fun homeListsBothKindsInTafsCloudsWordsAndLeavesOutOneItCantDraw() = runTest {
    // An unknown kind, a soon item with no Later, and an unreadable one are left out: the app has no words of its own for them.
    reads("""[$soon,$stopped,{"kind":"sign_in_new","grant":"g_9","words":{"title":"x","sign_in":"y"}},
      {"kind":"sign_in_soon","grant":"g_9","words":{"title":"x","sign_in":"y"}},{"bad":true}]""")
    val items = model().state.value.signIns
    assertEquals(listOf("g_7", "g_8"), items.map { it.grant })
    assertFalse(items[0].stopped)
    assertTrue(items[1].stopped)
    assertEquals("Skip this run", items[1].words.skip)
  }

  @Test fun eachCountsInNeedsYouWhenTafsCloudSendsNoCount() = runTest {
    reads("[$soon,$stopped]")
    assertEquals(2, model().state.value.needsYouCount)
  }

  @Test fun laterIsARequestAndTheListIsReadAgain() = runTest {
    reads()
    transport.answer(later, 200, """{"ok":true}""")
    val m = model()
    val homeReads = transport.requestsTo("GET /v1/home").size
    assertTrue(m.signInLater(m.state.value.signIns.single()))
    assertEquals(1, transport.requestsTo(later).size)
    assertEquals(homeReads + 1, transport.requestsTo("GET /v1/home").size)
  }

  @Test fun skipThisRunIsARequestOnTheTafsHeldRun() = runTest {
    reads("[$stopped]")
    transport.answer("POST /v1/tafs/taf_2/held-sign-in/skip", 200, """{"ok":true}""")
    val m = model()
    assertTrue(m.skipHeldRun(m.state.value.signIns.single()))
    assertEquals(1, transport.requestsTo("POST /v1/tafs/taf_2/held-sign-in/skip").size)
    assertTrue(transport.requestsTo(skip).isEmpty())
  }

  @Test fun aRefusalSaysWhyAndNothingIsGuessed() = runTest {
    reads()
    transport.answer(later, 409, """{"error":"This sign-in doesn't need you any more."}""")
    val m = model()
    assertFalse(m.signInLater(m.state.value.signIns.single()))
    assertTrue(!m.state.value.lastError.isNullOrBlank())
    assertEquals(1, m.state.value.signIns.size)
  }

  @Test fun connectAppsReadsEachSavedSignInsHealthInTafsCloudsWords() {
    val read = KiffClient.json.decodeFromString<ConnectApps>(
      """{"apps":[],"saved_sign_ins":[
        {"grant":"g_7","site":"supplier-portal.example","used_by":["File invoices"],"health":{"state":"needs_you_soon",
          "needs_you_by":"2026-12-02T09:12:00Z","words":{"line":"Needs you by Tue 2 Dec · used by File invoices","why":"…"}},
          "sign_in":{"title":"Sign in again"}},
        {"grant":"g_9","site":"books.example","used_by":["Month end"],"health":{"state":"signed_in","words":{"line":"Signed in · checked today 07:00"}}},
        {"grant":"g_10","site":"x.example","health":{"state":"unknown","words":{"line":""}}}]}""",
    )
    val saved = read.savedSignIns
    assertEquals(listOf("g_7", "g_9"), saved.map { it.grant })
    assertTrue(saved[0].health.needsYou)
    assertFalse(saved[1].health.needsYou)
    assertEquals("Sign in again", saved[0].signIn?.title)
  }
}
