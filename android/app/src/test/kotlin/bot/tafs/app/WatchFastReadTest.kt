package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.watch.RefreshFloor
import bot.tafs.app.watch.WatchRelay
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import bot.tafs.watchlink.WatchRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's reads answer within its 10 s (docs/watch-wear.md): the owner's Galaxy S23 took 15 s, restoring its session and
 * reading tafs cloud and KIFF before it answered, and the watch drew its kept list, then nothing. Holding a read, the phone
 * answers from it at once and the fresh one lands for the next ask; holding none, it waits within the limit, then says "not
 * now", never an empty list or a count of 0.
 */
class WatchFastReadTest {
  private val base = 1_764_090_000_000L
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()

  private fun reads() {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
  }

  private suspend fun TestScope.restored(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    return testModel(transport = transport, secrets = secrets, start = false, scope = backgroundScope).also { it.restore(onScreen = false) }
  }

  private fun TestScope.relay(model: suspend () -> AppModel?) =
    WatchRelay(model = model, now = { base + testScheduler.currentTime }, floor = RefreshFloor(), background = backgroundScope)

  private fun TestScope.ask(r: (Long) -> WatchRequest) = WatchLink.encode(r(base + testScheduler.currentTime))

  @Test fun holdingARead_needsYouAnswersUnderASecondWhileTheCloudIsSlow() = runTest {
    reads()
    val model = restored()
    // tafs cloud stops answering: the fresh read the watch's ask starts hangs.
    transport.hold("GET /v1/home")
    val relay = relay { model }
    val started = testScheduler.currentTime
    val (path, data) = ask { WatchRequest.NeedsYou(it) }
    assertTrue(relay.answer(path, data) is WatchReply.NeedsYou)
    assertTrue("answered from what the phone holds, under a second", testScheduler.currentTime - started < 1_000)
    transport.release("GET /v1/home")
  }

  @Test fun theTafListIsntBlockedBehindNeedsYou() = runTest {
    reads()
    val model = restored()
    transport.hold("GET /v1/home")
    val relay = relay { model }
    val started = testScheduler.currentTime
    val (nPath, nData) = ask { WatchRequest.NeedsYou(it) }
    val (tPath, tData) = ask { WatchRequest.Tafs(it) }
    val needsYou = async { relay.answer(nPath, nData) }
    val list = async { relay.answer(tPath, tData) }
    assertTrue(list.await() is WatchReply.Tafs)
    assertTrue(needsYou.await() is WatchReply.NeedsYou)
    assertTrue("both within a second", testScheduler.currentTime - started < 1_000)
    transport.release("GET /v1/home")
  }

  @Test fun aPhoneStillRestoringSaysNotNowInTime() = runTest {
    reads()
    val model = restored()
    // A restore that takes longer than the watch waits (a phone woken from cold).
    val relay = relay { delay(20_000); model }
    val started = testScheduler.currentTime
    val (path, data) = ask { WatchRequest.Tafs(it) }
    assertEquals(WatchReply.Failed(bot.tafs.app.model.Words.Watch.openOnPhone), relay.answer(path, data))
    assertTrue("within the watch's 10 s", testScheduler.currentTime - started < 10_000)
  }

  @Test fun nothingReadYetIsNeverAnEmptyListOrACountOfZero() = runTest {
    reads()
    transport.reset()
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage"]}""")
    transport.drop("GET /v1/home")
    val model = restored()
    val relay = relay { model }
    for (r in listOf<(Long) -> WatchRequest>({ WatchRequest.Tafs(it) }, { WatchRequest.NeedsYou(it) })) {
      val (path, data) = ask(r)
      assertEquals(WatchReply.Failed(bot.tafs.app.model.Words.Watch.openOnPhone), relay.answer(path, data))
    }
  }
}
