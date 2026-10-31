package bot.tafs.app

import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
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
  }
}
