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
 * no prompt; tafs cloud checks the AI consent given on the phone, and its receipt comes back as it is, and a retry carries the same id.
 * The phone never names the taf or marks the ask as the watch's: tafs cloud's own route does.
 */
class WatchAskTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val now = 1_763_244_000_000L

  private fun consent(given: Boolean) =
    """{"consent":{"given":$given,"version":"1","words":{"title":"Your data and the AI","lines":["tafs uses AI."],"allow":"Allow","not_now":"Not now","withdraw":"Withdraw","given":"AI use: allowed"}}}"""

  private fun reads(given: Boolean = true, home: String = """{"tafs":[],"others":[],"removing":[]}""") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage"]}""")
    transport.answer("GET /v1/home", 200, home)
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

  /** tafs cloud checks the AI consent (`ai_consent_required`): the watch is told, in its words, to allow it on the phone. */
  @Test fun withoutTheAIConsentTafsCloudRefusesAndTheWatchIsTold() = runTest {
    reads(given = false)
    val m = model()
    transport.answer("POST /v1/watch/asks", 403, """{"error":"Allow AI use first","code":"ai_consent_required"}""")
    val reply = WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Ask(now, "w_7f3a9c21", "Move my call"))
    assertEquals(WatchReply.Failed("Before you ask tafs, allow AI use in tafs on your phone."), reply)
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

  /** tafs cloud checks the AI consent and says so by its code: the watch gets its own words for it, as for asking not being on. */
  // MARK: Chatting with a taf (TAFS_WATCH_TAF_CHAT)

  private val withRefunds = """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}"""

  /** The watch names the phone's taf; tafs cloud gets its own id, inside the bytes the device key signs. */
  @Test fun aMessageToATafNamesItsTafsCloudIdInsideTheSignedBody() = runTest {
    reads(home = withRefunds)
    val m = model()
    transport.answer("POST /v1/watch/asks", 200, """{"ask":{"id":"wa_02","client_id":"w_7f3a9c23","state":"done","taf":{"id":"taf_1","name":"Refunds"},"line":"Refunded 31 EUR to the customer, as the Card allows","more":true,"kind":"taf","words":"Done","updated":"2026-11-16T19:00:00Z"}}""")
    val reply = WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Ask(now, "w_7f3a9c23", "Refund order 1182", tafId = "refunds-1"))
    val sent = transport.requests.last { it.method == "POST" && it.url.endsWith("/v1/watch/asks") }
    val body = kotlinx.serialization.json.Json.parseToJsonElement(String(sent.body!!)) as kotlinx.serialization.json.JsonObject
    assertEquals(setOf("client_id", "text", "taf_id"), body.keys)
    assertEquals("taf_1", (body["taf_id"] as kotlinx.serialization.json.JsonPrimitive).content)
    assertTrue(sent.headers["KIFF-Device-Signature"].orEmpty().matches(Regex("^t=[^,]+,v1=.+$")))
    // The rest is on the phone: passed on as tafs cloud says it.
    assertTrue((reply as WatchReply.AskTaken).ask.more)
    // Marked as a taf's chat, so the watch keeps none of it.
    assertTrue(reply.ask.tafChat)
    assertEquals("Refunds", reply.ask.tafName)
  }

  @Test fun aTafWaitingOnThePersonIsSaidHeldAndTheWatchNeverApproves() = runTest {
    reads(home = withRefunds)
    val m = model()
    transport.answer("POST /v1/watch/asks", 200, """{"ask":{"id":"wa_03","client_id":"w_7f3a9c24","state":"done","held":true,"words":"Done","updated":"2026-11-16T19:00:00Z"}}""")
    val reply = WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Ask(now, "w_7f3a9c24", "Refund order 1183", tafId = "refunds-1"))
    assertTrue((reply as WatchReply.AskTaken).ask.held)
  }

  /** A taf the phone doesn't list sends nothing, and the words never go to Coach. */
  @Test fun aTafThePhoneDoesntListSendsNothing() = runTest {
    reads()
    val m = model()
    val reply = WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Ask(now, "w_7f3a9c25", "Hello", tafId = "gone-1"))
    assertEquals(WatchReply.Failed(bot.tafs.app.model.Words.Watch.openOnPhone), reply)
    assertTrue(transport.requests.none { it.method == "POST" && it.url.endsWith("/v1/watch/asks") })
  }

  /** tafs cloud's switch off, a taf not the person's to use, or Coach named as a taf: Open on phone, never Coach's chat. */
  @Test fun aRefusedMessageToATafSaysOpenOnPhoneNeverCoach() = runTest {
    for (refusal in listOf("""404 {"error":"off","code":"watch_taf_chat_off"}""", """404 {"error":"no","code":"no_taf"}""", """400 {"error":"coach","code":"coach_taf"}""", """404 {"error":"off","code":"watch_off"}""")) {
      reads(home = withRefunds)
      val m = model()
      val (status, json) = refusal.split(" ", limit = 2)
      val posted = transport.requests.count { it.method == "POST" && it.url.endsWith("/v1/watch/asks") }
      transport.answer("POST /v1/watch/asks", status.toInt(), json)
      assertEquals(refusal, WatchReply.Failed(bot.tafs.app.model.Words.Watch.openOnPhone),
        WatchRelay(model = { m }, now = { now }).ask(WatchRequest.Ask(now, "w_7f3a9c26", "Hello", tafId = "refunds-1")))
      assertEquals("$refusal reached tafs cloud", posted + 1, transport.requests.count { it.method == "POST" && it.url.endsWith("/v1/watch/asks") })
    }
    // The rest as for an ask: the AI consent in the watch's words.
    assertEquals(WatchReply.Failed(bot.tafs.app.model.Words.Watch.askNeedsConsent), WatchRelay.refusedTafMessage(bot.tafs.app.kiff.CloudError(403, "Allow AI use first", code = "ai_consent_required")))
  }

  @Test fun tafsCloudsRefusalsReachTheWatchInItsWords() {
    assertEquals(WatchReply.Failed(bot.tafs.app.model.Words.Watch.askNeedsConsent), WatchRelay.refusedAsk(bot.tafs.app.kiff.CloudError(403, "Allow AI use first", code = "ai_consent_required")))
    assertEquals(WatchReply.Failed(bot.tafs.app.model.Words.Watch.askNotOn), WatchRelay.refusedAsk(bot.tafs.app.kiff.CloudError(404, "watch_off", code = "watch_off")))
    assertEquals(WatchReply.Unsure, WatchRelay.refusedAsk(bot.tafs.app.kiff.CloudError(502, "bad gateway")))
  }

  @Test fun aWatchCantSendThePhonesOwnMessage() = runTest {
    assertEquals(WatchReply.Refused(NotAccepted.UNKNOWN_PATH), WatchRelay(model = { testModel() }, now = { now }).answer(WatchLink.ASK_RESULT, "{}".encodeToByteArray()))
  }

  /** tafs cloud's push says only which ask changed: the phone reads it and passes it on, and shows nothing itself. */
  @Test fun theAsksPushIsForTheWatchOnly() {
    assertEquals(Alert.WatchAsk("wa_01"), AlertPayload.parse(mapOf("kind" to "background", "change" to "watch_ask", "ask_id" to "wa_01")))
    assertNull(AlertPayload.parse(mapOf("kind" to "background", "change" to "watch_ask")))
    val ask = bot.tafs.app.kiff.WatchAsk("wa_01", "w_7f3a9c21", "working", bot.tafs.app.kiff.WatchAskTaf("taf_1", "Calendar"), null, "Working", "2026-11-16T19:01:00Z")
    assertEquals(AskLine("wa_01", "w_7f3a9c21", "working", "Calendar", null, "Working", "2026-11-16T19:01:00Z"), WatchRelay.lineOf(ask))
    assertFalse(AlertPayload.CHANGES.contains(AlertPayload.WATCH_ASK))
  }
}
