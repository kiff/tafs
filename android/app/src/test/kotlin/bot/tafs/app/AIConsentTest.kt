package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Your data and the AI (docs/accounts.md): tafs cloud's consent screen, drawn in its words. It shows after sign-in until the person
 * answers, and again when tafs cloud refuses a model turn for it; the app sends the answer and decides nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AIConsentTest {
  private val t0 = Instant.parse("2026-11-13T09:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val consentRoute = "GET /v1/me/ai-consent"

  private fun consent(given: Boolean, version: String = "1", title: String = "Your data and the AI") =
    """{"given":$given,${if (given) "\"at\":\"2026-11-13T09:00:00.000Z\"," else ""}"version":"$version","words":{"title":"$title",""" +
      """"lines":["tafs uses Anthropic's Claude AI for Coach and your tafs.","Nothing is sent until you allow it. You can withdraw in Account."],""" +
      """"allow":"Allow","not_now":"Not now","withdraw":"Withdraw","given":"AI use: allowed"}}"""

  private fun reads(given: Boolean) {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer(consentRoute, 200, """{"consent":${consent(given)}}""")
  }

  private suspend fun TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0.plusMillis(currentTime) }, start = false, scope = backgroundScope)
      .also { it.restore() }
  }

  @Test fun theScreenShowsAtLaunchUntilAllowedAndTheYesNamesTheVersionRead() = runTest {
    reads(given = false)
    val m = model()
    assertTrue(m.state.value.aiConsentAsked)
    assertEquals("Your data and the AI", m.state.value.aiConsent!!.words.title)
    assertEquals("Not now", m.state.value.aiConsent!!.words.notNow)
    transport.answer("POST /v1/me/ai-consent", 200, """{"consent":${consent(true)}}""")
    m.allowAI()
    assertEquals("""{"version":"1"}""", String(transport.requestsTo("POST /v1/me/ai-consent").single().body!!))
    assertTrue(m.state.value.aiConsent!!.given)
    assertFalse(m.state.value.aiConsentAsked)
    assertFalse(m.state.value.aiConsentBusy)
  }

  @Test fun alreadyAllowedShowsNothing() = runTest {
    reads(given = true)
    val m = model()
    assertFalse(m.state.value.aiConsentAsked)
    assertEquals("AI use: allowed", m.state.value.aiConsent!!.words.given)
  }

  @Test fun notNowSendsNothingAndARefusedMessageShowsTheScreenAgainInTafsCloudsWords() = runTest {
    reads(given = false)
    val m = model()
    m.notNowAI()
    assertFalse(m.state.value.aiConsentAsked)
    assertTrue(transport.requestsTo("POST /v1/me/ai-consent").isEmpty())
    transport.answer(
      "POST /v1/tafs/taf_1/messages", 403,
      """{"error":"Allow AI use first: Coach and your tafs need it to answer.","code":"ai_consent_required","consent":${consent(false, title = "Vos données et l'IA")}}""",
    )
    assertFalse(m.deliverChat("Refund order 42", "refunds-1"))
    assertTrue(m.state.value.aiConsentAsked)
    assertEquals("Vos données et l'IA", m.state.value.aiConsent!!.words.title)
  }

  @Test fun wordsChangedSinceTheyReadThemComeBackToReadAgain() = runTest {
    reads(given = false)
    val m = model()
    transport.answer("POST /v1/me/ai-consent", 409, """{"error":"the consent's words changed: read them again","consent":${consent(false, version = "2")}}""")
    transport.answer(consentRoute, 200, """{"consent":${consent(false, version = "2")}}""")
    m.allowAI()
    assertEquals("2", m.state.value.aiConsent!!.version)
    assertFalse(m.state.value.aiConsent!!.given)
    assertTrue(m.state.value.aiConsentAsked)
    assertEquals("the consent's words changed: read them again", m.state.value.aiConsentError)
  }

  @Test fun withdrawingInAccountIsTafsCloudsToKeep() = runTest {
    reads(given = true)
    val m = model()
    transport.answer("DELETE /v1/me/ai-consent", 200, """{"consent":${consent(false)}}""")
    m.withdrawAI()
    assertEquals(1, transport.requestsTo("DELETE /v1/me/ai-consent").size)
    assertFalse(m.state.value.aiConsent!!.given)
    assertFalse(m.state.value.aiConsentAsked)
    assertNull(m.state.value.aiConsentError)
  }
}
