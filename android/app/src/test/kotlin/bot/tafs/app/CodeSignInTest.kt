package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.CodeSignIn
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.Words
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account › Sign-in (kiff/cloud `GET`/`PUT /v1/me/sign-in`): whether the person signs in with a code sent by email. KIFF owns the
 * setting; the app reads it, turns it off from here, and opens KIFF's own page to turn it on (a device session never passes the
 * recent-sign-in check). A KIFF without the route shows no section.
 */
class CodeSignInTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val route = "GET /v1/me/sign-in"
  private val change = "PUT /v1/me/sign-in"

  private suspend fun model(): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { Instant.parse("2026-11-22T13:00:00Z") }, start = false).also { it.restore() }
  }

  @Test fun theSettingReadsKiffsAnswer() {
    val setting = KiffClient.json.decodeFromString(CodeSignIn.serializer(), """{"email_code":false,"changed_at":"2026-11-22T13:05:00Z","available":true}""")
    assertEquals(CodeSignIn(emailCode = false, available = true), setting)
  }

  @Test fun accountReadsTheSettingAndAKiffWithoutTheRouteShowsNone() = runTest {
    transport.answer(route, 200, """{"email_code":true,"available":true}""")
    val m = model()
    m.loadCodeSignIn()
    assertEquals(CodeSignIn(emailCode = true, available = true), m.state.value.codeSignIn)
    transport.answer(route, 404, """{"error":"not found"}""")
    m.loadCodeSignIn()
    assertNull("no route, no section", m.state.value.codeSignIn)
    assertNull(m.state.value.codeSignInError)
  }

  /** A read KIFF refuses (a device session, 403) leaves no setting and says why, so Account shows the line instead of nothing. */
  @Test fun aRefusedReadSaysWhyWithNoSetting() = runTest {
    transport.answer(route, 403, """{"error":"forbidden"}""")
    val m = model()
    m.loadCodeSignIn()
    assertNull(m.state.value.codeSignIn)
    val why = m.state.value.codeSignInError!!
    assertTrue(why, why.startsWith(Words.Settings.SignInCode.notLoaded(why = "").trimEnd().take(10)))
  }

  @Test fun offGoesFromTheAppWithTheSessionAndShowsKiffsAnswer() = runTest {
    transport.answer(route, 200, """{"email_code":true,"available":true}""")
    transport.answer(change, 200, """{"email_code":false,"changed_at":"2026-11-22T13:06:00Z","available":true}""")
    val m = model()
    m.loadCodeSignIn()
    m.turnCodeSignInOff()
    val put = transport.requestsTo(change).single()
    assertEquals("""{"email_code":false}""", String(put.body!!))
    assertFalse(m.state.value.codeSignIn!!.emailCode)
    assertFalse(m.state.value.codeSignInBusy)
    // Turning it on is never sent from the app: KIFF's page does that, after a recent sign-in.
    assertTrue(transport.requestsTo(change).none { String(it.body!!).contains("true") })
    assertEquals("https://app.kiff.dev/dashboard/sign-in", m.codeSignInPage)
  }

  @Test fun aRefusedChangeSaysSoAndKeepsTheSettingAsItWas() = runTest {
    transport.answer(route, 200, """{"email_code":true,"available":true}""")
    transport.answer(change, 503, """{"error":"unavailable"}""")
    val m = model()
    m.loadCodeSignIn()
    m.turnCodeSignInOff()
    assertTrue(m.state.value.codeSignIn!!.emailCode)
    assertTrue(m.state.value.codeSignInError!!.startsWith(Words.Settings.SignInCode.notChanged(why = "").trimEnd().removeSuffix(".").take(10)))
    assertFalse(m.state.value.codeSignInBusy)
  }
}
