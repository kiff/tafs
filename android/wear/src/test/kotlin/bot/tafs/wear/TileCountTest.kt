package bot.tafs.wear

import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The tile's count: the only thing on the watch's disk, shown only while unlocked, and forgotten on sign-out or when locked. */
class TileCountTest {
  private val t = 1_763_244_000_000L

  private class Memory : TileCount.Store {
    var kept: Pair<Int, Long>? = null
    override fun read() = kept
    override fun write(count: Int, at: Long) { kept = count to at }
    override fun clear() { kept = null }
  }

  @Test fun theModelKeepsOnlyTheCountAndItsTime() = runTest {
    val memory = Memory()
    val call = HeldLine("hold_1", "Refunds", "Refund order 1042", "€64.00", "Over €50 each", refusable = true)
    val model = WatchModel({ r -> if (r is WatchRequest.NeedsYou) Asked.Answered(WatchReply.NeedsYou(3, "3 need you", listOf(call))) else Asked.Unreachable() }, now = { t }, tile = TileCount(memory))
    model.refresh()
    assertEquals(3 to t, memory.kept)
  }

  @Test fun signingOutOnThePhoneForgetsIt() = runTest {
    val memory = Memory().apply { kept = 2 to t }
    val model = WatchModel({ Asked.Answered(WatchReply.SignedOut) }, now = { t }, tile = TileCount(memory))
    model.refresh()
    assertNull(memory.kept)
  }

  @Test fun itShowsOnlyWhileUnlockedAndLockedForgetsIt() {
    val memory = Memory().apply { kept = 2 to t }
    val tile = TileCount(memory)
    assertEquals(TileCount.Shown(2, t), tile.shown(unlocked = true))
    // Locked (taken off the wrist), or no screen lock at all: nothing shown, and nothing kept.
    assertNull(tile.shown(unlocked = false))
    assertNull(memory.kept)
    assertNull(tile.shown(unlocked = true))
  }

  @Test fun noPhoneLeavesTheLastCountAsItWas() = runTest {
    val memory = Memory().apply { kept = 2 to t }
    WatchModel({ Asked.Unreachable() }, now = { t + 60_000 }, tile = TileCount(memory)).refresh()
    assertEquals(2 to t, memory.kept)
  }
}
