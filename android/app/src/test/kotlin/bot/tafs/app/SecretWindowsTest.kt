package bot.tafs.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A secret shown once (an api door's key) is drawn in a secure window: never in a screenshot, the recents screen or a shared screen.
 * A dialog or a sheet is its own window, so the flag goes on it, not the activity (the doors proof on 21 Nov: a screenshot of the
 * emulator showed the key in plain text).
 */
class SecretWindowsTest {
  private val sources = File("src/main/kotlin/bot/tafs/app")

  private fun body(file: String, fn: String): String {
    val s = File(sources, file).readText().replace("\r\n", "\n")
    val at = s.indexOf("fun $fn(")
    assertTrue("$file still has $fn", at >= 0)
    return s.substring(at, (s.indexOf("\n}\n", at).takeIf { it > 0 } ?: s.length))
  }

  @Test fun anApiDoorsKeyIsShownInASecureWindow() {
    assertTrue(
      "the key's dialog sets SecureFlagPolicy.SecureOn on its own window",
      "SecureFlagPolicy.SecureOn" in body("views/ApiDoorViews.kt", "ApiKeyShownOnce"),
    )
  }
}
