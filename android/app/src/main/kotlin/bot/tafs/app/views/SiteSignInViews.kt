package bot.tafs.app.views

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import bot.tafs.app.design.Theme
import bot.tafs.app.design.scaled
import bot.tafs.app.kiff.SiteSignInView

/**
 * A browser connection's live sign-in view (kiff/planque#86, step 4): planque's one-time page in a web view, with planque's words
 * over it, and nothing else. The page is the browser as frames; what the owner types goes to the site through it, never to a taf.
 * The web view stays on the view's own address; a link elsewhere isn't followed. Closing it leaves the view to lapse.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteSignInScreen(view: SiteSignInView, onClose: () -> Unit) {
  // The view's own origin: the web view follows nothing off it (scheme, host and port).
  val origin = view.view
  val context = LocalContext.current
  val web = remember(view.view) {
    WebView(context).apply {
      // The page draws the browser's frames and sends the owner's taps and keys: it needs scripts, and nothing more.
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.allowFileAccess = false
      settings.allowContentAccess = false
      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
          !sameHttpsOrigin(request.url.toString(), origin)
      }
      loadUrl(view.view)
    }
  }
  // Closed, it keeps nothing: the page's cookies go with it (this is the app's only web view).
  DisposableEffect(web) {
    onDispose {
      web.stopLoading()
      web.destroy()
      android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
    }
  }
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    Column(Modifier.fillMaxSize().background(Theme.background).statusBarsPadding().navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(view.words.orEmpty(), style = scaled(13, color = Theme.soft), modifier = Modifier.weight(1f))
        TextButton(onClose) { Text("Close", style = scaled(15, FontWeight.SemiBold, Theme.amber)) }
      }
      AndroidView({ web }, Modifier.fillMaxWidth().weight(1f))
    }
  }
}

/** Whether [url] and [origin] are the same https origin: scheme, host and port (443 when none is written). */
internal fun sameHttpsOrigin(url: String, origin: String): Boolean = runCatching {
  val a = java.net.URI(url)
  val b = java.net.URI(origin)
  fun port(u: java.net.URI) = if (u.port == -1) 443 else u.port
  a.scheme == "https" && b.scheme == "https" && a.host != null && a.host.equals(b.host, ignoreCase = true) && port(a) == port(b)
}.getOrDefault(false)
