package bot.tafs.app

import bot.tafs.app.kiff.VendorSignInCancelled
import bot.tafs.app.kiff.VendorWait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A vendor's sign-in left in its tab lets go once the app is back, so no other approval waits 15 minutes behind it. */
@OptIn(ExperimentalCoroutinesApi::class)
class VendorWaitTest {
  @Test fun theTabClosedWithNoLinkLetsTheSignInGo() = runTest {
    val wait = VendorWait()
    val waiting = wait.start()
    val result = async { runCatching { waiting.await() } }
    wait.paused() // the tab took the screen
    wait.resumed(this) // the owner closed it and the app is back
    advanceTimeBy(VendorWait.RETURN_GRACE_MS - 1)
    runCurrent()
    assertFalse("a link may still arrive within the grace", waiting.isCompleted)
    advanceTimeBy(2)
    runCurrent()
    assertTrue(result.await().exceptionOrNull() is VendorSignInCancelled)
  }

  @Test fun aLinkThatCameBackWinsOverTheResume() = runTest {
    val wait = VendorWait()
    val waiting = wait.start()
    wait.paused()
    wait.deliver("tafs://planque/signed-in?code=c") // the new intent comes before the resume
    wait.resumed(this)
    advanceTimeBy(VendorWait.RETURN_GRACE_MS + 1)
    runCurrent()
    assertEquals("tafs://planque/signed-in?code=c", waiting.await())
  }

  @Test fun aResumeBeforeTheTabTookTheScreenLetsNothingGo() = runTest {
    val wait = VendorWait()
    val waiting = wait.start()
    wait.resumed(this) // the fingerprint's dialog closing, before the tab opened
    advanceTimeBy(VendorWait.RETURN_GRACE_MS + 1)
    runCurrent()
    assertFalse(waiting.isCompleted)
    wait.deliver("tafs://planque/signed-in?code=c")
    assertEquals("tafs://planque/signed-in?code=c", waiting.await())
  }

  @Test fun anEndedWaitIgnoresLateEvents() = runTest {
    val wait = VendorWait()
    val first = wait.start()
    wait.deliver("tafs://planque/signed-in?code=a")
    wait.done(first)
    assertFalse(wait.isWaiting)
    wait.paused()
    wait.resumed(this)
    val second = wait.start()
    advanceTimeBy(VendorWait.RETURN_GRACE_MS + 1)
    runCurrent()
    assertFalse("a resume from before this sign-in doesn't end it", second.isCompleted)
  }
}
