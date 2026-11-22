package bot.tafs.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing code's secret is never in a screenshot, the recents screen or a shared screen. Its sheet is a window of its own, so
 * the flag goes on the sheet as well as on the activity behind it (the doors proof on 21 Nov showed a dialog's secret in a
 * screenshot because only the activity was secure).
 */
class ChromeSecureSheetTest {
  private val sources = File("src/main/kotlin/bot/tafs/app")
  private fun text(f: String) = File(sources, f).readText().replace("\r\n", "\n")

  @Test fun theCodesSheetIsASecureWindow() {
    assertTrue("the Chrome sheet asks for a secure window while a code shows", "secure = pairing != null" in text("views/ChromesView.kt"))
    val windows = text("drive/DriveWindows.kt")
    assertTrue("DriveSheet hands it to the sheet's own window", "securePolicy = if (secure) androidx.compose.ui.window.SecureFlagPolicy.SecureOn" in windows)
    assertTrue("and the activity's window too, for a sheet drawn beside the sidebar", "FLAG_SECURE" in text("views/ChromesView.kt"))
  }
}
