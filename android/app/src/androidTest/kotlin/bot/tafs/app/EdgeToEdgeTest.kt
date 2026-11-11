package bot.tafs.app

import android.content.Context
import android.view.View
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Edge to edge without the window's deprecated bar colours (docs/android-app.md): the app draws behind the status and navigation
 * bars, and their icons follow the app's own Light or Dark from the first launch.
 */
class EdgeToEdgeTest {
  @get:Rule val awake = AwakeDevice()

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val display get() = context.getSharedPreferences("tafs.display", Context.MODE_PRIVATE)
  private var before: String? = null

  private fun launchedWith(theme: String, check: (MainActivity) -> Unit) {
    before = display.getString("theme", null)
    display.edit().putString("theme", theme).commit()
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      scenario.onActivity(check)
    }
  }

  @After fun back() {
    display.edit().apply { if (before == null) remove("theme") else putString("theme", before) }.commit()
  }

  private fun drawsBehindTheBars(activity: MainActivity) {
    // The content starts at the very top of the screen, under the status bar, not below it.
    val at = IntArray(2)
    activity.findViewById<View>(android.R.id.content).getLocationOnScreen(at)
    assertEquals(0, at[1])
    val window = activity.window.decorView
    assertTrue("the content fills the window", activity.findViewById<View>(android.R.id.content).height == window.height)
  }

  @Test fun lightDrawsBehindTheBarsWithDarkIcons() = launchedWith("light") { activity ->
    drawsBehindTheBars(activity)
    val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    assertTrue(bars.isAppearanceLightStatusBars)
    assertTrue(bars.isAppearanceLightNavigationBars)
  }

  @Test fun darkDrawsBehindTheBarsWithLightIcons() = launchedWith("dark") { activity ->
    drawsBehindTheBars(activity)
    val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    assertTrue(!bars.isAppearanceLightStatusBars)
    assertTrue(!bars.isAppearanceLightNavigationBars)
  }
}
