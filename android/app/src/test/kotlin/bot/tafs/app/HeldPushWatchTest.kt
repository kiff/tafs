package bot.tafs.app

import bot.tafs.app.push.AlertPayload
import bot.tafs.app.watch.KeptPut
import bot.tafs.app.watch.WatchResults
import bot.tafs.watchlink.WatchLink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding 2: a held call's alert reached the phone with its app in the background, but never the wrist, because nothing read
 * Needs you again until the app was opened. Now a held call's alert, or its answer, starts a job that reads it and keeps it for
 * the watch.
 */
class HeldPushWatchTest {
  private class Kept : KeptPut {
    val put = mutableListOf<String>()
    override suspend fun put(path: String, data: ByteArray) { put += path }
  }

  @Test fun aHeldCallsAlertOrAnswerIsAboutAHeldCall() {
    assertTrue(AlertPayload.heldCall(mapOf("kind" to "alert", "hold_id" to "h_1", "title" to "A taf needs you")))
    assertTrue(AlertPayload.heldCall(mapOf("kind" to "answered", "hold_id" to "h_1")))
    // Anything else leaves Needs you as it is: no read for it.
    assertFalse(AlertPayload.heldCall(mapOf("kind" to "alert", "title" to "Support replies answered")))
    assertFalse(AlertPayload.heldCall(mapOf("kind" to "answered", "request_id" to "r_1")))
    assertFalse(AlertPayload.heldCall(mapOf("kind" to "background", "change" to "watch_ask", "ask_id" to "a_1", "hold_id" to " ")))
  }

  @Test fun theJobKeepsNeedsYouForTheWatchWhenItChanged() = runTest {
    val kept = Kept()
    val results = WatchResults(readAsk = { null }, readMarks = { null }, keep = kept,
      readNeedsYou = { WatchLink.KEPT_NEEDS_YOU to ByteArray(1) })
    assertTrue(results.needsYou())
    assertEquals(listOf(WatchLink.KEPT_NEEDS_YOU), kept.put)
  }

  @Test fun nothingIsPutWhenThereIsNothingNewOrNoWatch() = runTest {
    val kept = Kept()
    // Signed out, no watch served in this sign-in, or Needs you as last put: no item.
    val results = WatchResults(readAsk = { null }, readMarks = { null }, keep = kept, readNeedsYou = { null })
    assertFalse(results.needsYou())
    assertTrue(kept.put.isEmpty())
  }
}
