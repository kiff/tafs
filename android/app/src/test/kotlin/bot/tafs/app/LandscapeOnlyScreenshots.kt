package bot.tafs.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.design.Theme
import bot.tafs.app.model.Res
import bot.tafs.app.views.SignInSheet
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
 * tafs on a device that is only ever landscape (docs/android-app.md, "Any screen"): a Chromebook's or desktop's window, 1280 ×
 * 800 dp, landscape, over the sample. Sign-in, the tafs, a taf's chat, Files and Devices (where a computer's QR is scanned)
 * each draw whole there. Drawn into docs/screenshots/android/landscape/ only when asked
 * (`./gradlew :app:testDebugUnitTest --tests '*LandscapeOnlyScreenshots*' -PlandscapeScreenshots`); otherwise each screen is
 * still drawn, so one that no longer draws in landscape fails here. With AnyOrientationTest, which reads the merged manifest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class LandscapeOnlyScreenshots {
  @get:Rule val compose = createComposeRule()

  private val repo = File(System.getProperty("tafs.repo") ?: "../..")

  private fun landscape() {
    RuntimeEnvironment.setQualifiers("en-w1280dp-h800dp-land-mdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
  }

  private fun app(vararg taps: String) {
    landscape()
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

  private fun shot(name: String) = captureScreenRoboImage(File(repo, "docs/screenshots/android/landscape/$name.png").path)

  /** Signing in, landscape only: KIFF's code and Open KIFF. */
  @Test fun signIn() {
    landscape()
    val transport = FakeTransport()
    transport.answer("POST /v1/auth/device/start", 200, """{"device_code":"dc_1","user_code":"TAFS-2026","verification_uri":"https://app.kiff.dev/device","verification_uri_complete":"https://app.kiff.dev/device?c=TAFS-2026","expires_in":600,"interval":5}""")
    transport.hold("POST /v1/auth/device/token")
    val model = testModel(transport = transport, signer = softwareSigner(), start = false)
    compose.setContent {
      TafsTheme { Box(Modifier.fillMaxSize().background(Theme.background)) { SignInSheet(model, onClose = {}) } }
    }
    compose.waitForIdle()
    shot("1-sign-in")
  }

  /** The tafs, beside the sidebar. */
  @Test fun tafs() { app(); shot("2-tafs") }

  /** A taf's chat, beside the sidebar. */
  @Test fun tafChat() { app("Support replies"); shot("3-taf-chat") }

  /** Account, where Files and Devices are, beside the sidebar. */
  @Test fun account() { app("Account"); shot("4-account") }

  /** Files in two panes, as a wide screen shows them: the list, and the file open beside it. */
  @Test fun files() {
    landscape()
    val now = java.time.Instant.now()
    val day = 86_400L
    val files = listOf(
      bot.tafs.app.files.KeptFile("f_1", "Tickets answered, 19 Nov.csv", "text/csv", 14_000, now.minusSeconds(3_600), now.plusSeconds(30 * day), "taf_support", "Support replies", "run_s1", "chat_s1"),
      bot.tafs.app.files.KeptFile("f_2", "Parcels without a scan.csv", "text/csv", 6_000, now.minusSeconds(7_200), now.plusSeconds(29 * day), "taf_parcels", "Late parcels", "run_p1", "chat_p1"),
    )
    val act = bot.tafs.app.views.FilesActions(close = {}, view = {}, pickTaf = {}, open = {}, closeFile = {}, download = {}, share = {},
      askDelete = {}, cancelDelete = {}, confirmDelete = {})
    compose.setContent {
      TafsTheme { bot.tafs.app.views.FilesScreen(bot.tafs.app.files.FilesState(files = files, showing = true), act, twoPane = true) }
    }
    compose.waitForIdle()
    shot("5-files")
  }

  /** Devices, where a computer's QR is scanned: Google's scanner opens from here, in whichever way the device is held. */
  @Test fun devices() {
    landscape()
    val model = testModel(clock = { java.time.Instant.now() }).also { it.showForScreenshots() }
    compose.setContent {
      TafsTheme {
        Box(Modifier.fillMaxSize().background(Theme.background)) {
          bot.tafs.app.views.DevicesSheet(model.state.value, refresh = {}, remove = { false }, onClose = {}, addDevice = {})
        }
      }
    }
    compose.waitForIdle()
    shot("6-devices")
  }
}
