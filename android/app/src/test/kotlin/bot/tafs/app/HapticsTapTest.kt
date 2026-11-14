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

  // Android 95: every tappable plays the tap, not only the shared buttons. A raw `clickable` outside the design components
  // (which tap themselves) is one with no touch effect.
  @Test fun noTappableOutsideTheDesignComponentsIsSilent() {
    val raw = Regex("\\.clickable[ ({]")
    val silent = sources.walkTopDown().filter { it.extension == "kt" && it.parentFile.name != "design" }
      .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (!line.startsWith("import ") && raw.containsMatchIn(line)) "${f.name}:${i + 1}" else null } }
      .toList()
    assertTrue("tappables with no touch effect: $silent", silent.isEmpty())
    // A tap and a long press: its tap plays the touch effect, unless the tap does nothing (`onClick = {}`). Compose plays its own
    // long-press feedback.
    val combined = sources.walkTopDown().filter { it.extension == "kt" && it.parentFile.name != "design" }
      .flatMap { f -> f.readLines().mapIndexedNotNull { i, line ->
        if (line.contains("combinedClickable(") && !line.contains("Haptics.tap()") && !line.contains("onClick = {}")) "${f.name}:${i + 1}" else null
      } }.toList()
    assertTrue("a tap and long press whose tap plays nothing: $combined", combined.isEmpty())
  }
}
