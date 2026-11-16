package bot.tafs.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.Res
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class PhoneStoreScreenshots(private val language: String, private val playLocale: String) {
  @get:Rule val compose = createComposeRule()

  private val repo = File(System.getProperty("tafs.repo") ?: "../..")

  @Test fun home() {
    RuntimeEnvironment.setQualifiers("$language-w360dp-h640dp-port-xxhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val model = testModel()
    model.showForScreenshots()
    compose.setContent { TafsTheme { SignedInView(model, onSignIn = {}) } }
    compose.waitForIdle()
    compose.onRoot().captureRoboImage(File(repo, "android/play/phone-screenshots/$playLocale/1-home.png").path)
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun languages(): List<Array<String>> = listOf(arrayOf("en", "en-US"), arrayOf("fr", "fr-FR"))
  }
}
