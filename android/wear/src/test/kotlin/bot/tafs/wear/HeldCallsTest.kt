package bot.tafs.wear

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import bot.tafs.watchlink.HeldLine
import bot.tafs.watchlink.KeptNeedsYou
import bot.tafs.watchlink.WatchLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A held call pushed by the phone (`kept/needs-you`, docs/watch-wear.md, Delivery, rows 1–3): its line at the top of the Inbox, the
 * tile's count and the watch's own alert, without the watch asking; gone when the phone no longer lists it; under the account mark
 * only; nothing kept on a locked watch, whose alert says only how many. Never an amount kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WatchApplication::class)
class HeldCallsTest {
  private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val t = 1_763_244_000_000L
  private val markA = "AAAAAAAAAAAAAAAAAAAAAA"
  private val markB = "BBBBBBBBBBBBBBBBBBBBBB"

  @Before fun words() = Res.install(context)

  private val refund = HeldLine("hold_1", "Stripe refunds", "Refund a charge in EUR · €10.00", refusable = true)
  private val ask = InboxItem(InboxItem.askId("wa_1"), InboxItem.Kind.ASK, "Wiki lookup", "Toulouse is in France.", t - 1_000)

  private class Inbox(var items: List<InboxItem> = emptyList(), var gone: Set<String> = emptySet()) : InboxStore {
    override fun read() = items
    override fun write(items: List<InboxItem>) { this.items = items }
    override fun dismissed() = gone
    override fun writeDismissed(ids: Set<String>) { gone = ids }
    override fun clear() { items = emptyList(); gone = emptySet() }
  }

  private class Tile : TileCount.Store {
    var kept: Pair<Int, Long>? = null
    override fun read() = kept
    override fun write(count: Int, at: Long) { kept = count to at }
    override fun clear() { kept = null }
  }

  private class Marks(var mark: String?) : MarkStore {
    override fun read() = mark
    override fun write(mark: String) { this.mark = mark }
    override fun clear() { mark = null }
  }

  private fun alerts() = context.getSystemService(NotificationManager::class.java)!!.activeNotifications.filter { it.notification.channelId == HeldCalls.CHANNEL }

  @Test fun aHeldCallGoesFirstInTheInboxWithNoAmountAndAnEndedOneLeaves() {
    val first = HeldCalls.inbox(listOf(ask), emptySet(), listOf(refund), t)!!
    assertEquals(listOf("held:hold_1", "ask:wa_1"), first.map { it.id })
    assertEquals("Stripe refunds", first.first().title)
    assertTrue("no amount kept", !first.first().line.contains("10"))
    assertNull("the same push again: nothing changes", HeldCalls.inbox(first, emptySet(), listOf(refund), t + 5))
    // Answered, refused or run out: the phone lists it no more, and its line goes; the ask's stays.
    assertEquals(listOf("ask:wa_1"), HeldCalls.inbox(first, emptySet(), emptyList(), t + 9)!!.map { it.id })
    // Taken away on the watch: it never comes back while the phone still lists it.
    assertNull(HeldCalls.inbox(listOf(ask), setOf("held:hold_1"), listOf(refund), t))
  }

  @Test fun onlyACallNewToTheWatchIsFresh() {
    assertEquals(setOf("hold_1"), HeldCalls.fresh(listOf(ask), emptySet(), listOf(refund)))
    assertEquals(emptySet<String>(), HeldCalls.fresh(listOf(HeldCalls.line(refund, t), ask), emptySet(), listOf(refund)))
    assertEquals(emptySet<String>(), HeldCalls.fresh(listOf(ask), setOf("held:hold_1"), listOf(refund)))
  }

  @Test fun thePushReachesTheInboxTheTileAndAnAlertWithTheAppClosed() {
    val inbox = Inbox(listOf(ask))
    val tile = Tile()
    val stores = KeptResults.Stores(thread = null, inbox = inbox, secure = true, signedOut = false, marks = Marks(markA), tile = tile)
    KeptResults.needsYou(context, KeptNeedsYou(t, 1, "1 held call needs you", listOf(refund), markA), stores, now = t)
    assertEquals("held:hold_1", inbox.items.first().id)
    assertEquals(1 to t, tile.kept)
    val alert = alerts().single()
    assertEquals("held:hold_1", alert.tag)
    assertEquals("Stripe refunds", alert.notification.extras.getString(android.app.Notification.EXTRA_TITLE))
    // The same push again: no second alert. Then the call ends: its alert goes with its line.
    KeptResults.needsYou(context, KeptNeedsYou(t + 5, 1, "1 held call needs you", listOf(refund), markA), stores, now = t + 5)
    assertEquals(1, alerts().size)
    KeptResults.needsYou(context, KeptNeedsYou(t + 9, 0, "Nothing needs you", emptyList(), markA), stores, now = t + 9)
    assertTrue(alerts().isEmpty())
    assertEquals(listOf("ask:wa_1"), inbox.items.map { it.id })
    assertEquals(0 to t + 9, tile.kept)
  }

  @Test fun aLockedWatchKeepsNothingAndItsAlertSaysOnlyHowMany() {
    val inbox = Inbox()
    val tile = Tile()
    val stores = KeptResults.Stores(thread = null, inbox = inbox, secure = false, signedOut = false, marks = Marks(markA), tile = tile)
    KeptResults.needsYou(context, KeptNeedsYou(t, 1, "1 held call needs you", listOf(refund), markA), stores, now = t)
    assertTrue("nothing kept", inbox.items.isEmpty())
    assertNull("no count kept", tile.kept)
    val alert = alerts().single()
    assertEquals(Words.Watch.heldCalls(1), alert.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
    assertTrue("never the taf", alert.notification.extras.getString(android.app.Notification.EXTRA_TITLE) != "Stripe refunds")
  }

  @Test fun anotherSignInsPushIsDropped() {
    val inbox = Inbox()
    val stores = KeptResults.Stores(thread = null, inbox = inbox, secure = true, signedOut = false, marks = Marks(markA), tile = Tile())
    val item = WatchLink.encodeKept(KeptNeedsYou(t, 1, "1 held call needs you", listOf(refund), markB))
    KeptResults.kept(context, WatchLink.KEPT_NEEDS_YOU, item, stores) { true }
    assertTrue(inbox.items.isEmpty())
    assertTrue(alerts().isEmpty())
    // Under the mark held, it's taken in.
    KeptResults.kept(context, WatchLink.KEPT_NEEDS_YOU, WatchLink.encodeKept(KeptNeedsYou(t, 1, "", listOf(refund), markA)), stores) { true }
    assertEquals(listOf("held:hold_1"), inbox.items.map { it.id })
  }

  @Test fun signedOutOnTheWatchNothingIsTakenOrShown() {
    val inbox = Inbox()
    val stores = KeptResults.Stores(thread = null, inbox = inbox, secure = true, signedOut = true, marks = Marks(markA), tile = Tile())
    KeptResults.needsYou(context, KeptNeedsYou(t, 1, "", listOf(refund), markA), stores, now = t)
    assertTrue(inbox.items.isEmpty())
    assertTrue(alerts().isEmpty())
  }
}
