package bot.tafs.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Row 44, D: the watch opens at once on what it kept, a short drop doesn't flash "phone not reachable", and what's kept is only
 * what the privacy text says: the count, never Needs you's line, a held call's details or a taf's state line, cleared on sign-out, when locked, and after 24 h.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = WatchApplication::class)
class WatchCacheTest {
  private var clock = 1_763_244_000_000L
  private val call = HeldLine("hold_1", "Refunds", "Refund order 1042", "€64.00", "Over €50 each", refusable = true)
  private val needsYou = WatchReply.NeedsYou(1, "1 held call needs you", listOf(call))
  private val tafs = WatchReply.Tafs(listOf(TafLine("taf_1", "Refunds", "Waiting for you · Refund €64.00", halted = false)), allHalted = false)

  private class Memory : CacheStore {
    var text: String? = null
    override fun read() = text
    override fun write(text: String) { this.text = text }
    override fun clear() { text = null }
  }

  private class Phone(var answer: (WatchRequest) -> Asked) : PhoneLink {
    override suspend fun ask(request: WatchRequest): Asked = answer(request)
  }

  private val store = Memory()
  private val cache = WatchCache(store) { clock }
  private val reads: (WatchRequest) -> Asked = { if (it is WatchRequest.NeedsYou) Asked.Answered(needsYou) else Asked.Answered(tafs) }
  private fun model(phone: PhoneLink, unlocked: () -> Boolean = { true }) = WatchModel(phone, now = { clock }, unlocked = unlocked, cache = cache)

  @Test fun whatsKeptHasNoHeldCallDetailsAndNoStateLine() = runTest {
    model(Phone(reads)).refresh()
    val kept = store.text!!
    // Not even Needs you's own line: tafs cloud's words, which nothing here can promise are amount-free.
    for (secret in listOf("Refund order 1042", "€64.00", "Over €50 each", "hold_1", "Waiting for you", "1 held call needs you")) assertFalse(secret, secret in kept)
    val snapshot = cache.load()!!
    assertEquals(1, snapshot.count)
    assertEquals(listOf(TafLine("taf_1", "Refunds", "", halted = false)), snapshot.tafs)
  }

  @Test fun theNextStartOpensAtOnceOnWhatWasKeptMarkedUpdating() = runTest {
    model(Phone(reads)).refresh()
    var seen: WatchModel.Shown? = null
    lateinit var next: WatchModel
    next = model(Phone { r -> seen = next.shown.value; reads(r) })
    next.refresh()
    val opened = seen as WatchModel.Shown.Read
    assertTrue("drawn from the cache before the phone answered", opened.updating)
    assertEquals(1, opened.needsYou.count)
    assertTrue("no held call from the cache", opened.needsYou.calls.isEmpty())
    // Then the phone's read, fresh.
    assertFalse((next.shown.value as WatchModel.Shown.Read).updating)
  }

  @Test fun aShortDropKeepsTheLastReadAndOnlyASustainedOneIsUnreachable() = runTest {
    val phone = Phone(reads)
    val m = model(phone)
    m.refresh()
    phone.answer = { Asked.Unreachable() }
    clock += 5_000
    m.refresh()
    assertTrue("kept, marked updating", (m.shown.value as WatchModel.Shown.Read).updating)
    clock += UNREACHABLE_AFTER_MS
    m.refresh()
    assertEquals(WatchModel.Shown.PhoneUnreachable(), m.shown.value)
  }

  @Test fun aLockedWatchSignOutAndADayEachClearIt() = runTest {
    model(Phone(reads)).refresh()
    model(Phone { Asked.Unreachable() }, unlocked = { false }).refresh()
    assertNull("locked: cleared, as a watch taken off the wrist locks", store.text)

    model(Phone(reads)).refresh()
    model(Phone { Asked.Answered(WatchReply.SignedOut) }).refresh()
    assertNull("signed out on the phone", store.text)

    model(Phone(reads)).refresh()
    model(Phone(reads)).signOutHere()
    assertNull("signed out on the watch", store.text)

    model(Phone(reads)).refresh()
    clock += WatchCache.MAX_AGE_MS + 1
    assertNull("older than a day", cache.load())
    assertNull(store.text)
  }

  @Test fun anUnreadableCacheIsNoneAndGoes() {
    store.text = "not json"
    assertNull(cache.load())
    assertNull(store.text)
  }
}
