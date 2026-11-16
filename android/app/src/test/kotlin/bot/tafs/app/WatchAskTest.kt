@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.push.Alert
import bot.tafs.app.push.AlertPayload
import bot.tafs.app.watch.WatchRelay
import bot.tafs.app.watch.WatchResults
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.NotAccepted
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ask tafs from the Wear OS watch (docs/watch-wear.md): the words go to tafs cloud as this phone's, signed with its device key and
 * no prompt, after the AI consent given on the phone; tafs cloud's receipt comes back as it is, and a retry carries the same id.
 * The phone never names the taf or marks the ask as the watch's: tafs cloud's own route does.
 */
class WatchAskTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val now = 1_763_244_000_000L

  private fun consent(given: Boolean) =
    """{"consent":{"given":$given,"version":"1","words":{"title":"Your data and the AI","lines":["tafs uses AI."],"allow":"Allow","not_now":"Not now","withdraw":"Withdraw","given":"AI use: allowed"}}}"""

  private fun reads(given: Boolean = true) {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/ai-consent", 200, consent(given))
  }

  private suspend fun TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, start = false, scope = backgroundScope).also { it.restore() }
  }

  private suspend fun WatchRelay.ask(r: WatchRequest): WatchReply = WatchLink.encode(r).let { (path, data) -> answer(path, data) }

  private val accepted = """{"ask":{"id":"wa_01","client_id":"w_7f3a9c21","state":"accepted","words":"Sent","at":"2026-11-16T19:00:00Z","updated":"2026-11-16T19:00:00Z"}}"""

  @Test fun theAskGoesToTafsCloudSignedWithTheDeviceKeyAndNoPrompt() = runTest {
    reads()
    val m = model()
    transport.answer("POST /v1/watch/asks", 200, accepted)
    val asked = auth.asked.size
    val reply = WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Ask(now, "w_7f3a9c21", "Move my four o'clock call to six"))
    assertEquals(WatchReply.AskTaken(AskLine("wa_01", "w_7f3a9c21", "accepted", words = "Sent", updated = "2026-11-16T19:00:00Z")), reply)
    val sent = transport.requests.last { it.method == "POST" && it.url.endsWith("/v1/watch/asks") }
    // Signed by this phone's device key over the request, as KIFF checks it; no prompt, and never the approval key.
    assertTrue(sent.headers["KIFF-Device-Signature"].orEmpty().matches(Regex("^t=[^,]+,v1=.+$")))
    assertTrue("no prompt from the wrist", auth.asked.size == asked && auth.fingerprints.isEmpty())
    // Only the words, the watch's id and nothing that names a taf or marks the ask as the watch's.
    val body = kotlinx.serialization.json.Json.parseToJsonElement(String(sent.body!!)).let { it as kotlinx.serialization.json.JsonObject }
    assertEquals(setOf("client_id", "text"), body.keys)
  }

  @Test fun aReplyNamesTheAskItContinues() = runTest {
    reads()
    val m = model()
    transport.answer("POST /v1/watch/asks", 200, accepted)
    WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Ask(now, "w_7f3a9c22", "Make it seven", replyTo = "wa_01"))
    val sent = transport.requests.last { it.method == "POST" && it.url.endsWith("/v1/watch/asks") }
    assertTrue(String(sent.body!!).contains("\"reply_to\":\"wa_01\""))
  }

  @Test fun withoutTheAIConsentNothingIsSent() = runTest {
    reads(given = false)
    val m = model()
    val before = transport.requests.size
    val reply = WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Ask(now, "w_7f3a9c21", "Move my call"))
    assertEquals(WatchReply.Failed("Before you ask tafs, allow AI use in tafs on your phone."), reply)
    assertTrue(transport.requests.drop(before).none { it.method == "POST" })
  }

  @Test fun noAnswerFromTafsCloudMayHaveArrived() = runTest {
    reads()
    val m = model()
    transport.drop("POST /v1/watch/asks")
    assertEquals(WatchReply.Unsure, WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Ask(now, "w_7f3a9c21", "Move my call")))
    transport.answer("POST /v1/watch/asks", 502, """{"error":"Try again"}""")
    assertEquals(WatchReply.Unsure, WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Ask(now, "w_7f3a9c21", "Move my call")))
  }

  @Test fun askingFromTheWatchNotOnYetSaysSo() = runTest {
    reads()
    val m = model()
    transport.answer("POST /v1/watch/asks", 404, """{"error":"not found","code":"watch_off"}""")
    assertEquals(WatchReply.Failed("Asking from your watch isn't on yet."), WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Ask(now, "w_7f3a9c21", "Move my call")))
  }

  @Test fun theSampleAsksNothing() = runTest {
    assertEquals(WatchReply.SignedOut, WatchRelay(model = { testModel() }, now = { now }).ask(WatchRequest.Ask(now, "w_7f3a9c21", "hi")))
  }

  @Test fun theAsksAreReadAsTafsCloudHasThem() = runTest {
    reads()
    val m = model()
    transport.answer("GET /v1/watch/asks", 200,
      """{"asks":[{"id":"wa_01","client_id":"w_7f3a9c21","state":"done","taf":{"id":"taf_1","name":"Calendar"},"line":"Moved your call to 18:00.","words":"Done","at":"2026-11-16T19:00:00Z","updated":"2026-11-16T19:02:00Z"}]}""")
    val reply = WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Asks(now, "2026-11-16T19:00:00Z"))
    assertEquals(WatchReply.AskList(listOf(AskLine("wa_01", "w_7f3a9c21", "done", "Calendar", "Moved your call to 18:00.", "Done", "2026-11-16T19:02:00Z"))), reply)
    // The time the watch read is passed on as it came, encoded.
    assertTrue(transport.requests.last { it.method == "GET" && it.url.contains("/v1/watch/asks") }.url.endsWith("/v1/watch/asks?since=2026-11-16T19%3A00%3A00Z"))
  }

  @Test fun aWatchCantSendThePhonesOwnMessage() = runTest {
    assertEquals(WatchReply.Refused(NotAccepted.UNKNOWN_PATH), WatchRelay(model = { testModel() }, now = { now }).answer(WatchLink.ASK_RESULT, "{}".encodeToByteArray()))
  }

  /** tafs cloud's push says only which ask changed: the phone reads it and passes it on, and shows nothing itself. */
  @Test fun theAsksPushIsForTheWatchOnly() {
    assertEquals(Alert.WatchAsk("wa_01"), AlertPayload.parse(mapOf("kind" to "background", "change" to "watch_ask", "ask_id" to "wa_01")))
    assertNull(AlertPayload.parse(mapOf("kind" to "background", "change" to "watch_ask")))
    val ask = bot.tafs.app.kiff.WatchAsk("wa_01", "w_7f3a9c21", "working", bot.tafs.app.kiff.WatchAskTaf("taf_1", "Calendar"), null, "Working", "2026-11-16T19:01:00Z")
    assertEquals(AskLine("wa_01", "w_7f3a9c21", "working", "Calendar", null, "Working", "2026-11-16T19:01:00Z"), WatchResults.pick(listOf(ask), "wa_01"))
    assertNull(WatchResults.pick(listOf(ask), "wa_02"))
    assertFalse(AlertPayload.CHANGES.contains(AlertPayload.WATCH_ASK))
  }
}
