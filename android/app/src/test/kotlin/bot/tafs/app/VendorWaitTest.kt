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

/**
 * A vendor's sign-in the owner came back from with no link is marked left, never dropped: on Android 97 the owner went to their
 * authenticator for a code, came back to tafs from the launcher, and "The sign-in was closed" while Google's page still waited.
 * The card then offers the page again, and Cancel; 15 minutes end it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VendorWaitTest {
  @Test fun backWithNoLinkIsLeftNotDropped() = runTest {
    val wait = VendorWait()
    val waiting = wait.start()
    wait.paused() // the tab took the screen
    wait.resumed(this) // the app is back, with no link
    advanceTimeBy(VendorWait.RETURN_GRACE_MS - 1)
    runCurrent()
    assertFalse("a link may still arrive within the grace", wait.left.value)
    advanceTimeBy(2)
    runCurrent()
    assertTrue(wait.left.value)
    assertFalse("the sign-in still waits", waiting.isCompleted)
    // The page opened again: back on the vendor's page, and its link still counts.
    wait.reopened()
    assertFalse(wait.left.value)
    wait.deliver("tafs://planque/signed-in?code=c")
    assertEquals("tafs://planque/signed-in?code=c", waiting.await())
  }

  @Test fun cancelEndsALeftSignIn() = runTest {
    val wait = VendorWait()
    val waiting = wait.start()
    val result = async { runCatching { waiting.await() } }
    wait.paused()
    wait.resumed(this)
    advanceTimeBy(VendorWait.RETURN_GRACE_MS + 1)
    runCurrent()
    wait.cancel()
    assertTrue(result.await().exceptionOrNull() is VendorSignInCancelled)
    wait.done(waiting)
    assertFalse(wait.left.value)
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
