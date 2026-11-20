package bot.tafs.app

import bot.tafs.app.kiff.tabIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KIFF's sign-in tab is a private browsing session where the browser offers one (docs/onboarding.md), opened in that browser;
 * a vendor's tab, and a browser without it, is an ordinary tab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class KiffTabTest {
  private val ephemeral = "androidx.browser.customtabs.extra.ENABLE_EPHEMERAL_BROWSING"

  @Test fun aBrowserThatOffersItOpensKiffsTabPrivately() {
    val tab = tabIntent("com.android.chrome")
    assertTrue(tab.intent.getBooleanExtra(ephemeral, false))
    assertEquals("the browser that said it offers it is the one that opens it", "com.android.chrome", tab.intent.`package`)
  }

  @Test fun otherwiseAnOrdinaryTab() {
    val tab = tabIntent(null)
    assertFalse(tab.intent.getBooleanExtra(ephemeral, false))
    assertNull(tab.intent.`package`)
  }

  @Test fun onlyKiffsTabAsksForIt() {
    val app = java.io.File("src/main/kotlin/bot/tafs/app/TafsApplication.kt").readText()
    assertTrue(app.contains("val appSignInTab: BrowserVendorSignIn by lazy { BrowserVendorSignIn(this, privateBrowsing = true) }"))
    assertTrue("a vendor's tab shares the browser's sign-in", app.contains("val vendorSignIn: BrowserVendorSignIn by lazy { BrowserVendorSignIn(this) }"))
  }
}
