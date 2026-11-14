package bot.tafs.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The touch effect on the places the owner found silent on Android 92: Home's calendar icon and everything tapped inside
 * the calendar. A raw `clickable` plays nothing; `tapping` plays `Haptics.tap`, as the app's buttons do.
 */
class HapticsTapTest {
  private val sources = File("src/main/kotlin/bot/tafs/app")
  private fun text(path: String) = File(sources, path).readText().replace("\r\n", "\n")

  @Test fun theCalendarAndItsIconTapWithTheTouchEffect() {
    val calendar = text("views/CalendarView.kt")
    assertFalse("a tappable in the calendar with no touch effect", calendar.contains(".clickable"))
    assertTrue(calendar.contains(".tapping"))
    assertTrue("the calendar icon on Home", text("views/HomeView.kt").contains(".tapping(onClick = onCalendar)"))
    assertTrue("tapping plays the tap", text("design/Haptics.kt").contains("{ Haptics.tap(); onClick() }"))
  }
}
