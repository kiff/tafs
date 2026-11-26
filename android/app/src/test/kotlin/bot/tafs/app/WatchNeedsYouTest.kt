package bot.tafs.app

import bot.tafs.app.app.Mode
import bot.tafs.app.watch.KeptPut
import bot.tafs.app.watch.WatchNeedsYou
import bot.tafs.app.watch.WatchRelay
import bot.tafs.watchlink.WatchLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Needs you kept for the watch (`kept/needs-you`, docs/watch-wear.md, Delivery): put when a call is held or ends, once the watch has
 * used tafs in this sign-in, with the account mark, and only what the Privacy page lets the watch keep: the count, and per held
 * call its taf and what it wants with no amount, never KIFF's amount or the reason.
 */
class WatchNeedsYouTest {
  private val noKeep = KeptPut { _, _ -> }
  private val mark = "mark_abcdefghijklmnop"
  private val loaded = testModel().state.value.copy(mode = Mode.kiff, listLoaded = true, watchServed = true)

  private fun needsYou() = WatchNeedsYou(noKeep, mark = { mark }, now = { 42L })

  @Test fun aHeldCallIsPutWithItsMarkAndNoAmountOrReason() {
    val call = loaded.held.first().let { it.copy(proposed = it.proposed.copy(summary = "Refund order T-3001"), reason = "Over the €5 limit for one call") }
    val state = loaded.copy(held = listOf(call))
    val (path, data) = needsYou().changed(state)!!
    assertEquals(WatchLink.KEPT + "needs-you", path)
    val kept = WatchLink.decodeKeptNeedsYou(data)!!
    assertEquals(mark, kept.mark)
    assertEquals(42L, kept.putAt)
    assertEquals(WatchRelay.needsYou(state).count, kept.count)
    val line = kept.calls.single()
    assertEquals(call.id, line.holdId)
    assertNull("never KIFF's amount", line.amount)
    assertNull("never the reason", line.reason)
    assertFalse("no reason anywhere in the item", data.decodeToString().contains("Over the"))
  }

  @Test fun putAgainOnlyWhenNeedsYouChanges() {
    val keeper = needsYou()
    assertNotNull(keeper.changed(loaded))
    assertNull("the same Needs you again: nothing", keeper.changed(loaded))
    // The call answered, refused or run out: it's put again, without it.
    val (_, data) = keeper.changed(loaded.copy(held = emptyList()))!!
    assertTrue(WatchLink.decodeKeptNeedsYou(data)!!.calls.isEmpty())
  }

  @Test fun nothingIsPutBeforeTheWatchUsesTafsOrBeforeARead() {
    assertNull("the watch hasn't used tafs in this sign-in", needsYou().changed(loaded.copy(watchServed = false)))
    assertNull("nothing read yet", needsYou().changed(loaded.copy(listLoaded = false)))
    assertNull("signed out", needsYou().changed(loaded.copy(mode = Mode.sample)))
  }

  @Test fun aSignOutPutsTheNextSignInsWhole() {
    val keeper = needsYou()
    assertNotNull(keeper.changed(loaded))
    keeper.forget()
    assertNotNull("after a sign-out, the same Needs you is put again", keeper.changed(loaded))
  }
}
