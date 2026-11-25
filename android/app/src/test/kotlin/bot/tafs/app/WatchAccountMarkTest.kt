@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.WatchAsk
import bot.tafs.app.kiff.WatchAskTaf
import bot.tafs.app.watch.KeptDrop
import bot.tafs.app.watch.KeptPut
import bot.tafs.app.watch.WatchChats
import bot.tafs.app.watch.WatchRelay
import bot.tafs.app.watch.WatchResults
import bot.tafs.app.watch.WatchSignedOut
import bot.tafs.watchlink.KeptSignedOut
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's half of the watch's account mark (docs/watch-wear.md, "What the watch keeps"): random per sign-in, never derived from
 * the account, on every reply and kept item while signed in; dropped at sign-out, when the phone also takes back what it kept for
 * the watch and puts its signed-out item; a new one at the next sign-in.
 */
class WatchAccountMarkTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val now = 1_763_244_000_000L

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
  }

  private suspend fun TestScope.signedIn(): AppModel {
    reads()
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, start = false, scope = backgroundScope).also { it.restore() }
  }

  private suspend fun WatchRelay.marked(r: WatchRequest) = WatchLink.encode(r).let { (path, data) -> answerMarked(path, data) }

  private val accepted = """{"ask":{"id":"wa_01","client_id":"w_7f3a9c21","state":"accepted","words":"Sent","at":"2026-11-16T19:00:00Z","updated":"2026-11-16T19:00:00Z"}}"""

  private fun asksSent() = transport.requests.count { it.method == "POST" && it.url.endsWith("/v1/watch/asks") }

  /** An act sent under another sign-in's mark is answered signed out, and nothing reaches tafs cloud; its own mark goes through. */
  @Test fun anActUnderAnotherSignInsMarkIsRefusedAndNothingIsSent() = runTest {
    val m = signedIn()
    transport.answer("GET /v1/me/ai-consent", 200, """{"consent":{"given":true,"version":"1","words":{"title":"Your data and the AI","lines":["tafs uses AI."],"allow":"Allow","not_now":"Not now","withdraw":"Withdraw","given":"AI use: allowed"}}}""")
    transport.answer("POST /v1/watch/asks", 200, accepted)
    val relay = WatchRelay(model = { m }, now = { now })
    val ask = WatchRequest.Ask(now, "w_7f3a9c21", "Refund order A-1")
    // Kept under A; the phone is on another sign-in now.
    val (path, data) = WatchLink.encode(ask, AppModel.newWatchMark())
    val (reply, mark) = relay.answerMarked(path, data)
    assertEquals(WatchReply.SignedOut, reply)
    assertNull("signed out carries no mark", mark)
    assertEquals(0, asksSent())
    // Under the phone's own mark, it goes.
    val (ownPath, ownData) = WatchLink.encode(ask, m.watchMark())
    assertTrue(relay.answerMarked(ownPath, ownData).first is WatchReply.AskTaken)
    assertEquals(1, asksSent())
    // An old-style request, with no mark: as before marks.
    val (oldPath, oldData) = WatchLink.encode(ask.copy(clientId = "w_7f3a9c22"))
    assertTrue(relay.answerMarked(oldPath, oldData).first is WatchReply.AskTaken)
    assertEquals(2, asksSent())
  }

  /** A request with a mark, to a phone with no session of its own: signed out, nothing done. */
  @Test fun aMarkedRequestWithNoSessionIsSignedOut() = runTest {
    val (path, data) = WatchLink.encode(WatchRequest.Refuse(now, "hold_1"), AppModel.newWatchMark())
    assertEquals(WatchReply.SignedOut, WatchRelay(model = { null }, now = { now }).answer(path, data))
    val sample = testModel(transport = transport, secrets = secrets, start = false, scope = backgroundScope).also { it.startSample() }
    assertEquals(WatchReply.SignedOut, WatchRelay(model = { sample }, now = { now }).answer(path, data))
    assertTrue(transport.requests.none { it.method == "POST" })
  }

  @Test fun aSignedInPhoneHasOneRandomMarkAndPutsItOnEveryReply() = runTest {
    val m = signedIn()
    val mark = m.watchMark()
    assertNotNull(mark)
    assertTrue("a mark as the watch takes it", WatchLink.isMark(mark))
    assertEquals("the same for the sign-in", mark, m.watchMark())
    for (part in listOf("t_01", "acme", "Acme")) assertTrue(part !in mark!!)
    val (reply, sent) = WatchRelay(model = { m }, now = { now }).marked(WatchRequest.NeedsYou(now))
    assertTrue(reply is WatchReply.NeedsYou)
    assertEquals(mark, sent)
    // On the wire, beside the reply's own fields.
    assertEquals(WatchLink.Marked(reply, mark), WatchLink.decodeMarked(WatchLink.encodeReply(reply, sent)))
  }

  @Test fun signedOutOrRefusedCarriesNoMark() = runTest {
    val (reply, mark) = WatchRelay(model = { null }, now = { now }).marked(WatchRequest.NeedsYou(now))
    assertEquals(WatchReply.SignedOut, reply)
    assertNull(mark)
    val m = signedIn()
    val (refused, none) = WatchRelay(model = { m }, now = { now }).answerMarked(WatchLink.NEEDS_YOU, "{}".encodeToByteArray())
    assertTrue(refused is WatchReply.Refused)
    assertNull(none)
  }

  /** A reply read across a change of sign-in is never marked as either: Open on phone, with no mark. */
  @Test fun aSignInThatChangesWhileReadingGivesNoMark() = runTest {
    val m = signedIn()
    m.watchMark()
    var calls = 0
    val relay = WatchRelay(model = { m.also { if (++calls == 2) secrets.save(AppModel.newWatchMark(), AppModel.WATCH_MARK) } }, now = { now })
    val (reply, mark) = relay.marked(WatchRequest.NeedsYou(now))
    assertTrue(reply is WatchReply.Failed)
    assertNull(mark)
  }

  @Test fun signOutDropsTheMarkAndTellsTheWatchANewSignInMakesANewOne() = runTest {
    val m = signedIn()
    val first = m.watchMark()
    val told = mutableListOf<String?>()
    m.watchSignedOut = { told += it }
    m.signOut(tellKiff = false)
    assertEquals(listOf(first), told)
    assertNull(m.watchMark())
    assertNull(secrets.load(AppModel.WATCH_MARK))
    reads()
    m.useSession("at_2", "t_01")
    val next = m.watchMark()
    assertNotNull(next)
    assertNotEquals(first, next)
  }

  @Test fun theSampleHasNoMark() = runTest {
    val m = testModel(transport = transport, secrets = secrets, start = false, scope = backgroundScope)
    m.startSample()
    assertNull(m.watchMark())
  }

  private class Kept : KeptPut, KeptDrop {
    val put = mutableListOf<Pair<String, ByteArray>>()
    val dropped = mutableListOf<Pair<String, Boolean>>()
    override suspend fun put(path: String, data: ByteArray) { put += path to data }
    override suspend fun drop(path: String, prefix: Boolean) { dropped += path to prefix }
  }

  /** At sign-out the phone takes back what it kept for the watch and puts its signed-out item with the mark it dropped. */
  @Test fun theWatchIsToldAtSignOutAndWhatWasKeptForItIsTakenBack() = runTest {
    val kept = Kept()
    val mark = AppModel.newWatchMark()
    WatchSignedOut(kept, kept, now = { now }).tell(mark)
    assertEquals(
      listOf(WatchLink.KEPT_ASK to true, WatchLink.KEPT_MARKS to false, WatchLink.KEPT_TAF_CHAT to true),
      kept.dropped,
    )
    val (path, data) = kept.put.single()
    assertEquals(WatchLink.KEPT_SIGNED_OUT, path)
    assertEquals(KeptSignedOut(now, mark), WatchLink.decodeKeptSignedOut(data))
    // The sample's sign-out has no mark: the item says so, and the watch wipes.
    kept.put.clear()
    WatchSignedOut(kept, kept, now = { now }).tell(null)
    assertEquals(KeptSignedOut(now, null), WatchLink.decodeKeptSignedOut(kept.put.single().second))
  }

  /** A drop that fails never stops the signed-out item. */
  @Test fun theSignedOutItemGoesEvenIfADropFails() = runTest {
    val kept = Kept()
    WatchSignedOut(kept, { _, _ -> throw IllegalStateException("no Data Layer") }, now = { now }).tell(null)
    assertEquals(WatchLink.KEPT_SIGNED_OUT, kept.put.single().first)
  }

  private val ask = WatchAsk("wa_01", "w_7f3a9c21", "done", WatchAskTaf("taf_1", "Calendar"), "Moved your call to 18:00.", "Done", "2026-11-16T19:01:00Z")

  @Test fun keptItemsCarryTheMarkAndNoneIsPutAcrossAChange() = runTest {
    val kept = Kept()
    val a = AppModel.newWatchMark()
    var mark = a
    val results = WatchResults(readAsk = { ask }, readMarks = { null }, keep = kept, now = { now }, mark = { mark })
    assertTrue(results.ask("wa_01"))
    assertEquals(a, WatchLink.decodeKeptAsk(kept.put.single().second)!!.mark)
    // The sign-in changed while the ask was read: nothing is put, under either mark.
    kept.put.clear()
    val changing = WatchResults(readAsk = { mark = AppModel.newWatchMark(); ask }, readMarks = { null }, keep = kept, now = { now }, mark = { mark })
    assertTrue(!changing.ask("wa_01"))
    assertTrue(kept.put.isEmpty())
  }

  @Test fun theChatsTheWatchOpenedAreForgottenAtSignOut() {
    val chats = WatchChats(KeptPut { _, _ -> }, now = { now }, mark = { "AAAAAAAAAAAAAAAAAAAAAA" })
    chats.opened("taf_1")
    val line = bot.tafs.app.model.ChatMessage(java.time.Instant.parse("2026-11-25T01:11:00Z"), bot.tafs.app.model.ChatMessage.From.owner, "Hello", session = "s1")
    assertEquals("AAAAAAAAAAAAAAAAAAAAAA", WatchLink.decodeKeptChat(chats.changed(mapOf("taf_1" to listOf(line))).single().second)!!.mark)
    chats.forget()
    assertTrue(chats.openedIds().isEmpty())
    assertTrue(chats.changed(mapOf("taf_1" to listOf(line))).isEmpty())
  }
}
