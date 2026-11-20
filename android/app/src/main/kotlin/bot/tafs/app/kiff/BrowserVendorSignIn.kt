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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference

/**
 * The vendor's sign-in page in a Chrome Custom Tab. planque sends the owner back to `tafs://planque/signed-in?...`, which the
 * app's activity catches and hands to [deliver]; the tab is the vendor's own, so the phone never sees their credentials. A sign-in
 * left unfinished for 15 minutes (how long planque takes the confirmation) is closed. The app back in front with no link from the
 * vendor ([resumed]) is [left]: the owner may have closed the tab, or gone to another app for a code and come back to tafs from the
 * launcher, which closes the tab with Google's page still waiting (the owner on Android 97). Nothing is dropped: the card offers
 * to open it again and Cancel. Opening it again runs the card's approval once more ([replace]), since a vendor's link is single-use
 * once its sign-in reached planque; [reopen] opens the same page only where no new approval can run.
 *
 * The tab opens from the app's own screen ([attach]), in tafs's task: an owner who leaves for another app mid-way (an
 * authenticator for a code) and comes back to tafs from the launcher comes back to the vendor's tab, not to tafs's screen, which
 * would read as the tab closed (the owner on Android 97). Only with no screen to open it from does it take a task of its own.
 */
class BrowserVendorSignIn(
  private val context: Context,
  /**
   * KIFF's own sign-in tab (docs/onboarding.md): a private browsing session where the browser offers one, so no cookie or earlier
   * sign-in comes in and every authorize goes through KIFF's form, as iOS's private sheet. A vendor's tab stays shared, so an
   * owner already signed in to the vendor only allows access. A browser without it shares its cookies, and KIFF's server holds.
   */
  private val privateBrowsing: Boolean = false,
) : VendorSignIn {
  private val wait = VendorWait()
  @Volatile private var url: String? = null
  @Volatile private var screen: WeakReference<Activity>? = null
  @Volatile private var launch: ((Intent) -> Unit)? = null

  /**
   * The screen the vendor's tab opens from, so it stays in tafs's task; [forResult] opens it for a result, which comes back once
   * the tab is gone ([closed]), never while it's still there behind another app.
   */
  fun attach(activity: Activity, forResult: ((Intent) -> Unit)? = null) { screen = WeakReference(activity); launch = forResult }

  fun detach(activity: Activity) { if (screen?.get() === activity) { screen = null; launch = null } }

  override suspend fun signIn(url: String): String {
    // Only a vendor's secure page is opened.
    if (!url.startsWith("https://")) throw VendorSignInCancelled()
    val waiting = wait.start()
    this.url = url
    try {
      open(url)
      return withTimeout(FIFTEEN_MINUTES_MS) { waiting.await() }
    } catch (_: TimeoutCancellationException) {
      throw VendorSignInCancelled()
    } finally {
      wait.done(waiting)
      if (!wait.isWaiting) this.url = null
    }
  }

  override val left: StateFlow<Boolean> get() = wait.left

  override fun reopen() {
    val again = url ?: return
    if (!wait.isWaiting) return
    wait.reopened()
    open(again)
  }

  override fun replace() = wait.replace()

  private fun open(url: String) {
    val browser = if (privateBrowsing) androidx.browser.customtabs.CustomTabsClient.getPackageName(context, null) else null
    val tab = tabIntent(browser?.takeIf { androidx.browser.customtabs.CustomTabsClient.isEphemeralBrowsingSupported(context, it) })
    val from = screen?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
    val forResult = launch
    if (from != null && forResult != null) forResult(tab.intent.setData(Uri.parse(url)))
    else if (from != null) tab.launchUrl(from, Uri.parse(url))
    else {
      tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      tab.launchUrl(context, Uri.parse(url))
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

  /** The vendor's tab is gone (its result came back): with no link by then, the sign-in is [left], with Open again and Cancel. */
  fun closed(scope: CoroutineScope) = wait.closed(scope)

  /** Whether a sign-in is waiting on the vendor's page. */
  val isWaiting: Boolean get() = wait.isWaiting

  private companion object {
    const val FIFTEEN_MINUTES_MS = 15 * 60 * 1000L
  }
}

/**
 * The wait for a vendor's sign-in to come back, apart from the browser so it can be tested. The link arrives with the activity's
 * new intent, before it resumes; [RETURN_GRACE_MS] covers a link that arrives just after. Only a resume after the app left the
 * screen while waiting counts: the fingerprint's dialog before the tab opens doesn't. A resume with no link marks the sign-in
 * [left] and keeps waiting: the owner reopens the page or cancels, and 15 minutes end it.
 */
/**
 * The tab's intent: private in [privateBrowser], the browser that said it offers private browsing, and opened in that same
 * browser so it is the one that keeps the session private; else an ordinary tab in the person's browser.
 */
internal fun tabIntent(privateBrowser: String?): CustomTabsIntent {
  val builder = CustomTabsIntent.Builder()
  if (privateBrowser != null) builder.setEphemeralBrowsingEnabled(true)
  return builder.build().also { tab -> privateBrowser?.let { tab.intent.setPackage(it) } }
}

class VendorWait {
  @Volatile private var waiting: CompletableDeferred<String>? = null
  @Volatile private var away = false
  private val _left = MutableStateFlow(false)
  val left: StateFlow<Boolean> get() = _left

  fun start(): CompletableDeferred<String> = CompletableDeferred<String>().also { waiting = it; away = false; _left.value = false }

  fun done(of: CompletableDeferred<String>) {
    if (waiting === of) { waiting = null; away = false; _left.value = false }
  }

  /** The page was opened again: the sign-in is back on the vendor's page. */
  fun reopened() {
    if (waiting != null) { away = false; _left.value = false }
  }

  fun deliver(link: String) {
    waiting?.complete(link)
  }

  fun cancel() {
    waiting?.completeExceptionally(VendorSignInCancelled())
  }

  /** The sign-in left is given up for a new approval: the wait ends with [VendorSignInReplaced]. */
  fun replace() {
    waiting?.completeExceptionally(VendorSignInReplaced())
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
      if (waiting === of && !of.isCompleted) _left.value = true
    }
  }

  /**
   * The vendor's tab is gone: closed with back or its ✕, or above tafs's `singleTask` screen when the owner came back from the
   * launcher. The link may still arrive within [RETURN_GRACE_MS]; with none, the sign-in is marked [left] whether or not a resume
   * was seen, so the card or sheet offers Open again and Cancel and is never left on "Approving…" with no way back. Nothing is
   * dropped: the launcher's case is one where Google's page was still waiting (the owner on Android 97).
   */
  fun closed(scope: CoroutineScope) {
    val of = waiting ?: return
    away = false
    scope.launch {
      delay(RETURN_GRACE_MS)
      if (waiting === of && !of.isCompleted) _left.value = true
    }
  }

  val isWaiting: Boolean get() = waiting != null

  companion object {
    /** How long after the app is back in front a vendor's link may still arrive. */
    const val RETURN_GRACE_MS = 1_500L
  }
}
