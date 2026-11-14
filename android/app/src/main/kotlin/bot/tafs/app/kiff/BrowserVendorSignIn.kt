package bot.tafs.app.kiff

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference

/**
 * The vendor's sign-in page in a Chrome Custom Tab. planque sends the owner back to `tafs://planque/signed-in?...`, which the
 * app's activity catches and hands to [deliver]; the tab is the vendor's own, so the phone never sees their credentials. A sign-in
 * left unfinished for 15 minutes (how long planque takes the confirmation) is closed, and so is one the owner left: the app back
 * in front with no link from the vendor ([resumed]) means the tab was closed, so the step lets go and the sign-in is offered again,
 * instead of every other approval waiting on it.
 *
 * The tab opens from the app's own screen ([attach]), in tafs's task: an owner who leaves for another app mid-way (an
 * authenticator for a code) and comes back to tafs from the launcher comes back to the vendor's tab, not to tafs's screen, which
 * would read as the tab closed (the owner on Android 97). Only with no screen to open it from does it take a task of its own.
 */
class BrowserVendorSignIn(private val context: Context) : VendorSignIn {
  private val wait = VendorWait()
  @Volatile private var screen: WeakReference<Activity>? = null

  /** The screen the vendor's tab opens from, so it stays in tafs's task. */
  fun attach(activity: Activity) { screen = WeakReference(activity) }

  fun detach(activity: Activity) { if (screen?.get() === activity) screen = null }

  override suspend fun signIn(url: String): String {
    // Only a vendor's secure page is opened.
    if (!url.startsWith("https://")) throw VendorSignInCancelled()
    val waiting = wait.start()
    try {
      val tab = CustomTabsIntent.Builder().build()
      val from = screen?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
      if (from != null) tab.launchUrl(from, Uri.parse(url))
      else {
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tab.launchUrl(context, Uri.parse(url))
      }
      return withTimeout(FIFTEEN_MINUTES_MS) { waiting.await() }
    } catch (_: TimeoutCancellationException) {
      throw VendorSignInCancelled()
    } finally {
      wait.done(waiting)
    }
  }

  /** The link the vendor's sign-in came back to. */
  fun deliver(link: String) = wait.deliver(link)

  /** The owner gave up waiting. */
  fun cancel() = wait.cancel()

  /** The app left the screen: the vendor's tab took it, or anything else did. */
  fun paused() = wait.paused()

  /** The app is back in front: with no link from the vendor by then, the sign-in was left, and it lets go. */
  fun resumed(scope: CoroutineScope) = wait.resumed(scope)

  /** Whether a sign-in is waiting on the vendor's page. */
  val isWaiting: Boolean get() = wait.isWaiting

  private companion object {
    const val FIFTEEN_MINUTES_MS = 15 * 60 * 1000L
  }
}

/**
 * The wait for a vendor's sign-in to come back, apart from the browser so it can be tested. The link arrives with the activity's
 * new intent, before it resumes; [RETURN_GRACE_MS] covers a link that arrives just after. Only a resume after the app left the
 * screen while waiting counts: the fingerprint's dialog before the tab opens doesn't.
 */
class VendorWait {
  @Volatile private var waiting: CompletableDeferred<String>? = null
  @Volatile private var away = false

  fun start(): CompletableDeferred<String> = CompletableDeferred<String>().also { waiting = it; away = false }

  fun done(of: CompletableDeferred<String>) {
    if (waiting === of) { waiting = null; away = false }
  }

  fun deliver(link: String) {
    waiting?.complete(link)
  }

  fun cancel() {
    waiting?.completeExceptionally(VendorSignInCancelled())
  }

  fun paused() {
    if (waiting != null) away = true
  }

  fun resumed(scope: CoroutineScope) {
    val of = waiting ?: return
    if (!away) return
    away = false
    scope.launch {
      delay(RETURN_GRACE_MS)
      if (waiting === of && !of.isCompleted) of.completeExceptionally(VendorSignInCancelled())
    }
  }

  val isWaiting: Boolean get() = waiting != null

  companion object {
    /** How long after the app is back in front a vendor's link may still arrive. */
    const val RETURN_GRACE_MS = 1_500L
  }
}
