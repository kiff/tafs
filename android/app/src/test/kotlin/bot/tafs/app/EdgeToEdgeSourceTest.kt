package bot.tafs.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Google Play flags the window's bar colours and the short-edges cutout mode as deprecated for edge to edge (docs/android-app.md).
 * The app draws edge to edge without them, and without androidx.activity's `enableEdgeToEdge`, which calls them.
 */
class EdgeToEdgeSourceTest {
  private val main = File("src/main")

  @Test fun theAppCallsNoneOfTheDeprecatedEdgeToEdgeApis() {
    assertTrue("run from the app module", main.isDirectory)
    val flagged = Regex("""enableEdgeToEdge|setStatusBarColor|setNavigationBarColor|setNavigationBarDividerColor|\.statusBarColor\s*=|\.navigationBarColor\s*=|LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES|LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT""")
    val found = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
      .flatMap { f -> f.readLines().withIndex().filter { (_, line) -> flagged.containsMatchIn(line) }.map { (i, line) -> "${f.name}:${i + 1}: ${line.trim()}" } }
      .toList()
    assertEquals(emptyList<String>(), found)
  }

  @Test fun fromAndroid15TheThemeSetsNoBarColours() {
    // Android 15 draws edge to edge on its own, and the bar colour attributes are deprecated there.
    val v35 = File(main, "res/values-v35/themes.xml").readText()
    assertTrue(!v35.contains("statusBarColor") && !v35.contains("navigationBarColor"))
  }
}
