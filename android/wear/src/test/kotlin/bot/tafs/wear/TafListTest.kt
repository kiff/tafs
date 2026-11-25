package bot.tafs.wear

import bot.tafs.watchlink.KeptTafs
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Your tafs never empties (row 27, the owner's Galaxy Watch 5 re-test): a list read that misses keeps the list the watch had, on
 * screen and kept, and is read again quietly; the phone's pushed list ([KeptTafs]) moves the Tafs page at once.
 */
class TafListTest {
  private val t = 1_763_244_000_000L
  private val wiki = TafLine("taf_1", "Wiki lookup", "Ready", halted = false)
  private val first = TafLine("taf_2", "Wikipedia first result", "Ready", halted = false)
  private val needsYou = WatchReply.NeedsYou(0, "Nothing needs you", emptyList())

  private class Phone(var tafs: () -> Asked) : PhoneLink {
    val asked = mutableListOf<WatchRequest>()
    override suspend fun ask(request: WatchRequest): Asked = (when (request) {
      is WatchRequest.NeedsYou -> Asked.Answered(WatchReply.NeedsYou(0, "Nothing needs you", emptyList()))
      is WatchRequest.Tafs -> tafs()
      is WatchRequest.Asks -> Asked.Answered(WatchReply.AskList(emptyList()))
      else -> Asked.Unreachable()
    }).also { asked += request }
  }

  private class Memory : CacheStore {
    var text: String? = null
    override fun read() = text
    override fun write(text: String) { this.text = text }
    override fun clear() { text = null }
  }

  private fun listed(vararg tafs: TafLine) = Asked.Answered(WatchReply.Tafs(tafs.toList(), allHalted = false))

  private fun shownTafs(m: WatchModel) = (m.shown.value as? WatchModel.Shown.Read)?.tafs?.tafs

  @Test fun aMissedListReadKeepsTheListShown() = runTest {
    val phone = Phone { listed(wiki, first) }
    val m = WatchModel(phone, now = { t }, unlocked = { true })
    m.refresh()
    assertEquals(listOf(wiki, first), shownTafs(m))
    // The phone slow, the 10 s passing: the page keeps its two tafs, never "Open on phone" alone.
    phone.tafs = { Asked.Unreachable(LinkFailure.NoAnswer) }
    m.refresh()
    assertEquals(listOf(wiki, first), shownTafs(m))
  }

  @Test fun aMissedListReadNeverSavesNothingOverTheKeptList() = runTest {
    val store = Memory()
    val phone = Phone { listed(wiki, first) }
    WatchModel(phone, now = { t }, unlocked = { true }, cache = WatchCache(store) { t }).refresh()
    // A new open: the list read misses this time.
    phone.tafs = { Asked.Unreachable(LinkFailure.NoAnswer) }
    val m = WatchModel(phone, now = { t }, unlocked = { true }, cache = WatchCache(store) { t })
    m.refresh()
    assertEquals(listOf("taf_1", "taf_2"), shownTafs(m)?.map { it.tafId })
    assertEquals(listOf("taf_1", "taf_2"), WatchCache(store) { t }.load()?.tafs?.map { it.tafId })
  }

  @Test fun aMissedListIsReadAgainQuietlyUntilThePhoneGivesIt() = runTest {
    var answers = 0
    val phone = Phone { listed(wiki) }
    val m = WatchModel(phone, now = { t }, unlocked = { true })
    m.refresh()
    // Missed twice, then given, with a taf added meanwhile.
    phone.tafs = { if (++answers < 3) Asked.Unreachable(LinkFailure.NoAnswer) else listed(wiki, first) }
    val waits = mutableListOf<Long>()
    m.refresh()
    assertEquals(listOf(wiki), shownTafs(m))
    m.retryTafs(wait = { waits += it })
    assertEquals(listOf(wiki, first), shownTafs(m))
    assertEquals(TAFS_RETRY_MS.take(2), waits)
    // Given once: no more tries.
    val before = phone.asked.size
    m.retryTafs(wait = { waits += it })
    assertEquals(before, phone.asked.size)
  }

  @Test fun theRetryStopsAfterItsLastTryAndKeepsTheList() = runTest {
    val phone = Phone { listed(wiki) }
    val m = WatchModel(phone, now = { t }, unlocked = { true })
    m.refresh()
    phone.tafs = { Asked.Unreachable(LinkFailure.NoAnswer) }
    m.refresh()
    val waits = mutableListOf<Long>()
    m.retryTafs(wait = { waits += it })
    assertEquals(TAFS_RETRY_MS, waits)
    assertEquals(listOf(wiki), shownTafs(m))
  }

  @Test fun theStartReadsAMissedListAgain() = runTest {
    var missed = true
    val phone = Phone { if (missed) Asked.Unreachable(LinkFailure.NoAnswer) else listed(wiki, first) }
    val m = WatchModel(phone, now = { t }, unlocked = { true })
    m.start(wait = { missed = false })
    assertEquals(listOf(wiki, first), shownTafs(m))
  }

  @Test fun thePhonesPushedListMovesTheTafsPageAtOnce() = runTest {
    val store = Memory()
    val m = WatchModel(Phone { listed(wiki) }, now = { t }, unlocked = { true }, cache = WatchCache(store) { t })
    m.refresh()
    m.tafsChanged(listOf(wiki, first), allHalted = false)
    assertEquals(listOf(wiki, first), shownTafs(m))
    assertEquals(2, WatchCache(store) { t }.load()?.tafs?.size)
  }

  @Test fun aPushedListWithTheAppClosedIsWhatTheNextOpenDraws() {
    val store = Memory()
    val stores = KeptResults.Stores(thread = null, inbox = null, secure = true, signedOut = false, cache = store)
    KeptResults.tafs = null
    KeptResults.tafList(KeptTafs(t, listOf(wiki, first)), stores) { t }
    assertEquals(listOf("taf_1", "taf_2"), WatchCache(store) { t }.load()?.tafs?.map { it.tafId })
    // Off the wrist, or signed out here: nothing is kept.
    val locked = Memory()
    KeptResults.tafList(KeptTafs(t, listOf(wiki)), KeptResults.Stores(thread = null, inbox = null, secure = false, signedOut = false, cache = locked)) { t }
    assertNull(locked.text)
  }

  @Test fun theKeptListRoundTrips() {
    val kept = KeptTafs(t, listOf(wiki, first.copy(paused = true)), allHalted = false, zone = "Europe/Paris", mark = "AbCdEfGhIjKlMnOpQr")
    assertEquals(kept, WatchLink.decodeKeptTafs(WatchLink.encodeKept(kept)))
    assertTrue(WatchLink.KEPT_TAFS.startsWith(WatchLink.KEPT))
    assertNull(WatchLink.keptChatTaf(WatchLink.KEPT_TAFS))
  }
}
