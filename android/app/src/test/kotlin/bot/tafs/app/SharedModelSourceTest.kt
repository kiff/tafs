package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.app.RestoreOnce
import bot.tafs.app.kiff.InMemorySecretStore
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
    assertTrue(activity.contains("app.restoredModel(onScreen = true)"))
    assertTrue("the watch's restore asks nobody anything", source("watch/WearRelayService.kt").contains("app.restoredModel(onScreen = false)"))
  }

  @Test fun itsRestoredOnceWhoeverAsksFirst() = runTest {
    var restored = 0
    val once = RestoreOnce({ restored++ })
    (1..5).map { async { once.run(onScreen = true) } }.awaitAll()
    once.run(onScreen = true)
    once.run(onScreen = false)
    assertEquals(1, restored)
  }

  @Test fun aRestoreThatFailedIsAskedAgain() = runTest {
    var tries = 0
    val once = RestoreOnce({ if (++tries == 1) error("the network dropped") })
    runCatching { once.run(onScreen = true) }
    once.run(onScreen = true)
    once.run(onScreen = true)
    assertEquals(2, tries)
  }

  @Test fun theWatchsRestoreLeavesWhatNeedsThePersonForTheScreensFirstAsk() = runTest {
    val asked = mutableListOf<String>()
    val once = RestoreOnce({ onScreen -> asked += "restore onScreen=$onScreen" }, { asked += "finish on screen" })
    repeat(3) { once.run(onScreen = false) }
    assertEquals(listOf("restore onScreen=false"), asked)
    (1..3).map { async { once.run(onScreen = true) } }.awaitAll()
    once.run(onScreen = false)
    once.run(onScreen = true)
    assertEquals(listOf("restore onScreen=false", "finish on screen"), asked)
  }

  /**
   * A session from before the approval key (kiff/tafs#233) registers it behind the fingerprint. The watch reading first, with
   * the app closed, never tries it (nobody could answer the prompt), and never uses it up: the screen's first ask registers it,
   * once.
   */
  @Test fun aWatchReadFirstLeavesTheApprovalKeyForTheScreenToRegisterOnce() = runTest {
    val transport = FakeTransport()
    val secrets = InMemorySecretStore()
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    val register = "POST /v1/me/device/approval-key"
    transport.answer(register, 200, """{"fingerprint":"0000 1111 2222 3333"}""")
    val model = testModel(transport = transport, secrets = secrets, start = false, scope = backgroundScope)
    val once = RestoreOnce({ onScreen -> model.restore(onScreen) }, { model.finishRestoreOnScreen() })

    once.run(onScreen = false)
    assertEquals(bot.tafs.app.app.Mode.kiff, model.state.value.mode)
    assertTrue("the watch's restore signs nothing", transport.requestsTo(register).isEmpty())

    once.run(onScreen = true)
    once.run(onScreen = true)
    assertEquals("registered once, on the screen's turn", 1, transport.requestsTo(register).size)
  }

  @Test fun withNoSessionTheRelayAnswersSignedOutAndDoesntRestoreAgain() = runTest {
    var restored = 0
    val once = RestoreOnce({ restored++ })
    val now = 1_763_244_000_000L
    // No session: what the restored app holds is signed out.
    val relay = WatchRelay(model = { once.run(onScreen = false); null }, now = { now })
    repeat(3) {
      val (path, data) = WatchLink.encode(WatchRequest.NeedsYou(now))
      assertEquals(WatchReply.SignedOut, relay.answer(path, data))
    }
    assertEquals(1, restored)
  }
}
