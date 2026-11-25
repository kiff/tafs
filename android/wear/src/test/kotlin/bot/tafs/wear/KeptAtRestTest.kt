package bot.tafs.wear

import androidx.test.core.app.ApplicationProvider
import bot.tafs.watchlink.AskLine
import bot.tafs.watchlink.ChatLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Inbox and the kept chats are encrypted at rest, as the cache is (docs/watch-wear.md, "What the watch keeps"): AES-GCM under
 * one key, the Keystore's on a watch (a software key here, as Robolectric has no Keystore). What won't decrypt reads as none and
 * is cleared; 0.4.7's plain Inbox is taken in once and its plain copy cleared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WatchApplication::class)
class KeptAtRestTest {
  private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
  private val t = 1_763_244_000_000L
  private val secret = "Refund order 1042 to Maria"

  private fun file(name: String) = java.io.File(context.filesDir, name)
  private fun text(name: String, key: javax.crypto.SecretKey = this.key) = EncryptedText(KeystoreCacheStore(context, name, key = { key }))

  private val item = InboxItem(InboxItem.askId("wa_01"), InboxItem.Kind.ASK, "Refunds", secret, t)
  private val turn = KeptTurn(WatchModel.Said(secret, t), AskLine("wa_01", "w_00000001", AskLine.DONE, "Refunds", line = "Done: $secret", words = "Done", updated = "u"))

  @Test fun theInboxIsStoredEncryptedAndReadsBack() {
    val store = KeptInboxStore(text("inbox.bin"))
    store.write(listOf(item))
    store.writeDismissed(setOf("held:hold_1"))
    val stored = String(file("inbox.bin").readBytes(), Charsets.ISO_8859_1)
    assertFalse("no message text at rest", stored.contains(secret) || stored.contains("Refunds") || stored.contains("hold_1"))
    val again = KeptInboxStore(text("inbox.bin"))
    assertEquals(listOf(item), again.read())
    assertEquals(setOf("held:hold_1"), again.dismissed())
  }

  @Test fun theKeptChatsAreStoredEncryptedAndReadBack() {
    val store = KeptThreadStore(text("threads.bin"))
    val threads = mapOf(COACH_THREAD to KeptThread(t, turns = listOf(turn)), tafThread("taf_1") to KeptThread(t, lines = listOf(ChatLine("m1", true, "a", secret))))
    store.write(threads)
    val stored = String(file("threads.bin").readBytes(), Charsets.ISO_8859_1)
    assertFalse("no message text at rest", stored.contains(secret) || stored.contains("coach") || stored.contains("taf_1"))
    assertEquals(threads, KeptThreadStore(text("threads.bin")).read())
  }

  /** The key gone (the lock removed, a reinstall) or the file damaged: none, cleared, never a crash. */
  @Test fun whatWontDecryptReadsAsNoneAndIsCleared() {
    KeptThreadStore(text("threads.bin")).write(mapOf(COACH_THREAD to KeptThread(t, turns = listOf(turn))))
    KeptInboxStore(text("inbox.bin")).write(listOf(item))
    val other = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    assertTrue(KeptThreadStore(text("threads.bin", other)).read().isEmpty())
    assertFalse("cleared", file("threads.bin").exists())
    assertTrue(KeptInboxStore(text("inbox.bin", other)).read().isEmpty())
    assertFalse("cleared", file("inbox.bin").exists())
    file("threads.bin").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17))
    assertTrue(KeptThreadStore(text("threads.bin")).read().isEmpty())
    assertFalse(file("threads.bin").exists())
  }

  /** 0.4.7's Inbox, in plain preferences: taken in once, written encrypted, and the plain copy cleared. */
  @Test fun aPlainInboxFromBeforeIsTakenInOnceAndCleared() {
    val legacy = context.getSharedPreferences("tafs.inbox.test", android.content.Context.MODE_PRIVATE)
    legacy.edit().putString("items", KeptInboxStore.encode(listOf(item))).putString("dismissed", """["held:hold_1"]""").commit()
    val store = KeptInboxStore(text("migrated.bin"), legacy)
    assertEquals(listOf(item), store.read())
    assertEquals(setOf("held:hold_1"), store.dismissed())
    assertTrue("the plain copy is gone", legacy.all.isEmpty())
    assertFalse(String(file("migrated.bin").readBytes(), Charsets.ISO_8859_1).contains(secret))
    // Taken in once: a second start finds nothing plain to take.
    assertEquals(listOf(item), KeptInboxStore(text("migrated.bin"), legacy).read())
  }
}
