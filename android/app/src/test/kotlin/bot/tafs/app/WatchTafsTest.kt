package bot.tafs.app

import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.Halt
import bot.tafs.app.watch.KeptPut
import bot.tafs.app.watch.KeptTafsJson
import bot.tafs.app.watch.WatchRelay
import bot.tafs.app.watch.WatchTafs
import bot.tafs.watchlink.WatchLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The taf list kept for the watch (`kept/tafs`, docs/watch-wear.md, Delivery): put on a change of the list only, once the watch
 * has used tafs in this sign-in, with the account mark; never when nothing was read, so the watch's list is never put empty
 * because one read was slow.
 */
class WatchTafsTest {
  private val noKeep = KeptPut { _, _ -> }
  private val mark = "mark_abcdefghijklmnop"
  private val loaded = testModel().state.value.copy(mode = Mode.kiff, listLoaded = true, watchServed = true)

  private fun tafs() = WatchTafs(noKeep, mark = { mark }, now = { 42L })

  @Test fun theListIsPutOnceAndAgainOnlyWhenItChanges() {
    val keeper = tafs()
    val (path, data) = keeper.changed(loaded)!!
    assertEquals(WatchLink.KEPT + "tafs", path)
    val json = Json.parseToJsonElement(data.decodeToString()).jsonObject
    assertEquals(42L, json.getValue("put_at").jsonPrimitive.long)
    assertEquals(mark, json.getValue("account_mark").jsonPrimitive.content)
    assertEquals(WatchRelay.tafs(loaded).tafs.size, json.getValue("tafs").jsonArray.size)
    assertEquals(false, json.getValue("all_halted").jsonPrimitive.boolean)
    assertNull("the same list again: nothing", keeper.changed(loaded))
    // A taf halted: the list changes, and it's put again.
    val first = loaded.tafs.first().id
    assertNotNull(keeper.changed(loaded.copy(halts = loaded.halts + (first to Halt(state = Halt.HALTED)))))
    // A name changed.
    assertNotNull(keeper.changed(loaded.copy(tafs = loaded.tafs.mapIndexed { i, t -> if (i == 0) t.copy(name = t.name + " 2") else t })))
  }

  @Test fun nothingIsPutBeforeTheWatchUsesTafsOrBeforeARead() {
    assertNull("the watch hasn't used tafs in this sign-in", tafs().changed(loaded.copy(watchServed = false)))
    assertNull("nothing read yet: never an empty list", tafs().changed(loaded.copy(listLoaded = false)))
    assertNull("signed out", tafs().changed(loaded.copy(mode = Mode.sample)))
    assertNull("the list couldn't be read", tafs().changed(loaded.copy(listUnread = true)))
  }

  @Test fun afterSignOutTheNextSignInsListIsPutWhole() {
    val keeper = tafs()
    keeper.changed(loaded)
    keeper.forget()
    assertNotNull(keeper.changed(loaded))
  }

  /** The first time the watch uses tafs, the list goes at once: being served is a change, not only the list's own. */
  @Test fun theFollowerPutsTheListAsSoonAsTheWatchIsServed() = kotlinx.coroutines.test.runTest {
    val puts = mutableListOf<String>()
    val keeper = WatchTafs(KeptPut { path, _ -> puts += path }, mark = { mark }, now = { 1L })
    val states = kotlinx.coroutines.flow.MutableStateFlow(loaded.copy(watchServed = false))
    keeper.follow(states, kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)))
    assertEquals(emptyList<String>(), puts)
    states.value = loaded
    assertEquals(listOf(WatchLink.KEPT + "tafs"), puts)
  }

  @Test fun noMarkIsWrittenThatIsntOne() {
    val data = KeptTafsJson.encode(WatchRelay.tafs(loaded), "not a mark", 1L)
    assertNull(Json.parseToJsonElement(data.decodeToString()).jsonObject["account_mark"])
  }
}
