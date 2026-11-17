package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.Res
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tablet's screens at the iPad's size (1366 × 1024 dp, landscape, 2732 × 2048 px), over the sample, to set beside the iPad's
 * reference files (docs/android-app.md, Tablets). Drawn into docs/screenshots/android/tablet/ only when asked
 * (`./gradlew :app:testDebugUnitTest --tests '*TabletScreenshots*' -PtabletScreenshots`); otherwise each screen is still drawn,
 * so one that no longer draws fails here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class TabletScreenshots {
  @get:Rule val compose = createComposeRule()

  private val repo = File(System.getProperty("tafs.repo") ?: "../..")

  private fun show(vararg taps: String) {
    RuntimeEnvironment.setQualifiers("en-w1366dp-h1024dp-land-xhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val model = testModel(clock = { java.time.Instant.now() }).also { it.showForScreenshots() }
    compose.setContent {
      TafsTheme {
        CompositionLocalProvider(LocalBiometry provides bot.tafs.app.signing.Biometry.name(strongEnrolled = true, face = false, fingerprint = true)) {
          bot.tafs.app.views.ProvideApprovalReadiness(model.state.value.mode, bot.tafs.app.model.ApprovalReadiness.Ready, recheck = {}) {
            SignedInView(model, onSignIn = {})
          }
        }
      }
    }
    for (words in taps) {
      compose.waitForIdle()
      compose.onAllNodes(hasText(words) and hasClickAction() and hasAnyAncestor(hasTestTag("sidebar")))[0].performClick()
    }
    compose.waitForIdle()
  }

  private fun shot(name: String) = captureScreenRoboImage(File(repo, "docs/screenshots/android/tablet/$name.png").path)

  /** Beside the iPad's `Overview.PNG`: the sidebar, and the Overview a tablet opens on. */
  @Test fun overview() { show(); shot("overview") }

  /** Beside the iPad's `Needs-you.PNG`: Needs you, opened from the sidebar, beside it. */
  @Test fun needsYou() { show("Needs you"); shot("needs-you") }

  /** Beside the iPad's `Taf-card-tab.PNG` and `Taf-chat-tab-finished.PNG`: a taf chosen in the sidebar, highlighted there. */
  @Test fun taf() { show("Refunds"); shot("taf") }
}
