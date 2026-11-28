package bot.tafs.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.Res
import bot.tafs.app.views.ConnectAppsSheet
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Connect apps over tafs cloud's own answer (contracts/connect-apps.json): under Yours, a sign-in to choose actions for, one
 * connected, and a sign-in no taf uses ("Not used by any taf", docs/connect-apps.md). The shot the Mac draws from the same file,
 * for reviewing what the screen looks like. Written to android/app/build/outputs/roborazzi/ only when asked
 * (`./gradlew :app:testDebugUnitTest -PphoneScreenshots --tests '*ConnectAppsScreenshot*'`); otherwise it's still drawn, so a
 * sheet that no longer draws this answer fails here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class ConnectAppsScreenshot {
  @get:Rule val compose = createComposeRule()

  private val repo = File(System.getProperty("tafs.repo") ?: "../..")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val answer = KiffClient.json.parseToJsonElement(File(repo, "contracts/connect-apps.json").readText()).jsonObject["answer"]!!.jsonObject

  /** The answer's sign-in that no taf uses: who uses it, as tafs cloud wrote it ("Not used by any taf"), its grey line. */
  private val unusedLine = answer["apps"]!!.jsonArray.map { it.jsonObject }
    .single { it["state"]?.jsonPrimitive?.content == "signed_in" && it["used_by"]?.jsonArray?.isEmpty() == true }["used_by_words"]!!.jsonPrimitive.content

  /** Someone who manages the account's Cards, with one taf (Rae), and Connect apps read from the contract's answer. */
  private fun model(): AppModel = runBlocking {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay"}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf-aaaa0000000000000000","name":"Rae","kiff_agent":"rae-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"rae-1":{"use":true,"oversee":true,"manage":true}}}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/connect/apps", 200, answer.toString())
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    testModel(transport = transport, secrets = secrets, clock = { Instant.parse("2026-11-28T01:00:00Z") }, start = false).also {
      it.restore(); it.refresh(); it.loadConnectApps()
    }
  }

  @Test fun anUnusedSignInIsUnderYours() {
    RuntimeEnvironment.setQualifiers("en-w384dp-h682dp-port-450dpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val m = model()
    compose.setContent {
      TafsTheme {
        ConnectAppsSheet(m.state.value, load = {}, onConnect = { _, _, _ -> }, onAskCoach = {}, onClose = {}, onDisconnect = {})
      }
    }
    compose.waitForIdle()
    compose.onNodeWithText(unusedLine).assertExists()
    captureScreenRoboImage(File(repo, "android/app/build/outputs/roborazzi/connect-apps-unused.png").path)
  }
}
