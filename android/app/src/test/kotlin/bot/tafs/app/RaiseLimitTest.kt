package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Raise the limit (docs/accounts.md#the-balance): at the spend limit, someone who manages billing raises it one step from Plan &
 * usage, confirmed then signed by the device key with the limit it raises from, as KIFF takes it; anyone else reads tafs cloud's
 * words. A fake KIFF and tafs cloud stand in.
 */
class RaiseLimitTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-11-07T09:00:00Z")

  private val waiting =
    """{"balance":{"state":"waiting","why":"limit_reached","meter_pct":100,"words":"Company · 4 seats · the spend limit is used",
       "detail":"All the included credits are used.","warning":"Your tafs are waiting: the spend limit is reached. Raise it to let them go on.",
       "raise":{"words":"Raise the spend limit by one step","from_cents":8000},"buy":null},
       "plan_line":"Company · waiting: the spend limit is reached","plan_tone":"waiting","home_line":"Your tafs are waiting: the spend limit is reached"}"""
  private val raised =
    """{"balance":{"state":"warning","why":null,"meter_pct":62,"words":"Company · 4 seats · 62% of the spend limit used","detail":null,
       "warning":"62% of the spend limit is used.","raise":{"words":"Raise the spend limit by one step","from_cents":13000},"buy":null},
       "plan_line":"Company · 62% of the limit used","plan_tone":"warning","home_line":null}"""
  private val notMine =
    """{"balance":{"state":"waiting","why":"limit_reached","meter_pct":100,"words":"Company · the spend limit is used",
       "warning":"Your tafs are waiting: the spend limit is reached. An admin of this account can raise it.","raise":null,"buy":null},
       "plan_line":"Company · waiting: the spend limit is reached","plan_tone":"waiting","home_line":"Your tafs are waiting"}"""

  private suspend fun model(balance: String): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/usage", 200, """{"plan":"team","period":{"start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"}}""")
    transport.answer("GET /v1/usage/balance", 200, balance)
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also {
      it.restore()
      it.refreshUsage()
    }
  }

  private fun verifies(publicDER: ByteArray, message: ByteArray, signatureBase64: String): Boolean {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDER))
    return Signature.getInstance("SHA256withECDSA").run {
      initVerify(key)
      update(message)
      verify(Base64.getDecoder().decode(signatureBase64))
    }
  }

  @Test fun theRaiseIsConfirmedOnceThenKiffsPutSignedByTheDeviceKeyFromTheLimitReadThenTheBalanceReadAgain() = runTest {
    val m = model(waiting)
    transport.answer("PUT /v1/me/usage/limit", 200, """{"limit_cents":13000}""")
    transport.answer("GET /v1/usage/balance", 200, raised)
    assertTrue(m.raiseLimit())
    assertEquals(listOf("Raise the spend limit"), auth.asked)
    val put = transport.requestsTo("PUT /v1/me/usage/limit").single()
    assertEquals("https://api.kiff.dev/v1/me/usage/limit", put.url)
    assertEquals("""{"raise":true,"from_cents":8000}""", String(put.body!!))
    val (at, signature) = put.headers.getValue("KIFF-Device-Signature").split(",").let { it[0].removePrefix("t=") to it[1].removePrefix("v1=") }
    val message = KiffClient.requestMessage("t_01", "PUT", "/v1/me/usage/limit", put.body, at)
    assertTrue("KIFF takes it from the device key (a phone-signed route)", verifies(signer.publicKeyDER, message, signature))
    assertFalse("not the approval key, which KIFF would refuse here", verifies(signer.approvalPublicKeyDER, message, signature))
    assertEquals("Company · 4 seats · 62% of the spend limit used", m.state.value.balance!!.balance.words)
    assertEquals(13000L, m.state.value.balance!!.balance.raise!!.fromCents)
    assertFalse(m.state.value.raising)
    assertNull(m.state.value.raiseError)
  }

  @Test fun whileTheBalanceIsReadAfterARaiseASecondTapSendsNothing() = runTest {
    val m = model(waiting)
    transport.answer("PUT /v1/me/usage/limit", 200, """{"limit_cents":13000}""")
    transport.answer("GET /v1/usage/balance", 200, raised)
    transport.hold("GET /v1/usage/balance")
    val first = launch { m.raiseLimit() }
    testScheduler.runCurrent()
    // KIFF took it and the read hangs: still busy, so the button stays off and a second call does nothing.
    assertTrue(m.state.value.raising)
    assertFalse(m.raiseLimit())
    assertEquals(1, transport.requestsTo("PUT /v1/me/usage/limit").size)
    assertEquals(1, auth.asked.size)
    transport.release("GET /v1/usage/balance")
    first.join()
    assertFalse(m.state.value.raising)
    assertFalse(m.state.value.balance!!.balance.waiting)
  }

  @Test fun aRaiseWhoseReadFailsSaysItWentOnAndOffersOnlyReadingAgain() = runTest {
    val m = model(waiting)
    transport.answer("PUT /v1/me/usage/limit", 200, """{"limit_cents":13000}""")
    transport.answer("GET /v1/usage/balance", 503, """{"error":"down","words":"The balance can't be read right now."}""")
    assertTrue("KIFF took it", m.raiseLimit())
    assertTrue(m.state.value.balanceStale)
    assertFalse(m.state.value.raising)
    // What's shown is from before: no second raise, no prompt, until the balance is read.
    assertFalse(m.raiseLimit())
    assertEquals(1, transport.requestsTo("PUT /v1/me/usage/limit").size)
    assertEquals(1, auth.asked.size)
    // A read that still fails keeps it stale; reading signs nothing.
    transport.answer("GET /v1/usage/balance", 503, """{"error":"down"}""")
    m.readBalance()
    assertTrue(m.state.value.balanceStale)
    // Read: the balance as it is now, and no longer stale.
    transport.answer("GET /v1/usage/balance", 200, raised)
    m.readBalance()
    assertFalse(m.state.value.balanceStale)
    assertEquals(13000L, m.state.value.balance!!.balance.raise!!.fromCents)
    assertEquals(1, auth.asked.size)
    assertEquals(1, transport.requestsTo("PUT /v1/me/usage/limit").size)
  }

  @Test fun aLimitThatChangedMeanwhileIsReadAgainAndOfferedFromTheNewLimit() = runTest {
    val m = model(waiting)
    transport.answer("PUT /v1/me/usage/limit", 409, """{"error":"limit_changed"}""")
    transport.answer("GET /v1/usage/balance", 200, raised)
    assertFalse(m.raiseLimit())
    assertEquals("The limit changed meanwhile. Here it is now: raise it again if it's still needed.", m.state.value.raiseError)
    assertEquals(13000L, m.state.value.balance!!.balance.raise!!.fromCents)
    assertFalse(m.state.value.balanceStale)
    assertFalse(m.state.value.raising)
  }

  @Test fun aChangedLimitSaysItWasReadAgainNotToReadItAgain() = runTest {
    val m = model(waiting)
    transport.answer("PUT /v1/me/usage/limit", 409, """{"error":"limit_changed","message":"The spend limit changed meanwhile. Read it again.","limit_cents":13000}""")
    transport.answer("GET /v1/usage/balance", 200, raised)
    assertFalse(m.raiseLimit())
    assertEquals("The limit changed meanwhile. Here it is now: raise it again if it's still needed.", m.state.value.raiseError)
    assertEquals("read again, and offered from the new limit", 13000L, m.state.value.balance!!.balance.raise!!.fromCents)
  }

  @Test fun aCodeThisAppDoesntKnowShowsKiffsWords() = runTest {
    val m = model(waiting)
    transport.answer("PUT /v1/me/usage/limit", 409, """{"error":"no_spend_limit","message":"This plan has no spend limit to raise."}""")
    assertFalse(m.raiseLimit())
    assertEquals("This plan has no spend limit to raise.", m.state.value.raiseError)
  }

  @Test fun pastTheCeilingKiffsWordsAreShown() = runTest {
    val m = model(waiting)
    transport.answer("PUT /v1/me/usage/limit", 409, """{"error":"limit_ceiling","message":"The limit can't go higher from the phone. Contact KIFF to raise it further.","limit_cents":13000}""")
    assertFalse(m.raiseLimit())
    assertEquals("The limit can't go higher from the phone. Contact KIFF to raise it further.", m.state.value.raiseError)
  }

  @Test fun pastTheCeilingWithNoWordsFromKiffTheAppsOwnSayToContactKiff() = runTest {
    val m = model(waiting)
    transport.answer("PUT /v1/me/usage/limit", 409, """{"error":"limit_ceiling"}""")
    assertFalse(m.raiseLimit())
    assertEquals("The limit is as high as it can be raised from the app. Contact KIFF to raise it further.", m.state.value.raiseError)
  }

  @Test fun someoneWhoMayNotRaiseSendsNothingAndReadsWhoCan() = runTest {
    val m = model(notMine)
    assertFalse(m.raiseLimit())
    assertTrue(auth.asked.isEmpty())
    assertTrue(transport.requestsTo("PUT /v1/me/usage/limit").isEmpty())
    assertEquals("Your tafs are waiting: the spend limit is reached. An admin of this account can raise it.", m.state.value.balance!!.balance.warning)
  }

  @Test fun aRaiseNobodyConfirmedSendsNothingAndSaysSo() = runTest {
    val m = model(waiting)
    auth.confirms = false
    assertFalse(m.raiseLimit())
    assertTrue(transport.requestsTo("PUT /v1/me/usage/limit").isEmpty())
    assertEquals("Not done: it wasn't confirmed.", m.state.value.raiseError)
    assertTrue("still waiting", m.state.value.balance!!.balance.waiting)
  }

  @Test fun kiffRefusingTheRoleSaysSoInWords() = runTest {
    val m = model(waiting)
    transport.answer("PUT /v1/me/usage/limit", 403, """{"error":"not allowed"}""")
    assertFalse(m.raiseLimit())
    assertEquals("The limit wasn't raised. Your role on this account doesn't allow this.", m.state.value.raiseError)
    assertTrue(m.state.value.balance!!.balance.waiting)
  }
}
