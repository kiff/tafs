package bot.tafs.app

import bot.tafs.app.app.RestoreOnce
import bot.tafs.app.kiff.WatchAsk
import bot.tafs.app.kiff.WatchAskTaf
import bot.tafs.app.kiff.WatchInboxMarks
import bot.tafs.app.push.Alert
import bot.tafs.app.push.AlertPayload
import bot.tafs.app.watch.KeptPut
import bot.tafs.app.watch.RefreshFloor
import bot.tafs.app.watch.WatchResults
import bot.tafs.watchlink.KeptAsk
import bot.tafs.watchlink.KeptMarks
import bot.tafs.watchlink.WatchLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's half of "kept and pushed" (docs/watch-wear.md, Delivery): tafs cloud's push for the watch becomes a kept Data Layer
 * item, read with the phone's own session; a restore the watch stopped waiting for goes on and is joined, never started over.
 */
class WatchKeptPhoneTest {
  private val now = 1_763_244_000_000L
  private val ask = WatchAsk("wa_01", "w_7f3a9c21", "done", WatchAskTaf("taf_1", "Calendar"), "Moved your call to 18:00.", "Done", "2026-11-16T19:01:00Z")

  private class Kept : KeptPut {
    val put = mutableListOf<Pair<String, ByteArray>>()
    override suspend fun put(path: String, data: ByteArray) {
      put += path to data
    }
  }

  @Test fun aWatchAskPushPutsTheAskAsAKeptItem() = runTest {
    val kept = Kept()
    val results = WatchResults(readAsk = { if (it == "wa_01") ask else null }, readMarks = { null }, keep = kept, now = { now })
    assertTrue(results.ask("wa_01"))
    val (path, data) = kept.put.single()
    assertEquals("/tafs/watch/v1/kept/ask/wa_01", path)
    assertEquals(KeptAsk(now, bot.tafs.app.watch.WatchRelay.lineOf(ask)), WatchLink.decodeKeptAsk(data))
  }

  @Test fun anAskThePhoneCantReadPutsNothingAndATafsAnswerGoesToItsChat() = runTest {
    val kept = Kept()
    val results = WatchResults(readAsk = { id -> if (id == "wa_taf") ask.copy(id = "wa_taf", kind = "taf") else null }, readMarks = { null }, keep = kept, now = { now })
    // Signed out, or not this person's: nothing.
    assertTrue(!results.ask("wa_gone"))
    assertTrue(kept.put.isEmpty())
    // A taf's answer to a message from the watch: put, for that taf's chat there (never its Inbox, which the watch decides).
    assertTrue(results.ask("wa_taf"))
    assertEquals("taf", WatchLink.decodeKeptAsk(kept.put.single().second)!!.ask.kind)
  }

  /** A taf's chat the watch opened is kept for it as it changes on the phone: one item per change, none for another taf's. */
  @Test fun aTafChatTheWatchOpenedIsPutAsItChanges() {
    val chats = bot.tafs.app.watch.WatchChats(Kept(), now = { now })
    fun message(text: String, owner: Boolean) = bot.tafs.app.model.ChatMessage(java.time.Instant.parse("2026-11-25T01:11:00Z"),
      if (owner) bot.tafs.app.model.ChatMessage.From.owner else bot.tafs.app.model.ChatMessage.From.taf, text, session = "s1")
    val one = mapOf("wiki" to listOf(message("Why isn't it connected?", true)), "other" to listOf(message("Hi", true)))
    assertTrue("not opened on the watch: nothing", chats.changed(one).isEmpty())
    chats.opened("wiki")
    val first = chats.changed(one)
    assertEquals(listOf("/tafs/watch/v1/kept/taf-chat/wiki"), first.map { it.first })
    assertTrue("the same lines again: nothing", chats.changed(one).isEmpty())
    val answered = one + ("wiki" to one.getValue("wiki") + message("Chrome is closed.", false))
    val kept = WatchLink.decodeKeptChat(chats.changed(answered).single().second)!!
    assertEquals(listOf("Why isn't it connected?", "Chrome is closed."), kept.lines.map { it.text })
    // A message still on its way isn't one yet.
    val pending = answered + ("wiki" to answered.getValue("wiki") + message("And now?", true).copy(session = "pending"))
    assertTrue(chats.changed(pending).isEmpty())
  }

  /** The phone's Today sheet: a cut answer (`more`) is read whole on a tap (`GET /v1/watch/asks/{id}?full=1`). */
  @Test fun aChatThatLeftThePhoneIsPutEmptySoTheWatchsCopyGoesToo() {
    val chats = bot.tafs.app.watch.WatchChats(Kept(), now = { now })
    val said = bot.tafs.app.model.ChatMessage(java.time.Instant.parse("2026-11-25T01:11:00Z"), bot.tafs.app.model.ChatMessage.From.owner, "Refunds under 50 euros", session = "s1")
    chats.opened("wiki")
    // Not read yet: nothing to put, and nothing emptied.
    assertTrue(chats.changed(emptyMap()).isEmpty())
    assertEquals(1, chats.changed(mapOf("wiki" to listOf(said))).size)
    // The conversation was deleted on the phone: its chat left the phone's threads, and the watch's copy is put empty, once.
    val gone = chats.changed(emptyMap())
    assertEquals(listOf("/tafs/watch/v1/kept/taf-chat/wiki"), gone.map { it.first })
    assertTrue(WatchLink.decodeKeptChat(gone.single().second)!!.lines.isEmpty())
    assertTrue(chats.changed(emptyMap()).isEmpty())
  }

  @Test fun aCutAnswerOnTheTodaySheetIsReadWhole() = runTest {
    val turns = bot.tafs.app.watch.WatchDaysWire.turns(bot.tafs.app.kiff.KiffClient.json.decodeFromString(bot.tafs.app.kiff.WireWatchTurns.serializer(),
      """{"turns":[{"id":"wa_1","said":"Why?","state":"done","line":"Your Chrome isn't reachable…","words":"Done","more":true},{"id":"wa_2","said":"Ok","state":"done","words":"Done"}]}"""))
    assertEquals(listOf(true, false), turns.map { it.more })
    val transport = FakeTransport()
    transport.answer("GET /v1/watch/asks/wa_1", 200, """{"text":"Your Chrome isn't reachable (it's closed, or the computer is asleep)."}""")
    val cloud = bot.tafs.app.kiff.TafsCloud(transport).apply { token = "at_1" }
    assertEquals("Your Chrome isn't reachable (it's closed, or the computer is asleep).", bot.tafs.app.watch.TafsCloudWatchDaysStore(cloud).full("wa_1"))
  }

  @Test fun aWatchInboxPushPutsTheMarksAsAKeptItem() = runTest {
    val kept = Kept()
    val results = WatchResults(readAsk = { null }, readMarks = { WatchInboxMarks(read = listOf("wa_02"), dismissed = listOf("hold_1")) }, keep = kept, now = { now })
    assertTrue(results.inbox())
    val (path, data) = kept.put.single()
    assertEquals(WatchLink.KEPT_MARKS, path)
    assertEquals(KeptMarks(now, read = listOf("wa_02"), dismissed = listOf("hold_1")), WatchLink.decodeKeptMarks(data))
  }

  @Test fun theInboxPushIsForTheWatchOnly() {
    assertEquals(Alert.WatchInbox, AlertPayload.parse(mapOf("kind" to "background", "change" to "watch_inbox")))
  }

  /** The watch's job is in its own work, never Firebase's few seconds (TafsMessagingService). */
  @Test fun thePushHandsTheWorkToAJob() {
    val service = java.io.File("src/main/kotlin/bot/tafs/app/push/TafsMessagingService.kt").readText()
    assertTrue(service.contains("WatchResultsWork.ask(applicationContext, alert.askId)"))
    assertTrue(service.contains("WatchResultsWork.inbox(applicationContext)"))
    assertTrue("no blocking in Firebase's callback", !service.contains("runBlocking"))
  }

  /** Row 28: the watch's Open on phone from a taf's chat opens that taf. */
  @Test fun openOnPhoneFromATafsChatOpensThatTaf() {
    assertEquals(AlertPayload.taf("refunds-1"), AlertPayload.openedFromWatch("tafs://open/taf/refunds-1"))
    assertNull(AlertPayload.openedFromWatch("tafs://open/taf/"))
    assertNull(AlertPayload.openedFromWatch("tafs://open/taf/a b"))
  }

  /** A restore the watch stopped waiting for goes on in the app's scope; the next ask joins it, never starting it over. */
  @Test fun aRestoreACallerStoppedWaitingForGoesOnAndIsJoined() = runTest {
    var started = 0
    val gate = CompletableDeferred<Unit>()
    val once = RestoreOnce({ started++; gate.await() }, scope = backgroundScope)
    assertNull(withTimeoutOrNull(1_000) { once.run(onScreen = false) })
    gate.complete(Unit)
    once.run(onScreen = false)
    once.run(onScreen = false)
    assertEquals(1, started)
  }

  /** A restore that was cancelled, or failed, isn't done: the next ask runs it again. */
  @Test fun aCancelledRestoreRunsAgain() = runTest {
    for (scope in listOf(null, backgroundScope)) {
      var tries = 0
      val once = RestoreOnce({ if (++tries == 1) throw CancellationException("cut short") }, scope = scope)
      runCatching { once.run(onScreen = false) }
      once.run(onScreen = false)
      once.run(onScreen = false)
      assertEquals("$scope", 2, tries)
    }
  }

  /** The watch's reads join the one under way, and past the floor start one of their own. */
  @Test fun theWatchsReadsJoinTheOneUnderWay() = runTest {
    val floor = RefreshFloor()
    val gate = CompletableDeferred<Unit>()
    var reads = 0
    val first = floor.read(now, backgroundScope) { reads++; gate.await() }
    val second = floor.read(now + 20_000, backgroundScope) { reads++ }
    assertSame("joined while it runs, even past the floor", first, second)
    gate.complete(Unit)
    first!!.await()
    assertNull("within the floor of the last one: its read stands", floor.read(now + 5_000, backgroundScope) { reads++ })
    floor.read(now + 30_000, backgroundScope) { reads++ }!!.await()
    assertEquals(2, reads)
  }

  /** tafs cloud's `taf_chat` push: parsed, for the watch only; one with no taf is nothing. */
  @Test fun theTafChatPushIsForTheWatchOnly() {
    assertEquals(Alert.TafChat("t_cloud_1"), AlertPayload.parse(mapOf("kind" to "background", "change" to "taf_chat", "taf_id" to "t_cloud_1")))
    assertNull(AlertPayload.parse(mapOf("kind" to "background", "change" to "taf_chat")))
    val service = java.io.File("src/main/kotlin/bot/tafs/app/push/TafsMessagingService.kt").readText()
    assertTrue(service.contains("WatchResultsWork.tafChat(applicationContext, alert.tafId)"))
  }

  /** The job puts the chat for a taf the watch opened, by the phone's id; for one it never opened, nothing. */
  @Test fun theTafChatJobPutsOnlyAChatTheWatchOpened() = runTest {
    val kept = Kept()
    val messages = listOf(bot.tafs.app.model.ChatMessage(java.time.Instant.parse("2026-11-25T01:11:00Z"), bot.tafs.app.model.ChatMessage.From.taf, "Chrome is closed.", session = "s1"))
    val chats = mapOf("t_cloud_1" to ("wiki" to messages), "t_cloud_2" to ("other" to messages))
    val results = WatchResults(readAsk = { null }, readMarks = { null }, keep = kept, now = { now }, readChat = { chats[it] }, opened = { setOf("wiki") })
    assertTrue(results.tafChat("t_cloud_1"))
    val (path, data) = kept.put.single()
    assertEquals("/tafs/watch/v1/kept/taf-chat/wiki", path)
    assertEquals(listOf("Chrome is closed."), WatchLink.decodeKeptChat(data)!!.lines.map { it.text })
    assertTrue("never opened on the watch: nothing", !results.tafChat("t_cloud_2"))
    assertTrue("a taf this phone doesn't list: nothing", !results.tafChat("t_cloud_9"))
    assertEquals(1, kept.put.size)
  }

  /** The chats the watch opened are remembered across the phone's restarts, by id alone. */
  @Test fun theChatsTheWatchOpenedOutliveTheProcess() {
    val store = bot.tafs.app.watch.WatchChats.OpenedStore.Memory()
    bot.tafs.app.watch.WatchChats(Kept(), store = store).opened("wiki")
    assertEquals(setOf("wiki"), bot.tafs.app.watch.WatchChats(Kept(), store = store).openedIds())
  }
}
