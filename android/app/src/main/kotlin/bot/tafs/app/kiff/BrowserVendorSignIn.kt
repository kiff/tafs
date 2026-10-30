package bot.tafs.app.kiff

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The vendor's sign-in page in a Chrome Custom Tab. planque sends the owner back to `tafs://planque/signed-in?...`, which the
 * app's activity catches and hands to [deliver]; the tab is the vendor's own, so the phone never sees their credentials. A sign-in
 * left unfinished for 15 minutes (how long planque takes the confirmation) is closed.
 */
class BrowserVendorSignIn(private val context: Context) : VendorSignIn {
  @Volatile private var waiting: CompletableDeferred<String>? = null

  override suspend fun signIn(url: String): String {
    // Only a vendor's secure page is opened.
    if (!url.startsWith("https://")) throw VendorSignInCancelled()
    val wait = CompletableDeferred<String>()
    waiting = wait
    try {
      val tab = CustomTabsIntent.Builder().build()
      tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      tab.launchUrl(context, Uri.parse(url))
      return withTimeout(FIFTEEN_MINUTES_MS) { wait.await() }
    } catch (_: TimeoutCancellationException) {
      throw VendorSignInCancelled()
    } finally {
      waiting = null
    }
  }

  /** The link the vendor's sign-in came back to. */
  fun deliver(link: String) {
    waiting?.complete(link)
  }

  /** The owner gave up waiting. */
  fun cancel() {
    waiting?.completeExceptionally(VendorSignInCancelled())
  }

  /** Whether a sign-in is waiting on the vendor's page. */
  val isWaiting: Boolean get() = waiting != null

  private companion object {
    const val FIFTEEN_MINUTES_MS = 15 * 60 * 1000L
  }
}
