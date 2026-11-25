package bot.tafs.wear

import androidx.test.core.app.ApplicationProvider
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.ChatLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A taf's answer lands in its chat alone, whichever path brings it (the Galaxy Watch 5 re-test: "Paris" sat in the Inbox
 * after the open's read of the asks, while "Rambouillet", pushed, went only to Wiki lookup's chat). Coach's answers still go to
 * the Inbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WatchApplication::class)
class AnswerOnePlaceTest {
  private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val t = 1_763_244_000_000L
  // A taf's answer from a tafs cloud or path that didn't say `kind`: only the watch's own record says it's a taf's chat.
  private val paris = AskLine("wa_9", "w_00000009", AskLine.FAILED, "Wiki lookup", line = "I couldn't read the Paris page.", words = "Failed", updated = "2026-11-25T16:54:00Z")
  private val coach = AskLine("wa_1", "w_00000001", AskLine.DONE, "Calendar", line = "Moved your call to 18:00.", words = "Done", updated = "2026-11-25T17:00:00Z")

  @Before fun words() = Res.install(context)

  private class Threads(var threads: Map<String, KeptThread> = emptyMap()) : ThreadStore {
    override fun read() = threads
    override fun write(threads: Map<String, KeptThread>) { this.threads = threads }
    override fun clear() { threads = emptyMap() }
  }

  private class Inbox(var items: List<InboxItem> = emptyList()) : InboxStore {
    override fun read() = items
    override fun write(items: List<InboxItem>) { this.items = items }
    override fun dismissed() = emptySet<String>()
    override fun writeDismissed(ids: Set<String>) = Unit
    override fun clear() { items = emptyList() }
  }

  private class Phone(val asks: List<AskLine>) : PhoneLink {
    override suspend fun ask(request: WatchRequest): Asked = when (request) {
      is WatchRequest.NeedsYou -> Asked.Answered(WatchReply.NeedsYou(0, "Nothing needs you", emptyList()))
      is WatchRequest.Asks -> Asked.Answered(WatchReply.AskList(asks))
      else -> Asked.Unreachable()
    }
  }

  /** Wiki lookup's kept chat, its "Paris" turn already covered by the phone's lines, so only its id remains. */
  private fun wikiChat() = mapOf(tafThread("taf_1") to KeptThread(t, sent = listOf("wa_9")))

  @Test fun theOpensReadOfTheAsksFilesATafsAnswerInItsChatNotTheInbox() = runTest {
    val m = WatchModel(Phone(listOf(paris, coach)), now = { t }, unlocked = { true }, reviewFirst = true,
      inboxStore = Inbox(), thread = Threads(wikiChat()))
    m.refresh()
    m.readAsks()
    assertEquals(listOf("ask:wa_1"), m.inbox.value.map { it.id })
    assertTrue(m.answered().none { it.id == "wa_9" })
  }

  @Test fun aKeptTafsAnswerWithTheAppClosedNeverGoesToTheInbox() {
    val inbox = Inbox()
    val stores = KeptResults.Stores(thread = Threads(wikiChat()), inbox = inbox, secure = true, signedOut = false)
    AskResults.shown = null
    KeptResults.ask(context, paris, stores, now = t)
    assertTrue(inbox.items.isEmpty())
    KeptResults.ask(context, coach, stores, now = t)
    assertEquals(listOf("ask:wa_1"), inbox.items.map { it.id })
  }

  @Test fun aTafsAnswerAnOlderWatchFiledInTheInboxGoesAtTheReopen() = runTest {
    val filed = InboxLines.of(paris, t)!!
    val m = WatchModel(Phone(emptyList()), now = { t }, unlocked = { true }, reviewFirst = true,
      inboxStore = Inbox(listOf(filed)), thread = Threads(wikiChat()))
    m.reload()
    assertTrue(m.inbox.value.isEmpty())
  }

  @Test fun theTurnsThePhonesLinesCoverStayThisChatsByTheirIds() {
    // Sent from the watch, unanswered here; the phone's lines hold the message and its answer, so the lines show it.
    val turn = KeptTurn(WatchModel.Said("Paris", java.time.Instant.parse("2026-11-25T16:53:43Z").toEpochMilli()), paris.copy(state = AskLine.ACCEPTED))
    val lines = listOf(ChatLine("m1", mine = true, at = "2026-11-25T16:53:44Z", text = "Paris"),
      ChatLine("m2", mine = false, at = "2026-11-25T16:54:10Z", text = "I couldn't read the Paris page."))
    val merged = KeptThreads.withLines(KeptThread(t, turns = listOf(turn)), lines)
    assertTrue(merged.turns.isEmpty())
    assertEquals(listOf("wa_9"), merged.sent)
    assertTrue(KeptThreads.inTafChat(mapOf(tafThread("taf_1") to merged), "wa_9"))
    assertEquals(merged, KeptThreadStore.decode(KeptThreadStore.encode(mapOf("k" to merged)))["k"])
  }
}
