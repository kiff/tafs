package bot.tafs.wear

import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Row 44, D: a phone that's there isn't called unreachable, and a write is never sent twice. */
class PhoneRouteTest {
  private val reply = byteArrayOf(1)
  private val read = WatchRequest.NeedsYou(0)
  private val write = WatchRequest.Refuse(0, "hold_1")

  private class Phone(var nodes: MutableList<String?>, val fails: MutableSet<String> = mutableSetOf()) {
    var lookups = 0
    val sent = mutableListOf<String>()
    suspend fun lookup(): String? { lookups++; return if (nodes.isEmpty()) null else nodes.removeAt(0) }
    suspend fun send(node: String, path: String, data: ByteArray): ByteArray {
      sent += node
      if (node in fails) throw IllegalStateException("gone")
      return byteArrayOf(1)
    }
  }

  private fun route(p: Phone) = PhoneRoute(p::lookup, p::send, settleMs = 0)

  @Test fun thePhoneReachedLastIsAskedWithNoLookup() = runTest {
    val p = Phone(mutableListOf("n1"))
    val r = route(p)
    assertTrue(r.ask(read, "/p", reply) is PhoneRoute.Tried.Reply)
    assertTrue(r.ask(read, "/p", reply) is PhoneRoute.Tried.Reply)
    assertEquals("one lookup for two asks", 1, p.lookups)
    assertEquals(listOf("n1", "n1"), p.sent)
  }

  @Test fun noPhoneAtFirstIsLookedForOnceMore() = runTest {
    val p = Phone(mutableListOf(null, "n1"))
    val tried = route(p).ask(read, "/p", reply)
    assertEquals(PhoneRoute.Tried.Reply::class, tried::class)
    assertTrue((tried as PhoneRoute.Tried.Reply).retried)
    assertEquals(2, p.lookups)
  }

  @Test fun noPhoneTwiceIsNoPhone() = runTest {
    val p = Phone(mutableListOf(null, null))
    assertEquals(PhoneRoute.Tried.NoPhone(retried = true), route(p).ask(read, "/p", reply))
    assertTrue(p.sent.isEmpty())
  }

  @Test fun aReadThatFailedGoesOnceMoreToAPhoneFoundAfresh() = runTest {
    val p = Phone(mutableListOf("old", "new"), fails = mutableSetOf("old"))
    val r = route(p)
    val tried = r.ask(read, "/p", reply)
    assertTrue(tried is PhoneRoute.Tried.Reply && tried.retried)
    assertEquals(listOf("old", "new"), p.sent)
    // The new one is remembered.
    r.ask(read, "/p", reply)
    assertEquals(listOf("old", "new", "new"), p.sent)
  }

  @Test fun aWriteThatFailedIsNeverSentTwice() = runTest {
    for (w in listOf(write, WatchRequest.Halt(0, null), WatchRequest.Coach(0, "hi"))) {
      val p = Phone(mutableListOf("n1", "n2"), fails = mutableSetOf("n1"))
      assertTrue(route(p).ask(w, "/p", reply) is PhoneRoute.Tried.Failed)
      assertEquals(listOf("n1"), p.sent)
    }
  }

  @Test fun aFailedPhoneIsForgottenAndLookedUpNextTime() = runTest {
    val p = Phone(mutableListOf("n1", "n2"), fails = mutableSetOf("n1"))
    val r = route(p)
    assertTrue(r.ask(write, "/p", reply) is PhoneRoute.Tried.Failed)
    assertTrue(r.ask(write, "/p", reply) is PhoneRoute.Tried.Reply)
    assertEquals(listOf("n1", "n2"), p.sent)
  }
}
