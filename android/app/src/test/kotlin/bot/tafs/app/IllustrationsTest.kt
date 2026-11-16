package bot.tafs.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import bot.tafs.app.design.AllHaltedArt
import bot.tafs.app.design.AppConnectedArt
import bot.tafs.app.design.BriefClearArt
import bot.tafs.app.design.CoachLoadingArt
import bot.tafs.app.design.CoachStartArt
import bot.tafs.app.design.FirstRunArt
import bot.tafs.app.design.NeedsYouClearArt
import bot.tafs.app.design.NewTafArt
import bot.tafs.app.design.NoTafsArt
import bot.tafs.app.design.OverviewArt
import bot.tafs.app.design.Theme
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The illustrations (Illustrations.kt), each drawn on the ground in light and in dark, so one that no longer draws fails here.
 * Each is drawn at its board's own pixels (its frame's width at 2x), with 20 px of ground around it, so a picture can be laid
 * beside its board. The pictures are written only when asked (`-PphoneScreenshots`), into the directory the
 * `tafs.illustrations` property names (the module's build/illustrations by default), never into the repository.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class IllustrationsTest(private val board: String, private val dark: Boolean) {
  @get:Rule val compose = createComposeRule()

  @After fun light() {
    Theme.dark = false
  }

  @Test fun draws() {
    val (frameWidth, art) = ARTS.getValue(board)
    // 2x, so a dp is two of the board's pixels; wide enough for the widest frame and its ground.
    RuntimeEnvironment.setQualifiers("w1000dp-h1000dp-xhdpi")
    Theme.dark = dark
    compose.setContent {
      Box(Modifier.background(Theme.background).padding(10.dp)) {
        Box(Modifier.width((frameWidth / 2f).dp)) { art(Modifier.fillMaxWidth()) }
      }
    }
    compose.waitForIdle()
    if (System.getProperty("roborazzi.test.record") == "true") {
      val out = File(System.getProperty("tafs.illustrations") ?: OUT, "$board-${if (dark) "dark" else "light"}.png")
      out.parentFile?.mkdirs()
      compose.onRoot().captureRoboImage(out.path)
    }
  }

  companion object {
    private const val OUT = "build/illustrations"

    /** Each board's art and its frame's width in the board's pixels. */
    private val ARTS: Map<String, Pair<Int, @Composable (Modifier) -> Unit>> = mapOf(
      "01-first-run" to (716 to { m -> FirstRunArt(m) }),
      "02-no-tafs-yet" to (730 to { m -> NoTafsArt(m) }),
      "03-brief-nothing-to-do" to (706 to { m -> BriefClearArt(m) }),
      "04-all-halted" to (706 to { m -> AllHaltedArt(m) }),
      "05-app-connected" to (1026 to { m -> AppConnectedArt(m) }),
      "06-needs-you-nothing" to (714 to { m -> NeedsYouClearArt(m) }),
      "07-coach-nothing-yet" to (1176 to { m -> CoachStartArt(m) }),
      "08-new-taf-small" to (686 to { m -> NewTafArt(m) }),
      "09-coach-loading" to (1207 to { m -> CoachLoadingArt(m) }),
      "10-overview" to (1746 to { m -> OverviewArt(m) }),
    )

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} dark={1}")
    fun boards(): List<Array<Any>> = ARTS.keys.flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
  }
}
