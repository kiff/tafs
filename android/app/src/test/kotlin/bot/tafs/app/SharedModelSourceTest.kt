package bot.tafs.app

import bot.tafs.app.app.RestoreOnce
import bot.tafs.app.watch.WatchRelay
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's one model for the screen and the Wear OS watch's relay (docs/watch-wear.md): restored once, whoever asks first, and
 * never an activity's ViewModel, whose store would clear it, and cancel its work, when the activity finishes.
 */
class SharedModelSourceTest {
  private fun source(path: String) = File("src/main/kotlin/bot/tafs/app/$path").readText().replace("\r\n", "\n")

  @Test fun theScreenAndTheRelayTakeTheAppsModel() {
    val activity = source("MainActivity.kt")
    assertTrue(activity.contains("val model: AppModel = app.model"))
    assertFalse("never through a ViewModelStore, which clears it when the activity finishes", activity.contains("viewModel(factory"))
    assertTrue(activity.contains("app.restoredModel()"))
    assertTrue(source("watch/WearRelayService.kt").contains("app.restoredModel()"))
  }

  @Test fun itsRestoredOnceWhoeverAsksFirst() = runTest {
    var restored = 0
    val once = RestoreOnce { restored++ }
    (1..5).map { async { once.run() } }.awaitAll()
    once.run()
    assertEquals(1, restored)
  }

  @Test fun aRestoreThatFailedIsAskedAgain() = runTest {
    var tries = 0
    val once = RestoreOnce { if (++tries == 1) error("the network dropped") }
    runCatching { once.run() }
    once.run()
    once.run()
    assertEquals(2, tries)
  }

  @Test fun withNoSessionTheRelayAnswersSignedOutAndDoesntRestoreAgain() = runTest {
    var restored = 0
    val once = RestoreOnce { restored++ }
    val now = 1_763_244_000_000L
    // No session: what the restored app holds is signed out.
    val relay = WatchRelay(model = { once.run(); null }, now = { now })
    repeat(3) {
      val (path, data) = WatchLink.encode(WatchRequest.NeedsYou(now))
      assertEquals(WatchReply.SignedOut, relay.answer(path, data))
    }
    assertEquals(1, restored)
  }
}
