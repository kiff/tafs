package bot.tafs.app

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import bot.tafs.app.app.AppModel
import bot.tafs.app.drive.DriveInfo
import bot.tafs.app.drive.DriveInput
import bot.tafs.app.drive.DriveState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A drive on the app itself (docs/drive.md), as MainActivity wires it: the app leaving the screen ends it as `left_app`, and the
 * person's touch on the app pauses it, ending nothing. The drive is begun on the activity's own model, as an approved Start
 * would begin it; its stream has no tafs cloud here, which leaves it on.
 */
class DriveAppTest {
  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private var model: AppModel? = null

  private fun drive(): AppModel {
    rule.waitForIdle()
    lateinit var m: AppModel
    instrumentation.runOnMainSync {
      m = ViewModelProvider(rule.activity)[AppModel::class.java]
      m.drive.begin(DriveInfo("d_emulator", task = "Run Chargebacks"))
    }
    model = m
    rule.waitUntil(5_000) { DriveInput.driving }
    return m
  }

  private fun AppModel.endedFor(): String? = (drive.state.value as? DriveState.Over)?.why

  @After fun off() {
    instrumentation.runOnMainSync { model?.let { it.stopDrive("stopped"); it.drive.clear() } }
  }

  @Test fun leavingTheAppEndsTheDrive() {
    val m = drive()
    instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
    rule.waitUntil(5_000) { m.endedFor() != null }
    assertEquals("left_app", m.endedFor())
    assertFalse(DriveInput.driving)
  }

  @Test fun thePersonsTouchOnTheAppPausesTheDrive() {
    val m = drive()
    val decor = rule.activity.window.decorView
    val x = decor.width / 2f
    val y = decor.height / 2f
    val t = SystemClock.uptimeMillis()
    instrumentation.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
    instrumentation.sendPointerSync(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
    rule.waitUntil(5_000) { m.drive.paused }
    assertEquals(null, m.endedFor())
    assertTrue(DriveInput.driving)
    // Continue: Coach goes on.
    instrumentation.runOnMainSync { m.resumeDrive() }
    assertFalse(m.drive.paused)
  }
}
