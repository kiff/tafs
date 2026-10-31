package bot.tafs.app

import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Wakes the device and dismisses its keyguard before a screen test launches its activity, and waits until it is awake and
 * unlocked. A just-booted emulator can still be asleep or locked when the first test starts: its activity then never gets a
 * window, and the test fails with "No compose hierarchies found". Order it before the compose rule (`order = 0`).
 */
class AwakeDevice(private val timeoutMs: Long = 30_000) : ExternalResource() {
  override fun before() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val power = context.getSystemService(PowerManager::class.java)
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).close()
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (!power.isInteractive || keyguard.isKeyguardLocked) {
      check(SystemClock.uptimeMillis() < deadline) { "The device stayed asleep or locked for ${timeoutMs / 1000} s" }
      shell("input keyevent KEYCODE_WAKEUP")
      shell("wm dismiss-keyguard")
      Thread.sleep(250)
    }
    // A system dialog left over from boot ("System UI isn't responding") would sit over the test's activity.
    shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
  }
}

/**
 * Sets [content] and waits until its window is up, with a Compose hierarchy to read, before the test reads the screen. On a
 * just-booted emulator the activity's window can come a moment late; reading at once failed with "No compose hierarchies
 * found", which this waits out rather than the test being run again.
 */
fun ComposeContentTestRule.setShown(timeoutMs: Long = 30_000, content: @Composable () -> Unit) {
  setContent(content)
  waitUntil(timeoutMs) { runCatching { onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
}
