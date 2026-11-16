package bot.tafs.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppModel
import bot.tafs.app.design.Theme
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.views.OnboardingView
import bot.tafs.app.views.SignInSheet
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone's Play store screenshots (docs/android-app.md, Store screenshots): the real screens over the sample, drawn with the
 * app's own words in each of the 8 languages, reached by tapping as the person would. Never a mock-up, and nothing drawn over
 * them. 1080 × 1920 px (360 × 640 dp at xxhdpi), 9:16, inside Play's 2:1 limit for a phone. Drawn into
 * android/play/phone-screenshots/<locale>/ only when asked (`./gradlew :app:testDebugUnitTest -PphoneScreenshots`); otherwise
 * each screen is still drawn, so one that no longer draws fails here.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class PhoneStoreScreenshots(private val language: String, private val playLocale: String) {
  @get:Rule val compose = createComposeRule()

  private val repo = File(System.getProperty("tafs.repo") ?: "../..")

  /** The phone in [language], its words installed as the app installs them at start (TafsApplication). */
  private fun phone() {
    RuntimeEnvironment.setQualifiers("$language-w360dp-h640dp-port-xxhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    // Drawn in the app's own words: the screen's language is the one asked for.
    assertEquals(language, Res.language)
  }

  private fun shot(name: String) {
    compose.waitForIdle()
    captureScreenRoboImage(File(repo, "android/play/phone-screenshots/$playLocale/$name.png").path)
  }

  /**
   * The sample, signed in, as the store's screenshots open it (`showForScreenshots`): Coach's button is shown. On the phone's own
   * clock, as the app runs, so its held calls are still waiting rather than expired.
   */
  private fun sample(brief: Boolean = false): AppModel = testModel(clock = { java.time.Instant.now() }).also {
    it.showForScreenshots()
    if (brief) it.showBriefForScreenshots()
  }

  /** The signed-in app, on a phone whose fingerprint is set up for approvals, as RootView provides it. */
  private fun app(model: AppModel) = compose.setContent {
    TafsTheme {
      bot.tafs.app.views.ProvideApprovalReadiness(model.state.value.mode, bot.tafs.app.model.ApprovalReadiness.Ready, recheck = {}) {
        SignedInView(model, onSignIn = {})
      }
    }
  }

  private fun tap(words: String) = compose.onNode(hasText(words) and hasClickAction()).performClick()

  /** Your brief, in Needs you's place: its decisions, what's done and the next move. */
  @Test fun brief() {
    phone()
    val model = sample(brief = true)
    app(model)
    tap(model.state.value.needsYouTabTitle)
    shot("1-your-brief")
  }

  /** The tafs at work: each one's line and Card, and the held call that needs you on top. */
  @Test fun tafs() {
    phone()
    app(sample())
    shot("2-tafs")
  }

  /** Needs you: the held calls, each with its Card's reason and its answers. */
  @Test fun needsYou() {
    phone()
    app(sample())
    tap(Words.NeedsYou.title)
    shot("3-needs-you")
  }

  /** Coach, opened from its button: what Coach is, as a new account first sees it. */
  @Test fun coach() {
    phone()
    app(sample())
    tap(COACH)
    shot("4-coach")
  }

  private val transport = FakeTransport()
  private val signer = softwareSigner()

  private fun signIn(content: @Composable (AppModel) -> Unit) {
    phone()
    transport.answer("POST /v1/auth/device/start", 200, """{"device_code":"dc_1","user_code":"TAFS-2026","verification_uri":"https://app.kiff.dev/device","verification_uri_complete":"https://app.kiff.dev/device?c=TAFS-2026","expires_in":600,"interval":5}""")
    val model = testModel(transport = transport, signer = signer, start = false)
    compose.setContent {
      TafsTheme { Box(Modifier.fillMaxSize().background(Theme.background)) { OnboardingView(onSignIn = {}, onSample = {}) } }
      content(model)
    }
  }

  /** Signing in on a phone: KIFF's code and Open KIFF, while KIFF waits for the person's yes. */
  @Test fun signIn() {
    transport.hold("POST /v1/auth/device/token")
    signIn { TafsTheme { SignInSheet(it, onClose = {}) } }
    shot("5-sign-in")
  }

  /** Signed in: "You're connected." */
  @Test fun connected() {
    val token = """{"access_token":"at_1","expires_in":3600,"tenant":"t_01","subject":"user_1"}"""
    transport.answer("POST /v1/auth/device/token", 200, token)
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"sample","tenant_display_name":"Sample"}""")
    transport.answer("POST /v1/me/device/approval-key", 200, """{"fingerprint":"${bot.tafs.app.kiff.KiffClient.fingerprint(signer.approvalPublicKeyDER)}"}""")
    signIn { TafsTheme { SignInSheet(it, onClose = {}) } }
    compose.mainClock.advanceTimeBy(10_000)
    compose.waitUntil(5_000) { compose.onAllNodes(hasText(Words.SignIn.connected)).fetchSemanticsNodes().isNotEmpty() }
    shot("6-connected")
  }

  companion object {
    /** The 8 languages, each with the Play listing's own locale for its folder. */
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun languages(): List<Array<String>> = listOf(
      arrayOf("en", "en-US"), arrayOf("de", "de-DE"), arrayOf("fr", "fr-FR"), arrayOf("es", "es-ES"),
      arrayOf("it", "it-IT"), arrayOf("pt", "pt-PT"), arrayOf("nl", "nl-NL"), arrayOf("pl", "pl-PL"),
    )
  }
}
